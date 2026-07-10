package net.unfamily.another_dynamics.duct.filterimport;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Pluggable import from another mod's filter carrier item (import-only). */
public interface FilterImportSource {
    Identifier id();

    boolean canImport(ItemStack stack, HolderLookup.Provider registries);

    List<FilterImportChannel> channels(ItemStack stack, HolderLookup.Provider registries);

    FilterImportPreview preview(
            ItemStack stack, FilterImportChannel channel, HolderLookup.Provider registries);
}
