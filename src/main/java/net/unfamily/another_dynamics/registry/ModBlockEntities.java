package net.unfamily.another_dynamics.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.machine.connector.MachineConnectorBlockEntity;
import net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AnotherDynamicsMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DuctBlockEntity>> DUCT =
            TYPES.register(
                    "duct",
                    () ->
                            (ModBlocks.GAS_DUCT != null)
                                    ? BlockEntityType.Builder.of(
                                                    DuctBlockEntity::new,
                                                    ModBlocks.DUCT.get(),
                                                    ModBlocks.FLUID_DUCT.get(),
                                                    ModBlocks.ITEM_FLUID_DUCT.get(),
                                                    ModBlocks.GAS_DUCT.get())
                                            .build(null)
                                    : BlockEntityType.Builder.of(
                                                    DuctBlockEntity::new,
                                                    ModBlocks.DUCT.get(),
                                                    ModBlocks.FLUID_DUCT.get(),
                                                    ModBlocks.ITEM_FLUID_DUCT.get())
                                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SequentialBufferBlockEntity>>
            SEQUENTIAL_BUFFER =
                    TYPES.register(
                            "sequential_buffer",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    SequentialBufferBlockEntity::new,
                                                    ModBlocks.SEQUENTIAL_BUFFER.get())
                                            .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineConnectorBlockEntity>>
            MACHINE_CONNECTOR =
                    TYPES.register(
                            "machine_connector",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    MachineConnectorBlockEntity::new,
                                                    ModBlocks.MACHINE_CONNECTOR.get())
                                            .build(null));

    private ModBlockEntities() {}
}
