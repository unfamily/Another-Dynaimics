package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/**
 * Single filter entry matching, ported from DeepDrawerExtractorBlockEntity.matchesFilterEntry (iska_utils).
 *
 * <p>Item macros ({@code &}): {@code enchanted}; {@code damaged} (any damage &gt; 0) or {@code damaged} + operators on
 * {@link ItemStack#getDamageValue()} ({@code =, !=, <, <=, >, >=}).</p>
 */
public final class DuctFilterMatcher {
    private DuctFilterMatcher() {}

    private enum Cmp {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE
    }

    public static boolean matchesFilterEntry(
            ItemStack stack,
            Item item,
            Identifier itemId,
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
            return matchesItemMacro(stack, filter.substring(1));
        }

        if (filter.startsWith("#")) {
            String tagFilter = filter.substring(1);
            try {
                Identifier tagId = Identifier.parse(tagFilter);
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

    private static boolean matchesItemMacro(ItemStack stack, String macro) {
        if (macro.isEmpty()) {
            return false;
        }
        String lower = macro.toLowerCase();
        if (lower.equals("enchanted")) {
            if (stack.isEnchanted()) {
                return true;
            }
            return stack.is(Items.ENCHANTED_BOOK);
        }
        if (lower.startsWith("damaged")) {
            if (!stack.isDamageableItem()) {
                return false;
            }
            String rest = macro.substring("damaged".length()).trim();
            if (rest.isEmpty()) {
                return stack.isDamaged();
            }
            ParsedCmp parsed = parseCmp(rest);
            if (parsed == null) {
                return false;
            }
            int damage = stack.getDamageValue();
            return switch (parsed.cmp) {
                case EQ -> damage == parsed.value;
                case NE -> damage != parsed.value;
                case GT -> damage > parsed.value;
                case GE -> damage >= parsed.value;
                case LT -> damage < parsed.value;
                case LE -> damage <= parsed.value;
            };
        }
        return false;
    }

    private record ParsedCmp(Cmp cmp, int value) {}

    /** Parses operator + integer, e.g. {@code ">=100"} or {@code " = 0"}. */
    private static ParsedCmp parseCmp(String expr) {
        if (expr == null) {
            return null;
        }
        String s = expr.trim();
        if (s.isEmpty()) {
            return null;
        }
        Cmp cmp;
        String rhs;
        if (s.startsWith(">=")) {
            cmp = Cmp.GE;
            rhs = s.substring(2);
        } else if (s.startsWith("<=")) {
            cmp = Cmp.LE;
            rhs = s.substring(2);
        } else if (s.startsWith("!=")) {
            cmp = Cmp.NE;
            rhs = s.substring(2);
        } else if (s.startsWith(">")) {
            cmp = Cmp.GT;
            rhs = s.substring(1);
        } else if (s.startsWith("<")) {
            cmp = Cmp.LT;
            rhs = s.substring(1);
        } else if (s.startsWith("=")) {
            cmp = Cmp.EQ;
            rhs = s.substring(1);
        } else {
            return null;
        }
        try {
            return new ParsedCmp(cmp, Integer.parseInt(rhs.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean matchesAnyNonEmptyEntry(ItemStack stack, String filterLine, HolderLookup.Provider registries) {
        if (filterLine == null || filterLine.trim().isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();
        return matchesFilterEntry(stack, item, itemId, itemIdStr, itemModId, filterLine.trim(), registries);
    }
}
