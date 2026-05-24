package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: persist virtual editor and reopen settings copier hub. */
public record SettingsCopierReturnToHubPayload() implements CustomPacketPayload {
    public static final Type<SettingsCopierReturnToHubPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "settings_copier_return_hub"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsCopierReturnToHubPayload> STREAM_CODEC =
            StreamCodec.unit(new SettingsCopierReturnToHubPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
