package net.unfamily.another_dynamics.client.project;

import java.util.function.Function;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.model.EmptyModel;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.DuctCompositeGeometry;

public final class ProjectDuctUnbakedGeometry implements IUnbakedGeometry<ProjectDuctUnbakedGeometry> {
    private static final ResourceLocation MODEL_DEFAULT =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/project_duct_default");
    private static final ResourceLocation MODEL_LINE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/project_duct_line");

    @Override
    public BakedModel bake(
            IGeometryBakingContext context,
            ModelBaker baker,
            Function<Material, net.minecraft.client.renderer.texture.TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            net.minecraft.client.renderer.block.model.ItemOverrides overrides) {
        DuctCompositeGeometry geometry =
                DuctCompositeGeometry.bake(
                        MODEL_DEFAULT, MODEL_LINE, ProjectDuctTextures.BLOCK_TEXTURE.toString(), spriteGetter);
        BakedModel base = baker.bake(MODEL_LINE, modelState);
        if (base == null) {
            base = EmptyModel.BAKED;
        }
        var particle =
                spriteGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, ProjectDuctTextures.BLOCK_TEXTURE));
        return new ProjectDuctBakedModel(base, geometry, particle);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> modelGetter, IGeometryBakingContext context) {
        for (ResourceLocation dep : new ResourceLocation[] {MODEL_DEFAULT, MODEL_LINE}) {
            UnbakedModel model = modelGetter.apply(dep);
            if (model != null) {
                model.resolveParents(modelGetter);
            }
        }
    }
}
