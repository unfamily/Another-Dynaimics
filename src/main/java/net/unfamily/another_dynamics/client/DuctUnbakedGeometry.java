package net.unfamily.another_dynamics.client;

import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionLoader;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctTextures;

/**
 * Bakes to {@link DuctBakedModel}: composite geometry from configurable templates (datapack and/or block JSON) and
 * texture from {@link DuctDefinition} for {@link #ductLogicalId}.
 */
public final class DuctUnbakedGeometry implements IUnbakedGeometry<DuctUnbakedGeometry> {
    static final ResourceLocation CENTER_ONLY = ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_center_only");
    static final ResourceLocation SIMPLE_DUCT_LINE = ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/simple_duct_line");

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
        // ModelManager bakes models before DuctDefinitionLoader.apply() runs; eagerly load definitions here if needed.
        if (DuctDefinitionRegistry.all().isEmpty()) {
            try {
                DuctDefinitionLoader.loadEager(Minecraft.getInstance().getResourceManager());
            } catch (Exception ex) {
                AnotherDynamicsMod.LOGGER.error("Failed to eager-load duct definitions during baking", ex);
            }
        }
        ResourceLocation modelDefault = blockModelDefault
                .or(() -> DuctDefinitionRegistry.getByLogicalId(ductLogicalId).flatMap(DuctDefinition::compositeModelDefault))
                .orElse(DuctCompositeGeometry.DEFAULT_MODEL_DEFAULT);
        ResourceLocation modelLine = blockModelLine
                .or(() -> DuctDefinitionRegistry.getByLogicalId(ductLogicalId).flatMap(DuctDefinition::compositeModelLine))
                .orElse(DuctCompositeGeometry.DEFAULT_MODEL_LINE);
        String texStr = DuctTextures.compositeBlockTexture(ductLogicalId).toString();
        TextureAtlasSprite nodesSprite =
                spriteGetter.apply(
                        new Material(
                                InventoryMenu.BLOCK_ATLAS,
                                ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/nodes")));
        DuctCompositeGeometry geometry = DuctCompositeGeometry.bake(modelDefault, modelLine, texStr, spriteGetter);
        var geometryById = DuctBakedModel.bakeAllGeometries(spriteGetter, geometry);

        // Use simple_duct_line as the base model: it exists, has correct item display transforms,
        // and provides a sensible visual fallback if geometry fails to build.
        BakedModel base = baker.bake(SIMPLE_DUCT_LINE, modelState);
        if (base == null) {
            base = EmptyModel.BAKED;
        }
        TextureAtlasSprite particle = spriteGetter.apply(context.getMaterial("particle"));
        return new DuctBakedModel(base, geometry, particle, nodesSprite, ductLogicalId, geometryById);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> modelGetter, IGeometryBakingContext context) {
        for (ResourceLocation dep : new ResourceLocation[]{SIMPLE_DUCT_LINE, CENTER_ONLY}) {
            UnbakedModel m = modelGetter.apply(dep);
            if (m != null) {
                m.resolveParents(modelGetter);
            }
        }
    }
}
