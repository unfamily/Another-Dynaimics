package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: apply per-face energy buffer size limits (0 = AUTO). */
public record DuctEnergyBufferLimitsPayload(
        BlockPos pos, int faceOrdinal, int extractLimitFe, int insertLimitFe)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctEnergyBufferLimitsPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            AnotherDynamicsMod.MOD_ID, "duct_energy_buffer_limits"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctEnergyBufferLimitsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctEnergyBufferLimitsPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    DuctEnergyBufferLimitsPayload::faceOrdinal,
                    ByteBufCodecs.VAR_INT,
                    DuctEnergyBufferLimitsPayload::extractLimitFe,
                    ByteBufCodecs.VAR_INT,
                    DuctEnergyBufferLimitsPayload::insertLimitFe,
                    DuctEnergyBufferLimitsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
