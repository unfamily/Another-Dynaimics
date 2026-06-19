package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

/**
 * Fluid filter precedence (same rules as {@link DuctFilterLogic}).
 * <p><strong>Material-lane family:</strong> keep in sync with item and gas filter helpers and universal lanes.
 */
public final class DuctFluidFilterLogic {
    private DuctFluidFilterLogic() {}

    public static boolean passesFluidFilters(DuctFaceNode node, FluidStack stack, Level level) {
        return passesFluidFilters(node, stack, level, null);
    }

    public static boolean passesFluidFilters(
            DuctFaceNode node, FluidStack stack, Level level, @Nullable DuctDirectionalEndpoint counterparty) {
        return passesFluidFiltersWithConcat(
                node.denyOverridesAllow,
                node.allowFilters,
                node.denyFilters,
                node.allowConcatChannels,
                node.denyConcatChannels,
                node.allowRemoteNodes,
                node.denyRemoteNodes,
                node.allowRemoteIgnoreChannel,
                node.denyRemoteIgnoreChannel,
                stack,
                level,
                counterparty);
    }

    public static boolean passesFluidFiltersForBank(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, FluidStack stack, Level level) {
        return passesFluidFiltersForBank(node, bank, stack, level, null);
    }

    public static boolean passesFluidFiltersForBank(
            DuctFaceNode node,
            DuctFaceNode.FilterBank bank,
            FluidStack stack,
            Level level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (stack.isEmpty()) {
            return false;
        }
        DuctFaceNode view = bankView(node, bank);
        return passesFluidFilters(view, stack, level, counterparty);
    }

    private static DuctFaceNode bankView(DuctFaceNode node, DuctFaceNode.FilterBank bank) {
        DuctFaceNode view = new DuctFaceNode(() -> {});
        view.denyOverridesAllow = node.bankDenyOverridesAllow(bank);
        List<String> fullAllow = node.bankAllowFilters(bank);
        List<String> fullDeny = node.bankDenyFilters(bank);
        int ac = node.effectiveAllowBankCap;
        int dc = node.effectiveDenyBankCap;
        view.allowFilters.addAll(fullAllow.subList(0, Math.min(fullAllow.size(), ac)));
        view.denyFilters.addAll(fullDeny.subList(0, Math.min(fullDeny.size(), dc)));
        List<Integer> fullAllowConcat = node.bankAllowConcatChannels(bank);
        List<Integer> fullDenyConcat = node.bankDenyConcatChannels(bank);
        view.allowConcatChannels.addAll(
                fullAllowConcat.subList(0, Math.min(fullAllowConcat.size(), ac)));
        view.denyConcatChannels.addAll(
                fullDenyConcat.subList(0, Math.min(fullDenyConcat.size(), dc)));
        List<DuctDirectionalEndpoint> fullAllowRemote = node.bankAllowRemoteNodes(bank);
        List<DuctDirectionalEndpoint> fullDenyRemote = node.bankDenyRemoteNodes(bank);
        view.allowRemoteNodes.addAll(
                fullAllowRemote.subList(0, Math.min(fullAllowRemote.size(), ac)));
        view.denyRemoteNodes.addAll(
                fullDenyRemote.subList(0, Math.min(fullDenyRemote.size(), dc)));
        List<Boolean> fullAllowIgnore = node.bankAllowRemoteIgnoreChannel(bank);
        List<Boolean> fullDenyIgnore = node.bankDenyRemoteIgnoreChannel(bank);
        view.allowRemoteIgnoreChannel.addAll(
                fullAllowIgnore.subList(0, Math.min(fullAllowIgnore.size(), ac)));
        view.denyRemoteIgnoreChannel.addAll(
                fullDenyIgnore.subList(0, Math.min(fullDenyIgnore.size(), dc)));
        return view;
    }

