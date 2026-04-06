package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Deny/allow list precedence ({@link DuctFaceNode#denyOverridesAllow}) for item transport.
 */
public final class DuctFilterLogic {
    private DuctFilterLogic() {}

    public static boolean passesItemFilters(DuctFaceNode node, ItemStack stack, Level level) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        boolean hasA = hasAnyNonEmpty(node.allowFilters);
        boolean hasD = hasAnyNonEmpty(node.denyFilters);
        if (!hasA && !hasD) {
            return true;
        }

        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();

        boolean A = hasA && matchesAny(node.allowFilters, stack, item, itemId, itemIdStr, itemModId, reg);
        boolean D = hasD && matchesAny(node.denyFilters, stack, item, itemId, itemIdStr, itemModId, reg);

        if (node.denyOverridesAllow) {
            if (D) {
                return false;
            }
            if (hasA && !A) {
                return false;
            }
            return true;
        }
        if (hasA && A) {
            return true;
        }
        if (D) {
            return false;
        }
        if (hasA && !A) {
            return false;
        }
        return true;
    }

    private static boolean hasAnyNonEmpty(List<String> list) {
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(
            List<String> entries,
            ItemStack stack,
            Item item,
            ResourceLocation itemId,
            String itemIdStr,
            String itemModId,
            HolderLookup.Provider registries) {
        for (String raw : entries) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            if (DuctFilterMatcher.matchesFilterEntry(
                    stack, item, itemId, itemIdStr, itemModId, raw.trim(), registries)) {
                return true;
            }
        }
        return false;
    }

    public static int listHash(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String field : list) {
            sb.append(field != null ? field : "");
            sb.append('|');
        }
        return sb.toString().hashCode();
    }
}
