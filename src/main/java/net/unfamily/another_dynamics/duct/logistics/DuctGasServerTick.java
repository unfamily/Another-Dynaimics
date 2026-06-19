package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.*;
import net.unfamily.another_dynamics.duct.logistics.DuctSameBlockRouting;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Gas logistics (Mekanism chemicals); optional when Mek is absent.
 * <p><strong>Material-lane family:</strong> behavioural changes often need equivalent updates for item and fluid
 * logistics, including universal ducts with those kinds enabled.
 */
public final class DuctGasServerTick {
    private static final int RETRIEVE_ROUTE_RETRY_CAP = 32;
    private static final int EXTRACT_ROUTE_RETRY_CAP = 32;

    private DuctGasServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        try {
        if (!MekanismChemicalCompat.isLoaded()) {
            return;
        }
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.GAS)).orElse(false)) {
            return;
        }
        DuctGasTransportSpec spec = be.gasTransportSpec();
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.GAS)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            DuctFaceNode node = lanes.gas;
            if (hasStalledGas(lanes) && tryDrainGasStallForFace(be, level, dir, lanes.nodeMode, node, spec)) {
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
            int rate = DuctModuleEffects.effectiveGasActionRateTicks(be, dir, spec);
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
        } catch (Throwable t) {
            // Never let a bad compat call disable ticking for this block entity.
            AnotherDynamicsMod.LOGGER.error("Gas tick failed at {} (ductId={})", be.getBlockPos(), be.getLogicalDuctId(), t);
        }
    }

    private static void tryExtractPush(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctGasTransportSpec spec) {
        if (DuctFilterGasRouting.tryExtractEntryFirst(level, sourceBe, sourceFace, sourceMode, node, spec)) {
            return;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        if (isGasFaceStalled(sourceBe.getFaceLanes(sourceFace))) {
            return;
        }
        Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, srcPos, sourceFace);
        if (srcHandler == null) {
            return;
        }

        long want = node.extractBatch > 0 ? node.extractBatch : spec.batchDefault();
        want = spec.clampedBatch(want);
        Object available = MekanismChemicalCompat.drainProbe(srcHandler, want);
        if (MekanismChemicalCompat.isEmptyStack(available)) {
            return;
        }
        long keepCap =
                DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                        srcHandler,
                        node.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                        node.bankAllowConcatChannels(DuctFaceNode.FilterBank.EXTRACTOR),
                        available,
                        level.registryAccess());
        if (keepCap != Long.MAX_VALUE) {
            long capped = Math.min(MekanismChemicalCompat.getAmount(available), keepCap);
            if (capped <= 0) {
                return;
            }
            if (capped < MekanismChemicalCompat.getAmount(available)) {
                available = MekanismChemicalCompat.copyWithAmount(available, capped);
            }
        }
        if (!DuctGasFilterLogic.passesGasFiltersForBank(node, DuctFaceNode.FilterBank.EXTRACTOR, available, level)) {
            return;
        }

        boolean radioactivePayload = MekanismChemicalCompat.isRadioactive(available);
        if (radioactivePayload && !DuctPathfinder.gasPathAllowsRadioactive(level, List.of(srcPos))) {
            return;
        }

        // Build candidate insertion faces: full gas network, or only the radioactive-capable subgraph for radioactive cargo.
        String chemicalId = MekanismChemicalCompat.getTypeRegistryName(available);
        if (chemicalId == null || chemicalId.isEmpty()) {
            return;
        }
        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
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
            return;
        }
        int maxP = scored.getFirst().endpoint().insertionPriority();
        ArrayList<DestCandidate> validInTier = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            if (s.endpoint().insertionPriority() != maxP) {
                break;
            }
            probeGasDestinationForRouting(level, srcPos, sourceFace, node, available, s)
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
        DestCandidate pick = pickWithinTierForRouting(level, validInTier, extractRm, rr);
        if (pick == null) {
            return;
        }
        node.roundRobinCursor = rr[0];
        sourceBe.setChanged();

        long moved = Math.min(MekanismChemicalCompat.getAmount(available), pick.moved());
        if (moved <= 0) {
            return;
        }
        Object extracted = MekanismChemicalCompat.extractAny(srcHandler, moved);
        if (MekanismChemicalCompat.isEmptyStack(extracted) || MekanismChemicalCompat.getAmount(extracted) <= 0) {
            return;
        }
        String wantId = MekanismChemicalCompat.getTypeRegistryName(available);
        String gotId = MekanismChemicalCompat.getTypeRegistryName(extracted);
        if (wantId == null || !wantId.equals(gotId)) {
            // Put it back; extraction order can differ.
            MekanismChemicalCompat.insertExecute(srcHandler, extracted);
            return;
        }
        Object planned = extracted;

        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            Optional<List<BlockPos>> pathOpt =
                    DuctNetworkCache.shortestPath(
                            level, srcPos, pick.ductPos(), DuctNetworkType.GAS, radioactivePayload);
            if (pathOpt.isPresent()) {
                rawPath = pathOpt.get();
            } else if (radioactivePayload) {
                return;
            } else {
                rawPath = List.of(srcPos, pick.ductPos());
            }
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
        long edgeTicks = DuctModuleEffects.effectiveGasEdgeTravelTicks(sourceBe, sourceFace, spec);
        sourceBe.scheduleGasTransitPending(
                level, planned, pathWire, sourceFace, pick.face(), pick.ductPos(), spec, edgeTicks);
    }

    private static void tryRetrievePull(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctFaceNode node,
            DuctGasTransportSpec spec) {
        if (DuctFilterGasRouting.tryRetrieveEntryFirst(level, retrieverBe, retrieverFace, node, spec)) {
            return;
        }
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, retrieverPos, retrieverFace);
        if (destHandler == null) {
            return;
        }
        RoutingMode rm = node.routingForRetrieval(retrieverLanes.nodeMode);
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        int[] rr = new int[] {node.roundRobinCursor};
        Set<BlockPos> radioactiveGasSubnet = DuctNetworkCache.connectedRadioactiveGasDucts(level, retrieverPos);
        List<DuctTargetSelector.DonorCandidate> donors =
                listGasRetrievingDonorCandidates(
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
        if (donors.isEmpty()) {
            return;
        }
        for (int donorIdx = 0; donorIdx < donors.size() && donorIdx < RETRIEVE_ROUTE_RETRY_CAP; donorIdx++) {
            DuctTargetSelector.DonorCandidate donorCand = donors.get(donorIdx);
            BlockPos donor = donorCand.ductPos();
            Direction donorFace = donorCand.face();
            if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            if (isGasFaceStalled(donorBe.getFaceLanes(donorFace))) {
                continue;
            }
            Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, donor, donorFace);
            if (srcHandler == null) {
                continue;
            }
            long want = node.extractBatch > 0 ? node.extractBatch : spec.batchDefault();
            want = spec.clampedBatch(want);
            Object available = MekanismChemicalCompat.drainProbe(srcHandler, want);
            if (MekanismChemicalCompat.isEmptyStack(available)) {
                continue;
            }
            boolean radioactivePayload = MekanismChemicalCompat.isRadioactive(available);
            if (radioactivePayload && !radioactiveGasSubnet.contains(donor)) {
                continue;
            }
            if (radioactivePayload
                    && (!DuctPathfinder.gasPathAllowsRadioactive(level, List.of(donor))
                            || !DuctPathfinder.gasPathAllowsRadioactive(level, List.of(retrieverPos)))) {
                continue;
            }
            List<BlockPos> path;
            if (donor.equals(retrieverPos)) {
                path = List.of(retrieverPos);
            } else {
                Optional<List<BlockPos>> p =
                        DuctNetworkCache.shortestPath(
                                level, donor, retrieverPos, DuctNetworkType.GAS, radioactivePayload);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }
            DuctFaceLanes donorLanes = donorBe.getFaceLanes(donorFace);
            DuctFaceNode donorGas = donorLanes.gas;
            NodeMode donorMode = donorLanes.nodeMode;
            if (donorMode == NodeMode.FILTERING_INSERTION
                    && !DuctGasFilterLogic.passesGasFiltersForBank(
                            donorGas,
                            DuctFaceNode.FilterBank.FILTER,
                            available,
                            level,
                            DuctDirectionalEndpoint.connectionAtDuctFace(level, retrieverPos, retrieverFace))) {
                continue;
            }
            long keepCap =
                    DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                            srcHandler,
                            donorGas.bankAllowFilters(DuctFaceNode.FilterBank.FILTER),
                            donorGas.bankAllowCaps(DuctFaceNode.FilterBank.FILTER),
                            donorGas.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER),
                            available,
                            level.registryAccess());
            if (keepCap != Long.MAX_VALUE) {
                long capped = Math.min(MekanismChemicalCompat.getAmount(available), keepCap);
                if (capped <= 0) {
                    continue;
                }
                if (capped < MekanismChemicalCompat.getAmount(available)) {
                    available = MekanismChemicalCompat.copyWithAmount(available, capped);
                }
            }
            if (!DuctGasFilterLogic.passesGasFiltersForBank(
                    node,
                    DuctFaceNode.FilterBank.RETRIEVER,
                    available,
                    level,
                    DuctDirectionalEndpoint.connectionAtDuctFace(level, donor, donorFace))) {
                continue;
            }
            long pendingSum = donorBe.pendingOutboundGasAmountFromFace(donorFace, available);
            long remainingInStorage = MekanismChemicalCompat.getAmount(available) - pendingSum;
            if (remainingInStorage <= 0) {
                continue;
            }
            long plannedAmt = Math.min(want, remainingInStorage);

            Object planStack = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
            long simDest = MekanismChemicalCompat.simulateInsert(destHandler, planStack);
            if (simDest <= 0) {
                continue;
            }
            plannedAmt = Math.min(plannedAmt, simDest);
            planStack = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
            plannedAmt =
                    capInsertForRetrieverDestinationLimits(
                            level, retrieverBe, retrieverFace, planStack, destHandler, plannedAmt);
            if (plannedAmt <= 0) {
                continue;
            }
            planStack = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
            Object simExtract = MekanismChemicalCompat.simulateExtractChemical(srcHandler, plannedAmt);
            if (MekanismChemicalCompat.isEmptyStack(simExtract)
                    || MekanismChemicalCompat.getAmount(simExtract) < plannedAmt) {
                continue;
            }
            if (!DuctRedstoneLogic.isFaceTransportActive(level, retrieverPos, retrieverLanes.redstoneMode)) {
                continue;
            }
            if (roundRobinRetriever) {
                node.roundRobinCursor = rr[0] + donorIdx + 1;
            }
            Object extracted = MekanismChemicalCompat.extractAny(srcHandler, plannedAmt);
            if (MekanismChemicalCompat.isEmptyStack(extracted) || MekanismChemicalCompat.getAmount(extracted) <= 0) {
                continue;
            }
            String wantId = MekanismChemicalCompat.getTypeRegistryName(available);
            String gotId = MekanismChemicalCompat.getTypeRegistryName(extracted);
            if (wantId == null || !wantId.equals(gotId)) {
                MekanismChemicalCompat.insertExecute(srcHandler, extracted);
                continue;
            }
            Object planned = extracted;
            // Travel speed modules live on the retriever face (same as item retriever), not the donor.
            long edgeTicks = DuctModuleEffects.effectiveGasEdgeTravelTicks(retrieverBe, retrieverFace, spec);
            donorBe.scheduleGasTransitPending(
                    level, planned, OutboundShipment.copyPath(path), donorFace, retrieverFace, retrieverPos, spec, edgeTicks);
            retrieverBe.setChanged();
            return;
        }
    }

    public static boolean tryDrainGasStallForFace(
            DuctBlockEntity sourceBe,
            ServerLevel level,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctGasTransportSpec spec) {
        if (!MekanismChemicalCompat.isLoaded()) {
            return false;
        }
        DuctFaceLanes lanes = sourceBe.getFaceLanes(sourceFace);
        if (!hasStalledGas(lanes)) {
            return false;
        }
        for (int slot = 0; slot < lanes.stalledGas.length; slot++) {
            var tag = lanes.stalledGas[slot];
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            Object stalled = MekanismChemicalCompat.loadGasStackFromTag(tag, level.registryAccess());
            if (stalled == null || MekanismChemicalCompat.isEmptyStack(stalled) || MekanismChemicalCompat.getAmount(stalled) <= 0) {
                continue;
            }
            long moved = tryDeliverStalledGas(level, sourceBe, sourceFace, sourceMode, node, spec, stalled);
            if (moved > 0) {
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
        return false;
    }

    private static long tryDeliverStalledGas(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctGasTransportSpec spec,
            Object available) {
        if (MekanismChemicalCompat.isEmptyStack(available) || MekanismChemicalCompat.getAmount(available) <= 0) {
            return 0L;
        }
        if (!DuctGasFilterLogic.passesGasFiltersForBank(node, DuctFaceNode.FilterBank.EXTRACTOR, available, level)) {
            return 0L;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        boolean radioactivePayload = MekanismChemicalCompat.isRadioactive(available);
        boolean allowSelfFeed = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                DuctRoutingEndpointIndex.listScoredExtractDestinations(
                        level,
                        srcPos,
                        DuctNetworkType.GAS,
                        DuctTransportKind.GAS,
                        spec.edgeTravelTicks(),
                        node.channelLetter,
                        true,
                        true,
                        null,
                        allowSelfFeed,
                        sourceFace,
                        radioactivePayload);
        if (scored.isEmpty()) {
            return 0L;
        }
        int maxP = scored.getFirst().endpoint().insertionPriority();
        ArrayList<DestCandidate> validInTier = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            if (s.endpoint().insertionPriority() != maxP) {
                break;
            }
            probeGasDestinationForStall(level, sourceBe, sourceFace, node, available, s)
                    .ifPresent(validInTier::add);
            if (validInTier.size() >= EXTRACT_ROUTE_RETRY_CAP) {
                break;
            }
        }
        if (validInTier.isEmpty()) {
            return 0L;
        }
        int[] rr = new int[] {node.roundRobinCursor};
        RoutingMode stallRm = node.routingForStallResend(sourceMode);
        DestCandidate pick = pickWithinTierForRouting(level, validInTier, stallRm, rr);
        if (pick == null) {
            return 0L;
        }
        node.roundRobinCursor = rr[0];
        long moved = Math.min(MekanismChemicalCompat.getAmount(available), pick.moved());
        if (moved <= 0) {
            return 0L;
        }
        Object planned = MekanismChemicalCompat.copyWithAmount(available, moved);
        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            Optional<List<BlockPos>> pathOpt =
                    DuctNetworkCache.shortestPath(
                            level, srcPos, pick.ductPos(), DuctNetworkType.GAS, radioactivePayload);
            rawPath = pathOpt.orElseGet(() -> List.of(srcPos, pick.ductPos()));
        }
        long edgeTicks = DuctModuleEffects.effectiveGasEdgeTravelTicks(sourceBe, sourceFace, spec);
        sourceBe.scheduleGasTransitPending(
                level, planned, OutboundShipment.copyPath(rawPath), sourceFace, pick.face(), pick.ductPos(), spec, edgeTicks);
        return moved;
    }

    private static boolean hasStalledGas(DuctFaceLanes lanes) {
        if (lanes == null) {
            return false;
        }
        for (var g : lanes.stalledGas) {
            if (g != null && !g.isEmpty() && (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount"))) {
                return true;
            }
        }
        return false;
    }

    static boolean isGasFaceStalled(DuctFaceLanes lanes) {
        if (lanes == null) {
            return false;
        }
        int filled = 0;
        for (var g : lanes.stalledGas) {
            if (g != null && !g.isEmpty() && (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount"))) {
                filled++;
            }
        }
        return filled >= lanes.stalledGas.length;
    }

    static List<DuctTargetSelector.DonorCandidate> listGasRetrievingDonorCandidates(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            Object retrieverDestHandler,
            RoutingMode routing,
            int roundRobinCursor,
            int retrieverGasChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace,
            DuctGasTransportSpec spec,
            Set<BlockPos> radioactiveGasSubnet) {
        ArrayList<DuctTargetSelector.DonorCandidate> cands = new ArrayList<>();
        for (BlockPos p : DuctNetworkCache.connectedDucts(level, retrieverPos, DuctNetworkType.GAS)) {
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
                if (!allowSelfDonor
                        && DuctSameBlockRouting.skipSameBlockDonorFace(
                                retrieverPos, retrieverInventoryFace, p, d)) {
                    continue;
                }
                if (!be.isTransportKindEnabled(d, DuctTransportKind.GAS)) {
                    continue;
                }
                DuctFaceLanes donorLanes = be.getFaceLanes(d);
                DuctFaceNode donorGas = donorLanes.gas;
                if (!DuctRedstoneLogic.isFaceTransportActive(level, p, donorLanes.redstoneMode)) {
                    continue;
                }
                NodeMode donorMode = donorLanes.nodeMode;
                if (donorMode != NodeMode.NONE && donorMode != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!donorGas.eligibilityMode.isRetrievable()) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(donorGas.channelLetter, retrieverGasChannel)) {
                    continue;
                }
                Object srcH = MekanismChemicalCompat.getChemicalHandlerOnFace(level, p, d);
                if (srcH == null) {
                    continue;
                }
                long probeMax = spec.clampedBatch(Math.max(spec.batchDefault(), 1024L));
                Object sample = MekanismChemicalCompat.drainProbe(srcH, probeMax);
                if (MekanismChemicalCompat.isEmptyStack(sample)) {
                    continue;
                }
                if (donorMode == NodeMode.FILTERING_INSERTION
                        && !DuctGasFilterLogic.passesGasFiltersForBank(
                                donorGas,
                                DuctFaceNode.FilterBank.FILTER,
                                sample,
                                level,
                                DuctDirectionalEndpoint.connectionAtDuctFace(level, retrieverPos, retrieverInventoryFace))) {
                    continue;
                }
                if (MekanismChemicalCompat.simulateInsert(retrieverDestHandler, sample) <= 0) {
                    continue;
                }
                boolean radioactiveSample = MekanismChemicalCompat.isRadioactive(sample);
                if (radioactiveSample && !radioactiveGasSubnet.contains(p)) {
                    continue;
                }
                OptionalLong dist =
                        p.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctNetworkCache.routingTravelTicks(
                                        level,
                                        retrieverPos,
                                        p,
                                        spec.edgeTravelTicks(),
                                        DuctNetworkType.GAS,
                                        radioactiveSample);
                if (dist.isEmpty()) {
                    continue;
                }
                cands.add(new DuctTargetSelector.DonorCandidate(p, d, donorGas.insertionPriority, dist.getAsLong()));
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
            orderGasDonorTier(tier, routing, level, rrApplied ? 0 : roundRobinCursor);
            rrApplied = rrApplied || routing == RoutingMode.ROUND_ROBIN;
            out.addAll(tier);
        }
        return out;
    }

    private static void orderGasDonorTier(
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

    static long capInsertForRetrieverDestinationLimits(
            ServerLevel level,
            DuctBlockEntity destBe,
            Direction destFace,
            Object movingProbe,
            Object destHandler,
            long simulatedInsert) {
        if (simulatedInsert <= 0 || MekanismChemicalCompat.isEmptyStack(movingProbe)) {
            return 0L;
        }
        NodeMode dm = destBe.getFaceLanes(destFace).nodeMode;
        if (dm != NodeMode.RETRIEVING && dm != NodeMode.RETRIEVING_EXTRACTION) {
            return simulatedInsert;
        }
        DuctFaceNode destNode = destBe.getFaceLanes(destFace).gas;
        List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> concat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.RETRIEVER);
        return Math.min(
                simulatedInsert,
                DuctGasAllowLimitLogic.maxAdditionalInsertForAllowLine(
                        destHandler,
                        allowLines,
                        caps,
                        concat,
                        movingProbe,
                        level.registryAccess(),
                        (line, reg) ->
                                DuctGasAllowLimitLogic.countMatchingInStacks(
                                        DuctGasIncomingIndex.snapshot(level, destBe.getBlockPos()), line, reg)));
    }

    public static boolean gasShipmentMidTransitValid(ServerLevel level, DuctBlockEntity sourceBe, GasTransitShipment s) {
        if (!MekanismChemicalCompat.isLoaded() || s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
            return false;
        }
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
        return pinnedGasFaceSimulatesOk(level, sourceBe, destBe, s);
    }

    private static boolean pinnedGasFaceSimulatesOk(
            ServerLevel level, DuctBlockEntity sourceBe, DuctBlockEntity destBe, GasTransitShipment s) {
        DuctDirectionalEndpoint sourceEp =
                DuctDirectionalEndpoint.connectionAtDuctFace(level, sourceBe.getBlockPos(), s.sourceFace);
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
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destLanes.gas, DuctFaceNode.FilterBank.FILTER, s.stack, level, sourceEp)) {
            return false;
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destLanes.gas, DuctFaceNode.FilterBank.RETRIEVER, s.stack, level, sourceEp)) {
            return false;
        }
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, destPos, df);
        if (destHandler == null) {
            return false;
        }
        long simulated = MekanismChemicalCompat.simulateInsert(destHandler, s.stack);
        if (simulated <= 0) {
            return false;
        }
        if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
            simulated =
                    capInsertForRetrieverDestinationLimits(
                            level, destBe, df, s.stack, destHandler, simulated);
            if (simulated <= 0) {
                return false;
            }
        }
        return simulated > 0;
    }

    static Optional<DestCandidate> probeGasDestinationForRouting(
            ServerLevel level,
            BlockPos sourcePos,
            Direction sourceFace,
            DuctFaceNode sourceNode,
            Object available,
            DuctRoutingEndpointIndex.ScoredEndpoint scored) {
        DuctRoutingEndpointIndex.RoutingEndpoint ep = scored.endpoint();
        if (!(level.getBlockEntity(ep.pos()) instanceof DuctBlockEntity destBe)) {
            return Optional.empty();
        }
        Direction df = ep.face();
        DuctDirectionalEndpoint destEp = DuctDirectionalEndpoint.connectionAtDuctFace(level, ep.pos(), df);
        DuctDirectionalEndpoint sourceEp = DuctDirectionalEndpoint.connectionAtDuctFace(level, sourcePos, sourceFace);
        if (!DuctGasFilterLogic.passesGasFiltersForBank(
                sourceNode, DuctFaceNode.FilterBank.EXTRACTOR, available, level, destEp)) {
            return Optional.empty();
        }
        DuctFaceNode destNode = destBe.getFaceLanes(df).gas;
        NodeMode dm = destBe.getFaceLanes(df).nodeMode;
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, ep.pos(), df);
        if (destHandler == null) {
            return Optional.empty();
        }
        long availAmt = MekanismChemicalCompat.getAmount(available);
        if (availAmt <= 0) {
            return Optional.empty();
        }
        Object tryStack = MekanismChemicalCompat.copyWithAmount(available, availAmt);
        long simulated = MekanismChemicalCompat.simulateInsert(destHandler, tryStack);
        if (simulated <= 0) {
            return Optional.empty();
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.FILTER, available, level, sourceEp)) {
            return Optional.empty();
        }
        if (dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING) {
            List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
            List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
            List<Integer> concat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER);
            long maxAdd =
                    DuctGasAllowLimitLogic.maxAdditionalInsertAcrossAllowLines(
                            destHandler,
                            allowLines,
                            caps,
                            concat,
                            tryStack,
                            level.registryAccess(),
                            (line, reg) ->
                                    DuctGasAllowLimitLogic.countMatchingInStacks(
                                            DuctGasIncomingIndex.snapshot(level, ep.pos()), line, reg));
            if (maxAdd != Long.MAX_VALUE) {
                simulated = Math.min(simulated, maxAdd);
                if (simulated <= 0) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new DestCandidate(ep.pos(), df, ep.insertionPriority(), scored.distTicks(), simulated));
    }

    private static Optional<DestCandidate> probeGasDestinationForStall(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctFaceNode sourceNode,
            Object available,
            DuctRoutingEndpointIndex.ScoredEndpoint scored) {
        DuctRoutingEndpointIndex.RoutingEndpoint ep = scored.endpoint();
        if (!(level.getBlockEntity(ep.pos()) instanceof DuctBlockEntity destBe)) {
            return Optional.empty();
        }
        Direction df = ep.face();
        DuctDirectionalEndpoint destEp = DuctDirectionalEndpoint.connectionAtDuctFace(level, ep.pos(), df);
        DuctDirectionalEndpoint sourceEp =
                DuctDirectionalEndpoint.connectionAtDuctFace(level, sourceBe.getBlockPos(), sourceFace);
        DuctFaceNode destNode = destBe.getFaceLanes(df).gas;
        NodeMode dm = destBe.getFaceLanes(df).nodeMode;
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, ep.pos(), df);
        if (destHandler == null) {
            return Optional.empty();
        }
        Object tryStack = MekanismChemicalCompat.copyWithAmount(available, MekanismChemicalCompat.getAmount(available));
        if (!DuctGasFilterLogic.passesGasFiltersForBank(
                sourceNode, DuctFaceNode.FilterBank.EXTRACTOR, tryStack, level, destEp)) {
            return Optional.empty();
        }
        long simulated = MekanismChemicalCompat.simulateInsert(destHandler, tryStack);
        if (simulated <= 0) {
            return Optional.empty();
        }
        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.FILTER, tryStack, level, sourceEp)) {
            return Optional.empty();
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destNode, DuctFaceNode.FilterBank.RETRIEVER, tryStack, level, sourceEp)) {
            return Optional.empty();
        }
        return Optional.of(new DestCandidate(ep.pos(), df, ep.insertionPriority(), scored.distTicks(), simulated));
    }

    record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist, long moved) {}

    static DestCandidate pickWithinTierForRouting(
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
                tier.sort(Comparator.comparingLong((DestCandidate c) -> c.ductPos().asLong())
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
}

