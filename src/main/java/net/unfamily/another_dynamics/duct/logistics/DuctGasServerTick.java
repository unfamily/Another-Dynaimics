package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.*;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.jetbrains.annotations.Nullable;

/**
 * Gas ("chemical") logistics: plan extract+insert with simulation only, enqueue {@link GasTransitShipment};
 * {@link DuctBlockEntity} executes transfer when travel completes and re-validates each tick in transit.
 *
 * <p>Mekanism is optional: this does nothing when Mekanism isn't loaded.</p>
 */
public final class DuctGasServerTick {
    private static final int RETRIEVE_ROUTE_RETRY_CAP = 32;

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
        int rate = spec.clampedRateTicks(spec.rateDefaultTicks());
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            DuctFaceNode node = lanes.gas;
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
        BlockPos srcPos = sourceBe.getBlockPos();
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

        // Build candidate insertion faces across the gas network: priority first, then routing tie-break.
        List<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, srcPos, DuctNetworkType.GAS));
        boolean allowSelf = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        if (ducts.size() > 1 && !allowSelf) {
            ducts.remove(srcPos);
        }
        if (ducts.isEmpty()) {
            return;
        }

        String chemicalId = MekanismChemicalCompat.getTypeRegistryName(available);
        if (chemicalId == null || chemicalId.isEmpty()) {
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
                            : DuctPathfinder.distance(level, srcPos, destPos, spec, DuctNetworkType.GAS);
            if (dist.isEmpty()) {
                continue;
            }
            int dsm = destBe.getStorageMask();
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
                    continue;
                }
                DuctFaceLanes destLanes = destBe.getFaceLanes(df);
                DuctFaceNode destNode = destLanes.gas;
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

                Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, destPos, df);
                if (destHandler == null) {
                    continue;
                }

                long availAmt = MekanismChemicalCompat.getAmount(available);
                if (availAmt <= 0) {
                    continue;
                }
                Object tryStack = MekanismChemicalCompat.copyWithAmount(available, availAmt);
                long simulated = MekanismChemicalCompat.simulateInsert(destHandler, tryStack);
                if (simulated <= 0) {
                    continue;
                }

                if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                        && !DuctGasFilterLogic.passesGasFiltersForBank(destNode, DuctFaceNode.FilterBank.FILTER, available, level)) {
                    continue;
                }

                if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                        && !DuctGasFilterLogic.passesGasFiltersForBank(destNode, DuctFaceNode.FilterBank.RETRIEVER, available, level)) {
                    continue;
                }

                if (dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING) {
                    List<String> allowLines = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
                    List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
                    int idx = DuctGasAllowLimitLogic.firstMatchingAllowLineIndex(allowLines, tryStack, level.registryAccess());
                    if (idx >= 0 && idx < caps.size()) {
                        int lim = caps.get(idx);
                        if (lim > 0) {
                            String line = allowLines.get(idx);
                            long current =
                                    DuctGasAllowLimitLogic.countMatchingInHandler(destHandler, line, level.registryAccess());
                            long pending =
                                    DuctGasAllowLimitLogic.countMatchingInStacks(
                                            DuctGasIncomingIndex.snapshot(level, destPos), line, level.registryAccess());
                            long maxAdd = Math.max(0L, (long) lim - (current + pending));
                            simulated = Math.min(simulated, maxAdd);
                            if (simulated <= 0) {
                                continue;
                            }
                        }
                    }
                }
                if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
                    simulated =
                            capInsertForRetrieverDestinationLimits(
                                    level, destBe, df, tryStack, destHandler, simulated);
                    if (simulated <= 0) {
                        continue;
                    }
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

        long moved = Math.min(MekanismChemicalCompat.getAmount(available), pick.moved());
        if (moved <= 0) {
            return;
        }
        Object planned = MekanismChemicalCompat.copyWithAmount(available, moved);

        List<BlockPos> rawPath;
        if (pick.ductPos().equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            rawPath = DuctPathfinder.shortestPath(level, srcPos, pick.ductPos(), spec, DuctNetworkType.GAS)
                    .orElseGet(() -> List.of(srcPos, pick.ductPos()));
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
        sourceBe.scheduleGasTransitPending(level, planned, pathWire, sourceFace, pick.face(), pick.ductPos(), spec);
    }

    private static void tryRetrievePull(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctFaceNode node,
            DuctGasTransportSpec spec) {
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, retrieverPos, retrieverFace);
        if (destHandler == null) {
            return;
        }
        RoutingMode rm = retrieverLanes.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        int[] rr = new int[] {node.roundRobinCursor};
        boolean singleDuctNetwork =
                DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.GAS).size() == 1;
        List<DuctTargetSelector.DonorCandidate> donors =
                listGasRetrievingDonorCandidates(
                        level,
                        retrieverPos,
                        retrieverFace,
                        destHandler,
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
                        DuctPathfinder.shortestPath(level, donor, retrieverPos, spec, DuctNetworkType.GAS);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }
            if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
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
            DuctFaceLanes donorLanes = donorBe.getFaceLanes(donorFace);
            DuctFaceNode donorGas = donorLanes.gas;
            NodeMode donorMode = donorLanes.nodeMode;
            if (donorMode == NodeMode.FILTERING_INSERTION
                    && !DuctGasFilterLogic.passesGasFiltersForBank(
                            donorGas, DuctFaceNode.FilterBank.FILTER, available, level)) {
                continue;
            }
            long keepCap =
                    DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                            srcHandler,
                            donorGas.bankAllowFilters(DuctFaceNode.FilterBank.FILTER),
                            donorGas.bankAllowCaps(DuctFaceNode.FilterBank.FILTER),
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
                    node, DuctFaceNode.FilterBank.RETRIEVER, available, level)) {
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
            Object planned = MekanismChemicalCompat.copyWithAmount(available, plannedAmt);
            donorBe.scheduleGasTransitPending(
                    level, planned, OutboundShipment.copyPath(path), donorFace, retrieverFace, retrieverPos, spec);
            retrieverBe.setChanged();
            return;
        }
    }

    private static List<DuctTargetSelector.DonorCandidate> listGasRetrievingDonorCandidates(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            Object retrieverDestHandler,
            RoutingMode routing,
            int roundRobinCursor,
            int retrieverGasChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace,
            DuctGasTransportSpec spec) {
        ArrayList<DuctTargetSelector.DonorCandidate> cands = new ArrayList<>();
        for (BlockPos p : DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.GAS)) {
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
                                donorGas, DuctFaceNode.FilterBank.FILTER, sample, level)) {
                    continue;
                }
                if (MekanismChemicalCompat.simulateInsert(retrieverDestHandler, sample) <= 0) {
                    continue;
                }
                OptionalLong dist =
                        p.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctPathfinder.distance(level, retrieverPos, p, spec, DuctNetworkType.GAS);
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

    private static long capInsertForRetrieverDestinationLimits(
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
        return Math.min(
                simulatedInsert,
                DuctGasAllowLimitLogic.maxAdditionalInsertForAllowLine(
                        destHandler,
                        allowLines,
                        caps,
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
        DuctFaceNode srcNode = srcLanes.gas;
        Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, srcPos, s.sourceFace);
        if (srcHandler == null) {
            return false;
        }
        if (srcExtract) {
            if (!DuctGasFilterLogic.passesGasFiltersForBank(
                    srcNode, DuctFaceNode.FilterBank.EXTRACTOR, s.stack, level)) {
                return false;
            }
            long keepCap =
                    DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                            srcHandler,
                            srcNode.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR),
                            srcNode.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR),
                            s.stack,
                            level.registryAccess());
            if (keepCap != Long.MAX_VALUE && MekanismChemicalCompat.getAmount(s.stack) > keepCap) {
                return false;
            }
        } else {
            if (sm == NodeMode.FILTERING_INSERTION
                    && !DuctGasFilterLogic.passesGasFiltersForBank(
                            srcNode, DuctFaceNode.FilterBank.FILTER, s.stack, level)) {
                return false;
            }
            long keepCap =
                    DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                            srcHandler,
                            srcNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER),
                            srcNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER),
                            s.stack,
                            level.registryAccess());
            if (keepCap != Long.MAX_VALUE && MekanismChemicalCompat.getAmount(s.stack) > keepCap) {
                return false;
            }
        }
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        return pinnedGasFaceSimulatesOk(level, destBe, srcHandler, s);
    }

    private static boolean pinnedGasFaceSimulatesOk(
            ServerLevel level, DuctBlockEntity destBe, Object srcHandler, GasTransitShipment s) {
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
                        destLanes.gas, DuctFaceNode.FilterBank.FILTER, s.stack, level)) {
            return false;
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destLanes.gas, DuctFaceNode.FilterBank.RETRIEVER, s.stack, level)) {
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
        long amt = Math.min(MekanismChemicalCompat.getAmount(s.stack), simulated);
        Object drainSim = MekanismChemicalCompat.simulateExtractChemical(srcHandler, amt);
        return !MekanismChemicalCompat.isEmptyStack(drainSim) && MekanismChemicalCompat.getAmount(drainSim) >= amt;
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist, long moved) {}

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

