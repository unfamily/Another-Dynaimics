package net.unfamily.another_dynamics.duct;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.module.ModuleDefinitionLoader;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
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
            PreparableReloadListener.SharedState currentReload,
            Executor taskExecutor,
            PreparableReloadListener.PreparationBarrier preparationBarrier,
            Executor reloadExecutor) {
        ResourceManager resourceManager = currentReload.resourceManager();
        return CompletableFuture.supplyAsync(
                        () -> {
                            ProfilerFiller profiler = Profiler.get();
                            profiler.push(getName());
                            Map<Identifier, JsonElement> prepared = collectLoadJson(resourceManager);
                            profiler.pop();
                            return prepared;
                        },
                        taskExecutor)
                .thenCompose(preparationBarrier::wait)
                .thenAcceptAsync(
                        prepared -> {
                            ProfilerFiller profiler = Profiler.get();
                            profiler.push(getName());
                            tryApplyPrepared(prepared);
                            profiler.pop();
                        },
                        reloadExecutor);
    }

    /**
     * Synchronously loads all duct definitions from the given resource manager into the registry. Used when model
     * baking runs before the normal reload listener apply order.
     */
    public static void loadEager(ResourceManager resourceManager) {
        tryApplyPrepared(collectLoadJson(resourceManager));
    }

    static Map<Identifier, JsonElement> collectLoadJson(ResourceManager resourceManager) {
        Map<Identifier, JsonElement> prepared = new LinkedHashMap<>();
        Map<Identifier, List<Resource>> stacks =
                resourceManager.listResourceStacks(LOAD_FOLDER, rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, List<Resource>> entry : stacks.entrySet()) {
            Identifier fileRl = entry.getKey();
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
        // Client resource reload only sees assets/; data/*/load lives in datapacks. Fall back to mod jar
        // / build resources so definitions exist before model baking (same pattern as Colossal Reactors).
        if (prepared.isEmpty()) {
            prepared.putAll(collectFromModClasspath());
        }
        return prepared;
    }

    /**
     * Reads {@code data/another_dynamics/load/} JSON trees from the mod jar or Gradle resources folder.
     * Keys match {@link #stripLoadJsonKey} output (e.g. {@code another_dynamics:duct/item_duct}).
     */
    static Map<Identifier, JsonElement> collectFromModClasspath() {
        Map<Identifier, JsonElement> out = new LinkedHashMap<>();
        String loadRoot = "data/" + AnotherDynamicsMod.MOD_ID + "/" + LOAD_FOLDER;
        ModList.get().getModContainerById(AnotherDynamicsMod.MOD_ID).ifPresentOrElse(
                container -> {
                    var modFileInfo = container.getModInfo().getOwningFile();
                    if (modFileInfo == null) {
                        AnotherDynamicsMod.LOGGER.warn(
                                "No mod file for {}, cannot bootstrap {}", AnotherDynamicsMod.MOD_ID, loadRoot);
                        return;
                    }
                    Path root = modFileInfo.getFile().getFilePath();
                    try {
                        if (Files.isDirectory(root)) {
                            Path buildDir = root.getParent().getParent().getParent();
                            Path resourcesFolder = buildDir.resolve("resources").resolve("main");
                            Path base = Files.exists(resourcesFolder)
                                    ? resourcesFolder.resolve(loadRoot)
                                    : root.resolve(loadRoot);
                            walkLoadJsonTree(base, out);
                        } else {
                            try (var fs = FileSystems.newFileSystem(root, Map.of())) {
                                walkLoadJsonTree(fs.getPath(loadRoot), out);
                            }
                        }
                    } catch (IOException ex) {
                        AnotherDynamicsMod.LOGGER.error(
                                "Failed walking mod load path {}: {}", loadRoot, ex.getMessage());
                    }
                },
                () -> AnotherDynamicsMod.LOGGER.warn(
                        "Mod container not found for {} during load bootstrap", AnotherDynamicsMod.MOD_ID));
        if (!out.isEmpty()) {
            AnotherDynamicsMod.LOGGER.info(
                    "Bootstrapped {} load JSON file(s) from mod classpath ({})", out.size(), loadRoot);
        }
        return out;
    }

    private static void walkLoadJsonTree(Path base, Map<Identifier, JsonElement> out) throws IOException {
        if (base == null || !Files.exists(base)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(file -> readClasspathLoadJson(base, file, out));
        }
    }

    private static void readClasspathLoadJson(Path loadBase, Path file, Map<Identifier, JsonElement> out) {
        try {
            String rel = loadBase.relativize(file).toString().replace('\\', '/');
            String pathPart = LOAD_FOLDER + "/" + rel;
            Identifier fileId = Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, pathPart);
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
                if (parsed != null) {
                    out.put(stripLoadJsonKey(fileId), parsed);
                }
            }
        } catch (Exception ex) {
            AnotherDynamicsMod.LOGGER.error("Failed reading classpath load file {}: {}", file, ex.getMessage());
        }
    }

    /** Path {@code <ns>:load/foo.json} → definition map key {@code <ns>:foo} (matches legacy SimpleJson listener). */
    static Identifier stripLoadJsonKey(Identifier fileRl) {
        String path = fileRl.getPath();
        String prefix = LOAD_FOLDER + "/";
        String body = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        String withoutJson = body.endsWith(".json") ? body.substring(0, body.length() - ".json".length()) : body;
        return Identifier.fromNamespaceAndPath(fileRl.getNamespace(), withoutJson);
    }

    private static void tryApplyPrepared(Map<Identifier, JsonElement> prepared) {
        Map<Identifier, DuctDefinition> out = new HashMap<>();
        for (Map.Entry<Identifier, JsonElement> e : prepared.entrySet()) {
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
            EnumSet<DuctTransportKind> suppressedMekKinds = EnumSet.noneOf(DuctTransportKind.class);
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
                        if (kind == DuctTransportKind.GAS && !MekanismChemicalCompat.isLoaded()) {
                            suppressedMekKinds.add(DuctTransportKind.GAS);
                            AnotherDynamicsMod.LOGGER.warn(
                                    "Duct '{}' ({}): can_transport '{}' needs Mekanism chemical API; skipping entry",
                                    logicalId,
                                    e.getKey(),
                                    dec);
                            continue;
                        }
                        if (kind == DuctTransportKind.HEAT && !MekanismHeatCompat.isHeatCapabilityAvailable()) {
                            suppressedMekKinds.add(DuctTransportKind.HEAT);
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
            if (kinds.isEmpty()) {
                AnotherDynamicsMod.LOGGER.info(
                        "Duct '{}' ({}): no enabled transport kinds after Mekanism gating; not registering",
                        logicalId,
                        e.getKey());
                continue;
            }
            Identifier defaultTexture = Identifier.fromNamespaceAndPath(
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
            Optional<Identifier> modelDefault = Optional.empty();
            Optional<Identifier> modelLine = Optional.empty();
            boolean alwaysOpaqueRendering = false;
            if (o.has("rendering") && o.get("rendering").isJsonObject()) {
                JsonObject r = o.getAsJsonObject("rendering");
                if (r.has("default_texture")) {
                    defaultTexture = Identifier.parse(r.get("default_texture").getAsString());
                }
                if (r.has("model_default")) {
                    modelDefault = Optional.of(Identifier.parse(r.get("model_default").getAsString()));
                }
                if (r.has("model_line")) {
                    modelLine = Optional.of(Identifier.parse(r.get("model_line").getAsString()));
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
                    readStringFeatureSet(restrictionsRoot, "forbidden_features", "module_features", "upgrade_features");
            warnUnknownFeatureKeys(logicalId, disabledFeatures, forbiddenFeatures);
            int moduleSlots = 5;
            if (o.has("module_slots") && o.get("module_slots").isJsonPrimitive()) {
                try {
                    moduleSlots = o.get("module_slots").getAsInt();
                } catch (NumberFormatException ignored) {
                    moduleSlots = 5;
                }
            } else if (o.has("upgrade_slots") && o.get("upgrade_slots").isJsonPrimitive()) {
                try {
                    moduleSlots = o.get("upgrade_slots").getAsInt();
                } catch (NumberFormatException ignored) {
                    moduleSlots = 5;
                }
            }
            moduleSlots = DuctGuiLayout.clampModuleSlotCount(moduleSlots);
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
                            moduleSlots,
                            Set.copyOf(suppressedMekKinds)));
        }

        ModuleDefinitionLoader.tryApplyPrepared(prepared);

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
        int rateDefault = DuctEnergyTransportSpec.fallback().rateDefaultTicks();
        int rateMin = DuctEnergyTransportSpec.fallback().rateMinTicks();
        String rayColor = "#e30b28";
        float rayAlpha = DuctEnergyTransportSpec.fallback().rayAlpha();
        if (to.has("rate") && to.get("rate").isJsonObject()) {
            JsonObject r = to.getAsJsonObject("rate");
            if (r.has("default")) {
                rateDefault = r.get("default").getAsInt();
            }
            if (r.has("min")) {
                rateMin = r.get("min").getAsInt();
            }
        }
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
        return new DuctEnergyTransportSpec(extract, transfer, rateDefault, rateMin, rayColor, rayAlpha);
    }

    private static DuctHeatTransportSpec parseHeatTransport(JsonObject to) {
        double insulation = DuctHeatTransportSpec.fallback().insulation();
        if (to.has("insulation") && to.get("insulation").isJsonPrimitive()) {
            insulation = to.get("insulation").getAsDouble();
        }
        return new DuctHeatTransportSpec(insulation);
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
                    if ("special:upgrades".equals(s)) {
                        s = "special:modules";
                    }
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
