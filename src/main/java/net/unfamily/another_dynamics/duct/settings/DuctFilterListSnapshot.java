package net.unfamily.another_dynamics.duct.settings;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;

/**
 * Portable single filter list (allow or deny lines + optional caps) for {@link SettingsCopierStoreKind#FILTER}.
 * Bank and allow/deny role of the source list are not stored — paste can target any list.
 */
public final class DuctFilterListSnapshot {
    private static final String KEY_LINES = "Lines";
    private static final String KEY_CAPS = "Caps";
    private static final String KEY_CAPS_KEEP = "CapsKeep";
    private static final String KEY_HAS_KEEP = "HasKeepCaps";
    private static final String KEY_MATERIAL_KIND = "MaterialKind";
    private static final String KEY_ALLOW_CONCAT = "AllowConcat";
    private static final String KEY_DENY_CONCAT = "DenyConcat";

    private DuctFilterListSnapshot() {}

    public static boolean isFilterPayload(CompoundTag tag) {
        return tag != null
                && tag.contains(DuctFaceSettingsSnapshot.KEY_FMT, Tag.TAG_INT)
                && DuctFaceSettingsSnapshot.acceptsSnapshotFormat(tag.getInt(DuctFaceSettingsSnapshot.KEY_FMT))
                && SettingsCopierStoreKind.fromCompound(tag) == SettingsCopierStoreKind.FILTER;
    }

    public static FilterListMaterialKind getMaterialKind(CompoundTag data) {
        if (!isFilterPayload(data)) {
            return FilterListMaterialKind.NONE;
        }
        if (data.contains(KEY_MATERIAL_KIND, Tag.TAG_BYTE)) {
            return FilterListMaterialKind.fromOrdinal(data.getByte(KEY_MATERIAL_KIND));
        }
        return FilterListMaterialKind.NONE;
    }

    /** Portable allow-list snapshot (import / manual build) with explicit material kind. */
    public static CompoundTag buildPortableAllowList(
            List<String> lines, List<Integer> caps, FilterListMaterialKind materialKind) {
        return buildPortableAllowList(lines, caps, null, materialKind);
    }

