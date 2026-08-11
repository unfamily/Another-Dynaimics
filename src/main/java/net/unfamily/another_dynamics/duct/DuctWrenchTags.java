package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Convention tags for items that act as a duct wrench.
 * Accepts common variants used across mods: {@code #c:wrench}, {@code #c:wrenches},
 * {@code #c:tools/wrench}, {@code #c:tools/wrenches}.
 */
public final class DuctWrenchTags {
    public static final TagKey<Item> WRENCH = ItemTags.create(Identifier.fromNamespaceAndPath("c", "wrench"));
    public static final TagKey<Item> WRENCHES = ItemTags.create(Identifier.fromNamespaceAndPath("c", "wrenches"));
    public static final TagKey<Item> TOOLS_WRENCH =
            ItemTags.create(Identifier.fromNamespaceAndPath("c", "tools/wrench"));
    public static final TagKey<Item> TOOLS_WRENCHES =
            ItemTags.create(Identifier.fromNamespaceAndPath("c", "tools/wrenches"));

    public static boolean isWrench(net.minecraft.world.item.ItemStack stack) {
        return stack.is(WRENCH) || stack.is(WRENCHES) || stack.is(TOOLS_WRENCH) || stack.is(TOOLS_WRENCHES);
    }

    private DuctWrenchTags() {}
}
