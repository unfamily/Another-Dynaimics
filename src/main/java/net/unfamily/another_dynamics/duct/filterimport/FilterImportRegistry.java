package net.unfamily.another_dynamics.duct.filterimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** First matching {@link FilterImportSource} wins. */
public final class FilterImportRegistry {
    private static final List<FilterImportSource> SOURCES = new ArrayList<>();

    private FilterImportRegistry() {}

    public static void register(FilterImportSource source) {
        SOURCES.add(source);
    }

    public static void clearForTests() {
        SOURCES.clear();
    }

    public static boolean canImport(ItemStack stack, HolderLookup.Provider registries) {
        return findSource(stack, registries).isPresent();
    }

    public static Optional<FilterImportSource> findSource(
            ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        for (FilterImportSource source : SOURCES) {
            if (source.canImport(stack, registries)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    public static List<FilterImportChannel> channels(
            ItemStack stack, HolderLookup.Provider registries) {
        return findSource(stack, registries).map(s -> s.channels(stack, registries)).orElse(List.of());
    }

    public static Optional<FilterImportPreview> preview(
            ItemStack stack, FilterImportChannel channel, HolderLookup.Provider registries) {
        return findSource(stack, registries).map(s -> s.preview(stack, channel, registries));
    }
}
