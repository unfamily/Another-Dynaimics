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
import net.unfamily.another_dynamics.integration.iskalib.IskandertLibCompat;
import net.unfamily.another_dynamics.integration.iskalib.UnknownFluxCapabilities;
import net.unfamily.iskalib.flux.IUnknownFluxStorage;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctUnknownFluxTransportSpec;
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
 * Unknown Flux logistics: instant transfer only (no in-duct transit shipments).
 * <p><strong>Flux-lane family:</strong> behavioural changes often need equivalent updates for {@link DuctHeatServerTick},
 * {@link DuctFaceLanes} energy fields, and universal ducts with energy enabled (settings copier {@code EnergyHeat}).
 * <p>Per-face buffers and routing: see class body; rate from {@link DuctModuleEffects#ENERGY_ACTION_RATE_TICKS}.
 */
public final class DuctUnknownFluxServerTick {
    /** Min ticks between rays to the same duct destination on one face (RR to other targets is not throttled). */
    private static final int UNKNOWN_FLUX_RAY_COOLDOWN_SAME_DEST_TICKS = 6;

    private DuctUnknownFluxServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.UNKNOWN_FLUX)).orElse(false)) {
            return;
        }
        if (!IskandertLibCompat.isLoaded()) {
            return;
        }
        if (!be.isStorageAttachmentNode()) {
            return;
        }
        DuctUnknownFluxTransportSpec spec = be.unknownFluxTransportSpec();
        int sm = be.getStorageMask();

        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.UNKNOWN_FLUX)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (lanes.unknownFluxOutputBuffer > 0) {
                flushOutputBufferToNeighbor(level, be, dir, spec);
            }
        }

        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.UNKNOWN_FLUX)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            if (lanes.unknownFluxTicksUntilAction > 0) {
                lanes.unknownFluxTicksUntilAction--;
                continue;
            }
            int rate = DuctModuleEffects.effectiveUnknownFluxActionRateTicks(be, dir, spec);
            if (!DuctActionScheduling.isStaggerSlot(level, be.getBlockPos(), dir, rate)) {
                continue;
            }
            lanes.unknownFluxTicksUntilAction = Math.max(0, rate - 1);

            NodeMode nm = lanes.nodeMode;
            DuctFaceNode node = be.getFaceNode(dir);
            if (nm == NodeMode.EXTRACTION || nm == NodeMode.EXTRACTION_FILTERING) {
                RoutingMode rm = be.unknownFluxRoutingForFace(dir, true);
                pullExternalIntoInputBuffer(level, be, dir, spec);
                dispatchFromInputBuffer(level, be, dir, spec, rm, node);
            } else if (nm == NodeMode.RETRIEVING) {
                RoutingMode rm = be.unknownFluxRoutingForFace(dir, false);
                retrieveFromNetworkToOutput(level, be, dir, spec, rm, node);
                flushOutputBufferToNeighbor(level, be, dir, spec);
            } else if (nm == NodeMode.RETRIEVING_EXTRACTION) {
                RoutingMode rmR = be.unknownFluxRoutingForFace(dir, false);
                RoutingMode rmE = be.unknownFluxRoutingForFace(dir, true);
                retrieveFromNetworkToOutput(level, be, dir, spec, rmR, node);
                pullExternalIntoInputBuffer(level, be, dir, spec);
                dispatchFromInputBuffer(level, be, dir, spec, rmE, node);
            }
            be.setChanged();
        }
    }

    /** Stall recovery: try to move buffered FE back into the network or to a neighbor. */
    public static void tickStallBuffersForFace(
            DuctBlockEntity be, ServerLevel level, Direction face, DuctUnknownFluxTransportSpec spec) {
        DuctFaceLanes lanes = be.getFaceLanes(face);
        if (lanes.unknownFluxOutputBuffer > 0) {
            flushOutputBufferToNeighbor(level, be, face, spec);
        }
        if (lanes.unknownFluxInputBuffer > 0) {
            NodeMode nm = lanes.nodeMode;
            DuctFaceNode node = be.getFaceNode(face);
            if (nm == NodeMode.EXTRACTION || nm == NodeMode.EXTRACTION_FILTERING) {
                dispatchFromInputBuffer(level, be, face, spec, be.unknownFluxRoutingForFace(face, true), node);
            } else if (nm == NodeMode.RETRIEVING_EXTRACTION) {
                dispatchFromInputBuffer(level, be, face, spec, be.unknownFluxRoutingForFace(face, true), node);
            }
        }
        if (lanes.unknownFluxInputBuffer <= 0 && lanes.unknownFluxOutputBuffer <= 0) {
            be.syncStallVisualIfNeeded();
        }
    }

    private static void flushOutputBufferToNeighbor(
            ServerLevel level, DuctBlockEntity be, Direction face, DuctUnknownFluxTransportSpec spec) {
        long want = DuctModuleEffects.effectiveUnknownFluxExtractPerAction(be, face, spec);
        if (want <= 0) {
            return;
        }
        IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, be.getBlockPos(), face);
        if (ext == null || !ext.canReceiveUnknownFlux()) {
            return;
        }
        long stored = Math.max(0, be.getFaceLanes(face).unknownFluxOutputBuffer);
        if (stored <= 0) {
            return;
        }
        long move = Math.min(want, stored);
        move = Math.min(move, simulateMaxReceivable(ext, move));
        if (move <= 0) {
            return;
        }
        DuctFaceLanes lanes = be.getFaceLanes(face);
        long inserted = ext.insertUnknownFlux(move, false);
        if (inserted > 0) {
            lanes.unknownFluxOutputBuffer = Math.max(0, lanes.unknownFluxOutputBuffer - inserted);
            be.setChanged();
            trySendUnknownFluxRay(
                    level,
                    lanes,
                    be.getBlockPos(),
                    be.getBlockPos(),
                    spec,
                    face,
                    face);
            if (lanes.unknownFluxInputBuffer <= 0 && lanes.unknownFluxOutputBuffer <= 0) {
                be.syncStallVisualIfNeeded();
            }
        }
    }

    private static void pullExternalIntoInputBuffer(
            ServerLevel level, DuctBlockEntity be, Direction face, DuctUnknownFluxTransportSpec spec) {
        IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, be.getBlockPos(), face);
        if (ext == null || !ext.canExtractUnknownFlux()) {
            return;
        }
        DuctFaceLanes lanes = be.getFaceLanes(face);
        long cap = DuctModuleEffects.effectiveUnknownFluxInputBufferCap(be, face, spec);
        long stored = Math.max(0, lanes.unknownFluxInputBuffer);
        long free = Math.max(0, cap - stored);
        if (free <= 0) {
            return;
        }
        long want = Math.min(DuctModuleEffects.effectiveUnknownFluxExtractPerAction(be, face, spec), free);
        long canPull = simulateMaxExtractable(ext, want);
        if (canPull <= 0) {
            return;
        }
        long extracted = ext.extractUnknownFlux(Math.min(canPull, want), false);
        if (extracted > 0) {
            lanes.unknownFluxInputBuffer = stored + extracted;
            be.setChanged();
        }
    }

    private static void dispatchFromInputBuffer(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctUnknownFluxTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes sourceLanes = sourceBe.getFaceLanes(sourceFace);
        long stored = sourceLanes.unknownFluxInputBuffer;
        if (stored <= 0) {
            return;
        }
        long want = Math.min(DuctModuleEffects.effectiveUnknownFluxExtractPerAction(sourceBe, sourceFace, spec), stored);
        if (want <= 0) {
            return;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        var ducts = DuctNetworkCache.storageAttachmentMembers(level, srcPos, DuctNetworkType.UNKNOWN_FLUX);
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
                            : DuctNetworkCache.hopDistance(level, srcPos, destPos, DuctNetworkType.UNKNOWN_FLUX);
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
        int[] rr = new int[] {sourceLanes.unknownFluxRoundRobinCursor};
        DestCandidate pick = pickWithinTier(level, tier, routing, rr);
        sourceLanes.unknownFluxRoundRobinCursor = rr[0];
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
            DuctUnknownFluxTransportSpec spec,
            DuctFaceLanes sourceLanes,
            long want,
            DestCandidate pick) {
        if (!(level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        long avail = Math.min(want, sourceLanes.unknownFluxInputBuffer);
        if (avail <= 0) {
            return false;
        }
        long move = avail;
        IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, destBe.getBlockPos(), pick.face());
        long extRecv = ext != null && ext.canReceiveUnknownFlux() ? simulateMaxReceivable(ext, move) : 0;
        long outFree =
                Math.max(
                        0,
                        DuctModuleEffects.effectiveUnknownFluxOutputBufferCap(destBe, pick.face(), destBe.unknownFluxTransportSpec())
                                - destBe.getFaceLanes(pick.face()).unknownFluxOutputBuffer);
        long bufRecv = Math.min(move, outFree);
        if (extRecv <= 0 && bufRecv <= 0) {
            return false;
        }
        long extracted = sourceBe.extractUnknownFluxInputBuffer(sourceFace, move, false);
        if (extracted <= 0) {
            return false;
        }
        long left = extracted;
        if (extRecv > 0) {
            long toExt = Math.min(left, extRecv);
            long ins = ext.insertUnknownFlux(toExt, false);
            left -= ins;
        }
        if (left > 0 && bufRecv > 0) {
            left -= destBe.depositUnknownFluxOutputBuffer(pick.face(), left, false);
        }
        if (left > 0) {
            sourceLanes.unknownFluxInputBuffer += left;
            sourceBe.setChanged();
        }
        long moved = extracted - left;
        if (moved > 0) {
            trySendUnknownFluxRay(
                    level,
                    sourceLanes,
                    sourceBe.getBlockPos(),
                    pick.ductPos(),
                    spec,
                    sourceFace,
                    pick.face());
        }
        if (sourceLanes.unknownFluxInputBuffer <= 0 && sourceLanes.unknownFluxOutputBuffer <= 0) {
            sourceBe.syncStallVisualIfNeeded();
        }
        return moved > 0;
    }

    private static void retrieveFromNetworkToOutput(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctUnknownFluxTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        long want = DuctModuleEffects.effectiveUnknownFluxExtractPerAction(retrieverBe, retrieverFace, spec);
        if (want <= 0) {
            return;
        }
        long outCap = DuctModuleEffects.effectiveUnknownFluxOutputBufferCap(retrieverBe, retrieverFace, spec);
        long outStored = Math.max(0, retrieverLanes.unknownFluxOutputBuffer);
        long outFree = Math.max(0, outCap - outStored);
        if (outFree <= 0 && !canAcceptNetworkInsert(level, retrieverBe, retrieverFace, 1)) {
            return;
        }
        long budget = outFree > 0 ? Math.min(want, outFree) : want;

        List<BlockPos> ducts =
                new ArrayList<>(DuctNetworkCache.storageAttachmentMembers(level, retrieverPos, DuctNetworkType.UNKNOWN_FLUX));
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
                long avail = availableDonorUnknownFlux(level, donorBe, donorFace, budget);
                if (avail <= 0) {
                    continue;
                }
                OptionalLong dist =
                        donorPos.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctNetworkCache.hopDistance(level, retrieverPos, donorPos, DuctNetworkType.UNKNOWN_FLUX);
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
        int[] rr = new int[] {retrieverLanes.unknownFluxRoundRobinCursor};
        DonorCandidate pick = pickDonorWithinTier(level, tier, routing, rr);
        retrieverLanes.unknownFluxRoundRobinCursor = rr[0];
        if (pick == null) {
            retrieverBe.setChanged();
            return;
        }
        if (!(level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity donorBe)) {
            retrieverBe.setChanged();
            return;
        }
        long pull = Math.min(budget, availableDonorUnknownFlux(level, donorBe, pick.face(), budget));
        if (pull <= 0) {
            retrieverBe.setChanged();
            return;
        }
        long extracted = extractDonorUnknownFlux(level, donorBe, pick.face(), pull);
        if (extracted <= 0) {
            retrieverBe.setChanged();
            return;
        }
        long left = extracted;
        long toOut = Math.min(left, outFree);
        if (toOut > 0) {
            left -= retrieverBe.depositUnknownFluxOutputBuffer(retrieverFace, toOut, false);
        }
        if (left > 0) {
            IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, retrieverPos, retrieverFace);
            if (ext != null && ext.canReceiveUnknownFlux()) {
                left -= ext.insertUnknownFlux(left, false);
            }
        }
        long moved = extracted - left;
        if (moved > 0) {
            DuctFaceLanes donorLanes = donorBe.getFaceLanes(pick.face());
            trySendUnknownFluxRay(
                    level,
                    donorLanes,
                    pick.ductPos(),
                    retrieverPos,
                    donorBe.unknownFluxTransportSpec(),
                    pick.face(),
                    retrieverFace);
        }
        retrieverBe.setChanged();
    }

    private static long availableDonorUnknownFlux(
            ServerLevel level, DuctBlockEntity donorBe, Direction donorFace, long maxWant) {
        DuctFaceLanes lanes = donorBe.getFaceLanes(donorFace);
        long fromBuf = Math.min(maxWant, lanes.unknownFluxInputBuffer);
        if (fromBuf > 0) {
            return fromBuf;
        }
        IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, donorBe.getBlockPos(), donorFace);
        if (ext == null || !ext.canExtractUnknownFlux()) {
            return 0;
        }
        return simulateMaxExtractable(ext, maxWant);
    }

    private static long extractDonorUnknownFlux(
            ServerLevel level, DuctBlockEntity donorBe, Direction donorFace, long maxExtract) {
        DuctFaceLanes lanes = donorBe.getFaceLanes(donorFace);
        long fromBuf = Math.min(maxExtract, lanes.unknownFluxInputBuffer);
        if (fromBuf > 0) {
            return donorBe.extractUnknownFluxInputBuffer(donorFace, fromBuf, false);
        }
        IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, donorBe.getBlockPos(), donorFace);
        if (ext == null || !ext.canExtractUnknownFlux()) {
            return 0;
        }
        long can = simulateMaxExtractable(ext, maxExtract);
        if (can <= 0) {
            return 0;
        }
        return ext.extractUnknownFlux(Math.min(can, maxExtract), false);
    }

    private static boolean canAcceptNetworkInsert(ServerLevel level, DuctBlockEntity be, Direction face, long probe) {
        long outCap = DuctModuleEffects.effectiveUnknownFluxOutputBufferCap(be, face, be.unknownFluxTransportSpec());
        if (be.getFaceLanes(face).unknownFluxOutputBuffer < outCap) {
            return true;
        }
        IUnknownFluxStorage ext = getExternalUnknownFluxOnFace(level, be.getBlockPos(), face);
        return ext != null && ext.canReceiveUnknownFlux() && simulateMaxReceivable(ext, probe) > 0;
    }

    private static @Nullable IUnknownFluxStorage getExternalUnknownFluxOnFace(
            ServerLevel level, BlockPos ductPos, Direction ductFace) {
        if (!IskandertLibCompat.isLoaded()) {
            return null;
        }
        BlockPos neighbor = ductPos.relative(ductFace);
        return level.getCapability(UnknownFluxCapabilities.BLOCK, neighbor, ductFace.getOpposite());
    }

    private static long simulateMaxExtractable(@Nullable IUnknownFluxStorage src, long maxWant) {
        if (src == null || maxWant <= 0 || !src.canExtractUnknownFlux()) {
            return 0;
        }
        long got = src.extractUnknownFlux(maxWant, true);
        if (got > 0) {
            return got;
        }
        return src.extractUnknownFlux(1, true);
    }

    private static long simulateMaxReceivable(@Nullable IUnknownFluxStorage dest, long maxWant) {
        if (dest == null || maxWant <= 0 || !dest.canReceiveUnknownFlux()) {
            return 0;
        }
        long got = dest.insertUnknownFlux(maxWant, true);
        if (got > 0) {
            return got;
        }
        long one = dest.insertUnknownFlux(1, true);
        if (one <= 0) {
            return 0;
        }
        long best = one;
        long lo = 2;
        long hi = maxWant;
        while (lo <= hi) {
            long mid = lo + ((hi - lo) >>> 1);
            long r = dest.insertUnknownFlux(mid, true);
            if (r > 0) {
                best = Math.max(best, r);
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static void trySendUnknownFluxRay(
            ServerLevel level,
            DuctFaceLanes rayLanes,
            BlockPos fromDuct,
            BlockPos toDuct,
            DuctUnknownFluxTransportSpec spec,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        long now = level.getGameTime();
        long destKey = toDuct.asLong();
        if (rayLanes.lastUnknownFluxRayGameTime >= 0
                && rayLanes.lastUnknownFluxRayDestPos == destKey
                && now - rayLanes.lastUnknownFluxRayGameTime < UNKNOWN_FLUX_RAY_COOLDOWN_SAME_DEST_TICKS) {
            return;
        }
        if (sendRayIfVisible(level, fromDuct, toDuct, spec, sourceAttachFace, destAttachFace)) {
            rayLanes.lastUnknownFluxRayGameTime = now;
            rayLanes.lastUnknownFluxRayDestPos = destKey;
        }
    }

    private static boolean sendRayIfVisible(
            ServerLevel level,
            BlockPos fromDuct,
            BlockPos toDuct,
            DuctUnknownFluxTransportSpec spec,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        Optional<List<BlockPos>> pathOpt =
                fromDuct.equals(toDuct)
                        ? Optional.of(List.of(fromDuct))
                        : DuctNetworkCache.shortestPath(level, fromDuct, toDuct, DuctNetworkType.UNKNOWN_FLUX);
        if (pathOpt.isEmpty()) {
            if (!fromDuct.equals(toDuct)
                    && DuctNetworkCache.connectedDucts(level, fromDuct, DuctNetworkType.UNKNOWN_FLUX).contains(toDuct)) {
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
        float alpha = spec.rayAlpha();
        if (alpha <= 0f) {
            return false;
        }
        ModNetwork.sendUnknownFluxRayPath(level, path, sourceAttachFace, destAttachFace);
        return true;
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
            case RANDOM -> tier.get(level.getRandom().nextInt(tier.size()));
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
            case RANDOM -> tier.get(level.getRandom().nextInt(tier.size()));
        };
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    private record DonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}
}
