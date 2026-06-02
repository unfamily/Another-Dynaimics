package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctEnergyTransportSpec;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.network.ModNetwork;
import org.jetbrains.annotations.Nullable;

/**
 * Energy (FE/RF) logistics: instant transfer only (no in-duct transit shipments).
 * <p><strong>Flux-lane family:</strong> behavioural changes often need equivalent updates for {@link DuctHeatServerTick},
 * {@link DuctFaceLanes} energy fields, and universal ducts with energy enabled (settings copier {@code EnergyHeat}).
 * <p>Per-face buffers and routing: see class body; rate from {@link DuctModuleEffects#ENERGY_ACTION_RATE_TICKS}.
 */
public final class DuctEnergyServerTick {
    /** Min ticks between rays to the same duct destination on one face (RR to other targets is not throttled). */
    private static final int ENERGY_RAY_COOLDOWN_SAME_DEST_TICKS = 6;

    private DuctEnergyServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ENERGY)).orElse(false)) {
            return;
        }
        if (!be.isStorageAttachmentNode()) {
            return;
        }
        DuctEnergyTransportSpec spec = be.energyTransportSpec();
        int sm = be.getStorageMask();

        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.ENERGY)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (lanes.energyOutputBufferFe > 0) {
                flushOutputBufferToNeighbor(level, be, dir, spec);
            }
        }

        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.ENERGY)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            if (lanes.energyTicksUntilAction > 0) {
                lanes.energyTicksUntilAction--;
                continue;
            }
            int rate = DuctModuleEffects.effectiveEnergyActionRateTicks(be, dir, spec);
            if (!DuctActionScheduling.isStaggerSlot(level, be.getBlockPos(), dir, rate)) {
                continue;
            }
            lanes.energyTicksUntilAction = Math.max(0, rate - 1);

            NodeMode nm = lanes.nodeMode;
            DuctFaceNode node = be.getFaceNode(dir);
            if (nm == NodeMode.EXTRACTION || nm == NodeMode.EXTRACTION_FILTERING) {
                RoutingMode rm = be.energyRoutingForFace(dir, true);
                pullExternalIntoInputBuffer(level, be, dir, spec);
                dispatchFromInputBuffer(level, be, dir, spec, rm, node);
            } else if (nm == NodeMode.RETRIEVING) {
                RoutingMode rm = be.energyRoutingForFace(dir, false);
                retrieveFromNetworkToOutput(level, be, dir, spec, rm, node);
                flushOutputBufferToNeighbor(level, be, dir, spec);
            } else if (nm == NodeMode.RETRIEVING_EXTRACTION) {
                RoutingMode rmR = be.energyRoutingForFace(dir, false);
                RoutingMode rmE = be.energyRoutingForFace(dir, true);
                retrieveFromNetworkToOutput(level, be, dir, spec, rmR, node);
                pullExternalIntoInputBuffer(level, be, dir, spec);
                dispatchFromInputBuffer(level, be, dir, spec, rmE, node);
            }
            be.setChanged();
        }
    }

    /** Stall recovery: try to move buffered FE back into the network or to a neighbor. */
    public static void tickStallBuffersForFace(
            DuctBlockEntity be, ServerLevel level, Direction face, DuctEnergyTransportSpec spec) {
        DuctFaceLanes lanes = be.getFaceLanes(face);
        if (lanes.energyOutputBufferFe > 0) {
            flushOutputBufferToNeighbor(level, be, face, spec);
        }
        if (lanes.energyInputBufferFe > 0) {
            NodeMode nm = lanes.nodeMode;
            DuctFaceNode node = be.getFaceNode(face);
            if (nm == NodeMode.EXTRACTION || nm == NodeMode.EXTRACTION_FILTERING) {
                dispatchFromInputBuffer(level, be, face, spec, be.energyRoutingForFace(face, true), node);
            } else if (nm == NodeMode.RETRIEVING_EXTRACTION) {
                dispatchFromInputBuffer(level, be, face, spec, be.energyRoutingForFace(face, true), node);
            }
        }
        if (lanes.energyInputBufferFe <= 0 && lanes.energyOutputBufferFe <= 0) {
            be.syncStallVisualIfNeeded();
        }
    }

    private static void flushOutputBufferToNeighbor(
            ServerLevel level, DuctBlockEntity be, Direction face, DuctEnergyTransportSpec spec) {
        int want = DuctModuleEffects.effectiveEnergyExtractPerAction(be, face, spec);
        if (want <= 0) {
            return;
        }
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext == null || !ext.canReceive()) {
            return;
        }
        int stored = Math.max(0, be.getFaceLanes(face).energyOutputBufferFe);
        if (stored <= 0) {
            return;
        }
        int move = Math.min(want, stored);
        move = Math.min(move, simulateMaxReceivable(ext, move));
        if (move <= 0) {
            return;
        }
        DuctFaceLanes lanes = be.getFaceLanes(face);
        int inserted = ext.receiveEnergy(move, false);
        if (inserted > 0) {
            lanes.energyOutputBufferFe = Math.max(0, lanes.energyOutputBufferFe - inserted);
            be.setChanged();
            trySendEnergyRay(
                    level,
                    lanes,
                    be.getBlockPos(),
                    be.getBlockPos(),
                    spec,
                    face,
                    face);
            if (lanes.energyInputBufferFe <= 0 && lanes.energyOutputBufferFe <= 0) {
                be.syncStallVisualIfNeeded();
            }
        }
    }

    private static void pullExternalIntoInputBuffer(
            ServerLevel level, DuctBlockEntity be, Direction face, DuctEnergyTransportSpec spec) {
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext == null || !ext.canExtract()) {
            return;
        }
        DuctFaceLanes lanes = be.getFaceLanes(face);
        int cap = DuctModuleEffects.effectiveEnergyInputBufferCapFe(be, face, spec);
        int stored = Math.max(0, lanes.energyInputBufferFe);
        int free = Math.max(0, cap - stored);
        if (free <= 0) {
            return;
        }
        int want = Math.min(DuctModuleEffects.effectiveEnergyExtractPerAction(be, face, spec), free);
        int canPull = simulateMaxExtractable(ext, want);
        if (canPull <= 0) {
            return;
        }
        int extracted = ext.extractEnergy(Math.min(canPull, want), false);
        if (extracted > 0) {
            lanes.energyInputBufferFe = stored + extracted;
            be.setChanged();
        }
    }

    private static void dispatchFromInputBuffer(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes sourceLanes = sourceBe.getFaceLanes(sourceFace);
        int stored = sourceLanes.energyInputBufferFe;
        if (stored <= 0) {
            return;
        }
        int want = Math.min(DuctModuleEffects.effectiveEnergyExtractPerAction(sourceBe, sourceFace, spec), stored);
        if (want <= 0) {
            return;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        var ducts = DuctNetworkCache.connectedDucts(level, srcPos, DuctNetworkType.ENERGY);
        if (ducts.isEmpty()) {
            return;
        }
        ArrayList<DestCandidate> cands = new ArrayList<>();
        for (BlockPos destPos : ducts) {
            if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            int dsm = destBe.getStorageMask();
            OptionalLong dist =
                    destPos.equals(srcPos)
                            ? OptionalLong.of(0L)
                            : DuctNetworkCache.hopDistance(level, srcPos, destPos, DuctNetworkType.ENERGY);
            if (dist.isEmpty()) {
                continue;
            }
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
                    continue;
                }
                if (DuctSameBlockRouting.skipSameBlockDestFace(srcPos, sourceFace, destPos, df, false)) {
                    continue;
                }
                DuctFaceLanes destLanes = destBe.getFaceLanes(df);
                if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
                    continue;
                }
                NodeMode dm = destLanes.nodeMode;
                if (dm != NodeMode.NONE
                        && dm != NodeMode.FILTERING_INSERTION
                        && dm != NodeMode.EXTRACTION_FILTERING
                        && dm != NodeMode.RETRIEVING
                        && dm != NodeMode.RETRIEVING_EXTRACTION) {
                    continue;
                }
                if (!destBe.getFaceNode(df).eligibilityMode.isInsertable()) {
                    continue;
                }
                if (!canAcceptNetworkInsert(level, destBe, df, 1)) {
                    continue;
                }
                int priority = destBe.getFaceNode(df).insertionPriority;
                cands.add(new DestCandidate(destPos, df, priority, dist.orElse(Long.MAX_VALUE)));
            }
        }
        if (cands.isEmpty()) {
            return;
        }
        cands.sort(Comparator.comparingInt((DestCandidate c) -> c.priority()).reversed());
        int maxP = cands.getFirst().priority();
        ArrayList<DestCandidate> tier = new ArrayList<>();
        for (DestCandidate c : cands) {
            if (c.priority() == maxP) {
                tier.add(c);
            }
        }
        int[] rr = new int[] {sourceLanes.energyRoundRobinCursor};
        DestCandidate pick = pickWithinTier(level, tier, routing, rr);
        sourceLanes.energyRoundRobinCursor = rr[0];
        if (pick == null) {
            sourceBe.setChanged();
            return;
        }
        tryTransferInputToDest(level, sourceBe, sourceFace, spec, sourceLanes, want, pick);
        sourceBe.setChanged();
    }

    private static boolean tryTransferInputToDest(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            DuctFaceLanes sourceLanes,
            int want,
            DestCandidate pick) {
        if (!(level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        int avail = Math.min(want, sourceLanes.energyInputBufferFe);
        if (avail <= 0) {
            return false;
        }
        int move = avail;
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, destBe.getBlockPos(), pick.face());
        int extRecv = ext != null && ext.canReceive() ? simulateMaxReceivable(ext, move) : 0;
        int outFree =
                Math.max(
                        0,
                        DuctModuleEffects.effectiveEnergyOutputBufferCapFe(destBe, pick.face(), destBe.energyTransportSpec())
                                - destBe.getFaceLanes(pick.face()).energyOutputBufferFe);
        int bufRecv = Math.min(move, outFree);
        if (extRecv <= 0 && bufRecv <= 0) {
            return false;
        }
        int extracted = sourceBe.extractEnergyInputBuffer(sourceFace, move, false);
        if (extracted <= 0) {
            return false;
        }
        int left = extracted;
        if (extRecv > 0) {
            int toExt = Math.min(left, extRecv);
            int ins = ext.receiveEnergy(toExt, false);
            left -= ins;
        }
        if (left > 0 && bufRecv > 0) {
            left -= destBe.depositEnergyOutputBuffer(pick.face(), left, false);
        }
        if (left > 0) {
            sourceLanes.energyInputBufferFe += left;
            sourceBe.setChanged();
        }
        int moved = extracted - left;
        if (moved > 0) {
            trySendEnergyRay(
                    level,
                    sourceLanes,
                    sourceBe.getBlockPos(),
                    pick.ductPos(),
                    spec,
                    sourceFace,
                    pick.face());
        }
        if (sourceLanes.energyInputBufferFe <= 0 && sourceLanes.energyOutputBufferFe <= 0) {
            sourceBe.syncStallVisualIfNeeded();
        }
        return moved > 0;
    }

    private static void retrieveFromNetworkToOutput(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        int want = DuctModuleEffects.effectiveEnergyExtractPerAction(retrieverBe, retrieverFace, spec);
        if (want <= 0) {
            return;
        }
        int outCap = DuctModuleEffects.effectiveEnergyOutputBufferCapFe(retrieverBe, retrieverFace, spec);
        int outStored = Math.max(0, retrieverLanes.energyOutputBufferFe);
        int outFree = Math.max(0, outCap - outStored);
        if (outFree <= 0 && !canAcceptNetworkInsert(level, retrieverBe, retrieverFace, 1)) {
            return;
        }
        int budget = outFree > 0 ? Math.min(want, outFree) : want;

        List<BlockPos> ducts = new ArrayList<>(DuctNetworkCache.connectedDucts(level, retrieverPos, DuctNetworkType.ENERGY));
        if (ducts.isEmpty()) {
            return;
        }
        ArrayList<DonorCandidate> donors = new ArrayList<>();
        for (BlockPos donorPos : ducts) {
            if (!(level.getBlockEntity(donorPos) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            int sm = donorBe.getStorageMask();
            for (Direction donorFace : Direction.values()) {
                if ((sm & (1 << donorFace.ordinal())) == 0) {
                    continue;
                }
                if (DuctSameBlockRouting.skipSameBlockDonorFace(retrieverPos, retrieverFace, donorPos, donorFace)) {
                    continue;
                }
                DuctFaceLanes donorLanes = donorBe.getFaceLanes(donorFace);
                if (!DuctRedstoneLogic.isFaceTransportActive(level, donorPos, donorLanes.redstoneMode)) {
                    continue;
                }
                NodeMode dm = donorLanes.nodeMode;
                if (dm != NodeMode.NONE && dm != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!donorBe.getFaceNode(donorFace).eligibilityMode.isRetrievable()) {
                    continue;
                }
                int avail = availableDonorEnergy(level, donorBe, donorFace, budget);
                if (avail <= 0) {
                    continue;
                }
                OptionalLong dist =
                        donorPos.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctNetworkCache.hopDistance(level, retrieverPos, donorPos, DuctNetworkType.ENERGY);
                if (dist.isEmpty()) {
                    continue;
                }
                int priority = donorBe.getFaceNode(donorFace).insertionPriority;
                donors.add(new DonorCandidate(donorPos, donorFace, priority, dist.getAsLong()));
            }
        }
        if (donors.isEmpty()) {
            return;
        }
        donors.sort(Comparator.comparingInt((DonorCandidate c) -> c.priority()).reversed());
        int maxP = donors.getFirst().priority();
        ArrayList<DonorCandidate> tier = new ArrayList<>();
        for (DonorCandidate c : donors) {
            if (c.priority() == maxP) {
                tier.add(c);
            }
        }
        int[] rr = new int[] {retrieverLanes.energyRoundRobinCursor};
        DonorCandidate pick = pickDonorWithinTier(level, tier, routing, rr);
        retrieverLanes.energyRoundRobinCursor = rr[0];
        if (pick == null) {
            retrieverBe.setChanged();
            return;
        }
        if (!(level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity donorBe)) {
            retrieverBe.setChanged();
            return;
        }
        int pull = Math.min(budget, availableDonorEnergy(level, donorBe, pick.face(), budget));
        if (pull <= 0) {
            retrieverBe.setChanged();
            return;
        }
        int extracted = extractDonorEnergy(level, donorBe, pick.face(), pull);
        if (extracted <= 0) {
            retrieverBe.setChanged();
            return;
        }
        int left = extracted;
        int toOut = Math.min(left, outFree);
        if (toOut > 0) {
            left -= retrieverBe.depositEnergyOutputBuffer(retrieverFace, toOut, false);
        }
        if (left > 0) {
            IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, retrieverPos, retrieverFace);
            if (ext != null && ext.canReceive()) {
                left -= ext.receiveEnergy(left, false);
            }
        }
        int moved = extracted - left;
        if (moved > 0) {
            DuctFaceLanes donorLanes = donorBe.getFaceLanes(pick.face());
            trySendEnergyRay(
                    level,
                    donorLanes,
                    pick.ductPos(),
                    retrieverPos,
                    donorBe.energyTransportSpec(),
                    pick.face(),
                    retrieverFace);
        }
        retrieverBe.setChanged();
    }

    private static int availableDonorEnergy(
            ServerLevel level, DuctBlockEntity donorBe, Direction donorFace, int maxWant) {
        DuctFaceLanes lanes = donorBe.getFaceLanes(donorFace);
        int fromBuf = Math.min(maxWant, lanes.energyInputBufferFe);
        if (fromBuf > 0) {
            return fromBuf;
        }
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, donorBe.getBlockPos(), donorFace);
        if (ext == null || !ext.canExtract()) {
            return 0;
        }
        return simulateMaxExtractable(ext, maxWant);
    }

    private static int extractDonorEnergy(
            ServerLevel level, DuctBlockEntity donorBe, Direction donorFace, int maxExtract) {
        DuctFaceLanes lanes = donorBe.getFaceLanes(donorFace);
        int fromBuf = Math.min(maxExtract, lanes.energyInputBufferFe);
        if (fromBuf > 0) {
            return donorBe.extractEnergyInputBuffer(donorFace, fromBuf, false);
        }
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, donorBe.getBlockPos(), donorFace);
        if (ext == null || !ext.canExtract()) {
            return 0;
        }
        int can = simulateMaxExtractable(ext, maxExtract);
        if (can <= 0) {
            return 0;
        }
        return ext.extractEnergy(Math.min(can, maxExtract), false);
    }

    private static boolean canAcceptNetworkInsert(ServerLevel level, DuctBlockEntity be, Direction face, int probe) {
        int outCap = DuctModuleEffects.effectiveEnergyOutputBufferCapFe(be, face, be.energyTransportSpec());
        if (be.getFaceLanes(face).energyOutputBufferFe < outCap) {
            return true;
        }
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        return ext != null && ext.canReceive() && simulateMaxReceivable(ext, probe) > 0;
    }

    private static @Nullable IEnergyStorage getExternalEnergyHandlerOnFace(
            ServerLevel level, BlockPos ductPos, Direction ductFace) {
        BlockPos neighbor = ductPos.relative(ductFace);
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, ductFace.getOpposite());
    }

    private static int simulateMaxExtractable(@Nullable IEnergyStorage src, int maxWant) {
        if (src == null || maxWant <= 0 || !src.canExtract()) {
            return 0;
        }
        int got = src.extractEnergy(maxWant, true);
        if (got > 0) {
            return got;
        }
        return src.extractEnergy(1, true);
    }

    private static int simulateMaxReceivable(@Nullable IEnergyStorage dest, int maxWant) {
        if (dest == null || maxWant <= 0 || !dest.canReceive()) {
            return 0;
        }
        int got = dest.receiveEnergy(maxWant, true);
        if (got > 0) {
            return got;
        }
        int one = dest.receiveEnergy(1, true);
        if (one <= 0) {
            return 0;
        }
        int best = one;
        int lo = 2;
        int hi = maxWant;
        while (lo <= hi) {
            int mid = lo + ((hi - lo) >>> 1);
            int r = dest.receiveEnergy(mid, true);
            if (r > 0) {
                best = Math.max(best, r);
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static void trySendEnergyRay(
            ServerLevel level,
            DuctFaceLanes rayLanes,
            BlockPos fromDuct,
            BlockPos toDuct,
            DuctEnergyTransportSpec spec,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        long now = level.getGameTime();
        long destKey = toDuct.asLong();
        if (rayLanes.lastEnergyRayGameTime >= 0
                && rayLanes.lastEnergyRayDestPos == destKey
                && now - rayLanes.lastEnergyRayGameTime < ENERGY_RAY_COOLDOWN_SAME_DEST_TICKS) {
            return;
        }
        if (sendRayIfVisible(level, fromDuct, toDuct, spec, sourceAttachFace, destAttachFace)) {
            rayLanes.lastEnergyRayGameTime = now;
            rayLanes.lastEnergyRayDestPos = destKey;
        }
    }

    private static boolean sendRayIfVisible(
            ServerLevel level,
            BlockPos fromDuct,
            BlockPos toDuct,
            DuctEnergyTransportSpec spec,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        Optional<List<BlockPos>> pathOpt =
                fromDuct.equals(toDuct)
                        ? Optional.of(List.of(fromDuct))
                        : DuctNetworkCache.shortestPath(level, fromDuct, toDuct, DuctNetworkType.ENERGY);
        if (pathOpt.isEmpty()) {
            if (!fromDuct.equals(toDuct)
                    && DuctNetworkCache.connectedDucts(level, fromDuct, DuctNetworkType.ENERGY).contains(toDuct)) {
                pathOpt = Optional.of(List.of(fromDuct, toDuct));
            }
        }
        if (pathOpt.isEmpty()) {
            return false;
        }
        List<BlockPos> path = pathOpt.get();
        for (BlockPos p : path) {
            if (level.getBlockEntity(p) instanceof DuctBlockEntity be && be.ductAlwaysOpaqueRendering()) {
                return false;
            }
        }
        int alphaByte = Math.min(255, Math.max(0, Math.round(Math.clamp(spec.rayAlpha(), 0f, 1f) * 255f)));
        if (alphaByte <= 0) {
            return false;
        }
        String rayColor = spec.rayColor();
        if (rayColor == null || rayColor.isBlank()) {
            rayColor = "#e30b28";
        }
        if (rayColor.equalsIgnoreCase("random")) {
            int rgb = level.random.nextInt(0x1000000);
            int argb = (alphaByte << 24) | (rgb & 0xFFFFFF);
            ModNetwork.sendEnergyRayPath(level, path, argb, midOf(path), sourceAttachFace, destAttachFace);
            return true;
        }
        String s = rayColor.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return false;
        }
        try {
            int rgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            int argb = (alphaByte << 24) | rgb;
            ModNetwork.sendEnergyRayPath(level, path, argb, midOf(path), sourceAttachFace, destAttachFace);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Vec3 midOf(List<BlockPos> path) {
        BlockPos a = path.getFirst();
        BlockPos b = path.getLast();
        return new Vec3(
                (a.getX() + b.getX()) / 2.0 + 0.5,
                (a.getY() + b.getY()) / 2.0 + 0.5,
                (a.getZ() + b.getZ()) / 2.0 + 0.5);
    }

    private static @Nullable DestCandidate pickWithinTier(
            ServerLevel level, List<DestCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(DestCandidate::dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(DestCandidate::dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DestCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(
                                Comparator.comparingDouble((DestCandidate c) -> Math.abs(c.dist() - mean))
                                        .thenComparingLong(c -> c.ductPos().asLong())
                                        .thenComparingInt(c -> c.face().ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                ArrayList<DestCandidate> ordered = new ArrayList<>(tier);
                ordered.sort(
                        Comparator.comparingLong((DestCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, ordered.size());
                yield ordered.get(i);
            }
            case RANDOM -> tier.get(level.random.nextInt(tier.size()));
        };
    }

    private static @Nullable DonorCandidate pickDonorWithinTier(
            ServerLevel level, List<DonorCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(DonorCandidate::dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(DonorCandidate::dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DonorCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(
                                Comparator.comparingDouble((DonorCandidate c) -> Math.abs(c.dist() - mean))
                                        .thenComparingLong(c -> c.ductPos().asLong())
                                        .thenComparingInt(c -> c.face().ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                ArrayList<DonorCandidate> ordered = new ArrayList<>(tier);
                ordered.sort(
                        Comparator.comparingLong((DonorCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, ordered.size());
                yield ordered.get(i);
            }
            case RANDOM -> tier.get(level.random.nextInt(tier.size()));
        };
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    private record DonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}
}
