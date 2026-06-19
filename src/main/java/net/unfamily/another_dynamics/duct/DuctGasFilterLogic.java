package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import org.jetbrains.annotations.Nullable;

/**
 * Gas filter precedence (same rules as {@link DuctFilterLogic} / {@link DuctFluidFilterLogic}).
 * <p><strong>Material-lane family:</strong> keep in sync with item and fluid filter helpers and universal lanes.
 */
public final class DuctGasFilterLogic {
    private DuctGasFilterLogic() {}

    public static boolean passesGasFilters(DuctFaceNode node, Object chemicalStack, ServerLevel level) {
        return passesGasFilters(node, chemicalStack, level, null);
    }

    public static boolean passesGasFilters(
            DuctFaceNode node,
            Object chemicalStack,
            ServerLevel level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        return passesGasFiltersWithConcat(
                node.denyOverridesAllow,
                node.allowFilters,
                node.denyFilters,
                node.allowConcatChannels,
                node.denyConcatChannels,
                node.allowRemoteNodes,
                node.denyRemoteNodes,
                node.allowRemoteIgnoreChannel,
                node.denyRemoteIgnoreChannel,
                chemicalStack,
                level,
                counterparty);
    }

    public static boolean passesGasFiltersForBank(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, Object chemicalStack, ServerLevel level) {
        return passesGasFiltersForBank(node, bank, chemicalStack, level, null);
    }

    public static boolean passesGasFiltersForBank(
            DuctFaceNode node,
            DuctFaceNode.FilterBank bank,
            Object chemicalStack,
            ServerLevel level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }
        DuctFaceNode view = bankView(node, bank);
        return passesGasFilters(view, chemicalStack, level, counterparty);
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

    public static boolean passesGasFiltersWithConcat(
            boolean denyOverridesAllow,
            List<String> allowFilters,
            List<String> denyFilters,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> allowRemote,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowIgnoreChannel,
            List<Boolean> denyIgnoreChannel,
            Object chemicalStack,
            ServerLevel level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();

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
                    (i, trimmed) -> DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, trimmed, reg),
                    (i, trimmed) -> DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, trimmed, reg));
        }

        boolean hasA =
                DuctFilterRemoteNodeLogic.hasAnyApplicableNonEmpty(allowFilters, allowRemote, counterparty);
        boolean hasD =
                DuctFilterRemoteNodeLogic.hasAnyApplicableNonEmpty(denyFilters, denyRemote, counterparty);
        if (!hasA && !hasD) {
            return true;
        }
        boolean A =
                hasA && matchesAny(allowFilters, allowConcat, allowRemote, counterparty, chemicalStack, reg);
        boolean D =
                hasD && matchesAny(denyFilters, denyConcat, denyRemote, counterparty, chemicalStack, reg);
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

    public static boolean gasMatchesAllowUnit(
            DuctFaceNode.FilterBank bank,
            DuctFaceNode node,
            DuctFilterUnitLogic.FilterUnit unit,
            Object chemicalStack,
            ServerLevel level) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        List<String> allow = node.bankAllowFilters(bank);
        List<Integer> concat = node.bankAllowConcatChannels(bank);
        return DuctFilterUnitLogic.unitTextMatches(
                unit,
                allow,
                concat,
                (i, trimmed) -> DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, trimmed, reg));
    }

    public static boolean gasMatchesDenyForAllowUnit(
            DuctFaceNode.FilterBank bank,
            DuctFaceNode node,
            DuctFilterUnitLogic.FilterUnit allowUnit,
            Object chemicalStack,
            ServerLevel level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        return DuctFilterUnitLogic.denyBlocksAllowUnit(
                allowUnit,
                node.bankDenyFilters(bank),
                node.bankDenyConcatChannels(bank),
                node.bankDenyRemoteNodes(bank),
                node.bankDenyRemoteIgnoreChannel(bank),
                counterparty,
                (i, trimmed) -> DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, trimmed, reg));
    }

    private static boolean matchesAny(
            List<String> entries,
            List<Integer> concatChannels,
            List<DuctDirectionalEndpoint> remoteNodes,
            @Nullable DuctDirectionalEndpoint counterparty,
            Object chemicalStack,
            HolderLookup.Provider registries) {
        return DuctFilterConcatEvaluator.matchesAny(
                entries,
                concatChannels,
                (i, trimmed) -> {
                    if (!DuctFilterRemoteNodeLogic.lineApplicable(i, remoteNodes, counterparty)) {
                        return false;
                    }
                    return DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, trimmed, registries);
                });
    }
}
