package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: menu is open and ready; resend filter snapshots for the active transport kind. */
public record DuctFilterSyncRequestPayload(BlockPos pos, int faceOrdinal) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctFilterSyncRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_filter_sync_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctFilterSyncRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctFilterSyncRequestPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    DuctFilterSyncRequestPayload::faceOrdinal,
                    DuctFilterSyncRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
