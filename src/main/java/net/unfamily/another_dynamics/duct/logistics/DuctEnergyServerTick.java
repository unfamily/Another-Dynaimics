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
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctEnergyTransportSpec;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
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
 * <p>Per-face action rate is {@link DuctModuleEffects#ENERGY_ACTION_RATE_TICKS} ticks. Per action,
 * the amount moved is bounded by module-scaled {@code extract} and adjacent {@link IEnergyStorage} acceptance.
 * Network topology queries are cached per level ({@link DuctNetworkCache}).</p>
 */
public final class DuctEnergyServerTick {
    /** Minimum ticks between energy ray packets per face (sustained transfers). */
    private static final int ENERGY_RAY_COOLDOWN_TICKS = 40;
    /** One network dispatch per buffer-drain call so round-robin advances like fluid/gas rate actions. */
    private static final int MAX_BUFFER_DISPATCHES_PER_DRAIN = 1;

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
        // NONE/FILTERING: external machines push into the face buffer — drain every tick (heat has no FE buffer).
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.ENERGY)) {
                continue;
            }
            DuctFaceLanes drainLanes = be.getFaceLanes(dir);
            if (drainLanes.energyBufferFe <= 0) {
                continue;
            }
            if (!DuctBlockEntity.isExternalEnergyBufferPassThrough(drainLanes.nodeMode)) {
                continue;
            }
            tryDrainEnergyBufferForFace(be, level, dir, spec);
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
            DuctFaceNode node = be.getFaceNode(dir);
            if (lanes.energyTicksUntilAction > 0) {
                lanes.energyTicksUntilAction--;
                be.setChanged();
                continue;
            }
            int rate = DuctModuleEffects.effectiveEnergyActionRateTicks(be, dir, spec);
            lanes.energyTicksUntilAction = rate - 1;
            if (lanes.nodeMode == NodeMode.EXTRACTION || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING) {
                RoutingMode rm = be.energyRoutingForFace(dir, true);
                if (lanes.energyBufferFe > 0) {
                    tryDrainEnergyBufferForFace(be, level, dir, spec);
                }
                pullExternalEnergyIntoBuffer(level, be, dir, spec);
                if (lanes.energyBufferFe > 0) {
                    tryDrainEnergyBufferForFace(be, level, dir, spec);
                }
                tryExtractPushFromFaceSource(level, be, dir, spec, rm, node);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING) {
                RoutingMode rm = be.energyRoutingForFace(dir, false);
                tryRetrievePull(level, be, dir, spec, rm, node);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                RoutingMode rmR = be.energyRoutingForFace(dir, false);
                RoutingMode rmE = be.energyRoutingForFace(dir, true);
                tryRetrievePull(level, be, dir, spec, rmR, node);
                tryExtractPushFromFaceSource(level, be, dir, spec, rmE, node);
            } else if (lanes.nodeMode == NodeMode.NONE || lanes.nodeMode == NodeMode.FILTERING_INSERTION) {
                if (node.eligibilityMode.isRetrievable()) {
                    tryExtractPush(level, be, dir, spec, be.energyRoutingForFace(dir, true), node);
                }
            }
            if (lanes.nodeMode == NodeMode.RETRIEVING && lanes.energyBufferFe > 0) {
                tryFlushLocalEnergyToNeighbor(level, be, dir, spec);
            }
        }
    }

    /**
     * Push FE held in the duct face buffer toward network destinations (routing / round-robin apply).
     * Called every tick while {@code energyBufferFe > 0}, including when redstone has disabled the face.
     */
    public static void tryDrainEnergyBufferForFace(
            DuctBlockEntity be, ServerLevel level, Direction face, DuctEnergyTransportSpec spec) {
        DuctFaceLanes lanes = be.getFaceLanes(face);
        if (lanes.energyBufferFe <= 0) {
            return;
        }
        DuctFaceNode node = be.getFaceNode(face);
        NodeMode nm = lanes.nodeMode;
        RoutingMode rm = be.energyRoutingForFace(face, nm != NodeMode.RETRIEVING);
        if (nm == NodeMode.EXTRACTION || nm == NodeMode.EXTRACTION_FILTERING) {
            drainBufferWithRouting(level, be, face, spec, node, rm);
        } else if (nm == NodeMode.RETRIEVING_EXTRACTION) {
            drainBufferWithRouting(level, be, face, spec, node, be.energyRoutingForFace(face, true));
        } else if (nm == NodeMode.NONE || nm == NodeMode.FILTERING_INSERTION) {
            drainBufferWithRouting(level, be, face, spec, node, rm);
        } else if (nm == NodeMode.RETRIEVING) {
            tryFlushLocalEnergyToNeighbor(level, be, face, spec);
        }
        if (lanes.energyBufferFe <= 0) {
            be.syncStallVisualIfNeeded();
        }
    }

    /** Drain face buffer toward the network, rotating round-robin when multiple destinations accept. */
    private static void drainBufferWithRouting(
            ServerLevel level,
            DuctBlockEntity be,
            Direction face,
            DuctEnergyTransportSpec spec,
            DuctFaceNode node,
            RoutingMode routing) {
        for (int n = 0; n < MAX_BUFFER_DISPATCHES_PER_DRAIN; n++) {
            int before = be.getFaceLanes(face).energyBufferFe;
            if (before <= 0) {
                break;
            }
            tryExtractPushFromBuffer(level, be, face, spec, routing, node);
            if (be.getFaceLanes(face).energyBufferFe >= before) {
                break;
            }
        }
    }

    /** Push duct buffer / surplus into the adjacent machine on this face every tick (no destination buffer). */
    private static void tryFlushLocalEnergyToNeighbor(
            ServerLevel level, DuctBlockEntity be, Direction face, DuctEnergyTransportSpec spec) {
        int want = DuctModuleEffects.effectiveEnergyExtractPerAction(be, face, spec);
        if (want <= 0) {
            return;
        }
        IEnergyStorage dest = resolveEnergyInsertTarget(level, be, face, want);
        if (dest == null) {
            return;
        }
        pushBufferToLocalDest(level, be, face, dest, want);
    }

    private static @Nullable IEnergyStorage getExternalEnergyHandlerOnFace(ServerLevel level, BlockPos ductPos, Direction ductFace) {
        BlockPos neighbor = ductPos.relative(ductFace);
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, ductFace.getOpposite());
    }

    private static void pullExternalEnergyIntoBuffer(ServerLevel level, DuctBlockEntity be, Direction face, DuctEnergyTransportSpec spec) {
        if (!be.getFaceNode(face).eligibilityMode.isRetrievable()) {
            return;
        }
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
        int canPull = simulateMaxExtractable(ext, pull);
        if (canPull <= 0) {
            return;
        }
        int extracted = ext.extractEnergy(Math.min(canPull, pull), false);
        if (extracted <= 0) {
            return;
        }
        lanes.energyBufferFe = stored + extracted;
        be.setChanged();
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

    /** Same as heat {@link DuctHeatServerTick#tryExtractPush}: one network push per rate action from the face source. */
    private static void tryExtractPushFromFaceSource(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        tryExtractPush(level, sourceBe, sourceFace, spec, routing, node);
    }

    private static void tryExtractPushFromBuffer(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        IEnergyStorage buf = getEnergyBufferOnlySource(sourceBe, sourceFace);
        if (buf == null) {
            return;
        }
        tryExtractPushWithSource(level, sourceBe, sourceFace, spec, routing, node, buf);
    }

    private static void tryExtractPush(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        if (!node.eligibilityMode.isRetrievable()) {
            return;
        }
        IEnergyStorage src = getEnergySourceOnFace(level, sourceBe, sourceFace);
        if (src == null) {
            return;
        }
        tryExtractPushWithSource(level, sourceBe, sourceFace, spec, routing, node, src);
    }

    private static void tryExtractPushWithSource(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node,
            IEnergyStorage src) {
        DuctFaceLanes sourceLanes = sourceBe.getFaceLanes(sourceFace);
        BlockPos srcPos = sourceBe.getBlockPos();

        int want = DuctModuleEffects.effectiveEnergyExtractPerAction(sourceBe, sourceFace, spec);
        if (want <= 0) {
            return;
        }
        int availableSim = simulateMaxExtractable(src, want);
        if (availableSim <= 0) {
            return;
        }

        java.util.Set<BlockPos> ducts = DuctNetworkCache.connectedDucts(level, srcPos, DuctNetworkType.ENERGY);
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
                if (!canAttemptEnergyInsertOnFace(level, destBe, df)) {
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
        dispatchEnergyExtractToTier(
                level, sourceBe, sourceFace, spec, routing, sourceLanes, src, srcPos, tier, want, availableSim, rr);
        sourceLanes.energyRoundRobinCursor = rr[0];
        sourceBe.setChanged();
    }

    /** Same pick + single attempt as fluid/gas ({@link #pickWithinTier} advances RR cursor on pick). */
    private static boolean dispatchEnergyExtractToTier(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceLanes sourceLanes,
            IEnergyStorage src,
            BlockPos srcPos,
            ArrayList<DestCandidate> tier,
            int want,
            int availableSim,
            int[] roundRobinState) {
        DestCandidate pick = pickWithinTier(level, tier, routing, roundRobinState);
        if (pick == null) {
            return false;
        }
        return tryEnergyExtractToCandidate(
                level, sourceBe, sourceFace, spec, sourceLanes, src, srcPos, pick, want, availableSim);
    }

    private static boolean tryEnergyExtractToCandidate(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctEnergyTransportSpec spec,
            DuctFaceLanes sourceLanes,
            IEnergyStorage src,
            BlockPos srcPos,
            DestCandidate pick,
            int want,
            int availableSim) {
        int cap = Math.min(want, availableSim);
        if (cap <= 0) {
            return false;
        }
        DuctBlockEntity destBe2 =
                level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity be2 ? be2 : null;
        if (destBe2 == null) {
            return false;
        }
        IEnergyStorage dest = resolveEnergyInsertTarget(level, destBe2, pick.face(), cap);
        if (dest == null) {
            return false;
        }
        if (!transferEnergyFromSource(level, sourceBe, sourceFace, src, dest, cap)) {
            return false;
        }
        trySendEnergyRay(level, sourceLanes, srcPos, pick.ductPos(), sourceBe.energyTransportSpec(), sourceFace, pick.face());
        if (sourceLanes.energyBufferFe <= 0) {
            sourceBe.syncStallVisualIfNeeded();
        }
        return true;
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
        long wantL = Math.min(DuctModuleEffects.effectiveEnergyExtractPerAction(retrieverBe, retrieverFace, spec), (long) Integer.MAX_VALUE);
        int want = (int) Math.max(0L, wantL);
        if (want <= 0) {
            return;
        }
        IEnergyStorage dest = resolveEnergyInsertTarget(level, retrieverBe, retrieverFace, want);
        if (dest == null) {
            return;
        }
        IEnergyStorage buf = retrieverBe.energyBufferCapability(retrieverFace);
        int bufCap = buf != null ? buf.getMaxEnergyStored() : 0;
        int stored = Math.max(0, retrieverLanes.energyBufferFe);
        int free = Math.max(0, bufCap - stored);
        if (free <= 0 && !canAttemptEnergyInsertOnFace(level, retrieverBe, retrieverFace)) {
            return;
        }

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
                IEnergyStorage src = getEnergySourceOnFace(level, donorBe, donorFace);
                if (src == null || simulateMaxExtractable(src, want) <= 0) {
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
        dispatchEnergyRetrieveFromTier(
                level, retrieverBe, retrieverFace, spec, routing, retrieverLanes, dest, want, free, bufCap, tier, rr);
        retrieverLanes.energyRoundRobinCursor = rr[0];
        retrieverBe.setChanged();
    }

    private static boolean dispatchEnergyRetrieveFromTier(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctEnergyTransportSpec spec,
            RoutingMode routing,
            DuctFaceLanes retrieverLanes,
            IEnergyStorage dest,
            int want,
            int free,
            int bufCap,
            ArrayList<DonorCandidate> tier,
            int[] roundRobinState) {
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        DonorCandidate pick = pickDonorWithinTier(level, tier, routing, roundRobinState);
        if (pick == null) {
            return false;
        }
        return tryEnergyRetrieveFromDonor(
                level, retrieverBe, retrieverFace, spec, retrieverLanes, dest, want, free, bufCap, retrieverPos, pick);
    }

    private static boolean tryEnergyRetrieveFromDonor(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctEnergyTransportSpec spec,
            DuctFaceLanes retrieverLanes,
            IEnergyStorage dest,
            int want,
            int free,
            int bufCap,
            BlockPos retrieverPos,
            DonorCandidate pick) {
        IEnergyStorage src =
                level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity donorBe2
                        ? getEnergySourceOnFace(level, donorBe2, pick.face())
                        : null;
        if (src == null) {
            return false;
        }
        int pullBudget = Math.min(want, Math.max(0, free));
        if (pullBudget <= 0) {
            pullBudget = want;
        }
        if (pullBudget <= 0) {
            return false;
        }
        int availSim = simulateMaxExtractable(src, pullBudget);
        if (availSim <= 0) {
            return false;
        }
        int cap = (int) Math.min((long) availSim, (long) Integer.MAX_VALUE);
        cap = Math.min(cap, want);
        if (cap <= 0) {
            return false;
        }
        int extracted = src.extractEnergy(cap, false);
        if (extracted <= 0) {
            return false;
        }
        if (bufCap > 0) {
            int stored2 = Math.max(0, retrieverLanes.energyBufferFe);
            int free2 = Math.max(0, bufCap - stored2);
            int accept = Math.min(extracted, free2);
            if (accept > 0) {
                retrieverLanes.energyBufferFe = stored2 + accept;
                retrieverBe.setChanged();
            }
            int rem = extracted - accept;
            if (rem > 0) {
                dest.receiveEnergy(rem, false);
            }
        } else {
            dest.receiveEnergy(extracted, false);
        }
        pushBufferToLocalDest(level, retrieverBe, retrieverFace, dest, want);
        if (level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity donor) {
            trySendEnergyRay(level, retrieverLanes, pick.ductPos(), retrieverPos, donor.energyTransportSpec(), pick.face(), retrieverFace);
        }
        return true;
    }

    /**
     * Put energy back after a failed insert: same storage we extracted from first (usually the duct face buffer), then
     * face buffer, then adjacent handler. Never assume {@code canReceive()} means space — handlers often stay
     * {@code true} when full.
     */
    private static void refundExtractedEnergy(
            ServerLevel level, DuctBlockEntity be, Direction face, IEnergyStorage extractedFrom, int amount) {
        if (amount <= 0) {
            return;
        }
        int left = amount;
        if (extractedFrom != null && extractedFrom.canReceive()) {
            left -= extractedFrom.receiveEnergy(left, false);
        }
        if (left > 0) {
            refundEnergyRemainder(level, be, face, left);
        }
    }

    private static void refundEnergyRemainder(ServerLevel level, DuctBlockEntity be, Direction face, int amount) {
        if (amount <= 0) {
            return;
        }
        int left = amount;
        IEnergyStorage buf = be.energyBufferCapability(face);
        if (buf != null && buf.canReceive()) {
            left -= buf.receiveEnergy(left, false);
        }
        if (left <= 0) {
            return;
        }
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext != null && ext.canReceive()) {
            ext.receiveEnergy(left, false);
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
        transferEnergyFromSource(level, be, face, bufSrc, dest, want);
    }

    /**
     * Move up to {@code cap} FE from {@code src} into {@code dest}, using simulate hints when available but always
     * attempting a real insert so handlers that reject large simulate packets still receive partial amounts.
     */
    private static boolean transferEnergyFromSource(
            ServerLevel level,
            DuctBlockEntity be,
            Direction face,
            IEnergyStorage src,
            IEnergyStorage dest,
            int cap) {
        if (cap <= 0) {
            return false;
        }
        int move = Math.min(cap, simulateMaxExtractable(src, cap));
        if (move <= 0) {
            move = Math.min(cap, src.getEnergyStored());
        }
        if (move <= 0) {
            return false;
        }
        int extracted = src.extractEnergy(move, false);
        if (extracted <= 0) {
            return false;
        }
        int inserted = dest.receiveEnergy(extracted, false);
        if (inserted <= 0) {
            refundExtractedEnergy(level, be, face, src, extracted);
            return false;
        }
        if (inserted < extracted) {
            int rem = extracted - inserted;
            DuctFaceLanes lanes = be.getFaceLanes(face);
            IEnergyStorage bufCap = be.energyBufferCapability(face);
            int capBuf = bufCap != null ? bufCap.getMaxEnergyStored() : Integer.MAX_VALUE;
            int stored = Math.max(0, lanes.energyBufferFe);
            int accept = Math.min(rem, Math.max(0, capBuf - stored));
            if (accept > 0) {
                lanes.energyBufferFe = stored + accept;
                be.setChanged();
                rem -= accept;
            }
            if (rem > 0) {
                refundEnergyRemainder(level, be, face, rem);
            }
        }
        return true;
    }

    /** Whether this face can accept any FE right now ({@code canReceive()} alone is often true even when full). */
    private static boolean canAttemptEnergyInsertOnFace(ServerLevel level, DuctBlockEntity be, Direction face) {
        int probe = 1;
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        if (ext != null && ext.canReceive() && simulateMaxReceivable(ext, probe) > 0) {
            return true;
        }
        IEnergyStorage buf = be.energyBufferCapability(face);
        return buf != null && buf.canReceive() && simulateMaxReceivable(buf, probe) > 0;
    }

    /**
     * Pick an insert target: adjacent machine if it has space, else duct face buffer. Avoids always preferring a full
     * machine while the duct buffer could still accept.
     */
    private static @Nullable IEnergyStorage resolveEnergyInsertTarget(
            ServerLevel level, DuctBlockEntity be, Direction face, int maxWant) {
        if (maxWant <= 0) {
            return null;
        }
        IEnergyStorage ext = getExternalEnergyHandlerOnFace(level, be.getBlockPos(), face);
        IEnergyStorage buf = be.energyBufferCapability(face);
        int extRecv =
                ext != null && ext.canReceive() ? simulateMaxReceivable(ext, maxWant) : 0;
        int bufRecv =
                buf != null && buf.canReceive() ? simulateMaxReceivable(buf, maxWant) : 0;
        if (extRecv <= 0 && bufRecv <= 0) {
            return null;
        }
        if (extRecv > 0) {
            return ext;
        }
        return buf;
    }

    /**
     * Some {@link IEnergyStorage} handlers return 0 from simulate when they cannot extract the full requested packet.
     * Probe with 1 FE so partial pulls still work (e.g. generators with per-tick output caps).
     */
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

    /**
     * Some {@link IEnergyStorage} handlers return 0 from simulate when they cannot accept the full requested packet.
     * Probe with 1 FE so partial inserts still work (e.g. void miners with limited receive rate).
     */
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
        if (rayLanes.lastEnergyRayGameTime >= 0
                && now - rayLanes.lastEnergyRayGameTime < ENERGY_RAY_COOLDOWN_TICKS) {
            return;
        }
        if (sendRayIfVisible(level, fromDuct, toDuct, spec, sourceAttachFace, destAttachFace)) {
            rayLanes.lastEnergyRayGameTime = now;
        }
    }

    /** @return {@code true} if a ray packet was sent */
    private static boolean sendRayIfVisible(
            ServerLevel level,
            BlockPos fromDuct,
            BlockPos toDuct,
            DuctEnergyTransportSpec spec,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        if (level == null) {
            return false;
        }
        Optional<List<BlockPos>> pathOpt =
                fromDuct.equals(toDuct)
                        ? Optional.of(List.of(fromDuct))
                        : DuctNetworkCache.shortestPath(level, fromDuct, toDuct, DuctNetworkType.ENERGY);
        if (pathOpt.isEmpty()) {
            return false;
        }
        List<BlockPos> path = pathOpt.get();
        // If any duct is always-opaque, skip the ray.
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
        return new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, (a.getY() + b.getY()) / 2.0 + 0.5, (a.getZ() + b.getZ()) / 2.0 + 0.5);
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

    private static void orderDestTier(List<DestCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
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

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}
    private record DonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}
}

