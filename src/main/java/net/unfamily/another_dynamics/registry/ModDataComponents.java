package net.unfamily.another_dynamics.registry;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
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

    /** Datapack module declaration id ({@code another_dynamics:load/module/...}). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> DUCT_MODULE_DECLARATION =
            TYPES.register(
                    "duct_module_declaration",
                    () ->
                            DataComponentType.<ResourceLocation>builder()
                                    .persistent(ResourceLocation.CODEC)
                                    .networkSynchronized(ResourceLocation.STREAM_CODEC)
                                    .build());

    /** Serialized snapshot payload on {@link net.unfamily.another_dynamics.item.SettingsCopierItem}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> DUCT_FACE_SETTINGS =
            TYPES.register(
                    "duct_face_settings",
                    () ->
                            DataComponentType.<CompoundTag>builder()
                                    .persistent(CompoundTag.CODEC)
                                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.COMPOUND_TAG)
                                    .build());

    /**
     * When {@code true}, copier is in filter-list mode ({@link
     * net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind#FILTER}). Absent / false = all mode. Boolean
     * avoids byte {@code 0} (all) being treated as a missing component on sync.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> SETTINGS_COPIER_FILTER =
            TYPES.register(
                    "settings_copier_filter",
                    () ->
                            DataComponentType.<Boolean>builder()
                                    .persistent(Codec.BOOL)
                                    .networkSynchronized(ByteBufCodecs.BOOL)
                                    .build());

    /**
     * When {@code true}, copier is in sequential-buffer mode ({@link
     * net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind#SEQUENTIAL}). Absent / false = not sequential.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> SETTINGS_COPIER_SEQUENTIAL =
            TYPES.register(
                    "settings_copier_sequential",
                    () ->
                            DataComponentType.<Boolean>builder()
                                    .persistent(Codec.BOOL)
                                    .networkSynchronized(ByteBufCodecs.BOOL)
                                    .build());

    /** Sequential Buffer settings snapshot for {@link net.unfamily.another_dynamics.item.SettingsCopierItem}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>>
            SETTINGS_COPIER_SEQUENTIAL_DATA =
                    TYPES.register(
                            "settings_copier_sequential_data",
                            () ->
                                    DataComponentType.<CompoundTag>builder()
                                            .persistent(CompoundTag.CODEC)
                                            .networkSynchronized(ByteBufCodecs.COMPOUND_TAG)
                                            .build());

    /** Bound inventory endpoint for {@link net.unfamily.another_dynamics.item.RemoteNodeSelectorItem}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint>> REMOTE_NODE_ENDPOINT =
            TYPES.register(
                    "remote_node_endpoint",
                    () ->
                            DataComponentType.<net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint>builder()
                                    .persistent(net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint.CODEC)
                                    .networkSynchronized(net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint.STREAM_CODEC)
                                    .build());

    private ModDataComponents() {}
}
