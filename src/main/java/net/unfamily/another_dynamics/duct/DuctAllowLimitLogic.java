package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Per-allow-line caps: {@code 0} = unlimited. First non-empty allow line that matches an item wins (same index for cap).
 */
public final class DuctAllowLimitLogic {
    private DuctAllowLimitLogic() {}

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
            IItemHandler handlerAfterPending,
            List<String> allowLines,
            List<Integer> caps,
            ItemStack template,
            int lineIndex,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handlerAfterPending == null) {
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
        int current = countMatchingInHandler(handlerAfterPending, line, registries);
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
        int current = countMatchingInHandler(handler, line, registries);
        return Math.max(0, current - keep);
    }

    public static int countMatchingInHandler(
            IItemHandler handler, String filterLine, HolderLookup.Provider registries) {
        if (handler == null || filterLine == null || filterLine.trim().isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
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
     * Max items that may still be inserted for {@code template} without exceeding the allow-line {@code limit}
     * (first matching line). Uses inventory state in {@code handlerAfterPending} (e.g. after simulating in-flight).
     */
    public static int maxAdditionalInsertForAllowLine(
            IItemHandler handlerAfterPending,
            List<String> allowLines,
            List<Integer> caps,
            ItemStack template,
            HolderLookup.Provider registries) {
        if (template.isEmpty() || handlerAfterPending == null) {
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
        int current = countMatchingInHandler(handlerAfterPending, line, registries);
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
        int current = countMatchingInHandler(handler, line, registries);
        return Math.max(0, current - keep);
    }
}
