package net.unfamily.another_dynamics.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.fml.ModList;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlock;
import net.unfamily.another_dynamics.duct.FluidDuctBlock;
import net.unfamily.another_dynamics.duct.GasDuctBlock;
import net.unfamily.another_dynamics.duct.HybridItemFluidDuctBlock;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.machine.connector.MachineConnectorBlock;
import net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlock;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AnotherDynamicsMod.MOD_ID);
    private static final boolean MEKANISM_LOADED = ModList.get().isLoaded("mekanism");

    private static BlockBehaviour.Properties ductProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .strength(0.05f, 1.2f)
                .sound(SoundType.COPPER)
                .noOcclusion();
    }

    public static final DeferredBlock<Block> DUCT = BLOCKS.register("duct", () -> new DuctBlock(ductProperties()));

    public static final DeferredBlock<Block> PROJECT_DUCT =
            BLOCKS.register("project_duct", () -> new ProjectDuctBlock(ductProperties()));

    public static final DeferredBlock<Block> FLUID_DUCT =
            BLOCKS.register("fluid_duct", () -> new FluidDuctBlock(ductProperties()));

    public static final DeferredBlock<Block> ITEM_FLUID_DUCT =
            BLOCKS.register("item_fluid_duct", () -> new HybridItemFluidDuctBlock(ductProperties()));

    /** Only registered when Mekanism is present. */
    public static final DeferredBlock<Block> GAS_DUCT =
            MEKANISM_LOADED ? BLOCKS.register("gas_duct", () -> new GasDuctBlock(ductProperties())) : null;

    public static final DeferredBlock<Block> SEQUENTIAL_BUFFER =
            BLOCKS.register(
                    "sequential_buffer",
                    () ->
                            new SequentialBufferBlock(
                                    BlockBehaviour.Properties.of()
                                            .mapColor(MapColor.COLOR_GRAY)
                                            .strength(1.5f, 6.0f)
                                            .sound(SoundType.METAL)
                                            .requiresCorrectToolForDrops()));

    public static final DeferredBlock<Block> MACHINE_CONNECTOR =
            BLOCKS.register(
                    "machine_connector",
                    () ->
                            new MachineConnectorBlock(
                                    BlockBehaviour.Properties.of()
                                            .mapColor(MapColor.COLOR_GRAY)
                                            .strength(1.5f, 6.0f)
                                            .sound(SoundType.METAL)
                                            .requiresCorrectToolForDrops()));

    public static boolean isDuctBlock(Block block) {
        if (block == DUCT.get() || block == FLUID_DUCT.get() || block == ITEM_FLUID_DUCT.get()) {
            return true;
        }
        return GAS_DUCT != null && block == GAS_DUCT.get();
    }

    private ModBlocks() {}
}
