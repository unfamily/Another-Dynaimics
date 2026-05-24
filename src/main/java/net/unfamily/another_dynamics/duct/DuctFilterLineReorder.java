package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Sorts filter GUI rows by specificity (item id &gt; tag &gt; mod &gt; other), inspired by ISK Utils Deep Drawer ordering.
 */
public final class DuctFilterLineReorder {
    private DuctFilterLineReorder() {}

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries) {
        sortAllowDenyRows(allowLines, denyLines, allowCaps, denyCaps, registries, null);
    }

    /** @param allowCaps2 optional per-allow-line secondary caps (FILTER keep); reordered with allow rows when set. */
    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries,
            @Nullable List<Integer> allowCaps2) {
        sortLines(allowLines, allowCaps, allowCaps2, registries);
        sortLines(denyLines, denyCaps, null, registries);
    }

    private static void sortLines(
            List<String> lines,
            List<Integer> caps,
            @Nullable List<Integer> caps2,
            HolderLookup.Provider registries) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        record Row(String line, int cap, int cap2, int weight) {}
        ArrayList<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int cap = caps != null && i < caps.size() ? caps.get(i) : 0;
            int cap2 = caps2 != null && i < caps2.size() ? caps2.get(i) : 0;
            rows.add(new Row(line == null ? "" : line, cap, cap2, weightForLine(line, registries)));
        }
        rows.sort(Comparator.comparingInt(Row::weight).thenComparing(Row::line));
        ArrayList<String> sortedLines = new ArrayList<>(rows.size());
        ArrayList<Integer> sortedCaps = caps != null ? new ArrayList<>(rows.size()) : null;
        ArrayList<Integer> sortedCaps2 = caps2 != null ? new ArrayList<>(rows.size()) : null;
        for (Row r : rows) {
            sortedLines.add(r.line());
            if (sortedCaps != null) {
                sortedCaps.add(r.cap());
            }
            if (sortedCaps2 != null) {
                sortedCaps2.add(r.cap2());
            }
        }
        lines.clear();
        lines.addAll(sortedLines);
        if (caps != null && sortedCaps != null) {
            caps.clear();
            caps.addAll(sortedCaps);
        }
        if (caps2 != null && sortedCaps2 != null) {
            caps2.clear();
            caps2.addAll(sortedCaps2);
        }
    }

    /** Lower weight = earlier in list (more specific). */
    private static int weightForLine(String line, HolderLookup.Provider registries) {
        if (line == null || line.trim().isEmpty()) {
            return 10_000;
        }
        String t = line.trim();
        if (t.startsWith("#")) {
            return 100;
        }
        if (t.startsWith("@")) {
            return 200;
        }
        ResourceLocation id = ResourceLocation.tryParse(t);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return 0;
        }
        return 500;
    }
}
