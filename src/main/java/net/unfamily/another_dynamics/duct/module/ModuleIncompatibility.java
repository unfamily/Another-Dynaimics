package net.unfamily.another_dynamics.duct.module;

import net.minecraft.core.HolderLookup;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Parsed {@code incompatible_with} entry: {@code #namespace:tag} or {@code namespace:item}. */
public sealed interface ModuleIncompatibility permits ModuleIncompatibility.TagRef, ModuleIncompatibility.ItemRef {

    boolean matches(ItemStack stack, HolderLookup.Provider registries);

    record TagRef(TagKey<Item> tag) implements ModuleIncompatibility {
        @Override
        public boolean matches(ItemStack stack, HolderLookup.Provider registries) {
            return stack.is(tag);
        }
    }

    record ItemRef(Item item) implements ModuleIncompatibility {
        @Override
        public boolean matches(ItemStack stack, HolderLookup.Provider registries) {
            return stack.is(item);
        }
    }
}
