package net.unfamily.another_dynamics.duct;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctFilterDestinationResolver;
import net.unfamily.another_dynamics.duct.logistics.DuctHandlerSlotSemantics;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctInsertProbeCache;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.logistics.DuctTargetSelector;
import net.unfamily.another_dynamics.duct.logistics.DuctTransitDebugLog;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects.FilterSlotBonuses;

import org.jetbrains.annotations.Nullable;

/** Entry-first item extract/retrieve scheduling with bound filter destinations. */
final class DuctFilterItemRouting {
    private static final int ROUTE_RETRY_CAP = 8;

    private DuctFilterItemRouting() {}

    static boolean hasNonEmptyAllowLines(List<String> lines, int cap) {
        return DuctStallAllowBank.hasNonEmptyAllowLines(lines, cap);
    }

    static boolean hasStallRoutableAllowBank(
            List<String> allow, int allowCap, List<DuctDirectionalEndpoint> remote) {
        return DuctStallAllowBank.hasStallRoutableAllowBank(allow, allowCap, remote);
    }

    static boolean hasOnlyBoundAllowBank(
            List<String> allow, int allowCap, List<DuctDirectionalEndpoint> remote) {
        return DuctStallAllowBank.hasOnlyBoundAllowBank(allow, allowCap, remote);
    }

