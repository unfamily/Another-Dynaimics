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

/** Path packet for Unknown Flux visual rays (library tinted hit particles on the client). */
public record UnknownFluxRayPathPayload(
        List<BlockPos> ductPath,
        @org.jetbrains.annotations.Nullable Direction sourceAttachFace,
        @org.jetbrains.annotations.Nullable Direction destAttachFace)
        implements CustomPacketPayload {
    public static final Type<UnknownFluxRayPathPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "unknown_flux_ray_path"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnknownFluxRayPathPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        List<BlockPos> path = p.ductPath();
                        int n = path != null ? path.size() : 0;
                        buf.writeVarInt(n);
                        for (int i = 0; i < n; i++) {
                            BlockPos.STREAM_CODEC.encode(buf, path.get(i));
                        }
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
                        Direction srcFace = readFace(buf);
                        Direction dstFace = readFace(buf);
                        return new UnknownFluxRayPathPayload(List.copyOf(path), srcFace, dstFace);
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
