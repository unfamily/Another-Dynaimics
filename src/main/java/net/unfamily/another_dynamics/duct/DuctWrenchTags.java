package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Convention tags for items that act as a duct wrench ({@code #c:wrench}, {@code #c:tools/wrench}).
 */
public final class DuctWrenchTags {
    public static final TagKey<Item> WRENCH = ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "wrench"));
    public static final TagKey<Item> TOOLS_WRENCH =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    public static boolean isWrench(net.minecraft.world.item.ItemStack stack) {
        return stack.is(WRENCH) || stack.is(TOOLS_WRENCH);
    }

    private DuctWrenchTags() {}
}