    static boolean tryExtractEntryFirst(
            DuctBlockEntity self,
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction face,
            DuctFaceNode node,
            DuctFaceLanes faceLanes,
            IItemHandler sourceHandler,
            int rrFrozen,
            RoutingMode rm,
            boolean roundRobinRouting,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.EXTRACTOR;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(self, face);
        int allowCap = DuctModuleEffects.effectiveItemAllowBank(spec, faceLanes.nodeMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasNonEmptyAllowLines(allowFull, allowCap)) {
            return false;
        }
        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> allowRemote = subListEndpoint(node.bankAllowRemoteNodes(bank), allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);

        int unitIdx = 0;
        for (DuctFilterUnitLogic.FilterUnit unit : DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
            DuctFilterUnitLogic.UnitBinding binding =
                    DuctFilterUnitLogic.unitBinding(unit, allowRemote, allowIgnore, allowAnyFace);
            if (binding == null) {
                unitIdx++;
                continue;
            }
            ItemStack probe =
                    findProbeForUnit(self, level, face, node, bank, unit, sourceHandler);
            if (probe.isEmpty()) {
                unitIdx++;
                continue;
            }
            if (binding.endpoint() != null) {
                long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(self, face, spec);
                List<DuctFilterDestinationResolver.ResolvedFace> faces =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                self.getBlockPos(),
                                face,
                                DuctNetworkType.ITEM,
                                DuctTransportKind.ITEM,
                                edgeTicks,
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
                    if (!self.passesItemFilters(face, probe, level, bank, destCounterparty)) {
                        continue;
                    }
                    if (tryScheduleExtract(
                            self,
                            level,
                            spec,
                            face,
                            node,
                            sourceHandler,
                            probe,
                            rf.ductPos(),
                            rf.face(),
                            rrFrozen,
                            unitIdx,
                            roundRobinRouting,
                            destCounterparty,
                            unit)) {
                        return true;
                    }
                }
            } else {
                List<DuctTargetSelector.ExtractionCandidate> candidates =
                        DuctTargetSelector.listExtractionDeliveryCandidatesWithoutProbe(
                                level,
                                self.getBlockPos(),
                                face,
                                rm,
                                rrFrozen,
                                node.channelLetter,
                                true,
                                allowSelfFeed,
                                forbidSelfDestFace);
                int highestPendingFullPriority = Integer.MIN_VALUE;
                for (int candIdx = 0; candIdx < candidates.size() && candIdx < ROUTE_RETRY_CAP; candIdx++) {
                    DuctTargetSelector.ExtractionCandidate cand = candidates.get(candIdx);
                    if (cand.priority() < highestPendingFullPriority) {
                        break;
                    }
                    BlockPos dest = cand.ductPos();
                    Direction destFace = cand.face();
                    if (!(level.getBlockEntity(dest) instanceof DuctBlockEntity destBe)) {
                        continue;
                    }
                    DuctDirectionalEndpoint destCounterparty =
                            DuctDirectionalEndpoint.connectionAtDuctFace(level, dest, destFace);
                    if (!self.passesItemFilters(face, probe, level, bank, destCounterparty)) {
                        continue;
                    }
                    NodeMode destMode = destBe.getFaceLanes(destFace).nodeMode;
                    if (!DuctCapHelper.canInsertIntoFace(level, dest, destFace, probe)) {
                        continue;
                    }
                    if (destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING) {
                        if (!destBe.passesItemFilters(
                                destFace,
                                probe,
                                level,
                                DuctFaceNode.FilterBank.FILTER,
                                DuctDirectionalEndpoint.connectionAtDuctFace(level, self.getBlockPos(), face))) {
                            continue;
                        }
                    }
                    if (tryScheduleExtract(
                            self,
                            level,
                            spec,
                            face,
                            node,
                            sourceHandler,
                            probe,
                            dest,
                            destFace,
                            rrFrozen,
                            candIdx,
                            roundRobinRouting,
                            destCounterparty,
                            null)) {
                        return true;
                    }
                    if (!DuctIncomingIndex.snapshotTowardSameNeighbor(level, dest, destFace).isEmpty()) {
                        if (cand.priority() > highestPendingFullPriority) {
                            highestPendingFullPriority = cand.priority();
                        }
                        if (!roundRobinRouting && rm != RoutingMode.RANDOM) {
                            return false;
                        }
                    }
                }
            }
            unitIdx++;
        }
        return false;
    }

    static boolean tryRetrieveEntryFirst(
            DuctBlockEntity self,
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction retrieverFace,
            DuctFaceNode node,
            DuctFaceLanes retrieverLanes,
            int rrFrozen,
            RoutingMode rm,
            boolean roundRobinRetriever) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.RETRIEVER;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(self, retrieverFace);
        int allowCap = DuctModuleEffects.effectiveItemAllowBank(spec, retrieverLanes.nodeMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasNonEmptyAllowLines(allowFull, allowCap)) {
            return false;
        }
        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> allowRemote = subListEndpoint(node.bankAllowRemoteNodes(bank), allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);

        int unitIdx = 0;
        for (DuctFilterUnitLogic.FilterUnit unit : DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
            DuctFilterUnitLogic.UnitBinding binding =
                    DuctFilterUnitLogic.unitBinding(unit, allowRemote, allowIgnore, allowAnyFace);
            if (binding == null) {
                unitIdx++;
                continue;
            }
            if (binding.endpoint() != null) {
                long edgeTicks =
                        DuctModuleEffects.effectiveItemEdgeTravelTicks(self, retrieverFace, spec);
                List<DuctFilterDestinationResolver.ResolvedFace> donors =
                        DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                level,
                                self.getBlockPos(),
                                retrieverFace,
                                DuctNetworkType.ITEM,
                                DuctTransportKind.ITEM,
                                edgeTicks,
                                node.channelLetter,
                                binding.ignoreChannel(),
                                binding.endpoint(),
                                binding.anyFace(),
                                FilterRemoteNodeRole.RETRIEVE_PULL,
                                true,
                                retrieverFace);
                for (DuctFilterDestinationResolver.ResolvedFace donorFace : donors) {
                    if (!(level.getBlockEntity(donorFace.ductPos()) instanceof DuctBlockEntity donorBe)) {
                        continue;
                    }
                    if (tryPullFromDonor(
                            self,
                            level,
                            spec,
                            retrieverFace,
                            node,
                            donorFace.ductPos(),
                            donorFace.face(),
                            donorBe,
                            unit,
                            rrFrozen,
                            unitIdx,
                            roundRobinRetriever)) {
                        return true;
                    }
                }
            } else {
                List<DuctTargetSelector.DonorCandidate> donors =
                        DuctTargetSelector.listRetrievingDonorCandidates(
                                level,
                                self.getBlockPos(),
                                retrieverFace,
                                rm,
                                rrFrozen,
                                node.channelLetter,
                                true,
                                retrieverFace);
                for (int donorIdx = 0; donorIdx < donors.size() && donorIdx < ROUTE_RETRY_CAP; donorIdx++) {
                    DuctTargetSelector.DonorCandidate donorCand = donors.get(donorIdx);
                    BlockPos donor = donorCand.ductPos();
                    Direction donorFace = donorCand.face();
                    if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                        continue;
                    }
                    if (tryPullFromDonor(
                            self,
                            level,
                            spec,
                            retrieverFace,
                            node,
                            donor,
                            donorFace,
                            donorBe,
                            unit,
                            rrFrozen,
                            donorIdx,
                            roundRobinRetriever)) {
                        return true;
                    }
                }
            }
            unitIdx++;
        }
        return false;
    }

    private static ItemStack findProbeForUnit(
            DuctBlockEntity self,
            ServerLevel level,
            Direction face,
            DuctFaceNode node,
            DuctFaceNode.FilterBank bank,
            DuctFilterUnitLogic.FilterUnit unit,
            IItemHandler sourceHandler) {
        for (int slot = 0; slot < sourceHandler.getSlots(); slot++) {
            if (!DuctHandlerSlotSemantics.canExtractFromSlot(sourceHandler, slot)) {
                continue;
            }
            ItemStack p = sourceHandler.extractItem(slot, 1, true);
            if (p.isEmpty()) {
                continue;
            }
            if (!DuctFilterLogic.itemMatchesAllowUnit(bank, node, unit, p, level)) {
                continue;
            }
            if (self.capExtractableForSourceKeepRouting(level, self.getBlockPos(), face, self, p, 1) <= 0) {
                continue;
            }
            return p;
        }
        return ItemStack.EMPTY;
    }

    private static boolean tryScheduleExtract(
            DuctBlockEntity self,
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction face,
            DuctFaceNode node,
            IItemHandler sourceHandler,
            ItemStack probe,
            BlockPos dest,
            Direction destFace,
            int rrFrozen,
            int rrOffset,
            boolean roundRobinRouting,
            @Nullable DuctDirectionalEndpoint destCounterparty,
            @Nullable DuctFilterUnitLogic.FilterUnit boundUnit) {
        if (!(level.getBlockEntity(dest) instanceof DuctBlockEntity destBe)) {
            return false;
        }
        if (DuctInsertProbeCache.isRejected(level, dest, destFace, probe)) {
            return false;
        }
        if (!DuctCapHelper.canInsertIntoFace(level, dest, destFace, probe)) {
            return false;
        }
        NodeMode destMode = destBe.getFaceLanes(destFace).nodeMode;
        if (destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING) {
            if (!destBe.passesItemFilters(
                    destFace,
                    probe,
                    level,
                    DuctFaceNode.FilterBank.FILTER,
                    DuctDirectionalEndpoint.connectionAtDuctFace(level, self.getBlockPos(), face))) {
                return false;
            }
        }
        List<BlockPos> path;
        if (dest.equals(self.getBlockPos())) {
            path = List.of(self.getBlockPos());
        } else {
            Optional<List<BlockPos>> p =
                    DuctPathfinder.shortestItemPathForScheduling(level, self.getBlockPos(), dest, spec);
            if (p.isEmpty()) {
                return false;
            }
            path = p.get();
        }
        int tubeBatch = self.tubeOperationBatchSizeRouting(spec, face);
        long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(self, face, spec);
        long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
        int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
        int pendingSum = self.pendingOutboundItemCountFromSource(self.getBlockPos(), face, probe);
        int countCap = Math.min(Integer.MAX_VALUE, Math.max(tubeBatch, pendingSum) + tubeBatch);
        int availTotal =
                DuctCapHelper.countExtractableMatchingOnFace(
                        level, self.getBlockPos(), face, probe, countCap);
        int remainingInStorage = availTotal - pendingSum;
        if (remainingInStorage <= 0) {
            return false;
        }
        int plannedCount = Math.min(tubeBatch, remainingInStorage);
        plannedCount =
                Math.min(
                        plannedCount,
                        self.capExtractableForSourceKeepRouting(
                                level, self.getBlockPos(), face, self, probe, plannedCount));
        if (plannedCount <= 0) {
            return false;
        }
        if (boundUnit != null) {
            plannedCount =
                    Math.min(
                            plannedCount,
                            capInsertableForExtractorUnitLimit(
                                    level, dest, destFace, node, boundUnit, probe, plannedCount));
            if (plannedCount <= 0) {
                return false;
            }
        }
        ItemStack planned = probe.copy();
        planned.setCount(plannedCount);
        int destCap = self.maxSchedulableTowardFaceRouting(level, dest, destBe, destFace, planned, plannedCount);
        if (destCap <= 0) {
            return false;
        }
        if (destCap < plannedCount) {
            plannedCount = destCap;
            planned.setCount(plannedCount);
        }
        if (self.wouldExceedStallKindCap(planned)) {
            DuctTransitDebugLog.extractionGatedByUnsatisfiableTasks(
                    level, self.getBlockPos(), self.distinctUnsatisfiableBlockedKindsForDebug());
            return false;
        }
        ItemStack extracted =
                DuctCapHelper.extractMatchingUpToOnFace(
                        level, self.getBlockPos(), face, planned, plannedCount);
        if (extracted.isEmpty()) {
            return false;
        }
        return self.commitItemOutboundShipment(
                level,
                extracted,
                dest,
                destFace,
                travelTicks,
                face,
                node.channelLetter,
                path,
                edgeTicks,
                rrFrozen,
                rrOffset,
                roundRobinRouting);
    }

    /**
     * Caps planned extract count by EXTRACTOR Insert Limit on the bound destination inventory
     * (0 = unlimited). Uses only the allow unit's lines.
     */
    private static int capInsertableForExtractorUnitLimit(
            ServerLevel level,
            BlockPos destDuct,
            Direction destFace,
            DuctFaceNode sourceNode,
            DuctFilterUnitLogic.FilterUnit unit,
            ItemStack template,
            int maxFromCapacity) {
        if (maxFromCapacity <= 0 || template.isEmpty() || unit == null) {
            return 0;
        }
        IItemHandler raw = DuctCapHelper.getHandlerOnFace(level, destDuct, destFace);
        if (raw == null) {
            return maxFromCapacity;
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
        if (!DuctAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(uAllows, uCaps)) {
            return maxFromCapacity;
        }
        List<ItemStack> prior = DuctIncomingIndex.snapshotTowardSameNeighbor(level, destDuct, destFace);
        int maxAdd =
                DuctAllowLimitLogic.maxAdditionalInsertAcrossAllowLines(
                        raw, uAllows, uCaps, uConcat, template, prior, level.registryAccess());
        if (maxAdd == Integer.MAX_VALUE) {
            return maxFromCapacity;
        }
        return Math.min(maxFromCapacity, Math.max(0, maxAdd));
    }

    private static boolean tryPullFromDonor(
            DuctBlockEntity self,
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction retrieverFace,
            DuctFaceNode node,
            BlockPos donor,
            Direction donorFace,
            DuctBlockEntity donorBe,
            DuctFilterUnitLogic.FilterUnit unit,
            int rrFrozen,
            int rrOffset,
            boolean roundRobinRetriever) {
        if (DuctBlockEntity.isFaceStalledRouting(donorBe.getFaceLanes(donorFace))) {
            return false;
        }
        IItemHandler donorHandler = DuctCapHelper.getHandlerOnFace(level, donor, donorFace);
        if (donorHandler == null) {
            return false;
        }
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.RETRIEVER;
        int slotCount = donorHandler.getSlots();
        int slotStart = slotCount > 0 ? Math.floorMod(node.retrieverPullSlotCursor, slotCount) : 0;
        for (int si = 0; si < slotCount; si++) {
            int slot = (slotStart + si) % slotCount;
            if (!DuctHandlerSlotSemantics.canExtractFromSlot(donorHandler, slot)) {
                continue;
            }
            ItemStack probe = donorHandler.extractItem(slot, 1, true);
            if (probe.isEmpty()) {
                continue;
            }
            if (!DuctFilterLogic.itemMatchesAllowUnit(bank, node, unit, probe, level)) {
                continue;
            }
            NodeMode donorMode = donorBe.getFaceLanes(donorFace).nodeMode;
            if (donorMode == NodeMode.FILTERING_INSERTION
                    && !donorBe.passesItemFilters(
                            donorFace,
                            probe,
                            level,
                            DuctFaceNode.FilterBank.FILTER,
                            DuctDirectionalEndpoint.connectionAtDuctFace(level, self.getBlockPos(), retrieverFace))) {
                continue;
            }
            if (!self.passesItemFilters(
                    retrieverFace,
                    probe,
                    level,
                    bank,
                    DuctDirectionalEndpoint.connectionAtDuctFace(level, donor, donorFace))) {
                continue;
            }
            if (self.capExtractableForSourceKeepRouting(level, donor, donorFace, donorBe, probe, 1) <= 0) {
                continue;
            }
            List<BlockPos> path;
            if (donor.equals(self.getBlockPos())) {
                path = List.of(self.getBlockPos());
            } else {
                Optional<List<BlockPos>> p =
                        DuctPathfinder.shortestItemPathForScheduling(level, donor, self.getBlockPos(), spec);
                if (p.isEmpty()) {
                    return false;
                }
                path = p.get();
            }
            return self.commitRetrieverPullShipment(
                    level,
                    spec,
                    retrieverFace,
                    node,
                    donor,
                    donorFace,
                    donorBe,
                    donorHandler,
                    slot,
                    probe,
                    path,
                    rrFrozen,
                    rrOffset,
                    roundRobinRetriever);
        }
        return false;
    }

    /** Re-sends stalled stacks using entry-first filter destination routing. Returns true if anything was scheduled. */
    static boolean tryDrainStallEntryFirst(
            DuctBlockEntity self,
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction face,
            DuctFaceNode node,
            DuctFaceLanes lanes) {
        DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.EXTRACTOR;
        FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(self, face);
        int allowCap = DuctModuleEffects.effectiveItemAllowBank(spec, lanes.nodeMode, fb);
        List<String> allowFull = node.bankAllowFilters(bank);
        if (!hasStallRoutableAllowBank(allowFull, allowCap, node.bankAllowRemoteNodes(bank))) {
            return false;
        }
        RoutingMode rm =
                lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION
                        ? node.routingModeRetriever
                        : lanes.nodeMode.isHybrid()
                                ? node.routingModeExtractor
                                : node.routingMode;
        final int rrFrozen = node.roundRobinCursor;
        boolean allowSelfFeed = lanes.nodeMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        Direction forbidSelfDestFace = allowSelfFeed ? null : face;

        int allowSize = Math.min(allowFull.size(), allowCap);
        List<String> allow = allowFull.subList(0, allowSize);
        List<Integer> allowConcat = subList(node.bankAllowConcatChannels(bank), allowSize);
        List<DuctDirectionalEndpoint> allowRemote = subListEndpoint(node.bankAllowRemoteNodes(bank), allowSize);
        List<Boolean> allowIgnore = subListBool(node.bankAllowRemoteIgnoreChannel(bank), allowSize);
        List<Boolean> allowAnyFace = subListBool(node.bankAllowRemoteAnyFace(bank), allowSize);

        for (int slot = 0; slot < lanes.stalledBuffer.getSlots(); slot++) {
            ItemStack st = lanes.stalledBuffer.getStackInSlot(slot);
            if (st.isEmpty()) {
                continue;
            }
            int unitIdx = 0;
            for (DuctFilterUnitLogic.FilterUnit unit : DuctFilterUnitLogic.enumerateUnits(allow, allowConcat)) {
                DuctFilterUnitLogic.UnitBinding binding =
                        DuctFilterUnitLogic.unitBinding(unit, allowRemote, allowIgnore, allowAnyFace);
                if (binding == null) {
                    unitIdx++;
                    continue;
                }
                String allowLine =
                        unit.headIndex() >= 0 && unit.headIndex() < allow.size()
                                ? allow.get(unit.headIndex())
                                : "";
                boolean boundOnlyUnit =
                        binding.endpoint() != null
                                && (allowLine == null || allowLine.trim().isEmpty());
                if (!boundOnlyUnit
                        && !DuctFilterLogic.itemMatchesAllowUnit(bank, node, unit, st, level)) {
                    unitIdx++;
                    continue;
                }
                ItemStack probe = st.copy();
                probe.setCount(1);
                if (binding.endpoint() != null) {
                    long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(self, face, spec);
                    List<DuctFilterDestinationResolver.ResolvedFace> faces =
                            DuctFilterDestinationResolver.findFacesForInventoryEndpoint(
                                    level,
                                    self.getBlockPos(),
                                    face,
                                    DuctNetworkType.ITEM,
                                    DuctTransportKind.ITEM,
                                    edgeTicks,
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
                        if (!self.passesItemFilters(face, probe, level, bank, destCounterparty)) {
                            continue;
                        }
                        if (tryScheduleStallResend(
                                self,
                                level,
                                spec,
                                face,
                                node,
                                lanes,
                                slot,
                                st,
                                rf.ductPos(),
                                rf.face(),
                                rm,
                                rrFrozen,
                                unitIdx)) {
                            return true;
                        }
                    }
                } else {
                    List<DuctTargetSelector.ExtractionCandidate> candidates =
                            DuctTargetSelector.listInboundDeliveryCandidatesWithoutProbe(
                                    level,
                                    self.getBlockPos(),
                                    face,
                                    rm,
                                    rrFrozen,
                                    node.channelLetter,
                                    true,
                                    allowSelfFeed,
                                    forbidSelfDestFace);
                    for (int candIdx = 0; candIdx < candidates.size() && candIdx < ROUTE_RETRY_CAP; candIdx++) {
                        DuctTargetSelector.ExtractionCandidate cand = candidates.get(candIdx);
                        DuctDirectionalEndpoint destCounterparty =
                                DuctDirectionalEndpoint.connectionAtDuctFace(
                                        level, cand.ductPos(), cand.face());
                        if (!self.passesItemFilters(face, probe, level, bank, destCounterparty)) {
                            continue;
                        }
                        if (tryScheduleStallResend(
                                self,
                                level,
                                spec,
                                face,
                                node,
                                lanes,
                                slot,
                                st,
                                cand.ductPos(),
                                cand.face(),
                                rm,
                                rrFrozen,
                                candIdx)) {
                            return true;
                        }
                    }
                }
                unitIdx++;
            }
        }
        return false;
    }

    private static boolean tryScheduleStallResend(
            DuctBlockEntity self,
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction face,
            DuctFaceNode node,
            DuctFaceLanes lanes,
            int slot,
            ItemStack st,
            BlockPos dest,
            Direction destFace,
            RoutingMode rm,
            int rrFrozen,
            int rrOffset) {
        return self.commitStallResendFromSlot(
                level, spec, face, node, lanes, slot, st, dest, destFace, rm, rrFrozen, rrOffset);
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
}
