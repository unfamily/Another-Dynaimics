package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Advanced allow grouping: a group id {@code > 0} ties multiple allow rows. A row is eligible only if every row in the
 * same group can satisfy that step's cap semantics (limits with pending transit for inserts; keep caps for donor pulls)
 * in the same evaluation pass.
 */
public final class DuctAllowGroupLogic {
    private DuctAllowGroupLogic() {}

    /** Group id for the first allow row matching {@code probe} on {@code bank} ({@link FilterBank#FILTER} or {@link FilterBank#RETRIEVER} only). */
    public static int itemAllowLineGroupId(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, ItemStack probe, HolderLookup.Provider registries) {
        if (probe.isEmpty() || (bank != DuctFaceNode.FilterBank.FILTER && bank != DuctFaceNode.FilterBank.RETRIEVER)) {
            return 0;
        }
        List<String> allows = node.bankAllowFilters(bank);
        List<Integer> groups = node.bankAllowGroupIds(bank);
        int idx = DuctAllowLimitLogic.firstMatchingAllowLineIndex(allows, probe, registries);
        if (idx < 0 || idx >= groups.size()) {
            return 0;
        }
        return FilterGroupIds.normalize(groups.get(idx));
    }

    public static boolean itemFilterDestGroupAllowsExtractionDelivery(
            ServerLevel level,
            DuctBlockEntity destBe,
            net.minecraft.core.Direction destFace,
            ItemStack movingProbe,
            HolderLookup.Provider registries) {
        if (movingProbe.isEmpty()) {
            return true;
        }
        DuctFaceNode destNode = destBe.getFaceNode(destFace);
        List<String> allows = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
        List<Integer> caps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
        List<Integer> groups = destNode.bankAllowGroupIds(DuctFaceNode.FilterBank.FILTER);
        int idx = DuctAllowLimitLogic.firstMatchingAllowLineIndex(allows, movingProbe, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(groups, idx);
        if (g <= 0) {
            return true;
        }
        IItemHandler raw = DuctCapHelper.getHandlerOnFace(level, destBe.getBlockPos(), destFace);
        if (raw == null) {
            return false;
        }
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, destBe.getBlockPos());
        ItemStackHandler simulated = DuctCapHelper.simulateInventoryAfterPending(raw, prior);
        return itemGroupInsertLinesSatisfiable(allows, caps, groups, g, idx, movingProbe, simulated, registries);
    }

    public static boolean itemRetrieverDestGroupAllowsIncoming(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            net.minecraft.core.Direction retrieverFace,
            ItemStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty()) {
            return true;
        }
        DuctFaceNode retrNode = retrieverBe.getFaceNode(retrieverFace);
        List<String> retrAllows = retrNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = retrNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrGroups = retrNode.bankAllowGroupIds(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctAllowLimitLogic.firstMatchingAllowLineIndex(retrAllows, template, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(retrGroups, idx);
        if (g <= 0) {
            return true;
        }
        IItemHandler retrHandler = DuctCapHelper.getHandlerOnFace(level, retrieverBe.getBlockPos(), retrieverFace);
        if (retrHandler == null) {
            return false;
        }
        List<ItemStack> priorDest = DuctIncomingIndex.snapshot(level, retrieverBe.getBlockPos());
        ItemStackHandler simulatedDest = DuctCapHelper.simulateInventoryAfterPending(retrHandler, priorDest);
        return itemGroupInsertLinesSatisfiable(
                        retrAllows, retrCaps, retrGroups, g, idx, template, simulatedDest, registries)
                && DuctCrossTransportAllowGroupLogic.supplementRetrieverDestSiblingKinds(
                        level,
                        retrieverBe,
                        retrieverFace,
                        g,
                        DuctTransportKind.ITEM,
                        idx,
                        template,
                        FluidStack.EMPTY,
                        MekanismChemicalCompat.emptyStack(),
                        registries);
    }

    public static boolean itemRetrieverGroupAllowsPull(
            ServerLevel level,
            DuctBlockEntity donorBe,
            net.minecraft.core.Direction donorFace,
            DuctBlockEntity retrieverBe,
            net.minecraft.core.Direction retrieverFace,
            ItemStack movingProbe,
            HolderLookup.Provider registries) {
        if (movingProbe.isEmpty()) {
            return true;
        }
        DuctFaceNode retrNode = retrieverBe.getFaceNode(retrieverFace);
        List<String> retrAllows = retrNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = retrNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrGroups = retrNode.bankAllowGroupIds(DuctFaceNode.FilterBank.RETRIEVER);
        int idx = DuctAllowLimitLogic.firstMatchingAllowLineIndex(retrAllows, movingProbe, registries);
        if (idx < 0) {
            return true;
        }
        int g = lineGroupAt(retrGroups, idx);
        if (g <= 0) {
            return true;
        }
        IItemHandler donorHandler = DuctCapHelper.getHandlerOnFace(level, donorBe.getBlockPos(), donorFace);
        if (donorHandler == null) {
            return false;
        }
        IItemHandler retrHandler = DuctCapHelper.getHandlerOnFace(level, retrieverBe.getBlockPos(), retrieverFace);
        if (retrHandler == null) {
            return false;
        }
        List<ItemStack> priorDest = DuctIncomingIndex.snapshot(level, retrieverBe.getBlockPos());
        ItemStackHandler simulatedDest = DuctCapHelper.simulateInventoryAfterPending(retrHandler, priorDest);
        if (!itemGroupInsertLinesSatisfiable(
                retrAllows, retrCaps, retrGroups, g, idx, movingProbe, simulatedDest, registries)) {
            return false;
        }
        DuctFaceNode donorNode = donorBe.getFaceNode(donorFace);
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
            ItemStack template =
                    j == idx && idx >= 0
                            ? movingProbe
                            : DuctFilterLineProbesRepresentative.itemStackForAllowLine(line, registries);
            if (template.isEmpty()) {
                continue;
            }
            if (!donorBe.passesItemFilters(donorFace, template, level, DuctFaceNode.FilterBank.FILTER)) {
                return false;
            }
            int donorLine = DuctAllowLimitLogic.firstMatchingAllowLineIndex(donorAllows, template, registries);
            if (donorLine < 0) {
                return false;
            }
            int ex =
                    DuctAllowLimitLogic.maxExtractRespectingKeepAtIndex(
                            donorHandler, donorAllows, donorKeeps, template, donorLine, registries);
            if (ex < 1) {
                return false;
            }
        }
        return true;
    }

    static boolean itemGroupInsertLinesSatisfiable(
            List<String> allowLines,
            List<Integer> caps,
            List<Integer> groupIds,
            int groupId,
            int winningIndex,
            ItemStack winningProbe,
            IItemHandler handlerAfterPending,
            HolderLookup.Provider registries) {
        for (int j = 0; j < allowLines.size(); j++) {
            if (lineGroupAt(groupIds, j) != groupId) {
                continue;
            }
            String line = allowLines.get(j);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            ItemStack template =
                    j == winningIndex && winningIndex >= 0
                            ? winningProbe
                            : DuctFilterLineProbesRepresentative.itemStackForAllowLine(line, registries);
            if (template.isEmpty()) {
                continue;
            }
            int maxAdd =
                    DuctAllowLimitLogic.maxAdditionalInsertForAllowLineAtIndex(
                            handlerAfterPending, allowLines, caps, template, j, registries);
            if (maxAdd < 1) {
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
