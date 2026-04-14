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

import org.jetbrains.annotations.Nullable;

public final class ModuleDefinitionLoader {
    public static final String DECLARE_TYPE = "another_dynamics:declare_module";
    /** Legacy datapack id before rename. */
    public static final String LEGACY_DECLARE_TYPE = "another_dynamics:declare_upgrade";

    private ModuleDefinitionLoader() {}

    public static void tryApplyPrepared(Map<ResourceLocation, JsonElement> prepared) {
        AnotherDynamicsMod.LOGGER.info("tryApplyPrepared called with {} entries", prepared.size());
        Map<ResourceLocation, ModuleDefinition> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : prepared.entrySet()) {
            if (e.getKey().toString().contains("inc_module")) {
                AnotherDynamicsMod.LOGGER.info("Found increment module entry: {}", e.getKey());
            }
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
            ModuleDefinition.ItemQuantityModifiers iq = parseQuantityFor(o, "item");
            ModuleDefinition.ItemQuantityModifiers ir = parseKeyedQuantityFor(o, "item", "rate");
            ModuleDefinition.ItemQuantityModifiers is = parseKeyedQuantityFor(o, "item", "speed");
            ModuleDefinition.ItemQuantityModifiers fq = parseQuantityFor(o, "fluid");
            ModuleDefinition.ItemQuantityModifiers fr = parseKeyedQuantityFor(o, "fluid", "rate");
            ModuleDefinition.ItemQuantityModifiers fs = parseKeyedQuantityFor(o, "fluid", "speed");
            ModuleDefinition.ItemQuantityModifiers gq = parseQuantityFor(o, "gas");
            ModuleDefinition.ItemQuantityModifiers gr = parseKeyedQuantityFor(o, "gas", "rate");
            ModuleDefinition.ItemQuantityModifiers gs = parseKeyedQuantityFor(o, "gas", "speed");
            ModuleDefinition.ItemQuantityModifiers eq = parseQuantityFor(o, "energy");
            ModuleDefinition.ItemQuantityModifiers er = parseKeyedQuantityFor(o, "energy", "rate");
            ModuleDefinition.ItemQuantityModifiers es = parseKeyedQuantityFor(o, "energy", "speed");
            ModuleDefinition.ItemQuantityModifiers hq = parseQuantityFor(o, "heat");
            ModuleDefinition.ItemQuantityModifiers hr = parseKeyedQuantityFor(o, "heat", "rate");
            ModuleDefinition.ItemQuantityModifiers hs = parseKeyedQuantityFor(o, "heat", "speed");
            ModuleDefinition.FilterSlotModifiers filI = parseFilterFor(o, "item");
            ModuleDefinition.FilterSlotModifiers filF = parseFilterFor(o, "fluid");
            ModuleDefinition.FilterSlotModifiers filG = parseFilterFor(o, "gas");
            List<TagKey<Item>> matchTags = readMatchingItemTags(o);
            ModuleDefinition moduleDef = new ModuleDefinition(
                    id,
                    stack,
                    maxSlots,
                    incompat,
                    incompatActivation,
                    iq,
                    ir,
                    is,
                    fq,
                    fr,
                    fs,
                    gq,
                    gr,
                    gs,
                    eq,
                    er,
                    es,
                    hq,
                    hr,
                    hs,
                    filI,
                    filF,
                    filG,
                    matchTags);
            out.put(id, moduleDef);
            if (eq.multProduct() != 1.0 || er.multProduct() != 1.0 || es.multProduct() != 1.0) {
                AnotherDynamicsMod.LOGGER.info("Loaded module {} with energy modifiers: qty={}, rate={}, speed={}", id, eq.multProduct(), er.multProduct(), es.multProduct());
            }
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

    private static boolean affectFor(JsonObject fo, String kind) {
        if (!fo.has("for") || !fo.get("for").isJsonPrimitive()) {
            return false;
        }
        return kind.equals(fo.get("for").getAsString());
    }

    private static void mergeBatchLikeObject(JsonObject b, boolean[] hasSet, int[] bestSet, int[] addSum, double[] mult) {
        if (b.has("set") && b.get("set").isJsonPrimitive()) {
            try {
                int v = b.get("set").getAsInt();
                if (!hasSet[0] || v > bestSet[0]) {
                    hasSet[0] = true;
                    bestSet[0] = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (b.has("add") && b.get("add").isJsonPrimitive()) {
            try {
                addSum[0] += b.get("add").getAsInt();
            } catch (NumberFormatException ignored) {
            }
        }
        if (b.has("mult") && b.get("mult").isJsonPrimitive()) {
            try {
                mult[0] *= b.get("mult").getAsDouble();
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * {@code affects[].for=<transport>.quantity} (preferred); legacy {@code batch} object is still accepted on item
     * rows.
     */
    private static ModuleDefinition.ItemQuantityModifiers parseQuantityFor(JsonObject root, String transportFor) {
        if (!root.has("affects") || !root.get("affects").isJsonArray()) {
            return ModuleDefinition.ItemQuantityModifiers.none();
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
            if (!affectFor(fo, transportFor)) {
                continue;
            }
            JsonObject q = itemQuantitySection(fo, transportFor);
            if (q == null) {
                AnotherDynamicsMod.LOGGER.warn("No {} found in affects section for transport {}", transportFor.equals("energy") ? "extract" : "quantity", transportFor);
                continue;
            }
            boolean[] hs = {hasSet};
            int[] bs = {setVal};
            int[] as = {addSum};
            double[] m = {mult};
            mergeBatchLikeObject(q, hs, bs, as, m);
            hasSet = hs[0];
            setVal = bs[0];
            addSum = as[0];
            mult = m[0];
        }
        return new ModuleDefinition.ItemQuantityModifiers(hasSet, setVal, addSum, mult);
    }

    @Nullable
    private static JsonObject itemQuantitySection(JsonObject affectForItem, String transportFor) {
        // Energy uses "extract" field like ducts
        if ("energy".equals(transportFor)) {
            if (affectForItem.has("extract") && affectForItem.get("extract").isJsonObject()) {
                return affectForItem.getAsJsonObject("extract");
            }
            return null;
        }
        // Item/fluid/gas use "quantity" or legacy "batch"
        if (affectForItem.has("quantity") && affectForItem.get("quantity").isJsonObject()) {
            return affectForItem.getAsJsonObject("quantity");
        }
        if (affectForItem.has("batch") && affectForItem.get("batch").isJsonObject()) {
            return affectForItem.getAsJsonObject("batch");
        }
        return null;
    }

    private static ModuleDefinition.ItemQuantityModifiers parseKeyedQuantityFor(
            JsonObject root, String transportFor, String key) {
        if (!root.has("affects") || !root.get("affects").isJsonArray()) {
            return ModuleDefinition.ItemQuantityModifiers.none();
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
            if (!affectFor(fo, transportFor)) {
                continue;
            }
            if (!fo.has(key) || !fo.get(key).isJsonObject()) {
                continue;
            }
            boolean[] hs = {hasSet};
            int[] bs = {setVal};
            int[] as = {addSum};
            double[] m = {mult};
            mergeBatchLikeObject(fo.getAsJsonObject(key), hs, bs, as, m);
            hasSet = hs[0];
            setVal = bs[0];
            addSum = as[0];
            mult = m[0];
        }
        return new ModuleDefinition.ItemQuantityModifiers(hasSet, setVal, addSum, mult);
    }

    private static ModuleDefinition.FilterSlotModifiers parseFilterFor(JsonObject root, String transportFor) {
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
            if (!affectFor(fo, transportFor)) {
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
