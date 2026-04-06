package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: flip {@link net.unfamily.another_dynamics.duct.DuctFaceNode#denyOverridesAllow}. */
public record DuctListLogicPayload(BlockPos pos, int faceOrdinal) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctListLogicPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_list_logic"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctListLogicPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctListLogicPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    DuctListLogicPayload::faceOrdinal,
                    DuctListLogicPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