    public static CompoundTag buildPortableAllowList(
            List<String> lines,
            List<Integer> caps,
            List<Integer> concatChannels,
            FilterListMaterialKind materialKind) {
        List<String> safeLines = lines != null ? new ArrayList<>(lines) : List.of();
        CompoundTag root = new CompoundTag();
        root.putInt(DuctFaceSettingsSnapshot.KEY_FMT, DuctFaceSettingsSnapshot.FORMAT_VERSION);
        root.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.FILTER.toTag());
        root.putByte(KEY_MATERIAL_KIND, (byte) materialKind.ordinal());
        root.put(KEY_LINES, toStringListTag(safeLines));
        List<Integer> safeCaps = caps != null ? new ArrayList<>(caps) : new ArrayList<>();
        syncAllowCapsSize(safeCaps, safeLines.size());
        root.putIntArray(KEY_CAPS, safeCaps.stream().mapToInt(i -> Math.max(0, i)).toArray());
        if (concatChannels != null && !concatChannels.isEmpty()) {
            List<Integer> safeConcat = new ArrayList<>(concatChannels);
            FilterConcatChannel.syncToLineSize(safeConcat, safeLines.size());
            root.putByteArray(KEY_ALLOW_CONCAT, toConcatByteArray(safeConcat));
        }
        return root;
    }

    public static CompoundTag captureList(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, boolean allowList) {
        return captureList(node, bank, allowList, FilterListMaterialKind.ITEM);
    }

    public static CompoundTag captureList(
            DuctFaceNode node,
            DuctFaceNode.FilterBank bank,
            boolean allowList,
            FilterListMaterialKind materialKind) {
        List<String> lines =
                allowList ? new ArrayList<>(node.bankAllowFilters(bank)) : new ArrayList<>(node.bankDenyFilters(bank));
        List<Integer> concat =
                allowList
                        ? new ArrayList<>(node.bankAllowConcatChannels(bank))
                        : new ArrayList<>(node.bankDenyConcatChannels(bank));
        CompoundTag root = new CompoundTag();
        root.putInt(DuctFaceSettingsSnapshot.KEY_FMT, DuctFaceSettingsSnapshot.FORMAT_VERSION);
        root.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.FILTER.toTag());
        root.putByte(KEY_MATERIAL_KIND, (byte) materialKind.ordinal());
        root.put(KEY_LINES, toStringListTag(lines));
        if (concat.stream().anyMatch(v -> v != null && v > 0)) {
            root.putByteArray(
                    allowList ? KEY_ALLOW_CONCAT : KEY_DENY_CONCAT, toConcatByteArray(concat));
        }
        if (allowList) {
            List<Integer> caps = new ArrayList<>(node.bankAllowCaps(bank));
            root.putIntArray(KEY_CAPS, caps.stream().mapToInt(i -> Math.max(0, i)).toArray());
            if (bank == DuctFaceNode.FilterBank.FILTER) {
                List<Integer> keep = new ArrayList<>(node.filterBankKeepCaps());
                root.putBoolean(KEY_HAS_KEEP, true);
                root.putIntArray(KEY_CAPS_KEEP, keep.stream().mapToInt(i -> Math.max(0, i)).toArray());
            }
        }
        return root;
    }

    public static boolean applyToList(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, boolean allowList, CompoundTag data) {
        if (!isFilterPayload(data)) {
            return false;
        }
        List<String> lines = readStringList(data, KEY_LINES);
        List<String> target = allowList ? node.bankAllowFilters(bank) : node.bankDenyFilters(bank);
        target.clear();
        target.addAll(lines);
        List<Integer> concatTarget =
                allowList ? node.bankAllowConcatChannels(bank) : node.bankDenyConcatChannels(bank);
        concatTarget.clear();
        String concatKey = allowList ? KEY_ALLOW_CONCAT : KEY_DENY_CONCAT;
        if (data.contains(concatKey, Tag.TAG_BYTE_ARRAY)) {
            readConcatInto(concatTarget, data.getByteArray(concatKey), lines.size());
        } else {
            FilterConcatChannel.syncToLineSize(concatTarget, lines.size());
        }
        if (allowList) {
            List<Integer> caps = readIntList(data, KEY_CAPS, lines.size());
            List<Integer> targetCaps = node.bankAllowCaps(bank);
            targetCaps.clear();
            for (int c : caps) {
                targetCaps.add(Math.max(0, c));
            }
            syncAllowCapsSize(targetCaps, lines.size());
            if (bank == DuctFaceNode.FilterBank.FILTER && data.getBoolean(KEY_HAS_KEEP)) {
                List<Integer> keep = readIntList(data, KEY_CAPS_KEEP, lines.size());
                List<Integer> targetKeep = node.filterBankKeepCaps();
                targetKeep.clear();
                for (int c : keep) {
                    targetKeep.add(Math.max(0, c));
                }
                syncAllowCapsSize(targetKeep, lines.size());
            }
        }
        return true;
    }

    private static byte[] toConcatByteArray(List<Integer> concat) {
        byte[] arr = new byte[concat.size()];
        for (int i = 0; i < concat.size(); i++) {
            int v = concat.get(i) != null ? concat.get(i) : 0;
            arr[i] = (byte) Math.clamp(v, 0, FilterConcatChannel.MAX_LETTER);
        }
        return arr;
    }

    private static void readConcatInto(List<Integer> target, byte[] arr, int lineCount) {
        target.clear();
        if (arr != null) {
            for (byte b : arr) {
                target.add(Math.clamp(b & 0xFF, 0, FilterConcatChannel.MAX_LETTER));
            }
        }
        FilterConcatChannel.syncToLineSize(target, lineCount);
    }

    private static void syncAllowCapsSize(List<Integer> caps, int allowSize) {
        while (caps.size() < allowSize) {
            caps.add(0);
        }
        while (caps.size() > allowSize) {
            caps.remove(caps.size() - 1);
        }
    }

    private static ListTag toStringListTag(List<String> list) {
        ListTag t = new ListTag();
        for (String s : list) {
            t.add(StringTag.valueOf(s != null ? s : ""));
        }
        return t;
    }

    private static List<String> readStringList(CompoundTag tag, String key) {
        List<String> out = new ArrayList<>();
        if (!tag.contains(key, Tag.TAG_LIST)) {
            return out;
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }

    private static List<Integer> readIntList(CompoundTag tag, String key, int sizeHint) {
        List<Integer> out = new ArrayList<>();
        if (tag.contains(key, Tag.TAG_INT_ARRAY)) {
            for (int v : tag.getIntArray(key)) {
                out.add(Math.max(0, v));
            }
        }
        syncAllowCapsSize(out, sizeHint);
        return out;
    }
}
