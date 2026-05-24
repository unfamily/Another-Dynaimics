package net.unfamily.another_dynamics.duct;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctHandlerSlotSemantics;

/**
 * Per-allow-line caps: {@code 0} = unlimited. First non-empty allow line that matches an item wins (same index for cap).
 */
public final class DuctAllowLimitLogic {
    private DuctAllowLimitLogic() {}

    /**
     * True when at least one non-empty allow line has a positive limit. If none, destination allow-limit logic should not
     * constrain inserts (cap 0 = unlimited per row).
     */
    public static boolean hasAnyPositiveKeepOnNonEmptyLine(List<String> allowLines, List<Integer> keepCaps) {
        if (allowLines == null || keepCaps == null) {
            return false;
        }
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int keep = i < keepCaps.size() ? keepCaps.get(i) : 0;
            if (keep > 0) {
                return true;
            }
        }
        return false;
    }

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

    private static int countPendingMatchingFilterLine(
            @Nullable List<ItemStack> priorPending, String filterLine, HolderLookup.Provider registries) {
        if (priorPending == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (ItemStack p : priorPending) {
            if (p.isEmpty()) {
                continue;
            }
            if (DuctFilterMatcher.matchesAnyNonEmptyEntry(p, filterLine, registries)) {
                sum += p.getCount();
            }
        }
        return sum;
    }

    public static int firstMatchingAllowLineIndex(
            List<String> allowLines, ItemStack template, HolderLookup.Provider registries) {
        if (template.isEmpty()) {
            return -1;
        }
        Item item = template.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (DuctFilterMatcher.matchesFilterEntry(
                    template, item, itemId, itemIdStr, itemModId, line.trim(), registries)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Per-line limit check: uses cap at {@code lineIndex} and counts only that line's pattern (not first matching row).
     */
    public static int maxAdditionalInsertForAllowLineAtIndex(
            IItemHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            ItemStack template,
            int lineIndex,
            @Nullable List<ItemStack> priorPending,
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
        if (!DuctFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
            return 0;
        }
        int lim = lineIndex < caps.size() ? caps.get(lineIndex) : 0;
        if (lim <= 0) {
            return Integer.MAX_VALUE;
        }
        int current =
                countMatchingInHandler(handler, line, registries, true)
                        + countPendingMatchingFilterLine(priorPending, line, registries);
        return Math.max(0, lim - current);
    }

    public static int maxExtractRespectingKeepAtIndex(
            IItemHandler handler,
            List<String> allowLines,
            List<Integer> keepCaps,
            ItemStack template,
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
        if (!DuctFilterMatcher.matchesAnyNonEmptyEntry(template, line, registries)) {
            return 0;
        }
        int keep = lineIndex < keepCaps.size() ? keepCaps.get(lineIndex) : 0;
        if (keep <= 0) {
            return Integer.MAX_VALUE;
        }
        int current = countMatchingInHandler(handler, line, registries, false);
        return Math.max(0, current - keep);
    }

    /**
     * @param insertSideOnly when true, only input/both slots (for insert limits); when false, only output/both (for
     *     extract keep); when null, all slots.
     */
    public static int countMatchingInHandler(
            IItemHandler handler, String filterLine, HolderLookup.Provider registries) {
        return countMatchingInHandler(handler, filterLine, registries, null);
    }

    public static int countMatchingInHandler(
            IItemHandler handler,
            String filterLine,
            HolderLookup.Provider registries,
            @Nullable Boolean insertSideOnly) {
        if (handler == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (insertSideOnly != null) {
                if (insertSideOnly) {
                    if (!DuctHandlerSlotSemantics.countsTowardInsertLimit(handler, slot)) {
                        continue;
                    }
                } else if (!DuctHandlerSlotSemantics.countsTowardExtractKeep(handler, slot)) {
                    continue;
                }
            }
            ItemStack s = handler.getStackInSlot(slot);
            if (s.isEmpty()) {
                continue;
            }
            if (DuctFilterMatcher.matchesAnyNonEmptyEntry(s, filterLine, registries)) {
                sum += s.getCount();
            }
        }
        return sum;
    }

    /**
     * Walks allow lines in order; returns headroom on the first matching line that still accepts inserts. When line
     * {@code i} is saturated, line {@code i+1} is evaluated if the template matches it.
     */
    public static int maxAdditionalInsertAcrossAllowLines(
            IItemHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            ItemStack template,
            @Nullable List<ItemStack> priorPending,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null || allowLines == null || caps == null) {
            return Integer.MAX_VALUE;
        }
        boolean anyPositiveCap = hasAnyPositiveAllowCapOnNonEmptyLine(allowLines, caps);
        if (!anyPositiveCap) {
            return Integer.MAX_VALUE;
        }
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (!DuctFilterMatcher.matchesAnyNonEmptyEntry(template, line.trim(), registries)) {
                continue;
            }
            int add =
                    maxAdditionalInsertForAllowLineAtIndex(
                            handler, allowLines, caps, template, i, priorPending, registries);
            if (add == Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (add > 0) {
                return add;
            }
        }
        return 0;
    }

    /**
     * Max extract while respecting keep caps, walking lines in order (first matching line with extractable headroom).
     */
    public static int maxExtractRespectingKeepAcrossLines(
            IItemHandler handler,
            List<String> allowLines,
            List<Integer> keepCaps,
            ItemStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handler == null || allowLines == null || keepCaps == null) {
            return Integer.MAX_VALUE;
        }
        if (!hasAnyPositiveKeepOnNonEmptyLine(allowLines, keepCaps)) {
            return Integer.MAX_VALUE;
        }
        boolean matchedAnyLine = false;
        for (int i = 0; i < allowLines.size(); i++) {
            String line = allowLines.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (!DuctFilterMatcher.matchesAnyNonEmptyEntry(template, line.trim(), registries)) {
                continue;
            }
            matchedAnyLine = true;
            int ex =
                    maxExtractRespectingKeepAtIndex(handler, allowLines, keepCaps, template, i, registries);
            if (ex == Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (ex > 0) {
                return ex;
            }
        }
        if (!matchedAnyLine) {
            return Integer.MAX_VALUE;
        }
        return 0;
    }

    /**
     * Max items that may still be inserted for {@code template} without exceeding the allow-line {@code limit}
     * (first matching line). Uses the real attached inventory {@code handler} plus only in-flight stacks in
     * {@code priorPending} that match that same filter line (not other item types).
     */
    public static int maxAdditionalInsertForAllowLine(
            IItemHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            ItemStack template,
            @Nullable List<ItemStack> priorPending,
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
        int current =
                countMatchingInHandler(handler, line, registries, true)
                        + countPendingMatchingFilterLine(priorPending, line, registries);
        return Math.max(0, lim - current);
    }

    /**
     * Max items extractable for {@code template} while leaving at least {@code keep} matching the first winning allow
     * line ({@code keep <= 0} = unlimited).
     */
    public static int maxExtractRespectingKeep(
            IItemHandler handler,
            List<String> allowLines,
            List<Integer> caps,
            ItemStack template,
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
        int current = countMatchingInHandler(handler, line, registries, false);
        return Math.max(0, current - keep);
    }
}
