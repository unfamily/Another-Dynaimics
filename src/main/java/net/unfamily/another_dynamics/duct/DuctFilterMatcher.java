package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Single filter entry matching, ported from DeepDrawerExtractorBlockEntity.matchesFilterEntry (iska_utils).
 */
public final class DuctFilterMatcher {
    private DuctFilterMatcher() {}

    public static boolean matchesFilterEntry(
            ItemStack stack,
            Item item,
            ResourceLocation itemId,
            String itemIdStr,
            String itemModId,
            String filter,
            HolderLookup.Provider registries) {
        if (filter == null || filter.isEmpty()) {
            return false;
        }

        if (filter.startsWith("-")) {
            String idFilter = filter.substring(1);
            return itemIdStr.equals(idFilter);
        }

        if (filter.startsWith("@")) {
            String modIdFilter = filter.substring(1);
            return itemModId.startsWith(modIdFilter);
        }

        if (filter.startsWith("&")) {
            String macroFilter = filter.substring(1).toLowerCase();
            return switch (macroFilter) {
                case "enchanted" -> stack.isEnchanted();
                case "damaged" -> stack.isDamaged();
                default -> false;
            };
        }

        if (filter.startsWith("#")) {
            String tagFilter = filter.substring(1);
            try {
                ResourceLocation tagId = ResourceLocation.parse(tagFilter);
                TagKey<Item> itemTag = ItemTags.create(tagId);
                return item.builtInRegistryHolder().is(itemTag);
            } catch (Exception e) {
                return false;
            }
        }

        if (filter.startsWith("?")) {
            String nbtFilter = filter.substring(1);
            try {
                Tag tag = stack.save(registries);
                if (tag instanceof CompoundTag compoundTag) {
                    String nbtString = compoundTag.toString();
                    return nbtString.contains(nbtFilter);
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }

        return itemIdStr.equals(filter);
    }

    public static boolean matchesAnyNonEmptyEntry(ItemStack stack, String filterLine, HolderLookup.Provider registries) {
        if (filterLine == null || filterLine.trim().isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();
        return matchesFilterEntry(stack, item, itemId, itemIdStr, itemModId, filterLine.trim(), registries);
    }
}
