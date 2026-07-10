package net.unfamily.another_dynamics.client.project;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.StandardModelParameters;
import net.neoforged.neoforge.client.model.UnbakedModelLoader;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class ProjectDuctGeometryLoader implements UnbakedModelLoader<ProjectDuctUnbakedModel> {
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "project_duct");

    @Override
    public ProjectDuctUnbakedModel read(JsonObject jsonObject, JsonDeserializationContext deserializationContext)
            throws JsonParseException {
        StandardModelParameters parameters = StandardModelParameters.parse(jsonObject, deserializationContext);
        return new ProjectDuctUnbakedModel(parameters);
    }
}
