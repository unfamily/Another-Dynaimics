package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFluidFilterLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;

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
        if (!DuctFluidFilterLogic.passesFluidFiltersForBank(node, DuctFaceNode.FilterBank.EXTRACTOR, available, level)) {
            return;
        }

        List<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, srcPos, DuctNetworkType.FLUID));
        ducts.remove(srcPos);
        ducts.sort(Comparator.comparingInt(a -> distManhattan(a, srcPos)));
        int n = ducts.size();
        if (n == 0) {
            return;
        }
        int start = Math.floorMod(node.roundRobinCursor, n);
        for (int i = 0; i < n; i++) {
            BlockPos destPos = ducts.get((start + i) % n);
            if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            Optional<FillPlan> plan = simulateFillIntoNetworkNeighbor(level, srcCap, destBe, available.copy());
            if (plan.isPresent()) {
                node.roundRobinCursor = (start + i + 1) % n;
                sourceBe.setChanged();
                FillPlan p = plan.get();
                FluidStack planned = new FluidStack(available.getFluid(), p.movedMb());
                List<BlockPos> rawPath =
                        DuctPathfinder.shortestPath(level, srcPos, destPos, spec, DuctNetworkType.FLUID)
                                .orElseGet(() -> List.of(srcPos, destPos));
                List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
                sourceBe.scheduleFluidTransitPending(
                        level, planned, pathWire, sourceFace, p.destStorageFace(), destPos, spec);
                return;
            }
        }
    }

    private record FillPlan(int movedMb, Direction destStorageFace) {}

    /**
     * Pick first valid destination face; drain/fill are simulated only.
     */
    private static Optional<FillPlan> simulateFillIntoNetworkNeighbor(
            ServerLevel level, IFluidHandler srcCap, DuctBlockEntity destBe, FluidStack toMove) {
        if (toMove.isEmpty()) {
            return Optional.empty();
        }
        BlockPos destPos = destBe.getBlockPos();
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
            if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                    && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                            destNode, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
                continue;
            }
            IFluidHandler destCap =
                    level.getCapability(Capabilities.FluidHandler.BLOCK, destPos.relative(df), df.getOpposite());
            if (destCap == null) {
                continue;
            }
            int simulated = destCap.fill(toMove, IFluidHandler.FluidAction.SIMULATE);
            if (simulated <= 0) {
                continue;
            }
            FluidStack drain = srcCap.drain(new FluidStack(toMove.getFluid(), simulated), IFluidHandler.FluidAction.SIMULATE);
            if (drain.isEmpty() || drain.getAmount() < simulated) {
                continue;
            }
            return Optional.of(new FillPlan(simulated, df));
        }
        return Optional.empty();
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

    private static int distManhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }
}
