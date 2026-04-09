package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * For ducts that enable more than one {@link DuctTransportKind}, allow-line group ids are shared: a delivery may proceed
 * only if every enabled lane with matching FILTER (or RETRIEVER) rows in that group can satisfy its limit/keep math in the
 * same evaluation (AND across item, fluid, and gas banks on the same face).
 */
public final class DuctCrossTransportAllowGroupLogic {
    private DuctCrossTransportAllowGroupLogic() {}

    public static boolean supplementFilterDestSiblingKinds(
            ServerLevel level,
            DuctBlockEntity destBe,
            Direction destFace,
            int groupId,
            DuctTransportKind primaryKind,
            int primaryWinIdx,
            ItemStack itemWinning,
            FluidStack fluidWinning,
            Object gasWinning,
            HolderLookup.Provider reg) {
        return supplementSiblingKinds(
                level, destBe, destFace, groupId, primaryKind, primaryWinIdx, itemWinning, fluidWinning, gasWinning, DuctFaceNode.FilterBank.FILTER, reg);
    }

    public static boolean supplementRetrieverDestSiblingKinds(
            ServerLevel level,
            DuctBlockEntity destBe,
            Direction destFace,
            int groupId,
            DuctTransportKind primaryKind,
            int primaryWinIdx,
            ItemStack itemWinning,
            FluidStack fluidWinning,
            Object gasWinning,
            HolderLookup.Provider reg) {
        return supplementSiblingKinds(
                level,
                destBe,
                destFace,
                groupId,
                primaryKind,
                primaryWinIdx,
                itemWinning,
                fluidWinning,
                gasWinning,
                DuctFaceNode.FilterBank.RETRIEVER,
                reg);
    }

    private static boolean supplementSiblingKinds(
            ServerLevel level,
            DuctBlockEntity destBe,
            Direction destFace,
            int groupId,
            DuctTransportKind primaryKind,
            int primaryWinIdx,
            ItemStack itemWinning,
            FluidStack fluidWinning,
            Object gasWinning,
            DuctFaceNode.FilterBank bank,
            HolderLookup.Provider reg) {
        if (groupId <= 0) {
            return true;
        }
        EnumSet<DuctTransportKind> kinds =
                destBe.ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        if (kinds.size() <= 1) {
            return true;
        }

        if (primaryKind != DuctTransportKind.ITEM && kinds.contains(DuctTransportKind.ITEM)) {
            DuctFaceNode itemNode = destBe.faceNodeForTransportKind(destFace, DuctTransportKind.ITEM);
            if (bankHasGroup(itemNode, bank, groupId)) {
                var h = DuctCapHelper.getHandlerOnFace(level, destBe.getBlockPos(), destFace);
                if (h == null) {
                    return false;
                }
                List<ItemStack> prior = DuctIncomingIndex.snapshot(level, destBe.getBlockPos());
                ItemStackHandler sim = DuctCapHelper.simulateInventoryAfterPending(h, prior);
                if (!DuctAllowGroupLogic.itemGroupInsertLinesSatisfiable(
                        itemNode.bankAllowFilters(bank),
                        itemNode.bankAllowCaps(bank),
                        itemNode.bankAllowGroupIds(bank),
                        groupId,
                        primaryKind == DuctTransportKind.ITEM ? primaryWinIdx : -1,
                        primaryKind == DuctTransportKind.ITEM ? itemWinning : ItemStack.EMPTY,
                        sim,
                        reg)) {
                    return false;
                }
            }
        }

        if (primaryKind != DuctTransportKind.FLUID && kinds.contains(DuctTransportKind.FLUID)) {
            DuctFaceNode fluidNode = destBe.faceNodeForTransportKind(destFace, DuctTransportKind.FLUID);
            if (bankHasGroup(fluidNode, bank, groupId)) {
                IFluidHandler fh =
                        level.getCapability(
                                Capabilities.FluidHandler.BLOCK,
                                destBe.getBlockPos().relative(destFace),
                                destFace.getOpposite());
                if (fh == null) {
                    return false;
                }
                if (!DuctFluidAllowGroupLogic.fluidGroupInsertLinesSatisfiableMb(
                        fluidNode.bankAllowFilters(bank),
                        fluidNode.bankAllowCaps(bank),
                        fluidNode.bankAllowGroupIds(bank),
                        groupId,
                        primaryKind == DuctTransportKind.FLUID ? primaryWinIdx : -1,
                        primaryKind == DuctTransportKind.FLUID ? fluidWinning : FluidStack.EMPTY,
                        fh,
                        destBe.getBlockPos(),
                        level,
                        reg)) {
                    return false;
                }
            }
        }

        if (primaryKind != DuctTransportKind.GAS
                && kinds.contains(DuctTransportKind.GAS)
                && MekanismChemicalCompat.isLoaded()) {
            DuctFaceNode gasNode = destBe.faceNodeForTransportKind(destFace, DuctTransportKind.GAS);
            if (bankHasGroup(gasNode, bank, groupId)) {
                Object gh = MekanismChemicalCompat.getChemicalHandlerOnFace(level, destBe.getBlockPos(), destFace);
                if (gh == null) {
                    return false;
                }
                if (!DuctGasAllowGroupLogic.gasGroupInsertLinesSatisfiable(
                        gasNode.bankAllowFilters(bank),
                        gasNode.bankAllowCaps(bank),
                        gasNode.bankAllowGroupIds(bank),
                        groupId,
                        primaryKind == DuctTransportKind.GAS ? primaryWinIdx : -1,
                        primaryKind == DuctTransportKind.GAS ? gasWinning : MekanismChemicalCompat.emptyStack(),
                        gh,
                        destBe.getBlockPos(),
                        level,
                        reg)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean bankHasGroup(DuctFaceNode node, DuctFaceNode.FilterBank bank, int groupId) {
        List<Integer> ids = node.bankAllowGroupIds(bank);
        for (Integer v : ids) {
            if (FilterGroupIds.normalize(v) == groupId) {
                return true;
            }
        }
        return false;
    }
}
