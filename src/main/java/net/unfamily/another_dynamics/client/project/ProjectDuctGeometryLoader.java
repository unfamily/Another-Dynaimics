package net.unfamily.another_dynamics.client.project;

import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class ProjectDuctGeometryLoader implements net.neoforged.neoforge.client.model.geometry.IGeometryLoader<ProjectDuctUnbakedGeometry> {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "project_duct");

    @Override
    public ProjectDuctUnbakedGeometry read(
            com.google.gson.JsonObject jsonObject,
            com.google.gson.JsonDeserializationContext deserializationContext) {
        return new ProjectDuctUnbakedGeometry();
    }
}
