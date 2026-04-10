package net.unfamily.another_dynamics.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Full ARGB from server (alpha from datapack {@code ray_alpha}; RGB from {@code ray_color} or random). */
public record EnergyRayPathPayload(List<BlockPos> ductPath, int argb) implements CustomPacketPayload {
    public static final Type<EnergyRayPathPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "energy_ray_path"));

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
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        n = Math.max(0, Math.min(512, n));
                        ArrayList<BlockPos> path = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            path.add(BlockPos.STREAM_CODEC.decode(buf));
                        }
                        int argb = ByteBufCodecs.INT.decode(buf);
                        return new EnergyRayPathPayload(List.copyOf(path), argb);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

