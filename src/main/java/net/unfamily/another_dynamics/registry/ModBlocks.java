package net.unfamily.another_dynamics.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlock;
import net.unfamily.another_dynamics.duct.FluidDuctBlock;
import net.unfamily.another_dynamics.duct.HybridItemFluidDuctBlock;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AnotherDynamicsMod.MOD_ID);

    private static BlockBehaviour.Properties ductProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .strength(0.05f, 1.2f)
                .sound(SoundType.COPPER)
                .noOcclusion();
    }

    public static final DeferredBlock<Block> DUCT = BLOCKS.register("duct", () -> new DuctBlock(ductProperties()));

    public static final DeferredBlock<Block> FLUID_DUCT =
            BLOCKS.register("fluid_duct", () -> new FluidDuctBlock(ductProperties()));

    public static final DeferredBlock<Block> ITEM_FLUID_DUCT =
            BLOCKS.register("item_fluid_duct", () -> new HybridItemFluidDuctBlock(ductProperties()));

    public static boolean isDuctBlock(Block block) {
        return block == DUCT.get() || block == FLUID_DUCT.get() || block == ITEM_FLUID_DUCT.get();
    }

    private ModBlocks() {}
}
