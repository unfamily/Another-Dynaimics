package net.unfamily.another_dynamics.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.project.ProjectDuctBlockStateModel;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionLoader;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctTextures;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.registry.ModBlocks;

/**
 * Installs dynamic {@link DuctBlockStateModel} / {@link ProjectDuctBlockStateModel} wrappers after model baking.
 */
public final class DuctBlockStateModels {
    private static final Map<Block, String> DEFAULT_LOGICAL_IDS = Map.of(
            ModBlocks.DUCT.get(), "another_dynamics:item_duct",
            ModBlocks.FLUID_DUCT.get(), "another_dynamics:fluid_duct",
            ModBlocks.ITEM_FLUID_DUCT.get(), "another_dynamics:item_fluid_duct");

    private DuctBlockStateModels() {}

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (DuctDefinitionRegistry.all().isEmpty()) {
            try {
                DuctDefinitionLoader.loadEager(Minecraft.getInstance().getResourceManager());
            } catch (Exception ex) {
                AnotherDynamicsMod.LOGGER.error("Failed to eager-load duct definitions during model baking", ex);
            }
        }

        var spriteGetter = DuctRenderingSupport.blockAtlasSpriteGetter();
        Map<String, DuctCompositeGeometry> geometryCache = DuctRenderingSupport.bakeAllGeometries(spriteGetter, null);
        DuctRenderingSupport.updateGlobalGeometryCache(geometryCache);

        var nodesSprite = spriteGetter.apply(
                DuctRenderingSupport.blockSprite(
                        Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/nodes")));
        var nodeBufferSprite = spriteGetter.apply(
                DuctRenderingSupport.blockSprite(
                        Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/node_buffer")));
        DuctRenderingSupport.updateOverlaySprites(nodesSprite, nodeBufferSprite);

        var blockModels = event.getBakingResult().blockStateModels();
        DuctCompositeGeometry projectGeometry = ProjectDuctBlockStateModel.bakeGeometry();

        for (BlockState state : blockModels.keySet().toArray(new BlockState[0])) {
            Block block = state.getBlock();
            BlockStateModel base = blockModels.get(state);
            if (block instanceof ProjectDuctBlock) {
                blockModels.put(state, new ProjectDuctBlockStateModel(base, projectGeometry));
                continue;
            }
            if (!ModBlocks.isDuctBlock(block)) {
                continue;
            }
            String defaultLogicalId = DEFAULT_LOGICAL_IDS.getOrDefault(block, "another_dynamics:item_duct");
            DuctCompositeGeometry fallback = resolveFallbackGeometry(defaultLogicalId, geometryCache, spriteGetter);
            blockModels.put(
                    state, new DuctBlockStateModel(base, defaultLogicalId, fallback, geometryCache));
        }
    }

    private static DuctCompositeGeometry resolveFallbackGeometry(
            String logicalId,
            Map<String, DuctCompositeGeometry> cache,
            java.util.function.Function<
                            net.minecraft.client.resources.model.sprite.SpriteId, net.minecraft.client.renderer.texture.TextureAtlasSprite>
                    spriteGetter) {
        DuctCompositeGeometry cached = cache.get(logicalId);
        if (cached != null && cached.isBuilt()) {
            return cached;
        }
        Optional<DuctDefinition> def = DuctDefinitionRegistry.getByLogicalId(logicalId);
        Identifier modelDefault =
                def.flatMap(DuctDefinition::compositeModelDefault).orElse(DuctCompositeGeometry.DEFAULT_MODEL_DEFAULT);
        Identifier modelLine =
                def.flatMap(DuctDefinition::compositeModelLine).orElse(DuctCompositeGeometry.DEFAULT_MODEL_LINE);
        Identifier texture = DuctTextures.compositeBlockTexture(logicalId);
        DuctCompositeGeometry baked = DuctCompositeGeometry.bake(modelDefault, modelLine, texture, spriteGetter);
        if (baked.isBuilt()) {
            Map<String, DuctCompositeGeometry> merged = new HashMap<>(cache);
            merged.put(logicalId, baked);
            DuctRenderingSupport.updateGlobalGeometryCache(Map.copyOf(merged));
            return baked;
        }
        return baked;
    }
}
