package net.unfamily.another_dynamics.duct.filterimport;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctFilterLineReorder;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.DuctFilterListSnapshot;
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;

/** Writes FILTER-mode copier snapshots from imported line lists. */
public final class FilterImportCopierWriter {
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
            FilterImportChannel channel,
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

        FilterListMaterialKind kind = FilterListMaterialKind.fromImportChannel(channel);
        var snap = DuctFilterListSnapshot.buildPortableAllowList(mutableLines, caps, kind);

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