    public static boolean passesFluidFiltersWithConcat(
            boolean denyOverridesAllow,
            List<String> allowFilters,
            List<String> denyFilters,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> allowRemote,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowIgnoreChannel,
            List<Boolean> denyIgnoreChannel,
            FluidStack stack,
            Level level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        Fluid fluid = stack.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();

        if (counterparty != null) {
            return DuctFilterUnitLogic.evaluateSequentialPrecedence(
                    denyOverridesAllow,
                    allowFilters,
                    denyFilters,
                    allowConcat,
                    denyConcat,
                    allowRemote,
                    denyRemote,
                    allowIgnoreChannel,
                    denyIgnoreChannel,
                    counterparty,
                    (i, trimmed) ->
                            DuctFluidFilterMatcher.matchesFilterEntry(
                                    stack, fluid, fluidId, fluidIdStr, fluidModId, trimmed, reg),
                    (i, trimmed) ->
                            DuctFluidFilterMatcher.matchesFilterEntry(
                                    stack, fluid, fluidId, fluidIdStr, fluidModId, trimmed, reg));
        }

        boolean hasA =
                DuctFilterRemoteNodeLogic.hasAnyApplicableNonEmpty(allowFilters, allowRemote, counterparty);
        boolean hasD =
                DuctFilterRemoteNodeLogic.hasAnyApplicableNonEmpty(denyFilters, denyRemote, counterparty);
        if (!hasA && !hasD) {
            return true;
        }
        boolean A =
                hasA
                        && matchesAny(
                                allowFilters,
                                allowConcat,
                                allowRemote,
                                counterparty,
                                stack,
                                fluid,
                                fluidId,
                                fluidIdStr,
                                fluidModId,
                                reg);
        boolean D =
                hasD
                        && matchesAny(
                                denyFilters,
                                denyConcat,
                                denyRemote,
                                counterparty,
                                stack,
                                fluid,
                                fluidId,
                                fluidIdStr,
                                fluidModId,
                                reg);
        if (denyOverridesAllow) {
            if (D) {
                return false;
            }
            if (hasA && !A) {
                return false;
            }
            return true;
        }
        if (hasA && A) {
            return true;
        }
        if (D) {
            return false;
        }
        if (hasA && !A) {
            return false;
        }
        return true;
    }

    public static boolean fluidMatchesAllowUnit(
            DuctFaceNode.FilterBank bank,
            DuctFaceNode node,
            DuctFilterUnitLogic.FilterUnit unit,
            FluidStack stack,
            Level level) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        Fluid fluid = stack.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();
        List<String> allow = node.bankAllowFilters(bank);
        List<Integer> concat = node.bankAllowConcatChannels(bank);
        return DuctFilterUnitLogic.unitTextMatches(
                unit,
                allow,
                concat,
                (i, trimmed) ->
                        DuctFluidFilterMatcher.matchesFilterEntry(
                                stack, fluid, fluidId, fluidIdStr, fluidModId, trimmed, reg));
    }

    public static boolean fluidMatchesDenyForAllowUnit(
            DuctFaceNode.FilterBank bank,
            DuctFaceNode node,
            DuctFilterUnitLogic.FilterUnit allowUnit,
            FluidStack stack,
            Level level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        Fluid fluid = stack.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();
        return DuctFilterUnitLogic.denyBlocksAllowUnit(
                allowUnit,
                node.bankDenyFilters(bank),
                node.bankDenyConcatChannels(bank),
                node.bankDenyRemoteNodes(bank),
                node.bankDenyRemoteIgnoreChannel(bank),
                counterparty,
                (i, trimmed) ->
                        DuctFluidFilterMatcher.matchesFilterEntry(
                                stack, fluid, fluidId, fluidIdStr, fluidModId, trimmed, reg));
    }

    private static boolean matchesAny(
            List<String> entries,
            List<Integer> concatChannels,
            List<DuctDirectionalEndpoint> remoteNodes,
            @Nullable DuctDirectionalEndpoint counterparty,
            FluidStack stack,
            Fluid fluid,
            ResourceLocation fluidId,
            String fluidIdStr,
            String fluidModId,
            HolderLookup.Provider registries) {
        return DuctFilterConcatEvaluator.matchesAny(
                entries,
                concatChannels,
                (i, trimmed) -> {
                    if (!DuctFilterRemoteNodeLogic.lineApplicable(i, remoteNodes, counterparty)) {
                        return false;
                    }
                    return DuctFluidFilterMatcher.matchesFilterEntry(
                            stack, fluid, fluidId, fluidIdStr, fluidModId, trimmed, registries);
                });
    }
}
