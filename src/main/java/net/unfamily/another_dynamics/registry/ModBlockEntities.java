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
            TYPES.register("duct", () -> BlockEntityType.Builder.of(DuctBlockEntity::new, ModBlocks.DUCT.get()).build(null));

    private ModBlockEntities() {}
}
