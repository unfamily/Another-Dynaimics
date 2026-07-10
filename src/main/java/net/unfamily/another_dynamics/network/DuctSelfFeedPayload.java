package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: set {@code selfFeed} for one duct face. */
public record DuctSelfFeedPayload(BlockPos pos, int faceOrdinal, boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctSelfFeedPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_self_feed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctSelfFeedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctSelfFeedPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    DuctSelfFeedPayload::faceOrdinal,
                    ByteBufCodecs.BOOL,
                    DuctSelfFeedPayload::enabled,
                    DuctSelfFeedPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

