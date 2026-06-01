package net.unfamily.another_dynamics.duct.filterimport;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctFilterLineReorder;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;

/** Writes FILTER-mode copier snapshots from imported line lists. */
public final class FilterImportCopierWriter {
    private static final String KEY_LINES = "Lines";
    private static final String KEY_CAPS = "Caps";

    private FilterImportCopierWriter() {}

    public static void optimizeImportedLines(
            List<String> lines, List<Integer> caps, HolderLookup.Provider registries) {
        if (lines == null || lines.size() < 2) {
            return;
        }
        List<String> deny = new ArrayList<>();
        List<Integer> denyCaps = new ArrayList<>();
        DuctFilterLineReorder.sortAllowDenyRows(lines, deny, caps, denyCaps, registries);
    }

    public static ItemStack writeFilterCopier(
            ItemStack base,
            List<String> lines,
            String customName,
            HolderLookup.Provider registries) {
        ItemStack out = base.isEmpty() ? new ItemStack(base.getItem()) : base.copy();
        if (!(out.getItem() instanceof SettingsCopierItem)) {
            return ItemStack.EMPTY;
        }
        List<String> mutableLines = new ArrayList<>(lines);
        List<Integer> caps = new ArrayList<>();
        for (int i = 0; i < mutableLines.size(); i++) {
            caps.add(0);
        }
        optimizeImportedLines(mutableLines, caps, registries);

        CompoundTag snap = new CompoundTag();
        snap.putInt(DuctFaceSettingsSnapshot.KEY_FMT, DuctFaceSettingsSnapshot.FORMAT_VERSION);
        snap.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.FILTER.toTag());
        ListTag lineTag = new ListTag();
        for (String s : mutableLines) {
            lineTag.add(StringTag.valueOf(s != null ? s : ""));
        }
        snap.put(KEY_LINES, lineTag);
        snap.putIntArray(KEY_CAPS, caps.stream().mapToInt(v -> v).toArray());

        DuctFaceSettingsSnapshot.writeToCopier(out, snap);
        SettingsCopierStoreKind.setMode(out, SettingsCopierStoreKind.FILTER);
        if (customName != null && !customName.isBlank()) {
            out.set(DataComponents.CUSTOM_NAME, Component.literal(customName.trim()));
        } else {
            out.remove(DataComponents.CUSTOM_NAME);
        }
        return out;
    }
}
