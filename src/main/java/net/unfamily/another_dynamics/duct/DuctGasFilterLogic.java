package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Gas allow/deny filtering: same precedence as {@link DuctFilterLogic} (items) and {@link DuctFluidFilterLogic}
 * ({@link DuctFaceNode#denyOverridesAllow} per bank).
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

        boolean A = hasA && matchesAny(node.allowFilters, chemicalStack, reg);
        boolean D = hasD && matchesAny(node.denyFilters, chemicalStack, reg);

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
            List<String> entries, Object chemicalStack, HolderLookup.Provider registries) {
        for (String raw : entries) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            if (DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, raw.trim(), registries)) {
                return true;
            }
        }
        return false;
    }
}
