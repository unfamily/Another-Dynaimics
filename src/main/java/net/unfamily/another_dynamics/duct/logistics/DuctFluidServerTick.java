package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import net.unfamily.another_dynamics.duct.DuctFluidAllowGroupLogic;
import net.unfamily.another_dynamics.duct.DuctFluidAllowLimitLogic;
import net.unfamily.another_dynamics.duct.DuctFluidFilterLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;

import org.jetbrains.annotations.Nullable;

/**
 * Fluid logistics: plan extract+fill with simulation only, enqueue {@link FluidTransitShipment}; {@link DuctBlockEntity}
 * executes transfer when travel completes and re-validates each tick in transit.
 */
public final class DuctFluidServerTick {
    private static final int RETRIEVE_ROUTE_RETRY_CAP = 32;

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
            NodeMode nm = lanes.nodeMode;
            if (nm == NodeMode.RETRIEVING || nm == NodeMode.RETRIEVING_EXTRACTION) {
                tryRetrievePull(level, be, dir, node, spec);
            }
            if (nm == NodeMode.EXTRACTION
                    || nm == NodeMode.EXTRACTION_FILTERING
                    || nm == NodeMode.RETRIEVING_EXTRACTION) {
                tryExtractPush(level, be, dir, nm, node, spec);
            }
        }
    }

    private static void tryExtractPush(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctFluidTransportSpec spec) {
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
        boolean allowSelf = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        if (ducts.size() > 1 && !allowSelf) {
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
                if (dm != NodeMode.NONE
                        && dm != NodeMode.FILTERING_INSERTION
                        && dm != NodeMode.EXTRACTION_FILTERING
                        && dm != NodeMode.RETRIEVING
                        && dm != NodeMode.RETRIEVING_EXTRACTION) {
                    continue;
                }
                if (!destNode.eligibilityMode.isInsertable()) {
                    continue;
                }
                if (!allowSelf && destPos.equals(srcPos) && df == sourceFace) {
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
                if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                        && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                                destNode, DuctFaceNode.FilterBank.RETRIEVER, toMove, level)) {
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
                    if (!DuctFluidAllowGroupLogic.filterDestGroupAllowsExtractionDelivery(
                            level, destBe, df, toMove, destCap, level.registryAccess())) {
                        continue;
                    }
                }
                if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
                    simulated =
                            capFillMbForRetrieverDestinationLimits(
                                    level, destBe, df, toMove, destCap, simulated);
                    if (simulated <= 0) {
                        continue;
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
        cands.sort(Comparator.comparingInt((DestCandidate c) -> c.priority()).reversed());
        int maxP = cands.getFirst().priority();
        ArrayList<DestCandidate> tier = new ArrayList<>();
        for (DestCandidate c : cands) {
            if (c.priority() == maxP) {
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

        int movedMb = Math.min(available.getAmount(), pick.movedMb());
        if (movedMb <= 0) {
            return;
        }
        FluidStack planned = new FluidStack(available.getFluid(), movedMb);
        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            rawPath =
                    DuctPathfinder.shortestPath(level, srcPos, pick.ductPos(), spec, DuctNetworkType.FLUID)
                            .orElseGet(() -> List.of(srcPos, pick.ductPos()));
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
        int fgrp = 0;
        if (level.getBlockEntity(pick.ductPos()) instanceof DuctBlockEntity destPick) {
            NodeMode dmPick = destPick.getFaceLanes(pick.face()).nodeMode;
            if (dmPick == NodeMode.FILTERING_INSERTION || dmPick == NodeMode.EXTRACTION_FILTERING) {
                fgrp =
                        DuctFluidAllowGroupLogic.fluidAllowLineGroupId(
                                destPick.getFluidFaceNode(pick.face()),
                                DuctFaceNode.FilterBank.FILTER,
                                planned,
                                level.registryAccess());
            }
        }
        sourceBe.scheduleFluidTransitPending(
                level, planned, pathWire, sourceFace, pick.face(), pick.ductPos(), spec, fgrp);
    }

    private static void tryRetrievePull(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctFaceNode node,
            DuctFluidTransportSpec spec) {
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        IFluidHandler destCap =
                level.getCapability(
                        Capabilities.FluidHandler.BLOCK,
                        retrieverPos.relative(retrieverFace),
                        retrieverFace.getOpposite());
        if (destCap == null) {
            return;
        }
        RoutingMode rm = retrieverLanes.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        int[] rr = new int[] {node.roundRobinCursor};
        boolean singleDuctNetwork =
                DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.FLUID).size() == 1;
        List<DuctTargetSelector.DonorCandidate> donors =
                listFluidRetrievingDonorCandidates(
                        level,
                        retrieverPos,
                        retrieverFace,
                        destCap,
                        rm,
                        rr[0],
                        node.channelLetter,
                        singleDuctNetwork,
                        singleDuctNetwork ? retrieverFace : null,
                        spec);
        if (donors.isEmpty()) {
            return;
        }
        for (int donorIdx = 0; donorIdx < donors.size() && donorIdx < RETRIEVE_ROUTE_RETRY_CAP; donorIdx++) {
            DuctTargetSelector.DonorCandidate donorCand = donors.get(donorIdx);
            BlockPos donor = donorCand.ductPos();
            Direction donorFace = donorCand.face();
            List<BlockPos> path;
            if (donor.equals(retrieverPos)) {
                path = List.of(retrieverPos);
            } else {
                Optional<List<BlockPos>> p =
                        DuctPathfinder.shortestPath(level, donor, retrieverPos, spec, DuctNetworkType.FLUID);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }
            if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            IFluidHandler srcCap =
                    level.getCapability(
                            Capabilities.FluidHandler.BLOCK,
                            donor.relative(donorFace),
                            donorFace.getOpposite());
            if (srcCap == null) {
                continue;
            }
            int wantMb = node.extractBatch > 0 ? node.extractBatch : spec.batchDefaultMb();
            wantMb = spec.clampedBatchMb(wantMb);
            FluidStack available = drainProbe(srcCap, wantMb);
            if (available.isEmpty()) {
                continue;
            }
            DuctFaceLanes donorLanes = donorBe.getFaceLanes(donorFace);
            DuctFaceNode donorFluid = donorLanes.fluid;
            NodeMode donorMode = donorLanes.nodeMode;
            if (donorMode == NodeMode.FILTERING_INSERTION
                    && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                            donorFluid, DuctFaceNode.FilterBank.FILTER, available, level)) {
                continue;
            }
            int keepCap =
                    DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                            srcCap,
                            donorFluid.bankAllowFilters(DuctFaceNode.FilterBank.FILTER),
                            donorFluid.bankAllowCaps(DuctFaceNode.FilterBank.FILTER),
                            available,
                            level.registryAccess());
            if (keepCap != Integer.MAX_VALUE) {
                int capped = Math.min(available.getAmount(), keepCap);
                if (capped <= 0) {
                    continue;
                }
                if (capped < available.getAmount()) {
                    available = new FluidStack(available.getFluid(), capped);
                }
            }
            if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                    node, DuctFaceNode.FilterBank.RETRIEVER, available, level)) {
                continue;
            }
            int pendingSum = donorBe.pendingOutboundFluidMbFromFace(donorFace, available);
            int remainingInStorage = available.getAmount() - pendingSum;
            if (remainingInStorage <= 0) {
                continue;
            }
            int plannedMb = Math.min(wantMb, remainingInStorage);

            FluidStack planStack = new FluidStack(available.getFluid(), plannedMb);
            int simDest = destCap.fill(planStack, IFluidHandler.FluidAction.SIMULATE);
            if (simDest <= 0) {
                continue;
            }
            plannedMb = Math.min(plannedMb, simDest);
            plannedMb =
                    capFillMbForRetrieverDestinationLimits(
                            level, retrieverBe, retrieverFace, planStack, destCap, plannedMb);
            if (plannedMb <= 0) {
                continue;
            }
            planStack = new FluidStack(available.getFluid(), plannedMb);
            FluidStack drainSim =
                    srcCap.drain(new FluidStack(available.getFluid(), plannedMb), IFluidHandler.FluidAction.SIMULATE);
            if (drainSim.isEmpty() || drainSim.getAmount() < plannedMb) {
                continue;
            }
            if (!DuctRedstoneLogic.isFaceTransportActive(level, retrieverPos, retrieverLanes.redstoneMode)) {
                continue;
            }
            if (roundRobinRetriever) {
                node.roundRobinCursor = rr[0] + donorIdx + 1;
            }
            FluidStack planned = new FluidStack(available.getFluid(), plannedMb);
            if (!DuctFluidAllowGroupLogic.retrieverPullGroupSatisfiable(
                    level,
                    donorBe,
                    donorFace,
                    retrieverBe,
                    retrieverFace,
                    planStack,
                    srcCap,
                    destCap,
                    level.registryAccess())) {
                continue;
            }
            int fgrp =
                    DuctFluidAllowGroupLogic.fluidAllowLineGroupId(
                            node, DuctFaceNode.FilterBank.RETRIEVER, planStack, level.registryAccess());
            donorBe.scheduleFluidTransitPending(
                    level,
                    planned,
                    OutboundShipment.copyPath(path),
                    donorFace,
                    retrieverFace,
                    retrieverPos,
                    spec,
                    fgrp);
            retrieverBe.setChanged();
            return;
        }
    }

    private static List<DuctTargetSelector.DonorCandidate> listFluidRetrievingDonorCandidates(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            IFluidHandler retrieverDestCap,
            RoutingMode routing,
            int roundRobinCursor,
            int retrieverFluidChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace,
            DuctFluidTransportSpec spec) {
        ArrayList<DuctTargetSelector.DonorCandidate> cands = new ArrayList<>();
        for (BlockPos p : DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.FLUID)) {
            if (!allowSelfDonor && p.equals(retrieverPos)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof DuctBlockEntity be)) {
                continue;
            }
            int sm = be.getStorageMask();
            for (Direction d : Direction.values()) {
                if ((sm & (1 << d.ordinal())) == 0) {
                    continue;
                }
                if (p.equals(retrieverPos) && forbidSelfDonorFace != null && d == forbidSelfDonorFace) {
                    continue;
                }
                DuctFaceLanes donorLanes = be.getFaceLanes(d);
                DuctFaceNode donorFluid = donorLanes.fluid;
                if (!DuctRedstoneLogic.isFaceTransportActive(level, p, donorLanes.redstoneMode)) {
                    continue;
                }
                NodeMode donorMode = donorLanes.nodeMode;
                if (donorMode != NodeMode.NONE && donorMode != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!donorFluid.eligibilityMode.isRetrievable()) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(donorFluid.channelLetter, retrieverFluidChannel)) {
                    continue;
                }
                IFluidHandler srcCap =
                        level.getCapability(Capabilities.FluidHandler.BLOCK, p.relative(d), d.getOpposite());
                if (srcCap == null) {
                    continue;
                }
                int probeMax = spec.clampedBatchMb(Math.max(spec.batchDefaultMb(), 1000));
                FluidStack sample = drainProbe(srcCap, probeMax);
                if (sample.isEmpty()) {
                    continue;
                }
                if (donorMode == NodeMode.FILTERING_INSERTION
                        && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                                donorFluid, DuctFaceNode.FilterBank.FILTER, sample, level)) {
                    continue;
                }
                if (retrieverDestCap.fill(sample.copy(), IFluidHandler.FluidAction.SIMULATE) <= 0) {
                    continue;
                }
                OptionalLong dist =
                        p.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctPathfinder.distance(level, retrieverPos, p, spec, DuctNetworkType.FLUID);
                if (dist.isEmpty()) {
                    continue;
                }
                cands.add(
                        new DuctTargetSelector.DonorCandidate(p, d, donorFluid.insertionPriority, dist.getAsLong()));
            }
        }
        if (cands.isEmpty()) {
            return List.of();
        }
        cands.sort(Comparator.comparingInt((DuctTargetSelector.DonorCandidate c) -> c.priority()).reversed());
        ArrayList<DuctTargetSelector.DonorCandidate> out = new ArrayList<>(cands.size());
        int i = 0;
        boolean rrApplied = false;
        while (i < cands.size()) {
            int pr = cands.get(i).priority();
            ArrayList<DuctTargetSelector.DonorCandidate> tier = new ArrayList<>();
            while (i < cands.size() && cands.get(i).priority() == pr) {
                tier.add(cands.get(i++));
            }
            orderFluidDonorTier(tier, routing, level, rrApplied ? 0 : roundRobinCursor);
            rrApplied = rrApplied || routing == RoutingMode.ROUND_ROBIN;
            out.addAll(tier);
        }
        return out;
    }

    private static void orderFluidDonorTier(
            List<DuctTargetSelector.DonorCandidate> tier,
            RoutingMode routing,
            ServerLevel level,
            int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(
                    Comparator.comparingLong(DuctTargetSelector.DonorCandidate::dist)
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case FARTHEST_FIRST -> tier.sort(
                    Comparator.comparingLong(DuctTargetSelector.DonorCandidate::dist).reversed()
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DuctTargetSelector.DonorCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                tier.sort(
                        Comparator.comparingDouble(
                                        (DuctTargetSelector.DonorCandidate c) -> Math.abs(c.dist() - mean))
                                .thenComparingLong(c -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DuctTargetSelector.DonorCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<DuctTargetSelector.DonorCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                for (int i2 = tier.size() - 1; i2 > 0; i2--) {
                    int j = level.random.nextInt(i2 + 1);
                    DuctTargetSelector.DonorCandidate a = tier.get(i2);
                    tier.set(i2, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private static int capFillMbForRetrieverDestinationLimits(
            ServerLevel level,
            DuctBlockEntity destBe,
            Direction destFace,
            FluidStack movingProbe,
            IFluidHandler destCap,
            int simulatedFillMb) {
        if (simulatedFillMb <= 0 || movingProbe.isEmpty()) {
            return 0;
        }
        NodeMode dm = destBe.getFaceLanes(destFace).nodeMode;
        if (dm != NodeMode.RETRIEVING && dm != NodeMode.RETRIEVING_EXTRACTION) {
            return simulatedFillMb;
        }
        DuctFaceNode destNode = destBe.getFaceLanes(destFace).fluid;
        if (!DuctFluidAllowGroupLogic.retrieverDestGroupAllowsIncomingMb(
                level, destBe, destFace, movingProbe, destCap, level.registryAccess())) {
            return 0;
        }
        List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(allowLines, movingProbe, level.registryAccess());
        if (idx < 0 || idx >= caps.size()) {
            return simulatedFillMb;
        }
        int lim = caps.get(idx);
        if (lim <= 0) {
            return simulatedFillMb;
        }
        String line = allowLines.get(idx);
        int current = DuctFluidAllowLimitLogic.countMatchingInHandlerMb(destCap, line, level.registryAccess());
        int pending =
                DuctFluidAllowLimitLogic.countMatchingInStacksMb(
                        DuctFluidIncomingIndex.snapshot(level, destBe.getBlockPos()),
                        line,
                        level.registryAccess());
        int maxAdd = Math.max(0, lim - (current + pending));
        return Math.min(simulatedFillMb, maxAdd);
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist, int movedMb) {}

    private static DestCandidate pickWithinTier(
            ServerLevel level, List<DestCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(c -> c.dist())).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(c -> c.dist())).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DestCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((DestCandidate c) -> Math.abs(c.dist() - mean))
                                .thenComparingLong(c -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DestCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
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
        boolean srcExtract =
                sm == NodeMode.EXTRACTION
                        || sm == NodeMode.EXTRACTION_FILTERING
                        || sm == NodeMode.RETRIEVING_EXTRACTION;
        boolean srcDonor = sm == NodeMode.NONE || sm == NodeMode.FILTERING_INSERTION;
        if (!srcExtract && !srcDonor) {
            return false;
        }
        DuctFaceNode srcNode = srcLanes.fluid;
        IFluidHandler srcCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, srcPos.relative(s.sourceFace), s.sourceFace.getOpposite());
        if (srcCap == null) {
            return false;
        }
        if (srcExtract) {
            if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                    srcNode, DuctFaceNode.FilterBank.EXTRACTOR, s.fluid, level)) {
                return false;
            }
            int keepCap =
                    DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                            srcCap,
                            srcNode.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                            srcNode.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                            s.fluid,
                            level.registryAccess());
            if (keepCap != Integer.MAX_VALUE && s.fluid.getAmount() > keepCap) {
                return false;
            }
        } else {
            if (sm == NodeMode.FILTERING_INSERTION
                    && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                            srcNode, DuctFaceNode.FilterBank.FILTER, s.fluid, level)) {
                return false;
            }
            int keepCap =
                    DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                            srcCap,
                            srcNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER),
                            srcNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER),
                            s.fluid,
                            level.registryAccess());
            if (keepCap != Integer.MAX_VALUE && s.fluid.getAmount() > keepCap) {
                return false;
            }
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
        if (dm != NodeMode.NONE
                && dm != NodeMode.FILTERING_INSERTION
                && dm != NodeMode.EXTRACTION_FILTERING
                && dm != NodeMode.RETRIEVING
                && dm != NodeMode.RETRIEVING_EXTRACTION) {
            return false;
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destLanes.fluid, DuctFaceNode.FilterBank.FILTER, s.fluid, level)) {
            return false;
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destLanes.fluid, DuctFaceNode.FilterBank.RETRIEVER, s.fluid, level)) {
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
        if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
            simulated =
                    capFillMbForRetrieverDestinationLimits(
                            level, destBe, df, s.fluid, destCap, simulated);
            if (simulated <= 0) {
                return false;
            }
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
        if (dm != NodeMode.NONE
                && dm != NodeMode.FILTERING_INSERTION
                && dm != NodeMode.EXTRACTION_FILTERING
                && dm != NodeMode.RETRIEVING
                && dm != NodeMode.RETRIEVING_EXTRACTION) {
            return;
        }
        FluidStack toMove = s.fluid.copy();
        DuctFaceNode srcNode = sourceBe.getFaceLanes(s.sourceFace).fluid;
        NodeMode sm = sourceBe.getFaceLanes(s.sourceFace).nodeMode;
        boolean donorSource = sm == NodeMode.NONE || sm == NodeMode.FILTERING_INSERTION;
        DuctFaceNode.FilterBank srcCapBank =
                donorSource ? DuctFaceNode.FilterBank.FILTER : DuctFaceNode.FilterBank.EXTRACTOR;
        if (!donorSource
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        srcNode, DuctFaceNode.FilterBank.EXTRACTOR, toMove, level)) {
            return;
        }
        if (donorSource
                && sm == NodeMode.FILTERING_INSERTION
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        srcNode, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
            return;
        }
        int keepCap =
                DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                        srcCap,
                        srcNode.bankAllowFilters(srcCapBank),
                        srcNode.bankAllowCaps(srcCapBank),
                        toMove,
                        level.registryAccess());
        if (keepCap != Integer.MAX_VALUE && toMove.getAmount() > keepCap) {
            return;
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destLanes.fluid, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
            return;
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destLanes.fluid, DuctFaceNode.FilterBank.RETRIEVER, toMove, level)) {
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
        if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
            simulated =
                    capFillMbForRetrieverDestinationLimits(level, destBe, df, toMove, destCap, simulated);
            if (simulated <= 0) {
                return;
            }
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
