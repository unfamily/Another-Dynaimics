package net.unfamily.another_dynamics.client;

import java.util.function.Function;

import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Bakes to {@link DuctBakedModel}: runtime composition of center, connection arms, storage nodes, and line template
 * geometry (see {@link DuctGeometryCache}). The block JSON uses {@code loader: "another_dynamics:duct"} so the
 * block never relies on a full-cube or full-template static model in the world.
 */
public final class DuctUnbakedGeometry implements IUnbakedGeometry<DuctUnbakedGeometry> {
    static final ResourceLocation CENTER_ONLY = ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_center_only");

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        DuctGeometryCache.INSTANCE.rebuild(spriteGetter);
        BakedModel base = baker.bake(CENTER_ONLY, modelState);
        if (base == null) {
            base = EmptyModel.BAKED;
        }
        TextureAtlasSprite particle = spriteGetter.apply(context.getMaterial("particle"));
        return new DuctBakedModel(base, DuctGeometryCache.INSTANCE, particle);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> modelGetter, IGeometryBakingContext context) {
        UnbakedModel m = modelGetter.apply(CENTER_ONLY);
        if (m != null) {
            m.resolveParents(modelGetter);
        }
    }
}
