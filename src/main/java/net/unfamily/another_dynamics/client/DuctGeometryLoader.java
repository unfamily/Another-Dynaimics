package net.unfamily.another_dynamics.client;

import java.util.Optional;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.StandardModelParameters;
import net.neoforged.neoforge.client.model.UnbakedModelLoader;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctIds;

public final class DuctGeometryLoader implements UnbakedModelLoader<DuctUnbakedModel> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct");

    @Override
    public DuctUnbakedModel read(JsonObject jsonObject, JsonDeserializationContext deserializationContext)
            throws JsonParseException {
        String ductId = jsonObject.has("duct_id") ? jsonObject.get("duct_id").getAsString() : DuctIds.DEFAULT_LOGICAL_ID;
        StandardModelParameters parameters = StandardModelParameters.parse(jsonObject, deserializationContext);
        return new DuctUnbakedModel(
                parameters, ductId, optionalModelId(jsonObject, "model_default"), optionalModelId(jsonObject, "model_line"));
    }

    private static Optional<Identifier> optionalModelId(JsonObject o, String key) {
        if (!o.has(key)) {
            return Optional.empty();
        }
        String s = o.get(key).getAsString();
        if (s.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Identifier.parse(s));
    }
}
