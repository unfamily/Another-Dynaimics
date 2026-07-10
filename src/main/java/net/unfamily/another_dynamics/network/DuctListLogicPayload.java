package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: flip deny/allow precedence for one filter bank. */
public record DuctListLogicPayload(BlockPos pos, int faceOrdinal, int transportKindOrdinal, int filterBankOrdinal)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctListLogicPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_list_logic"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctListLogicPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctListLogicPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    DuctListLogicPayload::faceOrdinal,
                    ByteBufCodecs.VAR_INT,
                    DuctListLogicPayload::transportKindOrdinal,
                    ByteBufCodecs.VAR_INT,
                    DuctListLogicPayload::filterBankOrdinal,
                    DuctListLogicPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
