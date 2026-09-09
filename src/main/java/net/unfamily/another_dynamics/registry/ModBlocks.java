package net.unfamily.another_dynamics.registry;

import java.util.function.UnaryOperator;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlock;
import net.unfamily.another_dynamics.duct.FluidDuctBlock;
import net.unfamily.another_dynamics.duct.GasDuctBlock;
import net.unfamily.another_dynamics.duct.HybridItemFluidDuctBlock;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.machine.connector.MachineConnectorBlock;
import net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlock;

/**
 * Block registrations use {@link DeferredRegister.Blocks#registerBlock} so {@link BlockBehaviour.Properties}
 * receive {@link BlockBehaviour.Properties#setId} before constructors run (required on Minecraft 26+).
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AnotherDynamicsMod.MOD_ID);

    /**
     * Gas duct depends on Mekanism chemical transport, which is not available for the 26.1.2 baseline
     * ({@link MekanismChemicalCompat#GAS_SUPPORT_ENABLED} == false). The block stays out of the registry until it ships.
     */
    private static final boolean GAS_SUPPORT = MekanismChemicalCompat.isGasSupportEnabled();

    private static final UnaryOperator<BlockBehaviour.Properties> DUCT_PROPERTIES = p -> p
            .mapColor(MapColor.COLOR_LIGHT_GRAY)
            .strength(0.05f, 1.2f)
            .sound(SoundType.COPPER)
            .noOcclusion();

    public static final DeferredBlock<Block> DUCT =
            BLOCKS.registerBlock("duct", DuctBlock::new, DUCT_PROPERTIES);

    public static final DeferredBlock<Block> PROJECT_DUCT =
            BLOCKS.registerBlock("project_duct", ProjectDuctBlock::new, DUCT_PROPERTIES);

    public static final DeferredBlock<Block> FLUID_DUCT =
            BLOCKS.registerBlock("fluid_duct", FluidDuctBlock::new, DUCT_PROPERTIES);

    public static final DeferredBlock<Block> ITEM_FLUID_DUCT =
            BLOCKS.registerBlock("item_fluid_duct", HybridItemFluidDuctBlock::new, DUCT_PROPERTIES);

    public static final DeferredBlock<Block> SEQUENTIAL_BUFFER =
            BLOCKS.registerBlock(
                    "sequential_buffer",
                    SequentialBufferBlock::new,
                    p -> p.mapColor(MapColor.COLOR_GRAY)
                            .strength(1.5f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops());

    public static final DeferredBlock<Block> MACHINE_CONNECTOR =
            BLOCKS.registerBlock(
                    "machine_connector",
                    MachineConnectorBlock::new,
                    p -> p.mapColor(MapColor.COLOR_GRAY)
                            .strength(1.5f, 6.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops());

    /** Only registered when Mekanism gas support is enabled (disabled on the 26.1.2 baseline). */
    public static final DeferredBlock<Block> GAS_DUCT =
            GAS_SUPPORT ? BLOCKS.registerBlock("gas_duct", GasDuctBlock::new, DUCT_PROPERTIES) : null;

    public static boolean isDuctBlock(Block block) {
        if (block == DUCT.get() || block == FLUID_DUCT.get() || block == ITEM_FLUID_DUCT.get()) {
            return true;
        }
        return GAS_DUCT != null && block == GAS_DUCT.get();
    }

    private ModBlocks() {}
}
