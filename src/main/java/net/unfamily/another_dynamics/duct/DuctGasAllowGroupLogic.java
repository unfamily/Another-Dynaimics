package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.unfamily.another_dynamics.duct.logistics.DuctGasIncomingIndex;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Gas allow-line grouping (AND semantics), mirroring {@link DuctFluidAllowGroupLogic}.
 */
public final class DuctGasAllowGroupLogic {
    private DuctGasAllowGroupLogic() {}

    public static int gasAllowLineGroupId(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, Object probe, HolderLookup.Provider registries) {
        if (!MekanismChemicalCompat.isLoaded()
                || probe == null
                || MekanismChemicalCompat.isEmptyStack(probe)
                || (bank != DuctFaceNode.FilterBank.FILTER && bank != DuctFaceNode.FilterBank.RETRIEVER)) {
            return 0;
        }
        List<String> allows = node.bankAllowFilters(bank);
        List<Integer> groups = node.bankAllowGroupIds(bank);
        int idx = DuctGasAllowLimitLogic.firstMatchingAllowLineIndex(allows, probe, registries);
        if (idx < 0 || idx >= groups.size()) {
            return 0;
        }
        return FilterGroupIds.normalize(groups.get(idx));
    }

    public static boolean filterDestGroupAllowsExtractionDelivery(
            ServerLevel level,
            DuctBlockEntity destBe,
            Direction destFace,
            Object movingProbe,
            Object destHandlerOrSim,
            HolderLookup.Provider registries) {
        if (!MekanismChemicalCompat.isLoaded()
                || movingProbe == null
                || MekanismChemicalCompat.isEmptyStack(movingProbe)
                || destHandlerOrSim == null) {
            return true;
        }
        DuctFaceNode destNode = destBe.getGasFaceNode(destFace);
        List<String> allows = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
        List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
        List<Integer> groups = destNode.bankAllowGroupIds(DuctFaceNode.FilterBank.FILTER);
        int idx = DuctGasAllowLimitLogic.firstMatchingAllowLineIndex(allows, movingProbe, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(groups, idx);
        if (g <= 0) {
            return true;
        }
        return gasGroupInsertLinesSatisfiable(
                        allows,
                        caps,
                        groups,
                        g,
                        idx,
                        movingProbe,
                        destHandlerOrSim,
                        destBe.getBlockPos(),
                        level,
                        registries)
                && DuctCrossTransportAllowGroupLogic.supplementFilterDestSiblingKinds(
                        level,
                        destBe,
                        destFace,
                        g,
                        DuctTransportKind.GAS,
                        idx,
                        ItemStack.EMPTY,
                        FluidStack.EMPTY,
                        movingProbe,
                        registries);
    }

    public static boolean retrieverDestGroupAllowsIncoming(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            Object template,
            Object destHandlerOrSim,
            HolderLookup.Provider registries) {
        if (!MekanismChemicalCompat.isLoaded() || template == null || MekanismChemicalCompat.isEmptyStack(template)) {
            return true;
        }
        DuctFaceNode retrNode = retrieverBe.getGasFaceNode(retrieverFace);
        List<String> retrAllows = retrNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = retrNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrGroups = retrNode.bankAllowGroupIds(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctGasAllowLimitLogic.firstMatchingAllowLineIndex(retrAllows, template, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(retrGroups, idx);
        if (g <= 0) {
            return true;
        }
        return gasGroupInsertLinesSatisfiable(
                        retrAllows,
                        retrCaps,
                        retrGroups,
                        g,
                        idx,
                        template,
                        destHandlerOrSim,
                        retrieverBe.getBlockPos(),
                        level,
                        registries)
                && DuctCrossTransportAllowGroupLogic.supplementRetrieverDestSiblingKinds(
                        level,
                        retrieverBe,
                        retrieverFace,
                        g,
                        DuctTransportKind.GAS,
                        idx,
                        ItemStack.EMPTY,
                        FluidStack.EMPTY,
                        template,
                        registries);
    }

    public static boolean retrieverPullGroupSatisfiable(
            ServerLevel level,
            DuctBlockEntity donorBe,
            Direction donorFace,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            Object movingProbe,
            Object donorCap,
            Object retrieverDestCap,
            HolderLookup.Provider registries) {
        if (!MekanismChemicalCompat.isLoaded()
                || movingProbe == null
                || MekanismChemicalCompat.isEmptyStack(movingProbe)
                || donorCap == null
                || retrieverDestCap == null) {
            return true;
        }
        DuctFaceNode retrNode = retrieverBe.getGasFaceNode(retrieverFace);
        List<String> retrAllows = retrNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = retrNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrGroups = retrNode.bankAllowGroupIds(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctGasAllowLimitLogic.firstMatchingAllowLineIndex(retrAllows, movingProbe, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(retrGroups, idx);
        if (g <= 0) {
            return true;
        }
        if (!gasGroupInsertLinesSatisfiable(
                retrAllows,
                retrCaps,
                retrGroups,
                g,
                idx,
                movingProbe,
                retrieverDestCap,
                retrieverBe.getBlockPos(),
                level,
                registries)) {
            return false;
        }
        DuctFaceNode donorNode = donorBe.getGasFaceNode(donorFace);
        List<String> donorAllows = donorNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
        List<Integer> donorKeeps = donorNode.filterBankKeepCaps();
        for (int j = 0; j < retrAllows.size(); j++) {
            if (lineGroupAt(retrGroups, j) != g) {
                continue;
            }
            String line = retrAllows.get(j);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            Object template =
                    j == idx && idx >= 0
                            ? movingProbe
                            : DuctFilterLineProbesRepresentative.chemicalStackForAllowLine(line, registries);
            if (template == null || MekanismChemicalCompat.isEmptyStack(template)) {
                continue;
            }
            if (!DuctGasFilterLogic.passesGasFiltersForBank(
                    donorNode, DuctFaceNode.FilterBank.FILTER, template, level)) {
                return false;
            }
            int donorLine = DuctGasAllowLimitLogic.firstMatchingAllowLineIndex(donorAllows, template, registries);
            if (donorLine < 0) {
                return false;
            }
            long ex =
                    DuctGasAllowLimitLogic.maxExtractRespectingKeepAtIndex(
                            donorCap, donorAllows, donorKeeps, template, donorLine, registries);
            if (ex < 1) {
                return false;
            }
        }
        return true;
    }

    static boolean gasGroupInsertLinesSatisfiable(
            List<String> allowLines,
            List<Integer> caps,
            List<Integer> groupIds,
            int groupId,
            int winningIndex,
            Object winningProbe,
            Object handlerAfterPending,
            BlockPos destDuctForPending,
            ServerLevel level,
            HolderLookup.Provider registries) {
        List<Object> pendingStacks = DuctGasIncomingIndex.snapshot(level, destDuctForPending);
        for (int j = 0; j < allowLines.size(); j++) {
            if (lineGroupAt(groupIds, j) != groupId) {
                continue;
            }
            String line = allowLines.get(j);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            Object template =
                    j == winningIndex && winningIndex >= 0
                            ? MekanismChemicalCompat.copyWithAmount(
                                    winningProbe, MekanismChemicalCompat.getAmount(winningProbe))
                            : DuctFilterLineProbesRepresentative.chemicalStackForAllowLine(line, registries);
            if (template == null || MekanismChemicalCompat.isEmptyStack(template)) {
                continue;
            }
            if (!DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
                return false;
            }
            int lim = j < caps.size() ? caps.get(j) : 0;
            if (lim <= 0) {
                continue;
            }
            long current = DuctGasAllowLimitLogic.countMatchingInHandler(handlerAfterPending, line, registries);
            long pending = DuctGasAllowLimitLogic.countMatchingInStacks(pendingStacks, line, registries);
            if ((long) lim - current - pending < 1) {
                return false;
            }
        }
        return true;
    }

    private static int lineGroupAt(List<Integer> groupIds, int index) {
        if (groupIds == null || index < 0 || index >= groupIds.size()) {
            return 0;
        }
        return FilterGroupIds.normalize(groupIds.get(index));
    }
}
