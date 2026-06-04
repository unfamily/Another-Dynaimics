package net.unfamily.another_dynamics.duct;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctTankSlotSemantics;

/**
 * Per-allow-line caps in mB: {@code 0} = unlimited. First non-empty allow line that matches a fluid wins
 * (same index for cap), mirroring {@link DuctAllowLimitLogic} for items.
 */
public final class DuctFluidAllowLimitLogic {
    private DuctFluidAllowLimitLogic() {}

    public static boolean hasAnyPositiveAllowCapOnNonEmptyLine(List<String> allowLines, List<Integer> caps) {
        if (allowLines == null || caps == null) {
            return false;
        }
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int lim = i < caps.size() ? caps.get(i) : 0;
            if (lim > 0) {
                return true;
            }
        }
        return false;
    }

    public static int firstMatchingAllowLineIndex(
            List<String> allowLines, FluidStack template, HolderLookup.Provider registries) {
        return firstMatchingAllowLineIndex(allowLines, null, template, registries);
    }

    public static int firstMatchingAllowLineIndex(
            List<String> allowLines,
            @Nullable List<Integer> concatChannels,
            FluidStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty()) {
            return -1;
        }
        Fluid fluid = template.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();
        return DuctFilterConcatEvaluator.firstMatchingLineIndex(
                allowLines,
                concatChannels,
                (i, trimmed) ->
                        DuctFluidFilterMatcher.matchesFilterEntry(
                                template, fluid, fluidId, fluidIdStr, fluidModId, trimmed, registries));
    }

    private static int countPendingMatchingFilterLineMb(
            @Nullable List<FluidStack> priorPending, String filterLine, HolderLookup.Provider registries) {
        return countMatchingInStacksMb(priorPending, filterLine, registries);
    }

    public static int maxAdditionalInsertForAllowLineMbAtIndex(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            int lineIndex,
            HolderLookup.Provider registries) {
        return maxAdditionalInsertForAllowLineMbAtIndex(
                handler, allowLines, caps, template, lineIndex, null, registries);
    }

    public static int maxAdditionalInsertForAllowLineMbAtIndex(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            int lineIndex,
            @Nullable List<FluidStack> priorPending,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null) {
            return Integer.MAX_VALUE;
        }
        if (lineIndex < 0 || lineIndex >= allowLines.size()) {
            return Integer.MAX_VALUE;
        }
        String line = allowLines.get(lineIndex);
        if (line == null || line.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
            return 0;
        }
        int lim = lineIndex < caps.size() ? caps.get(lineIndex) : 0;
        if (lim <= 0) {
            return Integer.MAX_VALUE;
        }
        int current =
                countMatchingInHandlerMb(handler, line, registries)
                        + countPendingMatchingFilterLineMb(priorPending, line, registries);
        return Math.max(0, lim - current);
    }

    public static int maxExtractRespectingKeepMbAtIndex(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> keepCaps,
            FluidStack template,
            int lineIndex,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null) {
            return Integer.MAX_VALUE;
        }
        if (lineIndex < 0 || lineIndex >= allowLines.size()) {
            return Integer.MAX_VALUE;
        }
        String line = allowLines.get(lineIndex);
        if (line == null || line.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
            return 0;
        }
        int keep = lineIndex < keepCaps.size() ? keepCaps.get(lineIndex) : 0;
        if (keep <= 0) {
            return Integer.MAX_VALUE;
        }
        int current = countMatchingInHandlerMb(handler, line, registries, true);
        return Math.max(0, current - keep);
    }

    public static int countMatchingInHandlerMb(
            IFluidHandler handler, String filterLine, HolderLookup.Provider registries) {
        return countMatchingInHandlerMb(handler, filterLine, registries, false);
    }

    /**
     * @param drainableTanksOnly when true, only tanks classified as output/both (for extract/keep caps).
     */
    public static int countMatchingInHandlerMb(
            IFluidHandler handler,
            String filterLine,
            HolderLookup.Provider registries,
            boolean drainableTanksOnly) {
        if (handler == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int t = 0; t < handler.getTanks(); t++) {
            if (drainableTanksOnly && !DuctTankSlotSemantics.canDrainFromFluidTank(handler, t)) {
                continue;
            }
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

    /**
     * Walks allow lines in order; returns headroom on the first matching line that still accepts inserts.
     */
    public static int maxAdditionalInsertAcrossAllowLinesMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            HolderLookup.Provider registries) {
        return maxAdditionalInsertAcrossAllowLinesMb(handler, allowLines, caps, template, null, registries);
    }

    public static int maxAdditionalInsertAcrossAllowLinesMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            @Nullable List<FluidStack> priorPending,
            HolderLookup.Provider registries) {
        return maxAdditionalInsertAcrossAllowLinesMb(
                handler, allowLines, caps, null, template, priorPending, registries);
    }

    public static int maxAdditionalInsertAcrossAllowLinesMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            @Nullable List<Integer> concatChannels,
            FluidStack template,
            @Nullable List<FluidStack> priorPending,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null || allowLines == null || caps == null) {
            return Integer.MAX_VALUE;
        }
        if (!hasAnyPositiveAllowCapOnNonEmptyLine(allowLines, caps)) {
            return Integer.MAX_VALUE;
        }
        java.util.Set<Integer> consumedConcat = new java.util.HashSet<>();
        for (int i = 0; i < allowLines.size(); i++) {
            int ch = FilterConcatChannel.channelAt(concatChannels, i);
            if (ch != 0 && consumedConcat.contains(ch)) {
                continue;
            }
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int capIndex = i;
            if (ch != 0) {
                if (!templateMatchesFluidConcatGroup(allowLines, concatChannels, ch, template, registries)) {
                    continue;
                }
                capIndex = firstNonEmptyFluidIndexForChannel(allowLines, concatChannels, ch);
                if (capIndex < 0) {
                    continue;
                }
                consumedConcat.add(ch);
            } else if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(template, line.trim(), registries)) {
                continue;
            }
            int add =
                    maxAdditionalInsertForAllowLineMbAtIndex(
                            handler, allowLines, caps, template, capIndex, priorPending, registries);
            if (add == Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (add > 0) {
                return add;
            }
        }
        return 0;
    }

    private static int firstNonEmptyFluidIndexForChannel(
            List<String> lines, @Nullable List<Integer> concatChannels, int channel) {
        for (int i = 0; i < lines.size(); i++) {
            if (FilterConcatChannel.channelAt(concatChannels, i) != channel) {
                continue;
            }
            String raw = lines.get(i);
            if (raw != null && !raw.trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean templateMatchesFluidConcatGroup(
            List<String> lines,
            @Nullable List<Integer> concatChannels,
            int channel,
            FluidStack template,
            HolderLookup.Provider registries) {
        boolean anyInGroup = false;
        for (int i = 0; i < lines.size(); i++) {
            if (FilterConcatChannel.channelAt(concatChannels, i) != channel) {
                continue;
            }
            String raw = lines.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            anyInGroup = true;
            if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(template, raw.trim(), registries)) {
                return false;
            }
        }
        return anyInGroup;
    }

    public static int maxExtractRespectingKeepAcrossLinesMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> keepCaps,
            FluidStack template,
            HolderLookup.Provider registries) {
        return maxExtractRespectingKeepAcrossLinesMb(
                handler, allowLines, keepCaps, null, template, registries);
    }

    public static int maxExtractRespectingKeepAcrossLinesMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> keepCaps,
            @Nullable List<Integer> concatChannels,
            FluidStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null || allowLines == null || keepCaps == null) {
            return Integer.MAX_VALUE;
        }
        java.util.Set<Integer> consumedConcat = new java.util.HashSet<>();
        for (int i = 0; i < allowLines.size(); i++) {
            int ch = FilterConcatChannel.channelAt(concatChannels, i);
            if (ch != 0 && consumedConcat.contains(ch)) {
                continue;
            }
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int capIndex = i;
            if (ch != 0) {
                if (!templateMatchesFluidConcatGroup(allowLines, concatChannels, ch, template, registries)) {
                    continue;
                }
                capIndex = firstNonEmptyFluidIndexForChannel(allowLines, concatChannels, ch);
                if (capIndex < 0) {
                    continue;
                }
                consumedConcat.add(ch);
            } else if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(template, line.trim(), registries)) {
                continue;
            }
            int ex =
                    maxExtractRespectingKeepMbAtIndex(
                            handler, allowLines, keepCaps, template, capIndex, registries);
            if (ex == Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (ex > 0) {
                return ex;
            }
        }
        return 0;
    }

    /** Max mB that may still be inserted without exceeding the allow-line {@code limit} (first matching unit). */
    public static int maxAdditionalInsertForAllowLineMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            HolderLookup.Provider registries) {
        return maxAdditionalInsertForAllowLineMb(handler, allowLines, caps, null, template, registries);
    }

    public static int maxAdditionalInsertForAllowLineMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            @Nullable List<Integer> concatChannels,
            FluidStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null) {
            return Integer.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, concatChannels, template, registries);
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

    /** Max mB extractable while leaving at least {@code keep} on the winning allow unit ({@code keep <= 0} unlimited). */
    public static int maxExtractRespectingKeepMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            FluidStack template,
            HolderLookup.Provider registries) {
        return maxExtractRespectingKeepMb(handler, allowLines, caps, null, template, registries);
    }

    public static int maxExtractRespectingKeepMb(
            IFluidHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            @Nullable List<Integer> concatChannels,
            FluidStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null) {
            return Integer.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, concatChannels, template, registries);
        if (idx < 0 || idx >= caps.size()) {
            return Integer.MAX_VALUE;
        }
        int keep = caps.get(idx);
        if (keep <= 0) {
            return Integer.MAX_VALUE;
        }
        String line = allowLines.get(idx);
        int current = countMatchingInHandlerMb(handler, line, registries, true);
        return Math.max(0, current - keep);
    }
}

