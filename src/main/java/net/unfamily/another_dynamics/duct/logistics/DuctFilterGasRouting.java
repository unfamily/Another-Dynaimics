package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctGasAllowLimitLogic;
import net.unfamily.another_dynamics.duct.DuctGasFilterLogic;
import net.unfamily.another_dynamics.duct.DuctGasTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctStallAllowBank;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.FilterRemoteNodeRole;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects.FilterSlotBonuses;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import org.jetbrains.annotations.Nullable;

/** Entry-first gas extract/retrieve scheduling with bound filter destinations. */
final class DuctFilterGasRouting {
    private static final int ROUTE_RETRY_CAP = 32;

    private DuctFilterGasRouting() {}

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
            DuctGasTransportSpec spec) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.EXTRACTOR;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(sourceBe, sourceFace);
        int allowCap = DuctModuleEffects.effectiveGasAllowBank(spec, sourceMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasNonEmptyAllowLines(allowFull, allowCap)) {
            return false;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, srcPos, sourceFace);
        if (srcHandler == null) {
            return false;
        }
        long want = DuctModuleEffects.effectiveGasExtractBatch(sourceBe, sourceFace, spec);
        Object baseAvailable = MekanismChemicalCompat.drainProbe(srcHandler, want);
        if (MekanismChemicalCompat.isEmptyStack(baseAvailable)) {
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
        long edgeTicks = DuctModuleEffects.effectiveGasEdgeTravelTicks(sourceBe, sourceFace, spec);

        for (net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit :
                net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.UnitBinding binding =
                    net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.unitBinding(
                            unit, allowRemote, allowIgnore, allowAnyFace);
            if (binding == null) {
                continue;
            }
            if (!DuctGasFilterLogic.gasMatchesAllowUnit(bank, node, unit, baseAvailable, level)) {
                continue;
            }
            Object available = applyKeepCap(srcHandler, node, baseAvailable, level);
            if (MekanismChemicalCompat.isEmptyStack(available)) {
                continue;
            }
            boolean radioactivePayload = MekanismChemicalCompat.isRadioactive(available);
            if (radioactivePayload && !DuctPathfinder.gasPathAllowsRadioactive(level, List.of(srcPos))) {
                continue;
            }
            String chemicalId = MekanismChemicalCompat.getTypeRegistryName(available);
            if (chemicalId == null || chemicalId.isEmpty()) {
                continue;
            }
            if (binding.endpoint() != null) {
                List<DuctFilterDestinationResolver.ResolvedFace> faces =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                srcPos,
                                sourceFace,
                                DuctNetworkType.GAS,
                                DuctTransportKind.GAS,
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
                    if (!DuctGasFilterLogic.passesGasFiltersForBank(
                            node, bank, available, level, destCounterparty)) {
                        continue;
                    }
                    if (tryScheduleExtract(
                            level,
                            sourceBe,
                            sourceFace,
                            node,
                            srcHandler,
                            available,
                            rf.ductPos(),
                            rf.face(),
                            spec,
                            edgeTicks,
                            radioactivePayload,
                            unit)) {
                        return true;
                    }
                }
            } else {
                List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                        DuctRoutingEndpointIndex.listScoredExtractDestinations(
                                level,
                                srcPos,
                                DuctNetworkType.GAS,
                                DuctTransportKind.GAS,
                                spec.edgeTravelTicks(),
                                node.channelLetter,
                                false,
                                true,
                                null,
                                allowSelfFeed,
                                sourceFace,
                                radioactivePayload);
                if (scored.isEmpty()) {
                    continue;
                }
                int maxP = scored.getFirst().endpoint().insertionPriority();
                ArrayList<DuctGasServerTick.DestCandidate> validInTier = new ArrayList<>();
                for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
                    if (s.endpoint().insertionPriority() != maxP) {
                        break;
                    }
                    DuctDirectionalEndpoint destEp =
                            DuctDirectionalEndpoint.connectionAtDuctFace(
                                    level, s.endpoint().pos(), s.endpoint().face());
                    if (!DuctGasFilterLogic.passesGasFiltersForBank(node, bank, available, level, destEp)) {
                        continue;
                    }
                    DuctGasServerTick.probeGasDestinationForRouting(level, srcPos, sourceFace, node, available, s)
                            .ifPresent(validInTier::add);
                    if (validInTier.size() >= ROUTE_RETRY_CAP) {
                        break;
                    }
                }
                DuctGasServerTick.DestCandidate pick =
                        DuctGasServerTick.pickWithinTierForRouting(level, validInTier, extractRm, rr);
                if (pick != null
                        && commitExtract(
                                level,
                                sourceBe,
                                sourceFace,
                                node,
                                srcHandler,
                                available,
                                pick,
                                spec,
                                edgeTicks,
                                radioactivePayload)) {
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
            DuctGasTransportSpec spec) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.RETRIEVER;
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(retrieverBe, retrieverFace);
        int allowCap = DuctModuleEffects.effectiveGasAllowBank(spec, retrieverLanes.nodeMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasNonEmptyAllowLines(allowFull, allowCap)) {
            return false;
        }
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, retrieverPos, retrieverFace);
        if (destHandler == null) {
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
        long edgeTicks = DuctModuleEffects.effectiveGasEdgeTravelTicks(retrieverBe, retrieverFace, spec);
        Set<BlockPos> radioactiveGasSubnet = DuctNetworkCache.connectedRadioactiveGasDucts(level, retrieverPos);

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
                                DuctNetworkType.GAS,
                                DuctTransportKind.GAS,
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
                            destHandler,
                            unit,
                            spec,
                            edgeTicks,
                            radioactiveGasSubnet,
                            rr,
                            donorIdx,
                            roundRobinRetriever)) {
                        return true;
                    }
                    donorIdx++;
                }
            } else {
                List<DuctTargetSelector.DonorCandidate> donors =
                        DuctGasServerTick.listGasRetrievingDonorCandidates(
                                level,
                                retrieverPos,
                                retrieverFace,
                                destHandler,
                                rm,
                                rr[0],
                                node.channelLetter,
                                true,
                                retrieverFace,
                                spec,
                                radioactiveGasSubnet);
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
                            destHandler,
                            unit,
                            spec,
                            edgeTicks,
                            radioactiveGasSubnet,
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

    private static Object applyKeepCap(
            Object srcHandler,
            DuctFaceNode node,
            Object available,
            ServerLevel level) {
        long keepCap =
                DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                        srcHandler,
                        node.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowConcatChannels(DuctFaceNode.FilterBank.EXTRACTOR),
                        available,
                        level.registryAccess());
        if (keepCap == Long.MAX_VALUE) {
            return available;
        }
        long capped = Math.min(MekanismChemicalCompat.getAmount(available), keepCap);
        if (capped <= 0) {
            return MekanismChemicalCompat.emptyStack();
        }
        if (capped < MekanismChemicalCompat.getAmount(available)) {
            return MekanismChemicalCompat.copyWithAmount(available, capped);
        }
        return available;
    }

    private static boolean tryScheduleExtract(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctFaceNode node,
            Object srcHandler,
            Object available,
            BlockPos destPos,
            Direction destFace,
            DuctGasTransportSpec spec,
            long edgeTicks,
            boolean radioactivePayload,
            @Nullable net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit boundUnit) {
        if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        DuctDirectionalEndpoint sourceEp =
                DuctDirectionalEndpoint.connectionAtDuctFace(level, sourceBe.getBlockPos(), sourceFace);
        DuctFaceNode destNode = destBe.getFaceLanes(destFace).gas;
        NodeMode dm = destBe.getFaceLanes(destFace).nodeMode;
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, destPos, destFace);
        if (destHandler == null) {
            return false;
        }
        Object toMove = available;
        if (boundUnit != null) {
            toMove = applyExtractorInsertLimitGas(destHandler, node, boundUnit, available, level, destPos);
            if (MekanismChemicalCompat.isEmptyStack(toMove)) {
                return false;
            }
        }
        long availAmt = MekanismChemicalCompat.getAmount(toMove);
        if (availAmt <= 0) {
            return false;
        }
        Object tryStack = MekanismChemicalCompat.copyWithAmount(toMove, availAmt);
        long simulated = MekanismChemicalCompat.simulateInsert(destHandler, tryStack);
        if (simulated <= 0) {
            return false;
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.FILTER, toMove, level, sourceEp)) {
            return false;
        }
        DuctGasServerTick.DestCandidate pick =
                new DuctGasServerTick.DestCandidate(destPos, destFace, destNode.insertionPriority, 0L, simulated);
        return commitExtract(
                level, sourceBe, sourceFace, node, srcHandler, toMove, pick, spec, edgeTicks, radioactivePayload);
    }

    private static Object applyExtractorInsertLimitGas(
            Object destHandler,
            DuctFaceNode sourceNode,
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit,
            Object available,
            ServerLevel level,
            BlockPos destPos) {
        if (MekanismChemicalCompat.isEmptyStack(available) || unit == null) {
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
            uConcat.add(net.unfamily.another_dynamics.duct.FilterConcatChannel.channelAt(concat, idx));
        }
        if (!DuctGasAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(uAllows, uCaps)) {
            return available;
        }
        long maxAdd =
                DuctGasAllowLimitLogic.maxAdditionalInsertAcrossAllowLines(
                        destHandler,
                        uAllows,
                        uCaps,
                        uConcat,
                        available,
                        level.registryAccess(),
                        (line, reg) ->
                                DuctGasAllowLimitLogic.countMatchingInStacks(
                                        DuctGasIncomingIndex.snapshot(level, destPos), line, reg));
        if (maxAdd == Long.MAX_VALUE) {
            return available;
        }
        long capped = Math.min(MekanismChemicalCompat.getAmount(available), Math.max(0L, maxAdd));
        if (capped <= 0) {
            return MekanismChemicalCompat.emptyStack();
        }
        if (capped < MekanismChemicalCompat.getAmount(available)) {
            return MekanismChemicalCompat.copyWithAmount(available, capped);
        }
        return available;
    }

    private static boolean commitExtract(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctFaceNode node,
            Object srcHandler,
            Object available,
            DuctGasServerTick.DestCandidate pick,
            DuctGasTransportSpec spec,
            long edgeTicks,
            boolean radioactivePayload) {
        long moved = Math.min(MekanismChemicalCompat.getAmount(available), pick.moved());
        if (moved <= 0) {
            return false;
        }
        Object extracted = MekanismChemicalCompat.extractAny(srcHandler, moved);
        if (MekanismChemicalCompat.isEmptyStack(extracted) || MekanismChemicalCompat.getAmount(extracted) <= 0) {
            return false;
        }
        String wantId = MekanismChemicalCompat.getTypeRegistryName(available);
        String gotId = MekanismChemicalCompat.getTypeRegistryName(extracted);
        if (wantId == null || !wantId.equals(gotId)) {
            MekanismChemicalCompat.insertExecute(srcHandler, extracted);
            return false;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            Optional<List<BlockPos>> pathOpt =
                    DuctNetworkCache.shortestPath(level, srcPos, pick.ductPos(), DuctNetworkType.GAS, radioactivePayload);
            if (pathOpt.isPresent()) {
                rawPath = pathOpt.get();
            } else if (radioactivePayload) {
                return false;
            } else {
                rawPath = List.of(srcPos, pick.ductPos());
            }
        }
        sourceBe.scheduleGasTransitPending(
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
            Object destHandler,
            net.unfamily.another_dynamics.duct.DuctFilterUnitLogic.FilterUnit unit,
            DuctGasTransportSpec spec,
            long edgeTicks,
            Set<BlockPos> radioactiveGasSubnet,
            int[] rr,
            int donorIdx,
            boolean roundRobinRetriever) {
        if (DuctGasServerTick.isGasFaceStalled(donorBe.getFaceLanes(donorFace))) {
            return false;
        }
        Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, donor, donorFace);
        if (srcHandler == null) {
            return false;
        }
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.RETRIEVER;
        long want = DuctModuleEffects.effectiveGasExtractBatch(retrieverBe, retrieverFace, spec);
        Object available = MekanismChemicalCompat.drainProbe(srcHandler, want);
        if (MekanismChemicalCompat.isEmptyStack(available)) {
            return false;
        }
        if (!DuctGasFilterLogic.gasMatchesAllowUnit(bank, node, unit, available, level)) {
            return false;
        }
        boolean radioactivePayload = MekanismChemicalCompat.isRadioactive(available);
        if (radioactivePayload && !radioactiveGasSubnet.contains(donor)) {
            return false;
        }
        DuctFaceNode donorGas = donorBe.getFaceLanes(donorFace).gas;
        NodeMode donorMode = donorBe.getFaceLanes(donorFace).nodeMode;
        if (donorMode == NodeMode.FILTERING_INSERTION
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        donorGas,
                        DuctFaceNode.FilterBank.FILTER,
                        available,
                        level,
                        DuctDirectionalEndpoint.connectionAtDuctFace(level, retrieverPos, retrieverFace))) {
            return false;
        }
        if (!DuctGasFilterLogic.passesGasFiltersForBank(
                node,
                bank,
                available,
                level,
                DuctDirectionalEndpoint.connectionAtDuctFace(level, donor, donorFace))) {
            return false;
        }
        long keepCap =
                DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                        srcHandler,
                        node.bankAllowFilters(bank),
                        node.bankAllowCaps(bank),
                        node.bankAllowConcatChannels(bank),
                        available,
                        level.registryAccess());
        if (keepCap != Long.MAX_VALUE) {
            long capped = Math.min(MekanismChemicalCompat.getAmount(available), keepCap);
            if (capped <= 0) {
                return false;
            }
            if (capped < MekanismChemicalCompat.getAmount(available)) {
                available = MekanismChemicalCompat.copyWithAmount(available, capped);
            }
        }
        long pendingSum = donorBe.pendingOutboundGasAmountFromFace(donorFace, available);
        long remainingInStorage = MekanismChemicalCompat.getAmount(available) - pendingSum;
        if (remainingInStorage <= 0) {
            return false;
        }
        long plannedAmt = Math.min(want, remainingInStorage);
        Object planStack = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
        long simDest = MekanismChemicalCompat.simulateInsert(destHandler, planStack);
        if (simDest <= 0) {
            return false;
        }
        plannedAmt = Math.min(plannedAmt, simDest);
        planStack = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
        plannedAmt =
                DuctGasServerTick.capInsertForRetrieverDestinationLimits(
                        level, retrieverBe, retrieverFace, planStack, destHandler, plannedAmt);
        if (plannedAmt <= 0) {
            return false;
        }
        planStack = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
        Object simExtract = MekanismChemicalCompat.simulateExtractChemical(srcHandler, plannedAmt);
        if (MekanismChemicalCompat.isEmptyStack(simExtract)
                || MekanismChemicalCompat.getAmount(simExtract) < plannedAmt) {
            return false;
        }
        if (!DuctRedstoneLogic.isFaceTransportActive(
                level, retrieverPos, retrieverBe.getFaceLanes(retrieverFace).redstoneMode)) {
            return false;
        }
        if (radioactivePayload
                && (!DuctPathfinder.gasPathAllowsRadioactive(level, List.of(donor))
                        || !DuctPathfinder.gasPathAllowsRadioactive(level, List.of(retrieverPos)))) {
            return false;
        }
        List<BlockPos> path;
        if (donor.equals(retrieverPos)) {
            path = List.of(retrieverPos);
        } else {
            Optional<List<BlockPos>> p =
                    DuctNetworkCache.shortestPath(level, donor, retrieverPos, DuctNetworkType.GAS, radioactivePayload);
            if (p.isEmpty()) {
                return false;
            }
            path = p.get();
        }
        if (roundRobinRetriever) {
            node.roundRobinCursor = rr[0] + donorIdx + 1;
        }
        Object extracted = MekanismChemicalCompat.extractAny(srcHandler, plannedAmt);
        if (MekanismChemicalCompat.isEmptyStack(extracted) || MekanismChemicalCompat.getAmount(extracted) <= 0) {
            return false;
        }
        String wantId = MekanismChemicalCompat.getTypeRegistryName(available);
        String gotId = MekanismChemicalCompat.getTypeRegistryName(extracted);
        if (wantId == null || !wantId.equals(gotId)) {
            MekanismChemicalCompat.insertExecute(srcHandler, extracted);
            return false;
        }
        donorBe.scheduleGasTransitPending(
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

    private static List<Integer> subList(List<Integer> in, int size) {
        return in.subList(0, Math.min(in.size(), size));
    }

    private static List<DuctDirectionalEndpoint> subListEndpoint(List<DuctDirectionalEndpoint> in, int size) {
        return in.subList(0, Math.min(in.size(), size));
    }

    private static List<Boolean> subListBool(List<Boolean> in, int size) {
        return in.subList(0, Math.min(in.size(), size));
    }

    /** Re-sends stalled gas using entry-first bound filter destinations. */
    static boolean tryDrainStallEntryFirst(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctGasTransportSpec spec,
            DuctFaceLanes lanes) {
        if (!MekanismChemicalCompat.isLoaded()) {
            return false;
        }
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.EXTRACTOR;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(sourceBe, sourceFace);
        int allowCap = DuctModuleEffects.effectiveGasAllowBank(spec, sourceMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        List<DuctDirectionalEndpoint> allowRemote = node.bankAllowRemoteNodes(bank);
        if (!DuctStallAllowBank.hasStallRoutableAllowBank(
                allowFull, allowCap, allowRemote)) {
            return false;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        Direction forbidSelfDestFace = allowSelfFeed ? null : sourceFace;
        long edgeTicks = DuctModuleEffects.effectiveGasEdgeTravelTicks(sourceBe, sourceFace, spec);
        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> remotes = subListEndpoint(allowRemote, allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);
        var regs = level.registryAccess();

        for (int slot = 0; slot < lanes.stalledGas.length; slot++) {
            var tag = lanes.stalledGas[slot];
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            Object stalled = MekanismChemicalCompat.loadGasStackFromTag(tag, regs);
            if (stalled == null
                    || MekanismChemicalCompat.isEmptyStack(stalled)
                    || MekanismChemicalCompat.getAmount(stalled) <= 0) {
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
                        && !DuctGasFilterLogic.gasMatchesAllowUnit(bank, node, unit, stalled, level)) {
                    continue;
                }
                List<DuctFilterDestinationResolver.ResolvedFace> faces =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                srcPos,
                                sourceFace,
                                DuctNetworkType.GAS,
                                DuctTransportKind.GAS,
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
                    if (!DuctGasFilterLogic.passesGasFiltersForBank(
                            node, bank, stalled, level, destCounterparty)) {
                        continue;
                    }
                    Object destHandler =
                            MekanismChemicalCompat.getChemicalHandlerOnFace(level, rf.ductPos(), rf.face());
                    if (destHandler == null) {
                        continue;
                    }
                    long moved = MekanismChemicalCompat.simulateInsert(destHandler, stalled);
                    if (moved <= 0) {
                        continue;
                    }
                    Object planned = MekanismChemicalCompat.copyWithAmount(stalled, moved);
                    planned =
                            applyExtractorInsertLimitGas(
                                    destHandler, node, unit, planned, level, rf.ductPos());
                    if (MekanismChemicalCompat.isEmptyStack(planned)) {
                        continue;
                    }
                    moved = MekanismChemicalCompat.getAmount(planned);
                    planned = MekanismChemicalCompat.copyWithAmount(stalled, moved);
                    List<BlockPos> rawPath;
                    if (rf.ductPos().equals(srcPos)) {
                        rawPath = List.of(srcPos);
                    } else {
                        rawPath =
                                DuctNetworkCache.shortestPath(level, srcPos, rf.ductPos(), DuctNetworkType.GAS)
                                        .orElseGet(() -> List.of(srcPos, rf.ductPos()));
                    }
                    sourceBe.scheduleGasTransitPending(
                            level,
                            planned,
                            OutboundShipment.copyPath(rawPath),
                            sourceFace,
                            rf.face(),
                            rf.ductPos(),
                            spec,
                            edgeTicks);
                    long left = MekanismChemicalCompat.getAmount(stalled) - moved;
                    if (left <= 0) {
                        lanes.stalledGas[slot] = new net.minecraft.nbt.CompoundTag();
                    } else {
                        Object rem = MekanismChemicalCompat.copyWithAmount(stalled, left);
                        net.minecraft.nbt.CompoundTag nt = new net.minecraft.nbt.CompoundTag();
                        MekanismChemicalCompat.saveGasStackToTag(rem, nt);
                        lanes.stalledGas[slot] = nt;
                    }
                    sourceBe.setChanged();
                    return true;
                }
            }
        }
        return false;
    }
}
