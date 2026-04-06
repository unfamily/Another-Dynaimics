package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class DuctDefinitionLoader extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static final String DECLARE_TYPE = "another_dynamics:declare_duct";

    public DuctDefinitionLoader() {
        super(GSON, "load");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
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
            String logicalId = DuctIds.normalizeLogicalId(o.get("id").getAsString());
            List<String> kinds = new ArrayList<>();
            Optional<DuctItemTransportSpec> itemTransport = Optional.empty();
            if (o.has("can_transport") && o.get("can_transport").isJsonArray()) {
                for (JsonElement t : o.getAsJsonArray("can_transport")) {
                    if (!t.isJsonObject()) {
                        continue;
                    }
                    JsonObject to = t.getAsJsonObject();
                    if (to.has("dec")) {
                        kinds.add(to.get("dec").getAsString());
                    }
                    if (to.has("dec") && "item".equals(to.get("dec").getAsString())) {
                        itemTransport = Optional.of(parseItemTransport(to));
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
            }
            out.put(
                    e.getKey(),
                    new DuctDefinition(
                            e.getKey(),
                            logicalId,
                            List.copyOf(kinds),
                            itemTransport,
                            defaultTexture,
                            modelDefault,
                            modelLine,
                            putInCreativeMenu,
                            sound));
        }
        DuctDefinitionRegistry.replaceAll(out);
        AnotherDynamicsMod.LOGGER.info("Loaded {} duct definition(s) from data/*/load", out.size());
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
        int speedMin = 1;
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
        if (to.has("filter") && to.get("filter").isJsonObject()) {
            JsonObject f = to.getAsJsonObject("filter");
            if (f.has("allow")) {
                allow = f.get("allow").getAsInt();
            }
            if (f.has("deny")) {
                deny = f.get("deny").getAsInt();
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
                Math.max(0, deny));
    }
}
