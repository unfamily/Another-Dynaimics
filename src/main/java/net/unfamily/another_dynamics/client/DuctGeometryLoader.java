package net.unfamily.another_dynamics.client;

import java.util.Optional;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctIds;

public final class DuctGeometryLoader implements IGeometryLoader<DuctUnbakedGeometry> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct");

    @Override
    public DuctUnbakedGeometry read(JsonObject jsonObject, JsonDeserializationContext deserializationContext) throws JsonParseException {
        String ductId = jsonObject.has("duct_id") ? jsonObject.get("duct_id").getAsString() : DuctIds.DEFAULT_LOGICAL_ID;
        return new DuctUnbakedGeometry(ductId, optionalModelId(jsonObject, "model_default"), optionalModelId(jsonObject, "model_line"));
    }

    private static Optional<ResourceLocation> optionalModelId(JsonObject o, String key) {
        if (!o.has(key)) {
            return Optional.empty();
        }
        String s = o.get(key).getAsString();
        if (s.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ResourceLocation.parse(s));
    }
}
