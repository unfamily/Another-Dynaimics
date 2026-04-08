package net.unfamily.another_dynamics.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlock;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AnotherDynamicsMod.MOD_ID);

    public static final DeferredBlock<Block> DUCT = BLOCKS.register("duct", () -> new DuctBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(0.05f, 1.2f)
                    .sound(SoundType.COPPER)
                    .noOcclusion()));

    private ModBlocks() {}
}
