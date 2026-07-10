package net.unfamily.another_dynamics.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Full ARGB from server (alpha from datapack {@code ray_alpha}; RGB from {@code ray_color} or random). Optional attach
 * faces pull particle endpoints into source/destination duct nodes (see {@link net.unfamily.another_dynamics.client.transit.DuctTransitPathGeometry}).
 */
public record EnergyRayPathPayload(
        List<BlockPos> ductPath, int argb, @org.jetbrains.annotations.Nullable Direction sourceAttachFace, @org.jetbrains.annotations.Nullable Direction destAttachFace)
        implements CustomPacketPayload {
    public static final Type<EnergyRayPathPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "energy_ray_path"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyRayPathPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        List<BlockPos> path = p.ductPath();
                        int n = path != null ? path.size() : 0;
                        buf.writeVarInt(n);
                        for (int i = 0; i < n; i++) {
                            BlockPos.STREAM_CODEC.encode(buf, path.get(i));
                        }
                        ByteBufCodecs.INT.encode(buf, p.argb());
                        writeFace(buf, p.sourceAttachFace());
                        writeFace(buf, p.destAttachFace());
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        n = Math.max(0, Math.min(512, n));
                        ArrayList<BlockPos> path = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            path.add(BlockPos.STREAM_CODEC.decode(buf));
                        }
                        int argb = ByteBufCodecs.INT.decode(buf);
                        Direction srcFace = readFace(buf);
                        Direction dstFace = readFace(buf);
                        return new EnergyRayPathPayload(List.copyOf(path), argb, srcFace, dstFace);
                    });

    private static void writeFace(RegistryFriendlyByteBuf buf, @org.jetbrains.annotations.Nullable Direction face) {
        buf.writeByte(face == null ? (byte) -1 : (byte) face.ordinal());
    }

    private static @org.jetbrains.annotations.Nullable Direction readFace(RegistryFriendlyByteBuf buf) {
        byte ord = buf.readByte();
        if (ord < 0 || ord >= Direction.values().length) {
            return null;
        }
        return Direction.values()[ord];
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

