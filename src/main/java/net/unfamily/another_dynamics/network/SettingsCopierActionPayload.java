package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: save or load settings copier data from the duct node GUI. */
public record SettingsCopierActionPayload(
        BlockPos pos,
        int faceOrdinal,
        int action,
        int viewKind,
        int transportKindOrdinal,
        int filterBankOrdinal,
        int allowDeny)
        implements CustomPacketPayload {
    public static final int ACTION_COPY = 0;
    public static final int ACTION_PASTE = 1;

    public static final int VIEW_MAIN = 0;
    public static final int VIEW_FILTER_LIST = 1;

    public static final int LIST_ALLOW = 0;
    public static final int LIST_DENY = 1;

    public static final Type<SettingsCopierActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "settings_copier_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsCopierActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        BlockPos.STREAM_CODEC.encode(buf, p.pos());
                        ByteBufCodecs.VAR_INT.encode(buf, p.faceOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.action());
                        ByteBufCodecs.VAR_INT.encode(buf, p.viewKind());
                        ByteBufCodecs.VAR_INT.encode(buf, p.transportKindOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.filterBankOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.allowDeny());
                    },
                    buf -> {
                        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                        int face = ByteBufCodecs.VAR_INT.decode(buf);
                        int action = ByteBufCodecs.VAR_INT.decode(buf);
                        int viewKind = ByteBufCodecs.VAR_INT.decode(buf);
                        int tk = ByteBufCodecs.VAR_INT.decode(buf);
                        int bank = ByteBufCodecs.VAR_INT.decode(buf);
                        int allowDeny = ByteBufCodecs.VAR_INT.decode(buf);
                        return new SettingsCopierActionPayload(pos, face, action, viewKind, tk, bank, allowDeny);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
