package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        sortLines(allowLines, allowCaps, registries);
        sortLines(denyLines, denyCaps, registries);
    }

    private static void sortLines(List<String> lines, List<Integer> caps, HolderLookup.Provider registries) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        record Row(String line, int cap, int weight) {}
        ArrayList<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int cap = caps != null && i < caps.size() ? caps.get(i) : 0;
            rows.add(new Row(line == null ? "" : line, cap, weightForLine(line, registries)));
        }
        rows.sort(Comparator.comparingInt(Row::weight).thenComparing(Row::line));
        lines.clear();
        if (caps != null) {
            caps.clear();
        }
        for (Row r : rows) {
            lines.add(r.line());
            if (caps != null) {
                caps.add(r.cap());
            }
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
