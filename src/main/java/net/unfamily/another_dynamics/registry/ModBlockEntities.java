package net.unfamily.another_dynamics.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AnotherDynamicsMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DuctBlockEntity>> DUCT =
            TYPES.register(
                    "duct",
                    () ->
                            (ModBlocks.GAS_DUCT != null)
                                    ? new BlockEntityType<>(
                                            DuctBlockEntity::new,
                                            ModBlocks.DUCT.get(),
                                            ModBlocks.FLUID_DUCT.get(),
                                            ModBlocks.ITEM_FLUID_DUCT.get(),
                                            ModBlocks.GAS_DUCT.get())
                                    : new BlockEntityType<>(
                                            DuctBlockEntity::new,
                                            ModBlocks.DUCT.get(),
                                            ModBlocks.FLUID_DUCT.get(),
                                            ModBlocks.ITEM_FLUID_DUCT.get()));

    private ModBlockEntities() {}
}
