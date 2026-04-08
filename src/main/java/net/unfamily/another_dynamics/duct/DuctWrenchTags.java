package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Convention tag for items that act as a duct wrench ({@code #c:tools/wrench}).
 */
public final class DuctWrenchTags {
    public static final TagKey<Item> WRENCH = ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    private DuctWrenchTags() {}
}
