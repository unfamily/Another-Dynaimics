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
 * Server -> client: full filter configuration for one duct face (menu open / after edits).
 */
public record DuctFilterSyncPayload(
        BlockPos pos,
        int faceOrdinal,
        int transportKindOrdinal,
        int filterBankOrdinal,
        List<String> allow,
        List<String> deny,
        List<Integer> allowCaps,
        List<Integer> allowCaps2,
        boolean denyOverridesAllow)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctFilterSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_filter_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctFilterSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        BlockPos.STREAM_CODEC.encode(buf, p.pos());
                        ByteBufCodecs.VAR_INT.encode(buf, p.faceOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.transportKindOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.filterBankOrdinal());
                        DuctFilterPacketCodecs.STRING_LIST.encode(buf, p.allow());
                        DuctFilterPacketCodecs.STRING_LIST.encode(buf, p.deny());
                        DuctFilterPacketCodecs.INT_LIST.encode(buf, p.allowCaps());
                        DuctFilterPacketCodecs.INT_LIST.encode(buf, p.allowCaps2());
                        buf.writeBoolean(p.denyOverridesAllow());
                    },
                    buf -> {
                        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                        int face = ByteBufCodecs.VAR_INT.decode(buf);
                        int tk = ByteBufCodecs.VAR_INT.decode(buf);
                        int bank = ByteBufCodecs.VAR_INT.decode(buf);
                        List<String> allow = DuctFilterPacketCodecs.STRING_LIST.decode(buf);
                        List<String> deny = DuctFilterPacketCodecs.STRING_LIST.decode(buf);
                        List<Integer> caps = DuctFilterPacketCodecs.INT_LIST.decode(buf);
                        List<Integer> caps2 = DuctFilterPacketCodecs.INT_LIST.decode(buf);
                        boolean over = buf.readBoolean();
                        return new DuctFilterSyncPayload(pos, face, tk, bank, allow, deny, caps, caps2, over);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
