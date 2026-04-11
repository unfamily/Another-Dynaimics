package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/** Deny/allow precedence for fluid transport (same semantics as {@link DuctFilterLogic} for items). */
public final class DuctFluidFilterLogic {
    private DuctFluidFilterLogic() {}

    public static boolean passesFluidFilters(DuctFaceNode node, FluidStack stack, Level level) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        boolean hasA = hasAnyNonEmpty(node.allowFilters);
        boolean hasD = hasAnyNonEmpty(node.denyFilters);
        if (!hasA && !hasD) {
            return true;
        }

        Fluid fluid = stack.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();

        boolean A = hasA && matchesAny(node.allowFilters, stack, fluid, fluidId, fluidIdStr, fluidModId, reg);
        boolean D = hasD && matchesAny(node.denyFilters, stack, fluid, fluidId, fluidIdStr, fluidModId, reg);

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

    public static boolean passesFluidFiltersForBank(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, FluidStack stack, Level level) {
        if (stack.isEmpty()) {
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
        return passesFluidFilters(view, stack, level);
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
            FluidStack stack,
            Fluid fluid,
            ResourceLocation fluidId,
            String fluidIdStr,
            String fluidModId,
            HolderLookup.Provider registries) {
        for (String raw : entries) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            if (DuctFluidFilterMatcher.matchesFilterEntry(
                    stack, fluid, fluidId, fluidIdStr, fluidModId, raw.trim(), registries)) {
                return true;
            }
        }
        return false;
    }
}
