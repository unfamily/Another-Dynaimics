package net.unfamily.another_dynamics.client;

import java.util.Optional;
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
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctTextures;

/**
 * Bakes to {@link DuctBakedModel}: composite geometry from configurable templates (datapack and/or block JSON) and
 * texture from {@link DuctDefinition} for {@link #ductLogicalId}.
 */
public final class DuctUnbakedGeometry implements IUnbakedGeometry<DuctUnbakedGeometry> {
    static final ResourceLocation CENTER_ONLY = ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_center_only");

    private final String ductLogicalId;
    private final Optional<ResourceLocation> blockModelDefault;
    private final Optional<ResourceLocation> blockModelLine;

    public DuctUnbakedGeometry(String ductLogicalId, Optional<ResourceLocation> blockModelDefault, Optional<ResourceLocation> blockModelLine) {
        this.ductLogicalId = ductLogicalId;
        this.blockModelDefault = blockModelDefault;
        this.blockModelLine = blockModelLine;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        ResourceLocation modelDefault = blockModelDefault
                .or(() -> DuctDefinitionRegistry.getByLogicalId(ductLogicalId).flatMap(DuctDefinition::compositeModelDefault))
                .orElse(DuctCompositeGeometry.DEFAULT_MODEL_DEFAULT);
        ResourceLocation modelLine = blockModelLine
                .or(() -> DuctDefinitionRegistry.getByLogicalId(ductLogicalId).flatMap(DuctDefinition::compositeModelLine))
                .orElse(DuctCompositeGeometry.DEFAULT_MODEL_LINE);
        String texStr = DuctTextures.compositeBlockTexture(ductLogicalId).toString();
        DuctCompositeGeometry geometry = DuctCompositeGeometry.bake(modelDefault, modelLine, texStr, spriteGetter);

        BakedModel base = baker.bake(CENTER_ONLY, modelState);
        if (base == null) {
            base = EmptyModel.BAKED;
        }
        TextureAtlasSprite particle = spriteGetter.apply(context.getMaterial("particle"));
        return new DuctBakedModel(base, geometry, particle);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> modelGetter, IGeometryBakingContext context) {
        UnbakedModel m = modelGetter.apply(CENTER_ONLY);
        if (m != null) {
            m.resolveParents(modelGetter);
        }
    }
}
