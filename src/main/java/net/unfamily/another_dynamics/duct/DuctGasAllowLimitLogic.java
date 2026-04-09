package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Per-allow-line caps (amount units): {@code 0} = unlimited. First non-empty allow line that matches wins
 * (same index for cap), mirroring {@link DuctFluidAllowLimitLogic}.
 */
public final class DuctGasAllowLimitLogic {
    private DuctGasAllowLimitLogic() {}

    public static int firstMatchingAllowLineIndex(
            List<String> allowLines, Object template, HolderLookup.Provider registries) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template)) {
            return -1;
        }
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (DuctGasFilterMatcher.matchesAnyNonEmptyEntry(template, line.trim(), registries)) {
                return i;
            }
        }
        return -1;
    }

    public static long countMatchingInHandler(
            Object handler, String filterLine, HolderLookup.Provider registries) {
        if (handler == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        try {
            int tanks = (int) handler.getClass().getMethod("getChemicalTanks").invoke(handler);
            for (int t = 0; t < tanks; t++) {
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

    /** Max amount that may still be inserted without exceeding the allow-line {@code limit} (first matching line). */
    public static long maxAdditionalInsertForAllowLine(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            Object template,
            HolderLookup.Provider registries,
            ServerPending pending) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null) {
            return Long.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, template, registries);
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

    /** Max extractable while leaving at least {@code keep} matching the winning allow line ({@code keep <= 0} unlimited). */
    public static long maxExtractRespectingKeep(
            Object handler,
            List<String> allowLines,
            List<Integer> caps,
            Object template,
            HolderLookup.Provider registries) {
        if (template == null || MekanismChemicalCompat.isEmptyStack(template) || handler == null) {
            return Long.MAX_VALUE;
        }
        int idx = firstMatchingAllowLineIndex(allowLines, template, registries);
        if (idx < 0 || idx >= caps.size()) {
            return Long.MAX_VALUE;
        }
        int keep = caps.get(idx);
        if (keep <= 0) {
            return Long.MAX_VALUE;
        }
        String line = allowLines.get(idx);
        long current = countMatchingInHandler(handler, line, registries);
        return Math.max(0L, current - (long) keep);
    }

    /** Pending gas toward a destination, for limit math (implements {@link #countMatchingInStacks} without copying lists). */
    @FunctionalInterface
    public interface ServerPending {
        long pendingForLine(String filterLine, HolderLookup.Provider registries);
    }
}
