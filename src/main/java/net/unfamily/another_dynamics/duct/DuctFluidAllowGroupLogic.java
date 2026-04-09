package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctFluidIncomingIndex;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Fluid allow-line grouping (AND semantics), mirroring {@link DuctAllowGroupLogic} for items.
 */
public final class DuctFluidAllowGroupLogic {
    private DuctFluidAllowGroupLogic() {}

    public static int fluidAllowLineGroupId(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, FluidStack probe, HolderLookup.Provider registries) {
        if (probe.isEmpty() || (bank != DuctFaceNode.FilterBank.FILTER && bank != DuctFaceNode.FilterBank.RETRIEVER)) {
            return 0;
        }
        List<String> allows = node.bankAllowFilters(bank);
        List<Integer> groups = node.bankAllowGroupIds(bank);
        int idx = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(allows, probe, registries);
        if (idx < 0 || idx >= groups.size()) {
            return 0;
        }
        return FilterGroupIds.normalize(groups.get(idx));
    }

    public static boolean filterDestGroupAllowsExtractionDelivery(
            ServerLevel level,
            DuctBlockEntity destBe,
            net.minecraft.core.Direction destFace,
            FluidStack movingProbe,
            IFluidHandler destHandlerOrSim,
            HolderLookup.Provider registries) {
        if (movingProbe.isEmpty() || destHandlerOrSim == null) {
            return true;
        }
        DuctFaceNode destNode = destBe.getFluidFaceNode(destFace);
        List<String> allows = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
        List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
        List<Integer> groups = destNode.bankAllowGroupIds(DuctFaceNode.FilterBank.FILTER);
        int idx = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(allows, movingProbe, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(groups, idx);
        if (g <= 0) {
            return true;
        }
        return fluidGroupInsertLinesSatisfiableMb(
                        allows, caps, groups, g, idx, movingProbe, destHandlerOrSim, destBe.getBlockPos(), level, registries)
                && DuctCrossTransportAllowGroupLogic.supplementFilterDestSiblingKinds(
                        level,
                        destBe,
                        destFace,
                        g,
                        DuctTransportKind.FLUID,
                        idx,
                        ItemStack.EMPTY,
                        movingProbe,
                        MekanismChemicalCompat.emptyStack(),
                        registries);
    }

    public static boolean retrieverDestGroupAllowsIncomingMb(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            net.minecraft.core.Direction retrieverFace,
            FluidStack template,
            IFluidHandler destHandlerOrSim,
            HolderLookup.Provider registries) {
        if (template.isEmpty()) {
            return true;
        }
        DuctFaceNode retrNode = retrieverBe.getFluidFaceNode(retrieverFace);
        List<String> retrAllows = retrNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = retrNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrGroups = retrNode.bankAllowGroupIds(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(retrAllows, template, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(retrGroups, idx);
        if (g <= 0) {
            return true;
        }
        return fluidGroupInsertLinesSatisfiableMb(
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
                        DuctTransportKind.FLUID,
                        idx,
                        ItemStack.EMPTY,
                        template,
                        MekanismChemicalCompat.emptyStack(),
                        registries);
    }

    public static boolean retrieverPullGroupSatisfiable(
            ServerLevel level,
            DuctBlockEntity donorBe,
            net.minecraft.core.Direction donorFace,
            DuctBlockEntity retrieverBe,
            net.minecraft.core.Direction retrieverFace,
            FluidStack movingProbe,
            IFluidHandler donorCap,
            IFluidHandler retrieverDestCap,
            HolderLookup.Provider registries) {
        if (movingProbe.isEmpty() || donorCap == null || retrieverDestCap == null) {
            return true;
        }
        DuctFaceNode retrNode = retrieverBe.getFluidFaceNode(retrieverFace);
        List<String> retrAllows = retrNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = retrNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrGroups = retrNode.bankAllowGroupIds(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(retrAllows, movingProbe, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(retrGroups, idx);
        if (g <= 0) {
            return true;
        }
        if (!fluidGroupInsertLinesSatisfiableMb(
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
        DuctFaceNode donorNode = donorBe.getFluidFaceNode(donorFace);
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
            FluidStack template =
                    j == idx && idx >= 0
                            ? movingProbe.copy()
                            : DuctFilterLineProbesRepresentative.fluidStackForAllowLine(line, registries);
            if (template.isEmpty()) {
                continue;
            }
            if (!DuctFluidFilterLogic.passesFluidFiltersForBank(
                    donorNode, DuctFaceNode.FilterBank.FILTER, template, level)) {
                return false;
            }
            int donorLine = DuctFluidAllowLimitLogic.firstMatchingAllowLineIndex(donorAllows, template, registries);
            if (donorLine < 0) {
                return false;
            }
            int ex =
                    DuctFluidAllowLimitLogic.maxExtractRespectingKeepMbAtIndex(
                            donorCap,
                            donorAllows,
                            donorKeeps,
                            template,
                            donorLine,
                            registries);
            if (ex < 1) {
                return false;
            }
        }
        return true;
    }

    static boolean fluidGroupInsertLinesSatisfiableMb(
            List<String> allowLines,
            List<Integer> caps,
            List<Integer> groupIds,
            int groupId,
            int winningIndex,
            FluidStack winningProbe,
            IFluidHandler handlerAfterPending,
            net.minecraft.core.BlockPos destDuctForPending,
            ServerLevel level,
            HolderLookup.Provider registries) {
        List<FluidStack> pendingStacks = DuctFluidIncomingIndex.snapshot(level, destDuctForPending);
        for (int j = 0; j < allowLines.size(); j++) {
            if (lineGroupAt(groupIds, j) != groupId) {
                continue;
            }
            String line = allowLines.get(j);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            FluidStack template =
                    j == winningIndex && winningIndex >= 0
                            ? winningProbe.copy()
                            : DuctFilterLineProbesRepresentative.fluidStackForAllowLine(line, registries);
            if (template.isEmpty()) {
                continue;
            }
            if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
                return false;
            }
            int lim = j < caps.size() ? caps.get(j) : 0;
            if (lim <= 0) {
                continue;
            }
            int current = DuctFluidAllowLimitLogic.countMatchingInHandlerMb(handlerAfterPending, line, registries);
            int pending = DuctFluidAllowLimitLogic.countMatchingInStacksMb(pendingStacks, line, registries);
            if (lim - current - pending < 1) {
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
