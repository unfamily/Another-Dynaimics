package net.unfamily.another_dynamics.duct;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.unfamily.another_dynamics.duct.logistics.DuctTankSlotSemantics;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Per-allow-line caps (amount units): {@code 0} = unlimited. First non-empty allow line that matches wins
 * (same index for cap), mirroring {@link DuctFluidAllowLimitLogic}.
 */
public final class DuctGasAllowLimitLogic {
    private DuctGasAllowLimitLogic() {}

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
            List<String> allowLines, Object template, HolderLookup.Provider registries) {
        return firstMatchingAllowLineIndex(allowLines, null, template, registries);
    }

    public static int firstMatchingAllowLineIndex(
            List<String> allowLines,
            @Nullable List<Integer> concatChannels,
            Object template,
            HolderLookup.Provider registries) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template)) {
            return -1;
        }
        return DuctFilterConcatEvaluator.firstMatchingLineIndex(
                allowLines,
                concatChannels,
                (i, trimmed) ->
                        DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, trimmed, registries));
    }

    public static long countMatchingInHandler(
            Object handler, String filterLine, HolderLookup.Provider registries) {
        return countMatchingInHandler(handler, filterLine, registries, false);
    }

    public static long countMatchingInHandler(
            Object handler, String filterLine, HolderLookup.Provider registries, boolean drainableTanksOnly) {
        if (handler == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        try {
            int tanks = (int) handler.getClass().getMethod("getChemicalTanks").invoke(handler);
            for (int t = 0; t < tanks; t++) {
                if (drainableTanksOnly && !DuctTankSlotSemantics.canDrainFromGasTank(handler, t)) {
                    continue;
                }
                Object inTank = handler.getClass().getMethod("getChemicalInTank", int.class).invoke(handler, t);
                if (MekanismChemicalCompat.isEmptyStack(inTank)) {
                    continue;
                }
                if (DuctGasFilterMatcher.matchesAnyNonEmptyEntry(inTank, filterLine, registries)) {
                    sum += MekanismChemicalCompat.getAmount(inTank);
                }
            }
        } catch (Throwable ignored) {
            return 0L;
        }
        return sum;
    }

    public static long countMatchingInStacks(
            List<Object> stacks, String filterLine, HolderLookup.Provider registries) {
        if (stacks == null || stacks.isEmpty() || filterLine == null || filterLine.trim().isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Object s : stacks) {
            if (s == null || MekanismChemicalCompat.isEmptyStack(s)) {
                continue;
            }
            if (DuctGasFilterMatcher.matchesAnyNonEmptyEntry(s, filterLine, registries)) {
                sum += MekanismChemicalCompat.getAmount(s);
            }
        }
        return sum;
    }

    public static long maxAdditionalInsertForAllowLineAtIndex(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            Object template,
            int lineIndex,
            HolderLookup.Provider registries,
            ServerPending pending) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null) {
            return Long.MAX_VALUE;
        }
        if (lineIndex < 0 || lineIndex >= allowLines.size()) {
            return Long.MAX_VALUE;
        }
        String line = allowLines.get(lineIndex);
        if (line == null || line.trim().isEmpty()) {
            return Long.MAX_VALUE;
        }
        if (!DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
            return 0L;
        }
        int lim = lineIndex < caps.size() ? caps.get(lineIndex) : 0;
        if (lim <= 0) {
            return Long.MAX_VALUE;
        }
        long current = countMatchingInHandler(handler, line, registries);
        long pend = pending == null ? 0L : pending.pendingForLine(line, registries);
        return Math.max(0L, (long) lim - (current + pend));
    }

    public static long maxAdditionalInsertAcrossAllowLines(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            Object template,
            HolderLookup.Provider registries,
            ServerPending pending) {
        return maxAdditionalInsertAcrossAllowLines(
                handler, allowLines, caps, null, template, registries, pending);
    }

    public static long maxAdditionalInsertAcrossAllowLines(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            @Nullable List<Integer> concatChannels,
            Object template,
            HolderLookup.Provider registries,
            ServerPending pending) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null || allowLines == null || caps == null) {
            return Long.MAX_VALUE;
        }
        if (!hasAnyPositiveAllowCapOnNonEmptyLine(allowLines, caps)) {
            return Long.MAX_VALUE;
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
                if (!templateMatchesGasConcatGroup(allowLines, concatChannels, ch, template, registries)) {
                    continue;
                }
                capIndex = firstNonEmptyGasIndexForChannel(allowLines, concatChannels, ch);
                if (capIndex < 0) {
                    continue;
                }
                consumedConcat.add(ch);
            } else if (!DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, line.trim(), registries)) {
                continue;
            }
            long add =
                    maxAdditionalInsertForAllowLineAtIndex(
                            handler, allowLines, caps, template, capIndex, registries, pending);
            if (add == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            if (add > 0L) {
                return add;
            }
        }
        return 0L;
    }

    private static int firstNonEmptyGasIndexForChannel(
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

    private static boolean templateMatchesGasConcatGroup(
            List<String> lines,
            @Nullable List<Integer> concatChannels,
            int channel,
            Object template,
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
            if (!DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, raw.trim(), registries)) {
                return false;
            }
        }
        return anyInGroup;
    }

    /** Max amount that may still be inserted without exceeding the allow-line {@code limit} (first matching unit). */
    public static long maxAdditionalInsertForAllowLine(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            Object template,
            HolderLookup.Provider registries,
            ServerPending pending) {
        return maxAdditionalInsertForAllowLine(
                handler, allowLines, caps, null, template, registries, pending);
    }

    public static long maxAdditionalInsertForAllowLine(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            @Nullable List<Integer> concatChannels,
            Object template,
            HolderLookup.Provider registries,
            ServerPending pending) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null) {
            return Long.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, concatChannels, template, registries);
        if (idx < 0 || idx >= caps.size()) {
            return Long.MAX_VALUE;
        }
        int lim = caps.get(idx);
        if (lim <= 0) {
            return Long.MAX_VALUE;
        }
        String line = allowLines.get(idx);
        long current = countMatchingInHandler(handler, line, registries);
        long pend = pending == null ? 0L : pending.pendingForLine(line, registries);
        return Math.max(0L, (long) lim - (current + pend));
    }

    /** Max extractable while leaving at least {@code keep} on the winning allow unit ({@code keep <= 0} unlimited). */
    public static long maxExtractRespectingKeep(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            Object template,
            HolderLookup.Provider registries) {
        return maxExtractRespectingKeep(handler, allowLines, caps, null, template, registries);
    }

    public static long maxExtractRespectingKeep(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            @Nullable List<Integer> concatChannels,
            Object template,
            HolderLookup.Provider registries) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null) {
            return Long.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, concatChannels, template, registries);
        if (idx < 0 || idx >= caps.size()) {
            return Long.MAX_VALUE;
        }
        int keep = caps.get(idx);
        if (keep <= 0) {
            return Long.MAX_VALUE;
        }
        String line = allowLines.get(idx);
        long current = countMatchingInHandler(handler, line, registries, true);
        return Math.max(0L, current - (long) keep);
    }

    /** Keep cap for a specific allow row index (used by grouped retriever + donor checks). */
    public static long maxExtractRespectingKeepAtIndex(
            Object handler,
            List<String> allowLines,
            List<Integer> keepCaps,
            Object template,
            int lineIndex,
            HolderLookup.Provider registries) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null) {
            return Long.MAX_VALUE;
        }
        if (lineIndex < 0 || lineIndex >= allowLines.size()) {
            return Long.MAX_VALUE;
        }
        String line = allowLines.get(lineIndex);
        if (line == null || line.trim().isEmpty()) {
            return Long.MAX_VALUE;
        }
        if (!DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
            return 0L;
        }
        int keep = lineIndex < keepCaps.size() ? keepCaps.get(lineIndex) : 0;
        if (keep <= 0) {
            return Long.MAX_VALUE;
        }
        long current = countMatchingInHandler(handler, line, registries, true);
        return Math.max(0L, current - (long) keep);
    }

    /** Pending gas toward a destination, for limit math (implements {@link #countMatchingInStacks} without copying lists). */
    @FunctionalInterface
    public interface ServerPending {
        long pendingForLine(String filterLine, HolderLookup.Provider registries);
    }
}
