package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: open settings copier hub from left-click in the world. */
public record SettingsCopierOpenHubPayload(int handOrdinal) implements CustomPacketPayload {
    public static final Type<SettingsCopierOpenHubPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "settings_copier_open_hub"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsCopierOpenHubPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeByte(p.handOrdinal() & 0xFF),
                    buf -> new SettingsCopierOpenHubPayload(buf.readByte() & 0xFF));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
