package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            String logicalId = o.get("id").getAsString();
            List<String> kinds = new ArrayList<>();
            if (o.has("can_transport") && o.get("can_transport").isJsonArray()) {
                for (JsonElement t : o.getAsJsonArray("can_transport")) {
                    if (t.isJsonObject() && t.getAsJsonObject().has("dec")) {
                        kinds.add(t.getAsJsonObject().get("dec").getAsString());
                    }
                }
            }
            ResourceLocation defaultTexture = ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID,
                    "block/duct/item/item_duct_0_light");
            if (o.has("rendering") && o.get("rendering").isJsonObject()) {
                JsonObject r = o.getAsJsonObject("rendering");
                if (r.has("default_texture")) {
                    defaultTexture = ResourceLocation.parse(r.get("default_texture").getAsString());
                }
            }
            out.put(e.getKey(), new DuctDefinition(e.getKey(), logicalId, List.copyOf(kinds), defaultTexture));
        }
        DuctDefinitionRegistry.replaceAll(out);
        AnotherDynamicsMod.LOGGER.info("Loaded {} duct definition(s) from data/*/load", out.size());
    }
}
