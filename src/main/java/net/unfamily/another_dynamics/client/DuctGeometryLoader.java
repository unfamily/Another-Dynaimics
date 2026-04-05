package net.unfamily.another_dynamics.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class DuctGeometryLoader implements IGeometryLoader<DuctUnbakedGeometry> {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct");

    @Override
    public DuctUnbakedGeometry read(JsonObject jsonObject, JsonDeserializationContext deserializationContext) throws JsonParseException {
        return new DuctUnbakedGeometry();
    }
}
