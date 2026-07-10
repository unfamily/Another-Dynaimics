package net.unfamily.another_dynamics.client.project;

import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.util.context.ContextMap;
import net.neoforged.neoforge.client.model.AbstractUnbakedModel;
import net.neoforged.neoforge.client.model.ExtendedUnbakedGeometry;
import net.neoforged.neoforge.client.model.StandardModelParameters;

/** Unbaked project duct model; world rendering is handled by {@link ProjectDuctBlockStateModel}. */
public final class ProjectDuctUnbakedModel extends AbstractUnbakedModel {
    private static final ProjectDuctGeometry GEOMETRY = new ProjectDuctGeometry();

    public ProjectDuctUnbakedModel(StandardModelParameters parameters) {
        super(parameters);
    }

    @Override
    public UnbakedGeometry geometry() {
        return GEOMETRY;
    }

    private static final class ProjectDuctGeometry implements ExtendedUnbakedGeometry {
        @Override
        public QuadCollection bake(
                TextureSlots textureSlots,
                net.minecraft.client.resources.model.ModelBaker baker,
                net.minecraft.client.renderer.block.dispatch.ModelState state,
                net.minecraft.client.resources.model.ModelDebugName debugName,
                ContextMap additionalProperties) {
            return QuadCollection.EMPTY;
        }
    }
}
