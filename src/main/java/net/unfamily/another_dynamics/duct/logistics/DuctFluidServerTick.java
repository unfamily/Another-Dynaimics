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
import net.unfamily.another_dynamics.duct.DuctFluidAllowLimitLogic;
import net.unfamily.another_dynamics.duct.DuctFluidFilterLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;

import org.jetbrains.annotations.Nullable;

/**
 * Fluid logistics: plan extract+fill with simulation only, enqueue {@link FluidTransitShipment}; {@link DuctBlockEntity}
 * executes transfer when travel completes and re-validates each tick in transit.
 * <p><strong>Material-lane family:</strong> behavioural changes often need equivalent updates for item logistics
 * ({@link DuctBlockEntity}) and gas ({@link DuctGasServerTick}), including universal ducts with those kinds enabled.
 */
public final class DuctFluidServerTick {
    private static final int RETRIEVE_ROUTE_RETRY_CAP = 32;
    private static final int EXTRACT_ROUTE_RETRY_CAP = 32;

    private DuctFluidServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.FLUID)).orElse(false)) {
            return;
        }
        DuctFluidTransportSpec spec = be.fluidTransportSpec();
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.FLUID)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            DuctFaceNode node = lanes.fluid;
            if (hasStalledFluid(lanes) && tryDrainFluidStallForFace(be, level, dir, lanes.nodeMode, node, spec)) {
                be.syncStallVisualIfNeeded();
                continue;
            }
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            if (node.ticksUntilAction > 0) {
                node.ticksUntilAction--;
                continue;
            }
            int rate = DuctModuleEffects.effectiveFluidActionRateTicks(be, dir, spec);
            if (!DuctActionScheduling.isStaggerSlot(level, be.getBlockPos(), dir, rate)) {
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
        if (isFluidFaceStalled(sourceBe.getFaceLanes(sourceFace))) {
            return;
        }
        IFluidHandler srcCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, srcPos.relative(sourceFace), sourceFace.getOpposite());
        if (srcCap == null) {
            return;
        }
        int wantMb = node.extractBatch > 0 ? node.extractBatch : spec.batchDefaultMb();
        wantMb = spec.clampedBatchMb(wantMb);
        FluidStack available = DuctFluidCapHelper.drainProbe(srcCap, wantMb);
        if (available.isEmpty()) {
            return;
        }
        // Respect per-allow-line Keep (mB) on the source when allow filters are configured.
        int keepCap =
                DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                        srcCap,
                        node.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowConcatChannels(DuctFaceNode.FilterBank.EXTRACTOR),
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

        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                DuctRoutingEndpointIndex.listScoredExtractDestinations(
                        level,
                        srcPos,
                        DuctNetworkType.FLUID,
                        DuctTransportKind.FLUID,
                        spec.edgeTravelTicks(),
                        node.channelLetter,
                        false,
                        true,
                        null,
                        allowSelfFeed,
                        sourceFace,
                        false);
        if (scored.isEmpty()) {
            return;
        }
        int maxP = scored.getFirst().endpoint().insertionPriority();
        ArrayList<DestCandidate> validInTier = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            if (s.endpoint().insertionPriority() != maxP) {
                break;
            }
            probeFluidDestination(level, srcCap, available, s)
                    .ifPresent(validInTier::add);
            if (validInTier.size() >= EXTRACT_ROUTE_RETRY_CAP) {
                break;
            }
        }
        if (validInTier.isEmpty()) {
            return;
        }
        int[] rr = new int[] {node.roundRobinCursor};
        RoutingMode extractRm = node.routingForExtraction(sourceMode);
        DestCandidate pick = pickWithinTier(level, validInTier, extractRm, rr);
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
        FluidStack extracted = DuctFluidCapHelper.drainMatching(srcCap, planned, planned.getAmount());
        if (extracted.isEmpty() || extracted.getAmount() <= 0) {
            return;
        }
        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            rawPath =
                    DuctNetworkCache.shortestPath(level, srcPos, pick.ductPos(), DuctNetworkType.FLUID)
                            .orElseGet(() -> List.of(srcPos, pick.ductPos()));
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
        long edgeTicks = DuctModuleEffects.effectiveFluidEdgeTravelTicks(sourceBe, sourceFace, spec);
        sourceBe.scheduleFluidTransitPending(
                level, extracted, pathWire, sourceFace, pick.face(), pick.ductPos(), spec, edgeTicks);
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
        RoutingMode rm = node.routingForRetrieval(retrieverLanes.nodeMode);
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        int[] rr = new int[] {node.roundRobinCursor};
        List<DuctTargetSelector.DonorCandidate> donors =
                listFluidRetrievingDonorCandidates(
                        level,
                        retrieverPos,
                        retrieverFace,
                        destCap,
                        rm,
                        rr[0],
                        node.channelLetter,
                        true,
                        retrieverFace,
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
                        DuctNetworkCache.shortestPath(level, donor, retrieverPos, DuctNetworkType.FLUID);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }
            if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            if (isFluidFaceStalled(donorBe.getFaceLanes(donorFace))) {
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
            FluidStack available = DuctFluidCapHelper.drainProbe(srcCap, wantMb);
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
                            donorFluid.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER),
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
            int simDest = DuctFluidCapHelper.simulateFill(destCap, planStack);
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
                    DuctFluidCapHelper.simulateDrainMatching(
                            srcCap, available, plannedMb);
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
            FluidStack extracted = DuctFluidCapHelper.drainMatching(srcCap, planned, planned.getAmount());
            if (extracted.isEmpty() || extracted.getAmount() <= 0) {
                continue;
            }
            // Travel speed modules live on the retriever face (same as item retriever), not the donor.
            long edgeTicks = DuctModuleEffects.effectiveFluidEdgeTravelTicks(retrieverBe, retrieverFace, spec);
            donorBe.scheduleFluidTransitPending(
                    level,
                    extracted,
                    OutboundShipment.copyPath(path),
                    donorFace,
                    retrieverFace,
                    retrieverPos,
                    spec,
                    edgeTicks);
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
        for (BlockPos p : DuctNetworkCache.connectedDucts(level, retrieverPos, DuctNetworkType.FLUID)) {
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
                if (!be.isTransportKindEnabled(d, DuctTransportKind.FLUID)) {
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
                FluidStack sample = DuctFluidCapHelper.drainProbe(srcCap, probeMax);
                if (sample.isEmpty()) {
                    continue;
                }
                if (donorMode == NodeMode.FILTERING_INSERTION
                        && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                                donorFluid, DuctFaceNode.FilterBank.FILTER, sample, level)) {
                    continue;
                }
                if (DuctFluidCapHelper.simulateFill(retrieverDestCap, sample) <= 0) {
                    continue;
                }
                OptionalLong dist =
                        p.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctNetworkCache.routingTravelTicks(
                                        level, retrieverPos, p, spec.edgeTravelTicks(), DuctNetworkType.FLUID);
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
        List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> concat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.RETRIEVER);
        int maxAdd =
                DuctFluidAllowLimitLogic.maxAdditionalInsertAcrossAllowLinesMb(
                        destCap,
                        allowLines,
                        caps,
                        concat,
                        movingProbe,
                        DuctFluidIncomingIndex.snapshot(level, destBe.getBlockPos()),
                        level.registryAccess());
        if (maxAdd == Integer.MAX_VALUE) {
            return simulatedFillMb;
        }
        return Math.min(simulatedFillMb, maxAdd);
    }

    private static java.util.Optional<DestCandidate> probeFluidDestination(
            ServerLevel level, IFluidHandler srcCap, FluidStack available, DuctRoutingEndpointIndex.ScoredEndpoint scored) {
        DuctRoutingEndpointIndex.RoutingEndpoint ep = scored.endpoint();
        if (!(level.getBlockEntity(ep.pos()) instanceof DuctBlockEntity destBe)) {
            return java.util.Optional.empty();
        }
        Direction df = ep.face();
        DuctFaceLanes destLanes = destBe.getFaceLanes(df);
        DuctFaceNode destNode = destLanes.fluid;
        NodeMode dm = destLanes.nodeMode;
        IFluidHandler destCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, ep.pos().relative(df), df.getOpposite());
        if (destCap == null) {
            return java.util.Optional.empty();
        }
        FluidStack toMove = available.copy();
        int simulated = DuctFluidCapHelper.simulateFill(destCap, toMove);
        if (simulated <= 0) {
            return java.util.Optional.empty();
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
            return java.util.Optional.empty();
        }
        if (dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING) {
            List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
            List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
            List<Integer> concat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER);
            int maxAdd =
                    DuctFluidAllowLimitLogic.maxAdditionalInsertAcrossAllowLinesMb(
                            destCap,
                            allowLines,
                            caps,
                            concat,
                            toMove,
                            DuctFluidIncomingIndex.snapshot(level, ep.pos()),
                            level.registryAccess());
            if (maxAdd != Integer.MAX_VALUE) {
                simulated = Math.min(simulated, maxAdd);
                if (simulated <= 0) {
                    return java.util.Optional.empty();
                }
            }
        }
        FluidStack drain = DuctFluidCapHelper.simulateDrainMatching(srcCap, toMove, simulated);
        if (drain.isEmpty() || drain.getAmount() < simulated) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                new DestCandidate(ep.pos(), df, ep.insertionPriority(), scored.distTicks(), simulated));
    }

    private static java.util.Optional<DestCandidate> probeFluidDestinationForStall(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            FluidStack available,
            DuctRoutingEndpointIndex.ScoredEndpoint scored) {
        DuctRoutingEndpointIndex.RoutingEndpoint ep = scored.endpoint();
        if (!(level.getBlockEntity(ep.pos()) instanceof DuctBlockEntity destBe)) {
            return java.util.Optional.empty();
        }
        Direction df = ep.face();
        DuctFaceLanes destLanes = destBe.getFaceLanes(df);
        DuctFaceNode destNode = destLanes.fluid;
        NodeMode dm = destLanes.nodeMode;
        IFluidHandler destCap =
                level.getCapability(Capabilities.FluidHandler.BLOCK, ep.pos().relative(df), df.getOpposite());
        if (destCap == null) {
            return java.util.Optional.empty();
        }
        FluidStack toMove = available.copy();
        int simulated = DuctFluidCapHelper.simulateFill(destCap, toMove);
        if (simulated <= 0) {
            return java.util.Optional.empty();
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.FILTER, toMove, level)) {
            return java.util.Optional.empty();
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.RETRIEVER, toMove, level)) {
            return java.util.Optional.empty();
        }
        if (dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING) {
            List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
            List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
            List<Integer> concat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER);
            int maxAdd =
                    DuctFluidAllowLimitLogic.maxAdditionalInsertAcrossAllowLinesMb(
                            destCap,
                            allowLines,
                            caps,
                            concat,
                            toMove,
                            DuctFluidIncomingIndex.snapshot(level, ep.pos()),
                            level.registryAccess());
            if (maxAdd != Integer.MAX_VALUE) {
                simulated = Math.min(simulated, maxAdd);
                if (simulated <= 0) {
                    return java.util.Optional.empty();
                }
            }
        }
        if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
            simulated = capFillMbForRetrieverDestinationLimits(level, destBe, df, toMove, destCap, simulated);
            if (simulated <= 0) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(
                new DestCandidate(ep.pos(), df, ep.insertionPriority(), scored.distTicks(), simulated));
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
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        return pinnedFaceSimulatesOk(level, destBe, s);
    }

    private static boolean pinnedFaceSimulatesOk(
            ServerLevel level, DuctBlockEntity destBe, FluidTransitShipment s) {
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
        int simulated = DuctFluidCapHelper.simulateFill(destCap, s.fluid);
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
        return simulated > 0;
    }

    /** Performs fill for the pinned destination face; refunds/stalls if fill accepts less than payload. */
    public static void tryExecutePlannedFluidTransfer(ServerLevel level, DuctBlockEntity sourceBe, FluidTransitShipment s) {
        if (s.fluid.isEmpty()) {
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
        int simulated = DuctFluidCapHelper.simulateFill(destCap, toMove);
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
        FluidStack payload = new FluidStack(toMove.getFluid(), take);
        int filled = DuctFluidCapHelper.executeFill(destCap, payload);
        int left = payload.getAmount() - Math.max(0, filled);
        if (left > 0) {
            sourceBe.refundBufferedFluidToSourceOrStall(level, s.sourceFace, new FluidStack(payload.getFluid(), left));
        }
        sourceBe.setChanged();
        destBe.setChanged();
    }

  /**
     * Try to deliver stalled fluid into the network (respecting filters, channel, routing) instead of only refunding to
     * the source block.
     *
     * @return true if any stall slot changed this tick
     */
    public static boolean tryDrainFluidStallForFace(
            DuctBlockEntity sourceBe,
            ServerLevel level,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctFluidTransportSpec spec) {
        DuctFaceLanes lanes = sourceBe.getFaceLanes(sourceFace);
        if (!hasStalledFluid(lanes)) {
            return false;
        }
        for (int slot = 0; slot < lanes.stalledFluids.length; slot++) {
            FluidStack stalled = lanes.stalledFluids[slot];
            if (stalled == null || stalled.isEmpty() || stalled.getAmount() <= 0) {
                continue;
            }
            int movedMb = tryDeliverStalledFluid(level, sourceBe, sourceFace, sourceMode, node, spec, stalled.copy());
            if (movedMb > 0) {
                stalled.shrink(movedMb);
                lanes.stalledFluids[slot] = stalled.isEmpty() ? FluidStack.EMPTY : stalled;
                sourceBe.setChanged();
                return true;
            }
        }
        return false;
    }

    private static int tryDeliverStalledFluid(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctFluidTransportSpec spec,
            FluidStack available) {
        if (available.isEmpty()) {
            return 0;
        }
        if (!DuctFluidFilterLogic.passesFluidFiltersForBank(node, DuctFaceNode.FilterBank.EXTRACTOR, available, level)) {
            return 0;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                DuctRoutingEndpointIndex.listScoredExtractDestinations(
                        level,
                        srcPos,
                        DuctNetworkType.FLUID,
                        DuctTransportKind.FLUID,
                        spec.edgeTravelTicks(),
                        node.channelLetter,
                        true,
                        true,
                        null,
                        allowSelfFeed,
                        sourceFace,
                        false);
        if (scored.isEmpty()) {
            return 0;
        }
        int maxP = scored.getFirst().endpoint().insertionPriority();
        ArrayList<DestCandidate> validInTier = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            if (s.endpoint().insertionPriority() != maxP) {
                break;
            }
            probeFluidDestinationForStall(level, sourceBe, sourceFace, available, s)
                    .ifPresent(validInTier::add);
            if (validInTier.size() >= EXTRACT_ROUTE_RETRY_CAP) {
                break;
            }
        }
        if (validInTier.isEmpty()) {
            return 0;
        }
        int[] rr = new int[] {node.roundRobinCursor};
        RoutingMode stallRm = node.routingForStallResend(sourceMode);
        DestCandidate pick = pickWithinTier(level, validInTier, stallRm, rr);
        if (pick == null) {
            return 0;
        }
        node.roundRobinCursor = rr[0];
        int movedMb = Math.min(available.getAmount(), pick.movedMb());
        if (movedMb <= 0) {
            return 0;
        }
        FluidStack planned = new FluidStack(available.getFluid(), movedMb);
        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            rawPath =
                    DuctNetworkCache.shortestPath(level, srcPos, pick.ductPos(), DuctNetworkType.FLUID)
                            .orElseGet(() -> List.of(srcPos, pick.ductPos()));
        }
        long edgeTicks = DuctModuleEffects.effectiveFluidEdgeTravelTicks(sourceBe, sourceFace, spec);
        sourceBe.scheduleFluidTransitPending(
                level, planned, OutboundShipment.copyPath(rawPath), sourceFace, pick.face(), pick.ductPos(), spec, edgeTicks);
        return movedMb;
    }

    private static boolean hasStalledFluid(DuctFaceLanes lanes) {
        if (lanes == null) {
            return false;
        }
        for (FluidStack fs : lanes.stalledFluids) {
            if (fs != null && !fs.isEmpty() && fs.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFluidFaceStalled(DuctFaceLanes lanes) {
        if (lanes == null) {
            return false;
        }
        int filled = 0;
        for (FluidStack fs : lanes.stalledFluids) {
            if (fs != null && !fs.isEmpty() && fs.getAmount() > 0) {
                filled++;
            }
        }
        return filled >= lanes.stalledFluids.length;
    }

}
