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
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.network.ModNetwork;
import org.jetbrains.annotations.Nullable;

/**
 * Energy (Forge Energy / RF) logistics: instant transfer only (no in-duct transit shipments).
 *
 * <p>Per action, the amount moved is bounded by {@code extract} of the source duct type and the bottleneck
 * {@code transfer} across the chosen duct path (minimum transfer along the path).</p>
 */
public final class DuctEnergyServerTick {
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
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            DuctFaceNode node = be.getFaceNode(dir);
            if (lanes.energyTicksUntilAction > 0) {
                lanes.energyTicksUntilAction--;
                be.setChanged();
                continue;
            }
            int rateTicks = DuctModuleEffects.effectiveEnergyActionRateTicks(be, dir, 10);
            lanes.energyTicksUntilAction = rateTicks - 1;
            if (lanes.nodeMode == NodeMode.EXTRACTION || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING) {
                RoutingMode rm = lanes.nodeMode.isHybrid() ? node.routingModeExtractor : node.routingMode;
                // Always pull into the duct buffer first, then push from buffer.
                // This prevents loss when the destination accepts less than expected and the buffer is full.
                pullExternalEnergyIntoBuffer(level, be, dir, spec);
                if (lanes.energyBufferFe > 0) {
                    tryExtractPushFromBuffer(level, be, dir, spec, rm, node);
                }
            } else if (lanes.nodeMode == NodeMode.RETRIEVING) {
                RoutingMode rm = lanes.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
                tryRetrievePull(level, be, dir, spec, rm, node);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                RoutingMode rmR = lanes.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
                RoutingMode rmE = lanes.nodeMode.isHybrid() ? node.routingModeExtractor : node.routingMode;
                tryRetrievePull(level, be, dir, spec, rmR, node);
                pullExternalEnergyIntoBuffer(level, be, dir, spec);
                if (lanes.energyBufferFe > 0) {
                    tryExtractPushFromBuffer(level, be, dir, spec, rmE, node);
                }
            } else if (lanes.nodeMode == NodeMode.NONE || lanes.nodeMode == NodeMode.FILTERING_INSERTION) {
                // Pass-through for buffered FE: if something injected into the duct buffer (e.g. Flux),
                // still try to push it onward even when the face is "NONE".
                if (lanes.energyBufferFe > 0) {
                    tryExtractPushFromBuffer(level, be, dir, spec, node.routingMode, node);
                }
            }
        }
    }

    private static void pullExternalEnergyIntoBuffer(ServerLevel level, DuctBlockEntity be, Direction face, DuctEnergyTransportSpec spec) {
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext == null || !ext.canExtract()) {
            return;
        }
        IEnergyStorage buf = be.energyBufferCapability(face);
        int bufCap = buf != null ? buf.getMaxEnergyStored() : 0;
        if (bufCap <= 0) {
            return;
        }
        DuctFaceLanes lanes = be.getFaceLanes(face);
        int stored = Math.max(0, lanes.energyBufferFe);
        int free = Math.max(0, bufCap - stored);
        if (free <= 0) {
            return;
        }
        long wantL = Math.min(DuctModuleEffects.effectiveEnergyExtractPerAction(be, face, spec), (long) Integer.MAX_VALUE);
        int want = (int) Math.max(0L, wantL);
        int pull = Math.min(want, free);
        if (pull <= 0) {
            return;
        }
        int extracted = ext.extractEnergy(pull, false);
        if (extracted <= 0) {
            return;
        }
        lanes.energyBufferFe = stored + extracted;
        be.setChanged();
    }

    private static @Nullable IEnergyStorage getExternalEnergyHandlerOnFace(ServerLevel level, BlockPos ductPos, Direction ductFace) {
        BlockPos neighbor = ductPos.relative(ductFace);
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, ductFace.getOpposite());
    }

    private static @Nullable IEnergyStorage getEnergySourceOnFace(ServerLevel level, DuctBlockEntity be, Direction face) {
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext != null && ext.canExtract()) {
            return ext;
        }
        IEnergyStorage buf = be.energyBufferCapability(face);
        return (buf != null && buf.canExtract()) ? buf : null;
    }

    private static @Nullable IEnergyStorage getEnergyBufferOnlySource(DuctBlockEntity be, Direction face) {
        IEnergyStorage buf = be.energyBufferCapability(face);
        return (buf != null && buf.canExtract()) ? buf : null;
    }

    private static @Nullable IEnergyStorage getEnergyDestOnFace(ServerLevel level, DuctBlockEntity be, Direction face) {
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext != null && ext.canReceive()) {
            return ext;
        }
        IEnergyStorage buf = be.energyBufferCapability(face);
        return (buf != null && buf.canReceive()) ? buf : null;
    }

    private static void tryExtractPushFromBuffer(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        IEnergyStorage src = getEnergyBufferOnlySource(sourceBe, sourceFace);
        if (src == null) {
            return;
        }
        tryExtractPush(level, sourceBe, sourceFace, spec, routing, node, src);
    }

    private static void tryExtractPush(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node,
            IEnergyStorage src) {
        DuctFaceLanes sourceLanes = sourceBe.getFaceLanes(sourceFace);
        BlockPos srcPos = sourceBe.getBlockPos();

        long want = Math.min(DuctModuleEffects.effectiveEnergyExtractPerAction(sourceBe, sourceFace, spec), Integer.MAX_VALUE);
        if (want <= 0) {
            return;
        }
        int availableSim = src.extractEnergy((int) want, true);
        if (availableSim <= 0) {
            return;
        }

        ArrayList<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, srcPos, DuctNetworkType.ENERGY));
        if (ducts.size() > 1) {
            ducts.remove(srcPos);
        }
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
                            : hopDistance(level, srcPos, destPos);
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
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

                IEnergyStorage dest = getEnergyDestOnFace(level, destBe, df);
                if (dest == null) {
                    continue;
                }

                int recvSim = dest.receiveEnergy(availableSim, true);
                if (recvSim <= 0) {
                    continue;
                }
                int priority = destBe.getFaceNode(df).insertionPriority;
                cands.add(new DestCandidate(destPos, df, priority, dist.orElse(Long.MAX_VALUE), recvSim));
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
        int rr = sourceLanes.energyRoundRobinCursor;
        DestCandidate pick = pickWithinTier(level, tier, routing, rr);
        if (pick == null) {
            return;
        }
        if (routing == RoutingMode.ROUND_ROBIN) {
            sourceLanes.energyRoundRobinCursor = rr + 1;
            sourceBe.setChanged();
        }

        {
            long bottleneck = bottleneckTransferAlongPath(level, srcPos, pick.ductPos());
            if (bottleneck <= 0) {
                return;
            }
            int cap = (int) Math.min((long) pick.moved(), Math.min(bottleneck, (long) Integer.MAX_VALUE));
            if (cap <= 0) {
                return;
            }
            IEnergyStorage dest =
                    level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity destBe2
                            ? getEnergyDestOnFace(level, destBe2, pick.face())
                            : null;
            if (dest == null) {
                return;
            }
            int recv = dest.receiveEnergy(cap, true);
            if (recv <= 0) {
                return;
            }
            int extracted = src.extractEnergy(recv, false);
            if (extracted <= 0) {
                return;
            }
            int inserted = dest.receiveEnergy(extracted, false);
            if (inserted <= 0) {
                return;
            }
            if (inserted < extracted) {
                // Never refund to source: buffer remainder in the duct on this face.
                int rem = extracted - inserted;
                DuctFaceLanes lanes = sourceBe.getFaceLanes(sourceFace);
                IEnergyStorage bufCap = sourceBe.energyBufferCapability(sourceFace);
                int capBuf = bufCap != null ? bufCap.getMaxEnergyStored() : Integer.MAX_VALUE;
                int stored = Math.max(0, lanes.energyBufferFe);
                int accept = Math.min(rem, Math.max(0, capBuf - stored));
                if (accept > 0) {
                    lanes.energyBufferFe = stored + accept;
                    sourceBe.setChanged();
                }
            }
            sendRayIfVisible(level, srcPos, pick.ductPos(), sourceBe.energyTransportSpec());
        }
    }

    private static void tryRetrievePull(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        IEnergyStorage dest = getEnergyDestOnFace(level, retrieverBe, retrieverFace);
        if (dest == null) {
            return;
        }
        IEnergyStorage buf = retrieverBe.energyBufferCapability(retrieverFace);
        int bufCap = buf != null ? buf.getMaxEnergyStored() : 0;
        int stored = Math.max(0, retrieverLanes.energyBufferFe);
        int free = Math.max(0, bufCap - stored);
        long wantL = Math.min(DuctModuleEffects.effectiveEnergyExtractPerAction(retrieverBe, retrieverFace, spec), (long) Integer.MAX_VALUE);
        int want = (int) Math.max(0L, wantL);
        // If we have no buffer space and the destination can't accept anything, nothing to do.
        int needSim = dest.receiveEnergy(want, true);
        if (free <= 0 && needSim <= 0) {
            return;
        }

        List<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.ENERGY));
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
                IEnergyStorage src = getEnergySourceOnFace(level, donorBe, donorFace);
                if (src == null) {
                    continue;
                }
                OptionalLong dist =
                        donorPos.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : hopDistance(level, retrieverPos, donorPos);
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
        DonorCandidate pick = pickDonorWithinTier(level, tier, routing, retrieverLanes.energyRoundRobinCursor);
        if (pick == null) {
            return;
        }

        IEnergyStorage src =
                level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity donorBe2
                        ? getEnergySourceOnFace(level, donorBe2, pick.face())
                        : null;
        if (src == null) {
            return;
        }
        // Pull from donor into the retriever's duct buffer first (bounded by buffer free space and bottleneck).
        int pullBudget = Math.min(want, Math.max(0, free));
        if (pullBudget <= 0) {
            // No buffer space; still allow direct insert if destination can receive (fallback).
            pullBudget = Math.max(0, needSim);
        }
        if (pullBudget <= 0) {
            return;
        }
        int availSim = src.extractEnergy(pullBudget, true);
        if (availSim <= 0) {
            return;
        }
        long bottleneck = bottleneckTransferAlongPath(level, pick.ductPos(), retrieverPos);
        if (bottleneck <= 0) {
            return;
        }
        int cap = (int) Math.min((long) availSim, Math.min(bottleneck, (long) Integer.MAX_VALUE));
        if (cap <= 0) {
            return;
        }
        int extracted = src.extractEnergy(cap, false);
        if (extracted <= 0) {
            return;
        }
        // Store into buffer (bounded).
        if (bufCap > 0) {
            int stored2 = Math.max(0, retrieverLanes.energyBufferFe);
            int free2 = Math.max(0, bufCap - stored2);
            int accept = Math.min(extracted, free2);
            if (accept > 0) {
                retrieverLanes.energyBufferFe = stored2 + accept;
                retrieverBe.setChanged();
            }
            // If buffer is full, keep a small direct insert fallback to avoid hard stalling.
            int rem = extracted - accept;
            if (rem > 0) {
                int ins = dest.receiveEnergy(rem, false);
                if (ins < rem) {
                    // Any remainder is intentionally NOT extracted unless it can be placed somewhere;
                    // so rem-ins is effectively prevented by the accept cap above.
                }
            }
        } else {
            // No buffer available: direct insert only.
            dest.receiveEnergy(extracted, false);
        }
        // Always try to flush buffer into the local destination right away.
        pushBufferToLocalDest(level, retrieverBe, retrieverFace, dest, want);
        if (level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity donor) {
            sendRayIfVisible(level, pick.ductPos(), retrieverPos, donor.energyTransportSpec());
        }
    }

    private static void pushBufferToLocalDest(
            ServerLevel level,
            DuctBlockEntity be,
            Direction face,
            IEnergyStorage dest,
            int want) {
        IEnergyStorage bufSrc = getEnergyBufferOnlySource(be, face);
        if (bufSrc == null) {
            return;
        }
        int avail = bufSrc.extractEnergy(want, true);
        if (avail <= 0) {
            return;
        }
        int recvSim = dest.receiveEnergy(avail, true);
        if (recvSim <= 0) {
            return;
        }
        int extracted = bufSrc.extractEnergy(recvSim, false);
        if (extracted <= 0) {
            return;
        }
        dest.receiveEnergy(extracted, false);
    }

    private static void sendRayIfVisible(ServerLevel level, BlockPos fromDuct, BlockPos toDuct, DuctEnergyTransportSpec spec) {
        if (level == null) {
            return;
        }
        Optional<List<BlockPos>> pathOpt =
                fromDuct.equals(toDuct)
                        ? Optional.of(List.of(fromDuct))
                        : DuctPathfinder.shortestPath(level, fromDuct, toDuct, 1L, DuctNetworkType.ENERGY);
        if (pathOpt.isEmpty()) {
            return;
        }
        List<BlockPos> path = pathOpt.get();
        // If any duct is always-opaque, skip the ray.
        for (BlockPos p : path) {
            if (level.getBlockEntity(p) instanceof DuctBlockEntity be && be.ductAlwaysOpaqueRendering()) {
                return;
            }
        }
        int alphaByte = Math.min(255, Math.max(0, Math.round(Math.clamp(spec.rayAlpha(), 0f, 1f) * 255f)));
        if (alphaByte <= 0) {
            return;
        }
        String rayColor = spec.rayColor();
        if (rayColor == null || rayColor.isBlank()) {
            rayColor = "#e30b28";
        }
        if (rayColor.equalsIgnoreCase("random")) {
            int rgb = level.random.nextInt(0x1000000);
            int argb = (alphaByte << 24) | (rgb & 0xFFFFFF);
            ModNetwork.sendEnergyRayPath(level, path, argb, midOf(path));
            return;
        }
        String s = rayColor.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return;
        }
        try {
            int rgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            int argb = (alphaByte << 24) | rgb;
            ModNetwork.sendEnergyRayPath(level, path, argb, midOf(path));
        } catch (NumberFormatException ignored) {
        }
    }

    private static Vec3 midOf(List<BlockPos> path) {
        BlockPos a = path.getFirst();
        BlockPos b = path.getLast();
        return new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, (a.getY() + b.getY()) / 2.0 + 0.5, (a.getZ() + b.getZ()) / 2.0 + 0.5);
    }

    private static OptionalLong hopDistance(ServerLevel level, BlockPos from, BlockPos to) {
        if (from.equals(to)) {
            return OptionalLong.of(0L);
        }
        Optional<List<BlockPos>> path = DuctPathfinder.shortestPath(level, from, to, 1L, DuctNetworkType.ENERGY);
        if (path.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(0L, path.get().size()));
    }

    private static long bottleneckTransferAlongPath(ServerLevel level, BlockPos fromDuct, BlockPos toDuct) {
        if (fromDuct.equals(toDuct)) {
            if (!(level.getBlockEntity(fromDuct) instanceof DuctBlockEntity be)) {
                return 0L;
            }
            return be.energyTransportSpec().clampedTransfer();
        }
        Optional<List<BlockPos>> pathOpt =
                DuctPathfinder.shortestPath(level, fromDuct, toDuct, 1L, DuctNetworkType.ENERGY);
        if (pathOpt.isEmpty()) {
            return 0L;
        }
        long min = Long.MAX_VALUE;
        for (BlockPos p : pathOpt.get()) {
            if (!(level.getBlockEntity(p) instanceof DuctBlockEntity be)) {
                return 0L;
            }
            long tr = be.energyTransportSpec().clampedTransfer();
            min = Math.min(min, tr);
            if (min <= 0L) {
                return 0L;
            }
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    private static @Nullable DestCandidate pickWithinTier(ServerLevel level, List<DestCandidate> tier, RoutingMode routing, int roundRobinCursor) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        orderTier(tier, routing, level, roundRobinCursor);
        return tier.getFirst();
    }

    private static void orderTier(List<DestCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(
                    Comparator.comparingLong(DestCandidate::dist)
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case FARTHEST_FIRST -> tier.sort(
                    Comparator.comparingLong(DestCandidate::dist).reversed()
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DestCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                tier.sort(
                        Comparator.comparingDouble((DestCandidate c) -> Math.abs(c.dist() - mean))
                                .thenComparingLong(c -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DestCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<DestCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                for (int i2 = tier.size() - 1; i2 > 0; i2--) {
                    int j = level.random.nextInt(i2 + 1);
                    DestCandidate a = tier.get(i2);
                    tier.set(i2, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private static @Nullable DonorCandidate pickDonorWithinTier(ServerLevel level, List<DonorCandidate> tier, RoutingMode routing, int roundRobinCursor) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        orderDonorTier(tier, routing, level, roundRobinCursor);
        return tier.getFirst();
    }

    private static void orderDonorTier(List<DonorCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(
                    Comparator.comparingLong(DonorCandidate::dist)
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case FARTHEST_FIRST -> tier.sort(
                    Comparator.comparingLong(DonorCandidate::dist).reversed()
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DonorCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                tier.sort(
                        Comparator.comparingDouble((DonorCandidate c) -> Math.abs(c.dist() - mean))
                                .thenComparingLong(c -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DonorCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<DonorCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                for (int i2 = tier.size() - 1; i2 > 0; i2--) {
                    int j = level.random.nextInt(i2 + 1);
                    DonorCandidate a = tier.get(i2);
                    tier.set(i2, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist, int moved) {}
    private record DonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}
}

