package net.unfamily.another_dynamics.duct;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.integration.mekanism.MekanismHeatCompat;

/**
 * Loads duct declarations from {@code data/&lt;namespace&gt;/load/*.json} on server and client reload. Uses
 * {@link ResourceManager#listResourceStacks} so overrides from high-priority packs (including injected packs) win, same
 * pattern as Colossal Reactors {@code load/} data.
 */
public final class DuctDefinitionLoader implements PreparableReloadListener {
    private static final Gson GSON = new Gson();
    private static final String LOAD_FOLDER = "load";
    private static final String DECLARE_TYPE = "another_dynamics:declare_duct";

    public DuctDefinitionLoader() {}

    @Override
    public String getName() {
        return AnotherDynamicsMod.MOD_ID + ":duct_definitions";
    }

    @Override
    public CompletableFuture<Void> reload(
            PreparableReloadListener.PreparationBarrier stage,
            ResourceManager resourceManager,
            ProfilerFiller prepareProfiler,
            ProfilerFiller applyProfiler,
            Executor prepareExecutor,
            Executor applyExecutor) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            prepareProfiler.push(getName());
                            Map<ResourceLocation, JsonElement> prepared = collectLoadJson(resourceManager);
                            prepareProfiler.pop();
                            return prepared;
                        },
                        prepareExecutor)
                .thenCompose(stage::wait)
                .thenAcceptAsync(
                        prepared -> {
                            applyProfiler.push(getName());
                            tryApplyPrepared(prepared);
                            applyProfiler.pop();
                        },
                        applyExecutor);
    }

    /**
     * Synchronously loads all duct definitions from the given resource manager into the registry. Used when model
     * baking runs before the normal reload listener apply order.
     */
    public static void loadEager(ResourceManager resourceManager) {
        tryApplyPrepared(collectLoadJson(resourceManager));
    }

    static Map<ResourceLocation, JsonElement> collectLoadJson(ResourceManager resourceManager) {
        Map<ResourceLocation, JsonElement> prepared = new HashMap<>();
        Map<ResourceLocation, List<Resource>> stacks =
                resourceManager.listResourceStacks(LOAD_FOLDER, rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, List<Resource>> entry : stacks.entrySet()) {
            ResourceLocation fileRl = entry.getKey();
            List<Resource> stack = entry.getValue();
            if (stack.isEmpty()) {
                continue;
            }
            Resource resource = stack.getLast();
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
                if (parsed != null) {
                    prepared.put(stripLoadJsonKey(fileRl), parsed);
                }
            } catch (Exception ex) {
                AnotherDynamicsMod.LOGGER.error(
                        "Failed to parse duct load file {} ({}): {}",
                        fileRl,
                        resource.sourcePackId(),
                        ex.getMessage());
            }
        }
        return prepared;
    }

    /** Path {@code <ns>:load/foo.json} → definition map key {@code <ns>:foo} (matches legacy SimpleJson listener). */
    static ResourceLocation stripLoadJsonKey(ResourceLocation fileRl) {
        String path = fileRl.getPath();
        String prefix = LOAD_FOLDER + "/";
        String body = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        String withoutJson = body.endsWith(".json") ? body.substring(0, body.length() - ".json".length()) : body;
        return ResourceLocation.fromNamespaceAndPath(fileRl.getNamespace(), withoutJson);
    }

    private static void tryApplyPrepared(Map<ResourceLocation, JsonElement> prepared) {
        Map<ResourceLocation, DuctDefinition> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : prepared.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            if (!o.has("type") || !DECLARE_TYPE.equals(o.get("type").getAsString())) {
                continue;
            }
            if (!o.has("id")) {
                AnotherDynamicsMod.LOGGER.warn("Skipping duct load entry {}: missing id", e.getKey());
                continue;
            }
            String logicalId = DuctIds.normalize(o.get("id").getAsString());
            Optional<String> translationKey = Optional.empty();
            if (o.has("name")) {
                String s = o.get("name").getAsString();
                if (s != null && !s.isBlank()) {
                    translationKey = Optional.of(s.trim());
                }
            }
            List<String> kinds = new ArrayList<>();
            Optional<DuctItemTransportSpec> itemTransport = Optional.empty();
            Optional<DuctFluidTransportSpec> fluidTransport = Optional.empty();
            Optional<DuctGasTransportSpec> gasTransport = Optional.empty();
            Optional<DuctEnergyTransportSpec> energyTransport = Optional.empty();
            Optional<DuctHeatTransportSpec> heatTransport = Optional.empty();
            if (o.has("can_transport") && o.get("can_transport").isJsonArray()) {
                for (JsonElement t : o.getAsJsonArray("can_transport")) {
                    if (!t.isJsonObject()) {
                        continue;
                    }
                    JsonObject to = t.getAsJsonObject();
                    if (to.has("dec")) {
                        String dec = to.get("dec").getAsString();
                        DuctTransportKind kind = DuctTransportKind.fromJsonDec(dec);
                        if (kind == null) {
                            AnotherDynamicsMod.LOGGER.warn(
                                    "Duct '{}' ({}): unknown can_transport dec '{}'", logicalId, e.getKey(), dec);
                            continue;
                        }
                        if (kind == DuctTransportKind.HEAT && !MekanismHeatCompat.isHeatCapabilityAvailable()) {
                            AnotherDynamicsMod.LOGGER.warn(
                                    "Duct '{}' ({}): can_transport '{}' needs Mekanism heat API; skipping entry",
                                    logicalId,
                                    e.getKey(),
                                    dec);
                            continue;
                        }
                        kinds.add(dec);
                        switch (kind) {
                            case ITEM -> itemTransport = Optional.of(parseItemTransport(to));
                            case FLUID -> fluidTransport = Optional.of(parseFluidTransport(to));
                            case GAS -> gasTransport = Optional.of(parseGasTransport(to));
                            case ENERGY -> energyTransport = Optional.of(parseEnergyTransport(to));
                            case HEAT -> heatTransport = Optional.of(parseHeatTransport(to));
                        }
                    }
                }
            }
            ResourceLocation defaultTexture = ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID,
                    "block/duct/item/item_duct_0_light");
            boolean putInCreativeMenu = o.has("put_in_creative_menu") && o.get("put_in_creative_menu").getAsBoolean();
            Optional<String> sound = Optional.empty();
            if (o.has("sound")) {
                String s = o.get("sound").getAsString();
                if (!s.isBlank()) {
                    sound = Optional.of(s.trim());
                }
            }
            Optional<ResourceLocation> modelDefault = Optional.empty();
            Optional<ResourceLocation> modelLine = Optional.empty();
            boolean alwaysOpaqueRendering = false;
            if (o.has("rendering") && o.get("rendering").isJsonObject()) {
                JsonObject r = o.getAsJsonObject("rendering");
                if (r.has("default_texture")) {
                    defaultTexture = ResourceLocation.parse(r.get("default_texture").getAsString());
                }
                if (r.has("model_default")) {
                    modelDefault = Optional.of(ResourceLocation.parse(r.get("model_default").getAsString()));
                }
                if (r.has("model_line")) {
                    modelLine = Optional.of(ResourceLocation.parse(r.get("model_line").getAsString()));
                }
                if (r.has("always_opaque") && r.get("always_opaque").isJsonPrimitive()) {
                    alwaysOpaqueRendering = r.get("always_opaque").getAsBoolean();
                }
            }
            JsonObject restrictionsRoot = resolveRestrictionsObject(o);
            boolean connectWithCompatible =
                    readBooleanWithAliases(
                            restrictionsRoot,
                            true,
                            "connect_with_compatible",
                            "connect_with_compatbile");
            Set<String> disabledFeatures = readStringFeatureSet(restrictionsRoot, "disabled_features", "disabled_feathures");
            Set<String> forbiddenFeatures =
                    readStringFeatureSet(restrictionsRoot, "forbidden_features", "upgrade_features");
            warnUnknownFeatureKeys(logicalId, disabledFeatures, forbiddenFeatures);
            int upgradeSlots = 5;
            if (o.has("upgrade_slots") && o.get("upgrade_slots").isJsonPrimitive()) {
                try {
                    upgradeSlots = o.get("upgrade_slots").getAsInt();
                } catch (NumberFormatException ignored) {
                    upgradeSlots = 5;
                }
            }
            upgradeSlots = DuctGuiLayout.clampUpgradeSlotCount(upgradeSlots);
            out.put(
                    e.getKey(),
                    new DuctDefinition(
                            e.getKey(),
                            logicalId,
                            translationKey,
                            List.copyOf(kinds),
                            itemTransport,
                            fluidTransport,
                            gasTransport,
                            energyTransport,
                            heatTransport,
                            defaultTexture,
                            modelDefault,
                            modelLine,
                            putInCreativeMenu,
                            sound,
                            connectWithCompatible,
                            disabledFeatures,
                            forbiddenFeatures,
                            alwaysOpaqueRendering,
                            upgradeSlots));
        }

        if (out.isEmpty() && !DuctDefinitionRegistry.all().isEmpty()) {
            AnotherDynamicsMod.LOGGER.warn(
                    "Duct load reload found no declare_duct JSON entries; keeping {} prior definition(s). "
                            + "Check that data packs still expose data/*/load/*.json .",
                    DuctDefinitionRegistry.all().size());
            return;
        }

        DuctDefinitionRegistry.replaceAll(out);
        NeoForge.EVENT_BUS.post(new DuctDefinitionsReloadedEvent());
        AnotherDynamicsMod.LOGGER.info("Loaded {} duct definition(s) from data/*/load (resource stacks)", out.size());
    }

    private static DuctItemTransportSpec parseItemTransport(JsonObject to) {
        int batchDefault = 8;
        int batchMax = DuctItemTransportSpec.UNLIMITED_BATCH;
        if (to.has("batch") && to.get("batch").isJsonObject()) {
            JsonObject b = to.getAsJsonObject("batch");
            if (b.has("default")) {
                batchDefault = b.get("default").getAsInt();
            } else if (b.has("deafault")) {
                batchDefault = b.get("deafault").getAsInt();
            }
            if (b.has("max")) {
                batchMax = b.get("max").getAsInt();
            }
        }
        int rateDefault = 10;
        int rateMin = 1;
        if (to.has("rate") && to.get("rate").isJsonObject()) {
            JsonObject r = to.getAsJsonObject("rate");
            if (r.has("default")) {
                rateDefault = r.get("default").getAsInt();
            }
            if (r.has("min")) {
                rateMin = r.get("min").getAsInt();
            }
        }
        int speedDefault = 20;
        int speedMin = 0;
        if (to.has("speed") && to.get("speed").isJsonObject()) {
            JsonObject s = to.getAsJsonObject("speed");
            if (s.has("default")) {
                speedDefault = s.get("default").getAsInt();
            }
            if (s.has("min")) {
                speedMin = s.get("min").getAsInt();
            }
        }
        int allow = 3;
        int deny = 3;
        int allowHybrid = allow;
        int denyHybrid = deny;
        if (to.has("filter") && to.get("filter").isJsonObject()) {
            JsonObject f = to.getAsJsonObject("filter");
            if (f.has("allow")) {
                allow = f.get("allow").getAsInt();
            }
            if (f.has("deny")) {
                deny = f.get("deny").getAsInt();
            }
            allowHybrid = allow;
            denyHybrid = deny;
            if (f.has("allow_hybrid")) {
                allowHybrid = f.get("allow_hybrid").getAsInt();
            }
            if (f.has("deny_hybrid")) {
                denyHybrid = f.get("deny_hybrid").getAsInt();
            }
        }
        return new DuctItemTransportSpec(
                Math.max(0, batchDefault),
                batchMax,
                Math.max(1, rateDefault),
                Math.max(1, rateMin),
                Math.max(0, speedDefault),
                Math.max(0, speedMin),
                Math.max(0, allow),
                Math.max(0, deny),
                Math.max(0, allowHybrid),
                Math.max(0, denyHybrid));
    }

    private static DuctGasTransportSpec parseGasTransport(JsonObject to) {
        long batchDefault = 1000;
        long batchMax = DuctGasTransportSpec.UNLIMITED_BATCH;
        int rateDefault = 30;
        int rateMin = 1;
        int speedDefault = 20;
        int speedMin = 0;
        int denySlots = 4;
        int allowSlots = 4;
        int denyHybrid = 2;
        int allowHybrid = 2;

        if (to.has("batch") && to.get("batch").isJsonObject()) {
            JsonObject b = to.getAsJsonObject("batch");
            if (b.has("default")) {
                batchDefault = b.get("default").getAsLong();
            }
            if (b.has("max")) {
                long m = b.get("max").getAsLong();
                batchMax = m < 0 ? DuctGasTransportSpec.UNLIMITED_BATCH : m;
            }
        }
        if (to.has("rate") && to.get("rate").isJsonObject()) {
            JsonObject r = to.getAsJsonObject("rate");
            if (r.has("default")) {
                rateDefault = r.get("default").getAsInt();
            }
            if (r.has("min")) {
                rateMin = r.get("min").getAsInt();
            }
        }
        if (to.has("speed") && to.get("speed").isJsonObject()) {
            JsonObject s = to.getAsJsonObject("speed");
            if (s.has("default")) {
                speedDefault = s.get("default").getAsInt();
            }
            if (s.has("min")) {
                speedMin = s.get("min").getAsInt();
            }
        }
        if (to.has("filter") && to.get("filter").isJsonObject()) {
            JsonObject f = to.getAsJsonObject("filter");
            if (f.has("deny")) {
                denySlots = f.get("deny").getAsInt();
            }
            if (f.has("allow")) {
                allowSlots = f.get("allow").getAsInt();
            }
            if (f.has("deny_hybrid")) {
                denyHybrid = f.get("deny_hybrid").getAsInt();
            }
            if (f.has("allow_hybrid")) {
                allowHybrid = f.get("allow_hybrid").getAsInt();
            }
        }
        boolean moveRadioactive = true;
        if (to.has("move_radioactive") && to.get("move_radioactive").isJsonPrimitive()) {
            moveRadioactive = to.get("move_radioactive").getAsBoolean();
        } else if (to.has("mode_radioactive") && to.get("mode_radioactive").isJsonPrimitive()) {
            // Legacy typo in early datapacks
            moveRadioactive = to.get("mode_radioactive").getAsBoolean();
        }
        return new DuctGasTransportSpec(
                batchDefault,
                batchMax,
                rateDefault,
                rateMin,
                speedDefault,
                speedMin,
                allowSlots,
                denySlots,
                allowHybrid,
                denyHybrid,
                moveRadioactive);
    }

    private static DuctEnergyTransportSpec parseEnergyTransport(JsonObject to) {
        long extract = 1000;
        long transfer = 8000;
        String rayColor = "#e30b28";
        float rayAlpha = DuctEnergyTransportSpec.fallback().rayAlpha();
        if (to.has("extract") && to.get("extract").isJsonPrimitive()) {
            extract = to.get("extract").getAsLong();
        }
        if (to.has("transfer") && to.get("transfer").isJsonPrimitive()) {
            transfer = to.get("transfer").getAsLong();
        } else if (to.has("transfert") && to.get("transfert").isJsonPrimitive()) {
            // Legacy typo in early datapacks
            transfer = to.get("transfert").getAsLong();
        } else if (to.has("transfert") && to.get("transfert").isJsonObject()) {
            // Older shape used in some packs (keep for compatibility)
            JsonObject tr = to.getAsJsonObject("transfert");
            if (tr.has("default")) {
                extract = tr.get("default").getAsLong();
            }
            if (tr.has("max")) {
                transfer = tr.get("max").getAsLong();
            }
        }
        if (to.has("ray_color") && to.get("ray_color").isJsonPrimitive()) {
            String s = to.get("ray_color").getAsString();
            if (s != null && !s.isBlank()) {
                rayColor = s.trim();
            }
        }
        if (to.has("ray_alpha") && to.get("ray_alpha").isJsonPrimitive()) {
            try {
                rayAlpha = to.get("ray_alpha").getAsFloat();
            } catch (NumberFormatException ignored) {
            }
        }
        return new DuctEnergyTransportSpec(extract, transfer, rayColor, rayAlpha);
    }

    private static DuctHeatTransportSpec parseHeatTransport(JsonObject to) {
        double extract = DuctHeatTransportSpec.fallback().extract();
        double transfer = DuctHeatTransportSpec.fallback().transfer();
        if (to.has("extract") && to.get("extract").isJsonPrimitive()) {
            extract = to.get("extract").getAsDouble();
        }
        if (to.has("transfer") && to.get("transfer").isJsonPrimitive()) {
            transfer = to.get("transfer").getAsDouble();
        }
        return new DuctHeatTransportSpec(extract, transfer);
    }

    private static DuctFluidTransportSpec parseFluidTransport(JsonObject to) {
        int batchDefault = 1000;
        int batchMax = DuctFluidTransportSpec.UNLIMITED_BATCH_MB;
        if (to.has("batch") && to.get("batch").isJsonObject()) {
            JsonObject b = to.getAsJsonObject("batch");
            if (b.has("default")) {
                batchDefault = b.get("default").getAsInt();
            } else if (b.has("deafault")) {
                batchDefault = b.get("deafault").getAsInt();
            }
            if (b.has("max")) {
                batchMax = b.get("max").getAsInt();
            }
        }
        int rateDefault = 30;
        int rateMin = 1;
        if (to.has("rate") && to.get("rate").isJsonObject()) {
            JsonObject r = to.getAsJsonObject("rate");
            if (r.has("default")) {
                rateDefault = r.get("default").getAsInt();
            }
            if (r.has("min")) {
                rateMin = r.get("min").getAsInt();
            }
        }
        int speedDefault = 20;
        int speedMin = 0;
        if (to.has("speed") && to.get("speed").isJsonObject()) {
            JsonObject s = to.getAsJsonObject("speed");
            if (s.has("default")) {
                speedDefault = s.get("default").getAsInt();
            }
            if (s.has("min")) {
                speedMin = s.get("min").getAsInt();
            }
        }
        int allow = 4;
        int deny = 4;
        int allowHybrid = allow;
        int denyHybrid = deny;
        if (to.has("filter") && to.get("filter").isJsonObject()) {
            JsonObject f = to.getAsJsonObject("filter");
            if (f.has("allow")) {
                allow = f.get("allow").getAsInt();
            }
            if (f.has("deny")) {
                deny = f.get("deny").getAsInt();
            }
            allowHybrid = allow;
            denyHybrid = deny;
            if (f.has("allow_hybrid")) {
                allowHybrid = f.get("allow_hybrid").getAsInt();
            }
            if (f.has("deny_hybrid")) {
                denyHybrid = f.get("deny_hybrid").getAsInt();
            }
        }
        return new DuctFluidTransportSpec(
                Math.max(0, batchDefault),
                batchMax,
                Math.max(1, rateDefault),
                Math.max(1, rateMin),
                Math.max(0, speedDefault),
                Math.max(0, speedMin),
                Math.max(0, allow),
                Math.max(0, deny),
                Math.max(0, allowHybrid),
                Math.max(0, denyHybrid));
    }

    private static JsonObject resolveRestrictionsObject(JsonObject declareRoot) {
        if (declareRoot.has("restrictions") && declareRoot.get("restrictions").isJsonObject()) {
            return declareRoot.getAsJsonObject("restrictions");
        }
        return declareRoot;
    }

    private static boolean readBooleanWithAliases(JsonObject o, boolean defaultValue, String... names) {
        for (String name : names) {
            if (o.has(name) && o.get(name).isJsonPrimitive()) {
                return o.get(name).getAsBoolean();
            }
        }
        return defaultValue;
    }

    private static Set<String> readStringFeatureSet(JsonObject o, String primary, String... aliases) {
        JsonArray arr = null;
        if (o.has(primary) && o.get(primary).isJsonArray()) {
            arr = o.getAsJsonArray(primary);
        } else {
            for (String a : aliases) {
                if (o.has(a) && o.get(a).isJsonArray()) {
                    arr = o.getAsJsonArray(a);
                    break;
                }
            }
        }
        if (arr == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                String s = el.getAsString().trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static void warnUnknownFeatureKeys(String logicalId, Set<String> disabled, Set<String> forbidden) {
        for (String s : disabled) {
            if (!DuctFeatureKeys.looksLikeKnownKey(s)) {
                AnotherDynamicsMod.LOGGER.warn(
                        "Duct '{}' disabled_features contains unknown key '{}'", logicalId, s);
            }
        }
        for (String s : forbidden) {
            if (!DuctFeatureKeys.looksLikeKnownKey(s)) {
                AnotherDynamicsMod.LOGGER.warn(
                        "Duct '{}' forbidden_features contains unknown key '{}'", logicalId, s);
            }
        }
    }
}
