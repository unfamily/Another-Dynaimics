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
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFluidAllowLimitLogic;
import net.unfamily.another_dynamics.duct.DuctFluidFilterLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctStallAllowBank;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;
import net.unfamily.another_dynamics.duct.FilterRemoteNodeRole;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects.FilterSlotBonuses;

import org.jetbrains.annotations.Nullable;

/** Entry-first fluid extract/retrieve scheduling with bound filter destinations. */
final class DuctFilterFluidRouting {
    private static final int ROUTE_RETRY_CAP = 32;

    private DuctFilterFluidRouting() {}

    /** Bridges the 26.x {@link ResourceHandler} fluid capability back onto the legacy {@link IFluidHandler} API. */
    @Nullable
    private static IFluidHandler wrapFluidHandler(@Nullable ResourceHandler<FluidResource> handler) {
        return handler == null ? null : IFluidHandler.of(handler);
    }

    static boolean hasNonEmptyAllowLines(List<String> lines, int cap) {
        int n = Math.min(lines.size(), cap);
        for (int i = 0; i < n; i++) {
            String s = lines.get(i);
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static boolean tryExtractEntryFirst(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctFluidTransportSpec spec) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.EXTRACTOR;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(sourceBe, sourceFace);
        int allowCap = DuctModuleEffects.effectiveFluidAllowBank(spec, sourceMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasNonEmptyAllowLines(allowFull, allowCap)) {
            return false;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        IFluidHandler srcCap =
                DuctFluidCapHelper.blockHandler(level, srcPos.relative(sourceFace), sourceFace.getOpposite());
        if (srcCap == null) {
            return false;
        }
        int wantMb = DuctModuleEffects.effectiveFluidExtractBatchMb(sourceBe, sourceFace, spec);
        FluidStack baseAvailable = DuctFluidCapHelper.drainProbe(srcCap, wantMb);
        if (baseAvailable.isEmpty()) {
            return false;
        }

        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> allowRemote = subListEndpoint(node.bankAllowRemoteNodes(bank), allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);

        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        Direction forbidSelfDestFace = allowSelfFeed ? null : sourceFace;
        RoutingMode extractRm = node.routingForExtraction(sourceMode);
        int[] rr = new int[] {node.roundRobinCursor};
        long edgeTicks = DuctModuleEffects.effectiveFluidEdgeTravelTicks(sourceBe, sourceFace, spec);

        for (net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit :
                net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.UnitBinding binding =
                    net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.unitBinding(
                            unit, allowRemote, allowIgnore, allowAnyFace);
            if (binding == null) {
                continue;
            }
            if (!DuctFluidFilterLogic.fluidMatchesAllowUnit(bank, node, unit, baseAvailable, level)) {
                continue;
            }
            FluidStack available = applyKeepCap(srcCap, node, baseAvailable, level);
            if (available.isEmpty()) {
                continue;
            }
            if (binding.endpoint() != null) {
                List<DuctFilterDestinationResolver.ResolvedFace> faces =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                srcPos,
                                sourceFace,
                                DuctNetworkType.FLUID,
                                DuctTransportKind.FLUID,
                                spec.edgeTravelTicks(),
                                node.channelLetter,
                                binding.ignoreChannel(),
                                binding.endpoint(),
                                binding.anyFace(),
                                FilterRemoteNodeRole.EXTRACT_ROUTE,
                                allowSelfFeed,
                                forbidSelfDestFace);
                for (DuctFilterDestinationResolver.ResolvedFace rf : faces) {
                    DuctDirectionalEndpoint destCounterparty =
                            DuctDirectionalEndpoint.connectionAtDuctFace(level, rf.ductPos(), rf.face());
                    if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                            node, bank, available, level, destCounterparty)) {
                        continue;
                    }
                    if (tryScheduleExtract(
                            level,
                            sourceBe,
                            sourceFace,
                            node,
                            srcCap,
                            available,
                            rf.ductPos(),
                            rf.face(),
                            spec,
                            edgeTicks,
                            unit)) {
                        return true;
                    }
                }
            } else {
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
                    continue;
                }
                int maxP = scored.getFirst().endpoint().insertionPriority();
                ArrayList<DuctFluidServerTick.DestCandidate> validInTier = new ArrayList<>();
                for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
                    if (s.endpoint().insertionPriority() != maxP) {
                        break;
                    }
                    DuctDirectionalEndpoint destEp =
                            DuctDirectionalEndpoint.connectionAtDuctFace(
                                    level, s.endpoint().pos(), s.endpoint().face());
                    if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                            node, bank, available, level, destEp)) {
                        continue;
                    }
                    probeFluidDestination(level, srcPos, sourceFace, node, srcCap, available, s)
                            .ifPresent(validInTier::add);
                    if (validInTier.size() >= ROUTE_RETRY_CAP) {
                        break;
                    }
                }
                DuctFluidServerTick.DestCandidate pick = pickWithinTier(level, validInTier, extractRm, rr);
                if (pick != null
                        && commitExtract(
                                level, sourceBe, sourceFace, node, srcCap, available, pick, spec, edgeTicks)) {
                    node.roundRobinCursor = rr[0];
                    sourceBe.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    static boolean tryRetrieveEntryFirst(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctFaceNode node,
            DuctFluidTransportSpec spec) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.RETRIEVER;
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(retrieverBe, retrieverFace);
        int allowCap = DuctModuleEffects.effectiveFluidAllowBank(spec, retrieverLanes.nodeMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasNonEmptyAllowLines(allowFull, allowCap)) {
            return false;
        }
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        IFluidHandler destCap =
                DuctFluidCapHelper.blockHandler(level, retrieverPos.relative(retrieverFace),
                        retrieverFace.getOpposite());
        if (destCap == null) {
            return false;
        }
        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> allowRemote = subListEndpoint(node.bankAllowRemoteNodes(bank), allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);

        RoutingMode rm = node.routingForRetrieval(retrieverLanes.nodeMode);
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        int[] rr = new int[] {node.roundRobinCursor};
        long edgeTicks = DuctModuleEffects.effectiveFluidEdgeTravelTicks(retrieverBe, retrieverFace, spec);

        for (net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit :
                net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.UnitBinding binding =
                    net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.unitBinding(
                            unit, allowRemote, allowIgnore, allowAnyFace);
            if (binding == null) {
                continue;
            }
            if (binding.endpoint() != null) {
                List<DuctFilterDestinationResolver.ResolvedFace> donors =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                retrieverPos,
                                retrieverFace,
                                DuctNetworkType.FLUID,
                                DuctTransportKind.FLUID,
                                spec.edgeTravelTicks(),
                                node.channelLetter,
                                binding.ignoreChannel(),
                                binding.endpoint(),
                                binding.anyFace(),
                                FilterRemoteNodeRole.RETRIEVE_PULL,
                                true,
                                retrieverFace);
                int donorIdx = 0;
                for (DuctFilterDestinationResolver.ResolvedFace donorFace : donors) {
                    if (!(level.getBlockEntity(donorFace.ductPos()) instanceof DuctBlockEntity donorBe)) {
                        continue;
                    }
                    if (tryPullFromDonor(
                            level,
                            retrieverBe,
                            retrieverFace,
                            node,
                            donorFace.ductPos(),
                            donorFace.face(),
                            donorBe,
                            destCap,
                            unit,
                            spec,
                            edgeTicks,
                            rr,
                            donorIdx,
                            roundRobinRetriever)) {
                        return true;
                    }
                    donorIdx++;
                }
            } else {
                List<DuctTargetSelector.DonorCandidate> donors =
                        DuctFluidServerTick.listFluidRetrievingDonorCandidates(
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
                for (int donorIdx = 0; donorIdx < donors.size() && donorIdx < ROUTE_RETRY_CAP; donorIdx++) {
                    DuctTargetSelector.DonorCandidate donorCand = donors.get(donorIdx);
                    BlockPos donor = donorCand.ductPos();
                    Direction donorFace = donorCand.face();
                    if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                        continue;
                    }
                    if (tryPullFromDonor(
                            level,
                            retrieverBe,
                            retrieverFace,
                            node,
                            donor,
                            donorFace,
                            donorBe,
                            destCap,
                            unit,
                            spec,
                            edgeTicks,
                            rr,
                            donorIdx,
                            roundRobinRetriever)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static FluidStack applyKeepCap(
            IFluidHandler srcCap,
            DuctFaceNode node,
            FluidStack available,
            ServerLevel level) {
        int keepCap =
                DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                        srcCap,
                        node.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowConcatChannels(DuctFaceNode.FilterBank.EXTRACTOR),
                        available,
                        level.registryAccess());
        if (keepCap == Integer.MAX_VALUE) {
            return available;
        }
        int capped = Math.min(available.getAmount(), keepCap);
        if (capped <= 0) {
            return FluidStack.EMPTY;
        }
        if (capped < available.getAmount()) {
            return new FluidStack(available.getFluid(), capped);
        }
        return available;
    }

    private static boolean tryScheduleExtract(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctFaceNode node,
            IFluidHandler srcCap,
            FluidStack available,
            BlockPos destPos,
            Direction destFace,
            DuctFluidTransportSpec spec,
            long edgeTicks,
            @Nullable net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit boundUnit) {
        if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        DuctDirectionalEndpoint sourceEp =
                DuctDirectionalEndpoint.connectionAtDuctFace(level, sourceBe.getBlockPos(), sourceFace);
        DuctFaceNode destNode = destBe.getFaceLanes(destFace).fluid;
        NodeMode dm = destBe.getFaceLanes(destFace).nodeMode;
        IFluidHandler destCap =
                DuctFluidCapHelper.blockHandler(level, destPos.relative(destFace), destFace.getOpposite());
        if (destCap == null) {
            return false;
        }
        FluidStack toMove = available.copy();
        if (boundUnit != null) {
            toMove = applyExtractorInsertLimitMb(destCap, node, boundUnit, toMove, level, destPos);
            if (toMove.isEmpty()) {
                return false;
            }
        }
        int simulated = DuctFluidCapHelper.simulateFill(destCap, toMove);
        if (simulated <= 0) {
            return false;
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.FILTER, toMove, level, sourceEp)) {
            return false;
        }
        FluidStack drain = DuctFluidCapHelper.simulateDrainMatching(srcCap, toMove, simulated);
        if (drain.isEmpty() || drain.getAmount() < simulated) {
            return false;
        }
        DuctFluidServerTick.DestCandidate pick =
                new DuctFluidServerTick.DestCandidate(destPos, destFace, destNode.insertionPriority, 0L, simulated);
        return commitExtract(level, sourceBe, sourceFace, node, srcCap, available, pick, spec, edgeTicks);
    }

    private static FluidStack applyExtractorInsertLimitMb(
            IFluidHandler destCap,
            DuctFaceNode sourceNode,
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit,
            FluidStack available,
            ServerLevel level,
            BlockPos destPos) {
        if (available.isEmpty() || unit == null) {
            return available;
        }
        List<String> allows = sourceNode.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR);
        List<Integer> lims = sourceNode.extractorBankLimitCaps();
        List<Integer> concat = sourceNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.EXTRACTOR);
        java.util.ArrayList<String> uAllows = new java.util.ArrayList<>(unit.lineIndices().size());
        java.util.ArrayList<Integer> uCaps = new java.util.ArrayList<>(unit.lineIndices().size());
        java.util.ArrayList<Integer> uConcat = new java.util.ArrayList<>(unit.lineIndices().size());
        for (int idx : unit.lineIndices()) {
            uAllows.add(idx < allows.size() ? allows.get(idx) : "");
            uCaps.add(idx < lims.size() ? Math.max(0, lims.get(idx)) : 0);
            uConcat.add(FilterConcatChannel.channelAt(concat, idx));
        }
        if (!DuctFluidAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(uAllows, uCaps)) {
            return available;
        }
        List<FluidStack> prior = DuctFluidIncomingIndex.snapshot(level, destPos);
        int maxAdd =
                DuctFluidAllowLimitLogic.maxAdditionalInsertAcrossAllowLinesMb(
                        destCap, uAllows, uCaps, uConcat, available, prior, level.registryAccess());
        if (maxAdd == Integer.MAX_VALUE) {
            return available;
        }
        int capped = Math.min(available.getAmount(), Math.max(0, maxAdd));
        if (capped <= 0) {
            return FluidStack.EMPTY;
        }
        if (capped < available.getAmount()) {
            return new FluidStack(available.getFluid(), capped);
        }
        return available;
    }

    private static boolean commitExtract(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctFaceNode node,
            IFluidHandler srcCap,
            FluidStack available,
            DuctFluidServerTick.DestCandidate pick,
            DuctFluidTransportSpec spec,
            long edgeTicks) {
        int movedMb = Math.min(available.getAmount(), pick.movedMb());
        if (movedMb <= 0) {
            return false;
        }
        FluidStack planned = new FluidStack(available.getFluid(), movedMb);
        FluidStack extracted = DuctFluidCapHelper.drainMatching(srcCap, planned, planned.getAmount());
        if (extracted.isEmpty() || extracted.getAmount() <= 0) {
            return false;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            rawPath =
                    DuctNetworkCache.shortestPath(level, srcPos, pick.ductPos(), DuctNetworkType.FLUID)
                            .orElseGet(() -> List.of(srcPos, pick.ductPos()));
        }
        sourceBe.scheduleFluidTransitPending(
                level,
                extracted,
                OutboundShipment.copyPath(rawPath),
                sourceFace,
                pick.face(),
                pick.ductPos(),
                spec,
                edgeTicks);
        sourceBe.setChanged();
        return true;
    }

    private static boolean tryPullFromDonor(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctFaceNode node,
            BlockPos donor,
            Direction donorFace,
            DuctBlockEntity donorBe,
            IFluidHandler destCap,
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit,
            DuctFluidTransportSpec spec,
            long edgeTicks,
            int[] rr,
            int donorIdx,
            boolean roundRobinRetriever) {
        if (DuctFluidServerTick.isFluidFaceStalled(donorBe.getFaceLanes(donorFace))) {
            return false;
        }
        IFluidHandler srcCap =
                DuctFluidCapHelper.blockHandler(level, donor.relative(donorFace), donorFace.getOpposite());
        if (srcCap == null) {
            return false;
        }
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.RETRIEVER;
        int wantMb = DuctModuleEffects.effectiveFluidExtractBatchMb(retrieverBe, retrieverFace, spec);
        FluidStack available = DuctFluidCapHelper.drainProbe(srcCap, wantMb);
        if (available.isEmpty()) {
            return false;
        }
        if (!DuctFluidFilterLogic.fluidMatchesAllowUnit(bank, node, unit, available, level)) {
            return false;
        }
        DuctFaceNode donorFluid = donorBe.getFaceLanes(donorFace).fluid;
        NodeMode donorMode = donorBe.getFaceLanes(donorFace).nodeMode;
        if (donorMode == NodeMode.FILTERING_INSERTION
                && !DuctFluidFilterLogic.passesFluidFiltersForBank(
                        donorFluid,
                        DuctFaceNode.FilterBank.FILTER,
                        available,
                        level,
                        DuctDirectionalEndpoint.connectionAtDuctFace(level, retrieverPos, retrieverFace))) {
            return false;
        }
        if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                node,
                bank,
                available,
                level,
                DuctDirectionalEndpoint.connectionAtDuctFace(level, donor, donorFace))) {
            return false;
        }
        int keepCap =
                DuctFluidAllowLimitLogic.maxExtractRespectingKeepMb(
                        srcCap,
                        node.bankAllowFilters(bank),
                        node.bankAllowCaps(bank),
                        node.bankAllowConcatChannels(bank),
                        available,
                        level.registryAccess());
        if (keepCap != Integer.MAX_VALUE) {
            int capped = Math.min(available.getAmount(), keepCap);
            if (capped <= 0) {
                return false;
            }
            if (capped < available.getAmount()) {
                available = new FluidStack(available.getFluid(), capped);
            }
        }
        int pendingSum = donorBe.pendingOutboundFluidMbFromFace(donorFace, available);
        int remainingInStorage = available.getAmount() - pendingSum;
        if (remainingInStorage <= 0) {
            return false;
        }
        int plannedMb = Math.min(wantMb, remainingInStorage);
        FluidStack planStack = new FluidStack(available.getFluid(), plannedMb);
        int simDest = DuctFluidCapHelper.simulateFill(destCap, planStack);
        if (simDest <= 0) {
            return false;
        }
        plannedMb = Math.min(plannedMb, simDest);
        plannedMb =
                DuctFluidServerTick.capFillMbForRetrieverDestinationLimits(
                        level, retrieverBe, retrieverFace, planStack, destCap, plannedMb);
        if (plannedMb <= 0) {
            return false;
        }
        planStack = new FluidStack(available.getFluid(), plannedMb);
        FluidStack drainSim = DuctFluidCapHelper.simulateDrainMatching(srcCap, available, plannedMb);
        if (drainSim.isEmpty() || drainSim.getAmount() < plannedMb) {
            return false;
        }
        if (!DuctRedstoneLogic.isFaceTransportActive(
                level, retrieverPos, retrieverBe.getFaceLanes(retrieverFace).redstoneMode)) {
            return false;
        }
        List<BlockPos> path;
        if (donor.equals(retrieverPos)) {
            path = List.of(retrieverPos);
        } else {
            Optional<List<BlockPos>> p =
                    DuctNetworkCache.shortestPath(level, donor, retrieverPos, DuctNetworkType.FLUID);
            if (p.isEmpty()) {
                return false;
            }
            path = p.get();
        }
        if (roundRobinRetriever) {
            node.roundRobinCursor = rr[0] + donorIdx + 1;
        }
        FluidStack planned = new FluidStack(available.getFluid(), plannedMb);
        FluidStack extracted = DuctFluidCapHelper.drainMatching(srcCap, planned, planned.getAmount());
        if (extracted.isEmpty() || extracted.getAmount() <= 0) {
            return false;
        }
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
        return true;
    }

    private static DuctFluidServerTick.DestCandidate pickWithinTier(
            ServerLevel level,
            List<DuctFluidServerTick.DestCandidate> tier,
            RoutingMode routing,
            int[] roundRobinState) {
        return DuctFluidServerTick.pickWithinTierForRouting(level, tier, routing, roundRobinState);
    }

    private static Optional<DuctFluidServerTick.DestCandidate> probeFluidDestination(
            ServerLevel level,
            BlockPos sourcePos,
            Direction sourceFace,
            DuctFaceNode sourceNode,
            IFluidHandler srcCap,
            FluidStack available,
            DuctRoutingEndpointIndex.ScoredEndpoint scored) {
        return DuctFluidServerTick.probeFluidDestinationForRouting(
                level, sourcePos, sourceFace, sourceNode, srcCap, available, scored);
    }

    private static List<Integer> subList(List<Integer> in, int size) {
        return in.subList(0, Math.min(in.size(), size));
    }

    private static List<DuctDirectionalEndpoint> subListEndpoint(List<DuctDirectionalEndpoint> in, int size) {
        return in.subList(0, Math.min(in.size(), size));
    }

    private static List<Boolean> subListBool(List<Boolean> in, int size) {
        return in.subList(0, Math.min(in.size(), size));
    }

    /**
     * Re-sends stalled fluid using entry-first bound filter destinations. Returns true if anything was scheduled.
     */
    static boolean tryDrainStallEntryFirst(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctFluidTransportSpec spec,
            DuctFaceLanes lanes) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.EXTRACTOR;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(sourceBe, sourceFace);
        int allowCap = DuctModuleEffects.effectiveFluidAllowBank(spec, sourceMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        List<DuctDirectionalEndpoint> allowRemote = node.bankAllowRemoteNodes(bank);
        if (!DuctStallAllowBank.hasStallRoutableAllowBank(
                allowFull, allowCap, allowRemote)) {
            return false;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        Direction forbidSelfDestFace = allowSelfFeed ? null : sourceFace;
        long edgeTicks = DuctModuleEffects.effectiveFluidEdgeTravelTicks(sourceBe, sourceFace, spec);
        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> remotes = subListEndpoint(allowRemote, allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);

        for (int slot = 0; slot < lanes.stalledFluids.length; slot++) {
            FluidStack stalled = lanes.stalledFluids[slot];
            if (stalled == null || stalled.isEmpty() || stalled.getAmount() <= 0) {
                continue;
            }
            for (net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit :
                    net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
                net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.UnitBinding binding =
                        net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.unitBinding(
                                unit, remotes, allowIgnore, allowAnyFace);
                if (binding == null || binding.endpoint() == null) {
                    continue;
                }
                String allowLine =
                        unit.headIndex() >= 0 && unit.headIndex() < allow.size()
                                ? allow.get(unit.headIndex())
                                : "";
                boolean boundOnly = allowLine == null || allowLine.trim().isEmpty();
                if (!boundOnly
                        && !DuctFluidFilterLogic.fluidMatchesAllowUnit(bank, node, unit, stalled, level)) {
                    continue;
                }
                List<DuctFilterDestinationResolver.ResolvedFace> faces =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                srcPos,
                                sourceFace,
                                DuctNetworkType.FLUID,
                                DuctTransportKind.FLUID,
                                spec.edgeTravelTicks(),
                                node.channelLetter,
                                binding.ignoreChannel(),
                                binding.endpoint(),
                                binding.anyFace(),
                                FilterRemoteNodeRole.EXTRACT_ROUTE,
                                allowSelfFeed,
                                forbidSelfDestFace);
                for (DuctFilterDestinationResolver.ResolvedFace rf : faces) {
                    DuctDirectionalEndpoint destCounterparty =
                            DuctDirectionalEndpoint.connectionAtDuctFace(level, rf.ductPos(), rf.face());
                    if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                            node, bank, stalled, level, destCounterparty)) {
                        continue;
                    }
                    if (!(level.getBlockEntity(rf.ductPos()) instanceof DuctBlockEntity destBe)) {
                        continue;
                    }
                    IFluidHandler destCap =
                            DuctFluidCapHelper.blockHandler(level, rf.ductPos().relative(rf.face()),
                                    rf.face().getOpposite());
                    if (destCap == null) {
                        continue;
                    }
                    int moved = DuctFluidCapHelper.simulateFill(destCap, stalled);
                    if (moved <= 0) {
                        continue;
                    }
                    FluidStack planned = new FluidStack(stalled.getFluid(), moved);
                    planned =
                            applyExtractorInsertLimitMb(
                                    destCap, node, unit, planned, level, rf.ductPos());
                    if (planned.isEmpty()) {
                        continue;
                    }
                    moved = planned.getAmount();
                    planned = new FluidStack(stalled.getFluid(), moved);
                    List<BlockPos> rawPath;
                    if (rf.ductPos().equals(srcPos)) {
                        rawPath = List.of(srcPos);
                    } else {
                        rawPath =
                                DuctNetworkCache.shortestPath(level, srcPos, rf.ductPos(), DuctNetworkType.FLUID)
                                        .orElseGet(() -> List.of(srcPos, rf.ductPos()));
                    }
                    sourceBe.scheduleFluidTransitPending(
                            level,
                            planned,
                            OutboundShipment.copyPath(rawPath),
                            sourceFace,
                            rf.face(),
                            rf.ductPos(),
                            spec,
                            edgeTicks);
                    stalled.shrink(moved);
                    lanes.stalledFluids[slot] = stalled.isEmpty() ? FluidStack.EMPTY : stalled;
                    sourceBe.setChanged();
                    return true;
                }
            }
        }
        return false;
    }
}
