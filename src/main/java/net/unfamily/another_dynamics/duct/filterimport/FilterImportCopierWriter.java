package net.unfamily.another_dynamics.duct.filterimport;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFilterLineReorder;
import net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.DuctFilterListSnapshot;
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;

import org.jetbrains.annotations.Nullable;

/** Writes FILTER-mode copier snapshots from imported line lists. */
public final class FilterImportCopierWriter {
    private FilterImportCopierWriter() {}

    public static void optimizeImportedLines(
            List<String> lines, List<Integer> caps, HolderLookup.Provider registries) {
        optimizeImportedLines(lines, caps, null, registries);
    }

    public static void optimizeImportedLines(
            List<String> lines,
            List<Integer> caps,
            List<Integer> concatChannels,
            HolderLookup.Provider registries) {
        optimizeImportedLines(lines, caps, concatChannels, null, registries);
    }

    public static void optimizeImportedLines(
            List<String> lines,
            List<Integer> caps,
            List<Integer> concatChannels,
            List<DuctDirectionalEndpoint> destinations,
            HolderLookup.Provider registries) {
        if (lines == null || lines.size() < 2) {
            return;
        }
        List<String> deny = new ArrayList<>();
        List<Integer> denyCaps = new ArrayList<>();
        List<Integer> denyConcat = new ArrayList<>();
        DuctFilterLineReorder.sortAllowDenyRows(
                lines,
                deny,
                caps,
                denyCaps,
                registries,
                null,
                concatChannels,
                denyConcat,
                destinations,
                null);
    }

    public static ItemStack writeFilterCopier(
            ItemStack base,
            List<String> lines,
            String customName,
            FilterImportChannel channel,
            HolderLookup.Provider registries) {
        return writeFilterCopier(base, lines, null, customName, channel, registries);
    }

    public static ItemStack writeFilterCopier(
            ItemStack base,
            List<String> lines,
            List<Integer> concatChannels,
            String customName,
            FilterImportChannel channel,
            HolderLookup.Provider registries) {
        return writeFilterCopier(base, lines, concatChannels, null, customName, channel, registries);
    }

    public static ItemStack writeFilterCopier(
            ItemStack base,
            List<String> lines,
            List<Integer> concatChannels,
            List<@Nullable DuctDirectionalEndpoint> destinations,
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
        List<Integer> mutableConcat =
                concatChannels != null ? new ArrayList<>(concatChannels) : new ArrayList<>();
        while (mutableConcat.size() < mutableLines.size()) {
            mutableConcat.add(0);
        }
        List<DuctDirectionalEndpoint> mutableRemote =
                destinations != null ? new ArrayList<>(destinations) : new ArrayList<>();
        DuctFilterRemoteNodeLogic.syncToLineSize(mutableRemote, mutableLines.size());
        optimizeImportedLines(mutableLines, caps, mutableConcat, mutableRemote, registries);

        FilterListMaterialKind kind = FilterListMaterialKind.fromImportChannel(channel);
        var snap =
                DuctFilterListSnapshot.buildPortableAllowList(
                        mutableLines, caps, mutableConcat, mutableRemote, kind);

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
