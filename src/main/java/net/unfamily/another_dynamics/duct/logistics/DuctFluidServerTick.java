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
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctFluidFilterLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;

/**
 * Fluid logistics: extract from an attached tank, push to another duct on the fluid network that can accept into a tank;
 * the extracting duct schedules a client-only in-pipe visual along the shortest fluid path.
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
            Optional<FillResult> fill =
                    tryFillIntoNetworkNeighbor(level, srcCap, destBe, available.copy());
            if (fill.isPresent()) {
                node.roundRobinCursor = (start + i + 1) % n;
                sourceBe.setChanged();
                destBe.setChanged();
                FillResult fr = fill.get();
                List<BlockPos> rawPath =
                        DuctPathfinder.shortestPath(level, srcPos, destPos, spec, DuctNetworkType.FLUID)
                                .orElseGet(() -> List.of(srcPos, destPos));
                List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
                FluidStack viz = new FluidStack(available.getFluid(), Math.min(1000, fr.movedMb()));
                sourceBe.scheduleFluidTransitVisual(level, viz, pathWire, sourceFace, fr.destStorageFace(), spec);
                return;
            }
        }
    }

    private record FillResult(int movedMb, Direction destStorageFace) {}

    private static Optional<FillResult> tryFillIntoNetworkNeighbor(
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
            IFluidHandler destCap = level.getCapability(Capabilities.FluidHandler.BLOCK, destPos.relative(df), df.getOpposite());
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
            FluidStack actually = srcCap.drain(drain, IFluidHandler.FluidAction.EXECUTE);
            if (actually.isEmpty()) {
                return Optional.empty();
            }
            int filled = destCap.fill(actually, IFluidHandler.FluidAction.EXECUTE);
            if (filled < actually.getAmount()) {
                // Should be rare: refund excess to source
                srcCap.fill(
                        new FluidStack(actually.getFluid(), actually.getAmount() - filled),
                        IFluidHandler.FluidAction.EXECUTE);
            }
            return Optional.of(new FillResult(filled, df));
        }
        return Optional.empty();
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
