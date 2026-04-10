package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public record EnergyRayPayload(BlockPos fromDuct, int fromFaceOrdinal, BlockPos toDuct, int toFaceOrdinal, int rgb)
        implements CustomPacketPayload {
    public static final Type<EnergyRayPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "energy_ray"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyRayPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    EnergyRayPayload::fromDuct,
                    ByteBufCodecs.VAR_INT,
                    EnergyRayPayload::fromFaceOrdinal,
                    BlockPos.STREAM_CODEC,
                    EnergyRayPayload::toDuct,
                    ByteBufCodecs.VAR_INT,
                    EnergyRayPayload::toFaceOrdinal,
                    ByteBufCodecs.INT,
                    EnergyRayPayload::rgb,
                    EnergyRayPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

