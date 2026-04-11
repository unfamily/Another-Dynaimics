package net.unfamily.another_dynamics.duct.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class ModuleDefinitionLoader {
    public static final String DECLARE_TYPE = "another_dynamics:declare_module";
    /** Legacy datapack id before rename. */
    public static final String LEGACY_DECLARE_TYPE = "another_dynamics:declare_upgrade";

    private ModuleDefinitionLoader() {}

    public static void tryApplyPrepared(Map<ResourceLocation, JsonElement> prepared) {
        Map<ResourceLocation, ModuleDefinition> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : prepared.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            if (!o.has("type")) {
                continue;
            }
            String t = o.get("type").getAsString();
            if (!DECLARE_TYPE.equals(t) && !LEGACY_DECLARE_TYPE.equals(t)) {
                continue;
            }
            if (!o.has("id")) {
                AnotherDynamicsMod.LOGGER.warn("Skipping module load entry {}: missing id", e.getKey());
                continue;
            }
            ResourceLocation id = ResourceLocation.parse(o.get("id").getAsString());
            int stack = readPositiveInt(o, "stack", 64);
            int maxSlots = readPositiveInt(o, "max_slots", 1);
            List<ModuleIncompatibility> incompat = readIncompatibleList(o);
            int incompatActivation = readIncompatibilityActivation(o);
            ModuleDefinition.ItemBatchModifiers batch = parseItemBatchModifiers(o);
            ModuleDefinition.FilterSlotModifiers filterSlots = parseFilterSlotModifiers(o);
            List<TagKey<Item>> matchTags = readMatchingItemTags(o);
            out.put(
                    id,
                    new ModuleDefinition(id, stack, maxSlots, incompat, incompatActivation, batch, filterSlots, matchTags));
        }
        warnDuplicateMatchingTags(out);
        ModuleDefinitionRegistry.replaceAll(out);
        AnotherDynamicsMod.LOGGER.info("Loaded {} duct module definition(s) from data/*/load", out.size());
    }

    private static final String KEY_INCOMPATIBILITY_ACTIVATION = "incompatibility_activation";
    /** Legacy typo in early datapack drafts. */
    private static final String KEY_INCOMPATIBILITY_ACTIVATION_TYPO = "incompatibiliy_activation";

    private static int readIncompatibilityActivation(JsonObject o) {
        if (o.has(KEY_INCOMPATIBILITY_ACTIVATION) && o.get(KEY_INCOMPATIBILITY_ACTIVATION).isJsonPrimitive()) {
            try {
                return Math.max(1, o.get(KEY_INCOMPATIBILITY_ACTIVATION).getAsInt());
            } catch (NumberFormatException ex) {
                return 1;
            }
        }
        if (o.has(KEY_INCOMPATIBILITY_ACTIVATION_TYPO) && o.get(KEY_INCOMPATIBILITY_ACTIVATION_TYPO).isJsonPrimitive()) {
            try {
                return Math.max(1, o.get(KEY_INCOMPATIBILITY_ACTIVATION_TYPO).getAsInt());
            } catch (NumberFormatException ex) {
                return 1;
            }
        }
        return 1;
    }

    private static int readPositiveInt(JsonObject o, String key, int def) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return Math.max(1, o.get(key).getAsInt());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static List<ModuleIncompatibility> readIncompatibleList(JsonObject o) {
        List<ModuleIncompatibility> list = new ArrayList<>();
        if (!o.has("incompatible_with") || !o.get("incompatible_with").isJsonArray()) {
            return list;
        }
        for (JsonElement el : o.getAsJsonArray("incompatible_with")) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            String s = el.getAsString().trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.parse(s.substring(1));
                list.add(new ModuleIncompatibility.TagRef(TagKey.create(Registries.ITEM, tagId)));
            } else {
                ResourceLocation itemId = ResourceLocation.parse(s);
                Item item = BuiltInRegistries.ITEM.get(itemId);
                if (item != null && item != Items.AIR) {
                    list.add(new ModuleIncompatibility.ItemRef(item));
                }
            }
        }
        return list;
    }

    /**
     * Optional {@code matching_item_tags}: array of {@code #namespace:path} item tags; stacks in those tags count as
     * this module when the stack has no {@code duct_module_declaration} (KubeJS-only items, etc.).
     */
    private static List<TagKey<Item>> readMatchingItemTags(JsonObject o) {
        if (!o.has("matching_item_tags") || !o.get("matching_item_tags").isJsonArray()) {
            return List.of();
        }
        List<TagKey<Item>> list = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("matching_item_tags")) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            String s = el.getAsString().trim();
            if (s.isEmpty() || !s.startsWith("#")) {
                continue;
            }
            ResourceLocation tagId = ResourceLocation.parse(s.substring(1));
            list.add(TagKey.create(Registries.ITEM, tagId));
        }
        return List.copyOf(list);
    }

    private static void warnDuplicateMatchingTags(Map<ResourceLocation, ModuleDefinition> out) {
        Map<TagKey<Item>, ResourceLocation> owner = new HashMap<>();
        for (ModuleDefinition def : out.values()) {
            for (TagKey<Item> tag : def.matchingItemTags()) {
                ResourceLocation prev = owner.put(tag, def.id());
                if (prev != null && !prev.equals(def.id())) {
                    AnotherDynamicsMod.LOGGER.warn(
                            "matching_item_tags: item tag {} is used by both {} and {} (first wins at runtime)",
                            tag.location(),
                            prev,
                            def.id());
                }
            }
        }
    }

    private static ModuleDefinition.ItemBatchModifiers parseItemBatchModifiers(JsonObject root) {
        if (!root.has("affects") || !root.get("affects").isJsonArray()) {
            return ModuleDefinition.ItemBatchModifiers.none();
        }
        boolean hasSet = false;
        int setVal = 0;
        int addSum = 0;
        double mult = 1.0;
        for (JsonElement aff : root.getAsJsonArray("affects")) {
            if (!aff.isJsonObject()) {
                continue;
            }
            JsonObject fo = aff.getAsJsonObject();
            if (!fo.has("for") || !"item".equals(fo.get("for").getAsString())) {
                continue;
            }
            if (!fo.has("batch") || !fo.get("batch").isJsonObject()) {
                continue;
            }
            JsonObject b = fo.getAsJsonObject("batch");
            if (b.has("set") && b.get("set").isJsonPrimitive()) {
                int v = b.get("set").getAsInt();
                if (!hasSet || v > setVal) {
                    hasSet = true;
                    setVal = v;
                }
            }
            if (b.has("add") && b.get("add").isJsonPrimitive()) {
                addSum += b.get("add").getAsInt();
            }
            if (b.has("mult") && b.get("mult").isJsonPrimitive()) {
                mult *= b.get("mult").getAsDouble();
            }
        }
        return new ModuleDefinition.ItemBatchModifiers(hasSet, setVal, addSum, mult);
    }

    private static ModuleDefinition.FilterSlotModifiers parseFilterSlotModifiers(JsonObject root) {
        if (!root.has("affects") || !root.get("affects").isJsonArray()) {
            return ModuleDefinition.FilterSlotModifiers.none();
        }
        int allowAdd = 0;
        int denyAdd = 0;
        int allowH = 0;
        int denyH = 0;
        for (JsonElement aff : root.getAsJsonArray("affects")) {
            if (!aff.isJsonObject()) {
                continue;
            }
            JsonObject fo = aff.getAsJsonObject();
            if (!fo.has("for") || !"item".equals(fo.get("for").getAsString())) {
                continue;
            }
            if (!fo.has("filter") || !fo.get("filter").isJsonObject()) {
                continue;
            }
            JsonObject f = fo.getAsJsonObject("filter");
            allowAdd += readFilterKeyAdd(f, "allow");
            denyAdd += readFilterKeyAdd(f, "deny");
            allowH += readFilterKeyAdd(f, "allow_hybrid");
            denyH += readFilterKeyAdd(f, "deny_hybrid");
        }
        return new ModuleDefinition.FilterSlotModifiers(allowAdd, denyAdd, allowH, denyH);
    }

    private static int readFilterKeyAdd(JsonObject filter, String key) {
        if (!filter.has(key) || !filter.get(key).isJsonObject()) {
            return 0;
        }
        JsonObject o = filter.getAsJsonObject(key);
        if (!o.has("add") || !o.get("add").isJsonPrimitive()) {
            return 0;
        }
        try {
            return o.get("add").getAsInt();
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
