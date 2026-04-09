package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Per-allow-line caps in mB: {@code 0} = unlimited. First non-empty allow line that matches a fluid wins
 * (same index for cap), mirroring {@link DuctAllowLimitLogic} for items.
 */
public final class DuctFluidAllowLimitLogic {
    private DuctFluidAllowLimitLogic() {}

    public static int firstMatchingAllowLineIndex(
            List<String> allowLines, FluidStack template, HolderLookup.Provider registries) {
        if (template.isEmpty()) {
            return -1;
        }
        Fluid fluid = template.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (DuctFluidFilterMatcher.matchesFilterEntry(
                    template, fluid, fluidId, fluidIdStr, fluidModId, line.trim(), registries)) {
                return i;
            }
        }
        return -1;
    }

    public static int countMatchingInHandlerMb(
            IFluidHandler handler, String filterLine, HolderLookup.Provider registries) {
        if (handler == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int t = 0; t < handler.getTanks(); t++) {
            FluidStack in = handler.getFluidInTank(t);
            if (in.isEmpty()) {
                continue;
            }
            if (DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(in, filterLine, registries)) {
                sum += in.getAmount();
            }
        }
        return sum;
    }

    public static int countMatchingInStacksMb(
            List<FluidStack> stacks, String filterLine, HolderLookup.Provider registries) {
        if (stacks == null || stacks.isEmpty() || filterLine == null || filterLine.trim().isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (FluidStack s : stacks) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(s, filterLine, registries)) {
                sum += s.getAmount();
            }
        }
        return sum;
    }

    /** Max mB that may still be inserted without exceeding the allow-line {@code limit} (first matching line). */
    public static int maxAdditionalInsertForAllowLineMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null) {
            return Integer.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, template, registries);
        if (idx < 0 || idx >= caps.size()) {
            return Integer.MAX_VALUE;
        }
        int lim = caps.get(idx);
        if (lim <= 0) {
            return Integer.MAX_VALUE;
        }
        String line = allowLines.get(idx);
        int current = countMatchingInHandlerMb(handler, line, registries);
        return Math.max(0, lim - current);
    }

    /** Max mB extractable while leaving at least {@code keep} matching the winning allow line ({@code keep <= 0} unlimited). */
    public static int maxExtractRespectingKeepMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null) {
            return Integer.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, template, registries);
        if (idx < 0 || idx >= caps.size()) {
            return Integer.MAX_VALUE;
        }
        int keep = caps.get(idx);
        if (keep <= 0) {
            return Integer.MAX_VALUE;
        }
        String line = allowLines.get(idx);
        int current = countMatchingInHandlerMb(handler, line, registries);
        return Math.max(0, current - keep);
    }
}

