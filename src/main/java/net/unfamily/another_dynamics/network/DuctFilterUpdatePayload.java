package net.unfamily.another_dynamics.network;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Client -> server: replace allow/deny filter strings and list logic for one duct face.
 */
public record DuctFilterUpdatePayload(
        BlockPos pos, int faceOrdinal, List<String> allow, List<String> deny, boolean denyOverridesAllow)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctFilterUpdatePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_filter_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctFilterUpdatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        BlockPos.STREAM_CODEC.encode(buf, p.pos());
                        ByteBufCodecs.VAR_INT.encode(buf, p.faceOrdinal());
                        DuctFilterPacketCodecs.STRING_LIST.encode(buf, p.allow());
                        DuctFilterPacketCodecs.STRING_LIST.encode(buf, p.deny());
                        buf.writeBoolean(p.denyOverridesAllow());
                    },
                    buf -> {
                        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                        int face = ByteBufCodecs.VAR_INT.decode(buf);
                        List<String> allow = DuctFilterPacketCodecs.STRING_LIST.decode(buf);
                        List<String> deny = DuctFilterPacketCodecs.STRING_LIST.decode(buf);
                        boolean over = buf.readBoolean();
                        return new DuctFilterUpdatePayload(pos, face, allow, deny, over);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
