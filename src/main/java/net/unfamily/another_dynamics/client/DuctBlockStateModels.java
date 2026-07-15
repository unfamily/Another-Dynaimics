package net.unfamily.another_dynamics.client;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.project.ProjectDuctBlockStateModel;
import net.unfamily.another_dynamics.duct.DuctDefinitionLoader;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.registry.ModBlocks;

/**
 * Installs dynamic {@link DuctBlockStateModel} / {@link ProjectDuctBlockStateModel} wrappers after model baking.
 *
 * <p>Geometry is <em>not</em> baked here: the block atlas is not ready during {@link ModelEvent.ModifyBakingResult}.
 * See {@link DuctClientSetup#onBakingCompleted(ModelEvent.BakingCompleted)}.
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

        var blockModels = event.getBakingResult().blockStateModels();
        DuctCompositeGeometry empty = DuctCompositeGeometry.emptyGeometry();

        for (BlockState state : blockModels.keySet().toArray(new BlockState[0])) {
            Block block = state.getBlock();
            BlockStateModel base = blockModels.get(state);
            if (block instanceof ProjectDuctBlock) {
                blockModels.put(state, new ProjectDuctBlockStateModel(base));
                continue;
            }
            if (!ModBlocks.isDuctBlock(block)) {
                continue;
            }
            String defaultLogicalId = DEFAULT_LOGICAL_IDS.getOrDefault(block, "another_dynamics:item_duct");
            blockModels.put(state, new DuctBlockStateModel(base, defaultLogicalId, empty, Map.of()));
        }
    }
}
