package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Sorts filter GUI rows: concatenation groups first (A, B, …), then None rows by specificity.
 */
public final class DuctFilterLineReorder {
    private record SortRow(String line, int cap, int cap2, int concatCh, int weight, int origIndex) {}

    private DuctFilterLineReorder() {}

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries) {
        sortAllowDenyRows(allowLines, denyLines, allowCaps, denyCaps, registries, null, null, null);
    }

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries,
            @Nullable List<Integer> allowCaps2) {
        sortAllowDenyRows(allowLines, denyLines, allowCaps, denyCaps, registries, allowCaps2, null, null);
    }

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries,
            @Nullable List<Integer> allowCaps2,
            @Nullable List<Integer> allowConcat,
            @Nullable List<Integer> denyConcat) {
        sortLines(allowLines, allowCaps, allowCaps2, allowConcat, registries);
        sortLines(denyLines, denyCaps, null, denyConcat, registries);
    }

    private static void sortLines(
            List<String> lines,
            List<Integer> caps,
            @Nullable List<Integer> caps2,
            @Nullable List<Integer> concat,
            HolderLookup.Provider registries) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        if (concat != null) {
            FilterConcatChannel.syncToLineSize(concat, lines.size());
        }
        ArrayList<SortRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int cap = caps != null && i < caps.size() ? caps.get(i) : 0;
            int cap2 = caps2 != null && i < caps2.size() ? caps2.get(i) : 0;
            int ch = FilterConcatChannel.channelAt(concat, i);
            rows.add(
                    new SortRow(
                            line == null ? "" : line,
                            cap,
                            cap2,
                            ch,
                            weightForLine(line, registries),
                            i));
        }
        Map<Integer, List<SortRow>> concatGroups = new LinkedHashMap<>();
        ArrayList<SortRow> noneRows = new ArrayList<>();
        for (SortRow r : rows) {
            if (r.concatCh() > 0) {
                concatGroups.computeIfAbsent(r.concatCh(), k -> new ArrayList<>()).add(r);
            } else {
                noneRows.add(r);
            }
        }
        noneRows.sort(Comparator.comparingInt(SortRow::weight).thenComparing(SortRow::line));
        ArrayList<SortRow> ordered = new ArrayList<>(rows.size());
        for (int ch = 1; ch <= FilterConcatChannel.MAX_LETTER; ch++) {
            List<SortRow> group = concatGroups.get(ch);
            if (group == null || group.isEmpty()) {
                continue;
            }
            group.sort(Comparator.comparingInt(SortRow::origIndex));
            ordered.addAll(group);
        }
        ordered.addAll(noneRows);
        applySorted(lines, caps, caps2, concat, ordered);
    }

    private static void applySorted(
            List<String> lines,
            @Nullable List<Integer> caps,
            @Nullable List<Integer> caps2,
            @Nullable List<Integer> concat,
            List<SortRow> ordered) {
        ArrayList<String> sortedLines = new ArrayList<>(ordered.size());
        ArrayList<Integer> sortedCaps = caps != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<Integer> sortedCaps2 = caps2 != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<Integer> sortedConcat = concat != null ? new ArrayList<>(ordered.size()) : null;
        for (SortRow r : ordered) {
            sortedLines.add(r.line());
            if (sortedCaps != null) {
                sortedCaps.add(r.cap());
            }
            if (sortedCaps2 != null) {
                sortedCaps2.add(r.cap2());
            }
            if (sortedConcat != null) {
                sortedConcat.add(r.concatCh());
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
        if (concat != null && sortedConcat != null) {
            concat.clear();
            concat.addAll(sortedConcat);
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
