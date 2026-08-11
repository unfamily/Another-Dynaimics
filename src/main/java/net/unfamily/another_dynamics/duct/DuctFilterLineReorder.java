package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;

/**
 * Sorts filter GUI rows: concatenation groups first (A, B, …), then None rows by specificity.
 */
public final class DuctFilterLineReorder {
    private record SortRow(
            String line,
            int cap,
            int cap2,
            int concatCh,
            @Nullable DuctDirectionalEndpoint remote,
            boolean ignoreChannel,
            boolean anyFace,
            int weight,
            int origIndex) {}

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
        sortAllowDenyRows(allowLines, denyLines, allowCaps, denyCaps, registries, allowCaps2, null, null, null, null);
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
        sortAllowDenyRows(
                allowLines, denyLines, allowCaps, denyCaps, registries, allowCaps2, allowConcat, denyConcat, null, null, null, null);
    }

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries,
            @Nullable List<Integer> allowCaps2,
            @Nullable List<Integer> allowConcat,
            @Nullable List<Integer> denyConcat,
            @Nullable List<DuctDirectionalEndpoint> allowRemote,
            @Nullable List<DuctDirectionalEndpoint> denyRemote) {
        sortAllowDenyRows(
                allowLines,
                denyLines,
                allowCaps,
                denyCaps,
                registries,
                allowCaps2,
                allowConcat,
                denyConcat,
                allowRemote,
                denyRemote,
                null,
                null);
    }

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries,
            @Nullable List<Integer> allowCaps2,
            @Nullable List<Integer> allowConcat,
            @Nullable List<Integer> denyConcat,
            @Nullable List<DuctDirectionalEndpoint> allowRemote,
            @Nullable List<DuctDirectionalEndpoint> denyRemote,
            @Nullable List<Boolean> allowRemoteIgnoreChannel,
            @Nullable List<Boolean> denyRemoteIgnoreChannel) {
        sortAllowDenyRows(
                allowLines,
                denyLines,
                allowCaps,
                denyCaps,
                registries,
                allowCaps2,
                allowConcat,
                denyConcat,
                allowRemote,
                denyRemote,
                allowRemoteIgnoreChannel,
                denyRemoteIgnoreChannel,
                null,
                null);
    }

    public static void sortAllowDenyRows(
            List<String> allowLines,
            List<String> denyLines,
            List<Integer> allowCaps,
            List<Integer> denyCaps,
            HolderLookup.Provider registries,
            @Nullable List<Integer> allowCaps2,
            @Nullable List<Integer> allowConcat,
            @Nullable List<Integer> denyConcat,
            @Nullable List<DuctDirectionalEndpoint> allowRemote,
            @Nullable List<DuctDirectionalEndpoint> denyRemote,
            @Nullable List<Boolean> allowRemoteIgnoreChannel,
            @Nullable List<Boolean> denyRemoteIgnoreChannel,
            @Nullable List<Boolean> allowRemoteAnyFace,
            @Nullable List<Boolean> denyRemoteAnyFace) {
        sortLines(
                allowLines,
                allowCaps,
                allowCaps2,
                allowConcat,
                allowRemote,
                allowRemoteIgnoreChannel,
                allowRemoteAnyFace,
                registries);
        sortLines(
                denyLines,
                denyCaps,
                null,
                denyConcat,
                denyRemote,
                denyRemoteIgnoreChannel,
                denyRemoteAnyFace,
                registries);
    }

    private static void sortLines(
            List<String> lines,
            List<Integer> caps,
            @Nullable List<Integer> caps2,
            @Nullable List<Integer> concat,
            @Nullable List<DuctDirectionalEndpoint> remote,
            @Nullable List<Boolean> ignoreChannel,
            @Nullable List<Boolean> anyFace,
            HolderLookup.Provider registries) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        if (concat != null) {
            FilterConcatChannel.syncToLineSize(concat, lines.size());
        }
        if (remote != null) {
            net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic.syncToLineSize(remote, lines.size());
        }
        if (ignoreChannel != null) {
            net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                    ignoreChannel, lines.size());
        }
        if (anyFace != null) {
            net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                    anyFace, lines.size());
        }
        ArrayList<SortRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int cap = caps != null && i < caps.size() ? caps.get(i) : 0;
            int cap2 = caps2 != null && i < caps2.size() ? caps2.get(i) : 0;
            int ch = FilterConcatChannel.channelAt(concat, i);
            DuctDirectionalEndpoint remoteEp = remote != null && i < remote.size() ? remote.get(i) : null;
            boolean ig =
                    ignoreChannel != null
                            && i < ignoreChannel.size()
                            && Boolean.TRUE.equals(ignoreChannel.get(i));
            boolean any =
                    anyFace != null
                            && i < anyFace.size()
                            && Boolean.TRUE.equals(anyFace.get(i));
            rows.add(
                    new SortRow(
                            line == null ? "" : line,
                            cap,
                            cap2,
                            ch,
                            remoteEp,
                            ig,
                            any,
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
        // One &anything_else after other filled lines, before empty rows (extra copies dropped).
        ArrayList<SortRow> anythingElse = new ArrayList<>();
        ArrayList<SortRow> empties = new ArrayList<>();
        ordered.removeIf(
                r -> {
                    if (DuctFilterSpecialKeys.isAnythingElseLine(r.line())) {
                        if (anythingElse.isEmpty()) {
                            anythingElse.add(r);
                        }
                        return true;
                    }
                    if (r.line() == null || r.line().trim().isEmpty()) {
                        empties.add(r);
                        return true;
                    }
                    return false;
                });
        ordered.addAll(anythingElse);
        ordered.addAll(empties);
        applySorted(lines, caps, caps2, concat, remote, ignoreChannel, anyFace, ordered);
    }

    private static void applySorted(
            List<String> lines,
            @Nullable List<Integer> caps,
            @Nullable List<Integer> caps2,
            @Nullable List<Integer> concat,
            @Nullable List<DuctDirectionalEndpoint> remote,
            @Nullable List<Boolean> ignoreChannel,
            @Nullable List<Boolean> anyFace,
            List<SortRow> ordered) {
        ArrayList<String> sortedLines = new ArrayList<>(ordered.size());
        ArrayList<Integer> sortedCaps = caps != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<Integer> sortedCaps2 = caps2 != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<Integer> sortedConcat = concat != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<DuctDirectionalEndpoint> sortedRemote = remote != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<Boolean> sortedIgnore = ignoreChannel != null ? new ArrayList<>(ordered.size()) : null;
        ArrayList<Boolean> sortedAnyFace = anyFace != null ? new ArrayList<>(ordered.size()) : null;
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
            if (sortedRemote != null) {
                sortedRemote.add(r.remote());
            }
            if (sortedIgnore != null) {
                sortedIgnore.add(r.ignoreChannel());
            }
            if (sortedAnyFace != null) {
                sortedAnyFace.add(r.anyFace());
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
        if (remote != null && sortedRemote != null) {
            remote.clear();
            remote.addAll(sortedRemote);
        }
        if (ignoreChannel != null && sortedIgnore != null) {
            ignoreChannel.clear();
            ignoreChannel.addAll(sortedIgnore);
        }
        if (anyFace != null && sortedAnyFace != null) {
            anyFace.clear();
            anyFace.addAll(sortedAnyFace);
        }
    }

    /** Lower weight = earlier in list (more specific). */
    private static int weightForLine(String line, HolderLookup.Provider registries) {
        if (line == null || line.trim().isEmpty()) {
            return 10_000;
        }
        String t = line.trim();
        if (DuctFilterSpecialKeys.isAnythingElseLine(t)) {
            // After other filled lines, before empty rows (empties use 10_000).
            return 1_000;
        }
        if (t.startsWith("#")) {
            return 100;
        }
        if (t.startsWith("@")) {
            return 200;
        }
        Identifier id = Identifier.tryParse(t);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return 0;
        }
        return 500;
    }
}
