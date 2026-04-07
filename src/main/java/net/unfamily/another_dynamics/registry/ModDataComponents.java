package net.unfamily.another_dynamics.registry;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Item stack data: which logical duct type a placed {@link net.unfamily.another_dynamics.duct.DuctBlock} represents.
 */
public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, AnotherDynamicsMod.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DUCT_LOGICAL_ID =
            TYPES.register(
                    "duct_logical_id",
                    () ->
                            DataComponentType.<String>builder()
                                    .persistent(Codec.STRING)
                                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                                    .build());

    private ModDataComponents() {}
}
