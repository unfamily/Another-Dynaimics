package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: run filter import from import layer. */
public record FilterImportExecutePayload(int channelOrdinal, String primaryName, String secondaryName)
        implements CustomPacketPayload {
    public static final Type<FilterImportExecutePayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            AnotherDynamicsMod.MOD_ID, "filter_import_execute"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FilterImportExecutePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    FilterImportExecutePayload::channelOrdinal,
                    ByteBufCodecs.STRING_UTF8,
                    FilterImportExecutePayload::primaryName,
                    ByteBufCodecs.STRING_UTF8,
                    FilterImportExecutePayload::secondaryName,
                    FilterImportExecutePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
