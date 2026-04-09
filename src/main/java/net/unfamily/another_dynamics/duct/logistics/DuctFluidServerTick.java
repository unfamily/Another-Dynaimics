package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctChannelPolicy;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFluidAllowLimitLogic;
import net.unfamily.another_dynamics.duct.DuctFluidFilterLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;

/**
 * Fluid logistics: plan extract+fill with simulation only, enqueue {@link FluidTransitShipment}; {@link DuctBlockEntity}
 * executes transfer when travel completes and re-validates each tick in transit.
 */
public final class DuctFluidServerTick {
    private DuctFluidServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.FLUID)).orElse(false)) {
            return;
        }
        DuctFluidTransportSpec spec = be.fluidTransportSpec();
        int rate = spec.clampedRateTicks(spec.rateDefaultTicks());
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            DuctFaceNode node = lanes.fluid;
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            if (node.ticksUntilAction > 0) {
                node.ticksUntilAction--;
                be.setChanged();
                continue;
            }
            node.ticksUntilAction = rate - 1;
            if (lanes.nodeMode == NodeMode.EXTRACTION
                    || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING
                    || lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                tryExtractPush(level, be, dir, node, spec);
            }
        }
    }

    private static void tryExtractPush(
            ServerLevel level, DuctBlockEntity sourceBe, Direction sourceFace, DuctFaceNode node, DuctFluidTransportSpec spec) {
        BlockPos srcPos = sourceBe.getBlockPos();
        IFluidHandler srcCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, srcPos.relative(sourceFace), sourceFace.getOpposite());
        if (srcCap == null) {
            return;
        }
        int wantMb = node.extractBatch > 0 ? node.extractBatch : spec.batchDefaultMb();
        wantMb = spec.clampedBatchMb(wantMb);
        FluidStack available = drainProbe(srcCap, wantMb);
        if (available.isEmpty()) {
            return;
        }
        // Respect per-allow-line Keep (mB) on the source when allow filters are configured.
        int keepCap =
                DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                        srcCap,
                        node.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                        available,
                        level.registryAccess());
        if (keepCap != Integer.MAX_VALUE) {
            int capped = Math.min(available.getAmount(), keepCap);
            if (capped <= 0) {
                return;
            }
            if (capped < available.getAmount()) {
                available = new FluidStack(available.getFluid(), capped);
            }
        }
        if (!DuctFluidFilterLogic.passesFluidFiltersForBank(node, DuctFaceNode.FilterBank.EXTRACTOR, available, level)) {
            return;
        }

        // Build candidate insertion faces across the fluid network: priority first, then routing tie-break.
        List<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, srcPos, DuctNetworkType.FLUID));
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
            OptionalLong dist =
                    destPos.equals(srcPos)
                            ? OptionalLong.of(0L)
                            : DuctPathfinder.distance(level, srcPos, destPos, spec, DuctNetworkType.FLUID);
            if (dist.isEmpty()) {
                continue;
            }
            int dsm = destBe.getStorageMask();
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
                    continue;
                }
                DuctFaceLanes destLanes = destBe.getFaceLanes(df);
                DuctFaceNode destNode = destLanes.fluid;
                if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
                    continue;
                }
                NodeMode dm = destLanes.nodeMode;
                if (dm != NodeMode.NONE && dm != NodeMode.FILTERING_INSERTION && dm != NodeMode.EXTRACTION_FILTERING) {
                    continue;
                }
                if (destPos.equals(srcPos) && df == sourceFace) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(destNode.channelLetter, node.channelLetter)) {
                    continue;
                }
                IFluidHandler destCap =
                        level.getCapability(Capabilities.FluidHandler.BLOCK, destPos.relative(df), df.getOpposite());
                if (destCap == null) {
                    continue;
                }
                FluidStack toMove = available.copy();
                int simulated = destCap.fill(toMove, IFluidHandler.FluidAction.SIMULATE);
                if (simulated <= 0) {
                    continue;
                }
                if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                        && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                                destNode, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
                    continue;
                }
                if (dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING) {
                    List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
                    List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
                    int idx = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(allowLines, toMove, level.registryAccess());
                    if (idx >= 0 && idx < caps.size()) {
                        int lim = caps.get(idx);
                        if (lim > 0) {
                            String line = allowLines.get(idx);
                            int current = DuctFluidAllowLimitLogic.countMatchingInHandlerMb(destCap, line, level.registryAccess());
                            int pending =
                                    DuctFluidAllowLimitLogic.countMatchingInStacksMb(
                                            DuctFluidIncomingIndex.snapshot(level, destPos), line, level.registryAccess());
                            int maxAdd = Math.max(0, lim - (current + pending));
                            simulated = Math.min(simulated, maxAdd);
                            if (simulated <= 0) {
                                continue;
                            }
                        }
                    }
                }
                FluidStack drain = srcCap.drain(new FluidStack(toMove.getFluid(), simulated), IFluidHandler.FluidAction.SIMULATE);
                if (drain.isEmpty() || drain.getAmount() < simulated) {
                    continue;
                }
                cands.add(new DestCandidate(destPos, df, destNode.insertionPriority, dist.getAsLong(), simulated));
            }
        }
        if (cands.isEmpty()) {
            return;
        }
        cands.sort(Comparator.comparingInt((DestCandidate c) -> c.priority).reversed());
        int maxP = cands.getFirst().priority;
        ArrayList<DestCandidate> tier = new ArrayList<>();
        for (DestCandidate c : cands) {
            if (c.priority == maxP) {
                tier.add(c);
            }
        }
        int[] rr = new int[] {node.roundRobinCursor};
        DestCandidate pick = pickWithinTier(level, tier, node.routingMode, rr);
        if (pick == null) {
            return;
        }
        node.roundRobinCursor = rr[0];
        sourceBe.setChanged();

        int movedMb = Math.min(available.getAmount(), pick.movedMb);
        if (movedMb <= 0) {
            return;
        }
        FluidStack planned = new FluidStack(available.getFluid(), movedMb);
        List<BlockPos> rawPath =
                DuctPathfinder.shortestPath(level, srcPos, pick.ductPos, spec, DuctNetworkType.FLUID)
                        .orElseGet(() -> List.of(srcPos, pick.ductPos));
        List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
        sourceBe.scheduleFluidTransitPending(level, planned, pathWire, sourceFace, pick.face, pick.ductPos, spec);
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist, int movedMb) {}

    private static DestCandidate pickWithinTier(
            ServerLevel level, List<DestCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DestCandidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((DestCandidate c) -> Math.abs(c.dist - mean))
                                .thenComparingLong(c -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DestCandidate c) -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, tier.size());
                yield tier.get(i);
            }
            case RANDOM -> {
                int i = level.random.nextInt(tier.size());
                yield tier.get(i);
            }
        };
    }

    /**
     * While {@code travelTicks} &gt; 0: path intact, faces still valid, simulate still allows the planned move. Unloaded
     * chunks do not invalidate (same idea as item path checks).
     */
    public static boolean fluidShipmentMidTransitValid(ServerLevel level, DuctBlockEntity sourceBe, FluidTransitShipment s) {
        BlockPos srcPos = sourceBe.getBlockPos();
        if (!level.isLoaded(srcPos) || !level.isLoaded(s.destDuct)) {
            return true;
        }
        DuctFaceLanes srcLanes = sourceBe.getFaceLanes(s.sourceFace);
        if (!DuctRedstoneLogic.isFaceTransportActive(level, srcPos, srcLanes.redstoneMode)) {
            return false;
        }
        NodeMode sm = srcLanes.nodeMode;
        if (sm != NodeMode.EXTRACTION
                && sm != NodeMode.EXTRACTION_FILTERING
                && sm != NodeMode.RETRIEVING_EXTRACTION) {
            return false;
        }
        DuctFaceNode srcNode = srcLanes.fluid;
        if (!DuctFluidFilterLogic.passesFluidFiltersForBank(srcNode, DuctFaceNode.FilterBank.EXTRACTOR, s.fluid, level)) {
            return false;
        }
        IFluidHandler srcCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, srcPos.relative(s.sourceFace), s.sourceFace.getOpposite());
        if (srcCap == null) {
            return false;
        }
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        return pinnedFaceSimulatesOk(level, destBe, srcCap, s);
    }

    private static boolean pinnedFaceSimulatesOk(
            ServerLevel level, DuctBlockEntity destBe, IFluidHandler srcCap, FluidTransitShipment s) {
        BlockPos destPos = destBe.getBlockPos();
        Direction df = s.destFace;
        int dsm = destBe.getStorageMask();
        if ((dsm & (1 << df.ordinal())) == 0) {
            return false;
        }
        DuctFaceLanes destLanes = destBe.getFaceLanes(df);
        if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
            return false;
        }
        NodeMode dm = destLanes.nodeMode;
        if (dm != NodeMode.NONE && dm != NodeMode.FILTERING_INSERTION && dm != NodeMode.EXTRACTION_FILTERING) {
            return false;
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destLanes.fluid, DuctFaceNode.FilterBank.FILTER, s.fluid, level)) {
            return false;
        }
        IFluidHandler destCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, destPos.relative(df), df.getOpposite());
        if (destCap == null) {
            return false;
        }
        int simulated = destCap.fill(s.fluid, IFluidHandler.FluidAction.SIMULATE);
        if (simulated <= 0) {
            return false;
        }
        int amt = Math.min(s.fluid.getAmount(), simulated);
        FluidStack drain = srcCap.drain(new FluidStack(s.fluid.getFluid(), amt), IFluidHandler.FluidAction.SIMULATE);
        return !drain.isEmpty() && drain.getAmount() >= amt;
    }

    /** Performs drain+fill for the pinned destination face; refunds if fill accepts less than drained. */
    public static void tryExecutePlannedFluidTransfer(ServerLevel level, DuctBlockEntity sourceBe, FluidTransitShipment s) {
        BlockPos srcPos = sourceBe.getBlockPos();
        IFluidHandler srcCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, srcPos.relative(s.sourceFace), s.sourceFace.getOpposite());
        if (srcCap == null || s.fluid.isEmpty()) {
            return;
        }
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            return;
        }
        BlockPos destPos = destBe.getBlockPos();
        Direction df = s.destFace;
        int dsm = destBe.getStorageMask();
        if ((dsm & (1 << df.ordinal())) == 0) {
            return;
        }
        DuctFaceLanes destLanes = destBe.getFaceLanes(df);
        if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
            return;
        }
        NodeMode dm = destLanes.nodeMode;
        if (dm != NodeMode.NONE && dm != NodeMode.FILTERING_INSERTION && dm != NodeMode.EXTRACTION_FILTERING) {
            return;
        }
        FluidStack toMove = s.fluid.copy();
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destLanes.fluid, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
            return;
        }
        IFluidHandler destCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, destPos.relative(df), df.getOpposite());
        if (destCap == null) {
            return;
        }
        int simulated = destCap.fill(toMove, IFluidHandler.FluidAction.SIMULATE);
        if (simulated <= 0) {
            return;
        }
        int take = Math.min(toMove.getAmount(), simulated);
        FluidStack drainSim = srcCap.drain(new FluidStack(toMove.getFluid(), take), IFluidHandler.FluidAction.SIMULATE);
        if (drainSim.isEmpty() || drainSim.getAmount() < take) {
            return;
        }
        FluidStack actually = srcCap.drain(new FluidStack(toMove.getFluid(), take), IFluidHandler.FluidAction.EXECUTE);
        if (actually.isEmpty()) {
            return;
        }
        int filled = destCap.fill(actually, IFluidHandler.FluidAction.EXECUTE);
        if (filled < actually.getAmount()) {
            srcCap.fill(
                    new FluidStack(actually.getFluid(), actually.getAmount() - filled),
                    IFluidHandler.FluidAction.EXECUTE);
        }
        sourceBe.setChanged();
        destBe.setChanged();
    }

    private static FluidStack drainProbe(IFluidHandler h, int maxMb) {
        for (int t = 0; t < h.getTanks(); t++) {
            FluidStack in = h.getFluidInTank(t);
            if (in.isEmpty()) {
                continue;
            }
            int take = Math.min(maxMb, in.getAmount());
            FluidStack sim = h.drain(new FluidStack(in.getFluid(), take), IFluidHandler.FluidAction.SIMULATE);
            if (!sim.isEmpty()) {
                return sim;
            }
        }
        return FluidStack.EMPTY;
    }
}
