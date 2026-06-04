package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Gas filter precedence (same rules as {@link DuctFilterLogic} / {@link DuctFluidFilterLogic}).
 * <p><strong>Material-lane family:</strong> keep in sync with item and fluid filter helpers and universal lanes.
 */
public final class DuctGasFilterLogic {
    private DuctGasFilterLogic() {}

    public static boolean passesGasFilters(DuctFaceNode node, Object chemicalStack, ServerLevel level) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        boolean hasA = hasAnyNonEmpty(node.allowFilters);
        boolean hasD = hasAnyNonEmpty(node.denyFilters);
        if (!hasA && !hasD) {
            return true;
        }

        boolean A =
                hasA
                        && matchesAny(
                                node.allowFilters, node.allowConcatChannels, chemicalStack, reg);
        boolean D =
                hasD
                        && matchesAny(
                                node.denyFilters, node.denyConcatChannels, chemicalStack, reg);

        if (node.denyOverridesAllow) {
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

    public static boolean passesGasFiltersForBank(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, Object chemicalStack, ServerLevel level) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }
        DuctFaceNode view = new DuctFaceNode(() -> {});
        view.denyOverridesAllow = node.bankDenyOverridesAllow(bank);
        // Limit to effective capacity so entries preserved beyond current module capacity are not
        // evaluated during filtering.
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
        return passesGasFilters(view, chemicalStack, level);
    }

    private static boolean hasAnyNonEmpty(List<String> list) {
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(
            List<String> entries,
            List<Integer> concatChannels,
            Object chemicalStack,
            HolderLookup.Provider registries) {
        return DuctFilterConcatEvaluator.matchesAny(
                entries,
                concatChannels,
                (i, trimmed) ->
                        DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, trimmed, registries));
    }
}
