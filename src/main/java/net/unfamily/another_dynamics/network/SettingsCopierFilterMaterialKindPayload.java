package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: cycle portable filter list material kind in virtual FILTER editor. */
public record SettingsCopierFilterMaterialKindPayload(int materialKindOrdinal)
        implements CustomPacketPayload {
    public static final Type<SettingsCopierFilterMaterialKindPayload> TYPE =
            new Type<>(
                    Identifier.fromNamespaceAndPath(
                            AnotherDynamicsMod.MOD_ID, "settings_copier_filter_material_kind"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsCopierFilterMaterialKindPayload>
            STREAM_CODEC =
                    StreamCodec.composite(
                            ByteBufCodecs.VAR_INT,
                            SettingsCopierFilterMaterialKindPayload::materialKindOrdinal,
                            SettingsCopierFilterMaterialKindPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
