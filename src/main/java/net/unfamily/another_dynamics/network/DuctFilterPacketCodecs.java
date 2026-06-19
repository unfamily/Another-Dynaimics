package net.unfamily.another_dynamics.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;

import org.jetbrains.annotations.Nullable;

final class DuctFilterPacketCodecs {
    /** Per-entry UTF-8 cap (NBT {@code ?} filters from ghost slot can be long). */
    static final int MAX_FILTER_STRING_UTF = 16_384;
    /**
     * Max list length on the wire. Must exceed any realistic {@code filter.*} datapack cap plus
     * {@link net.unfamily.another_dynamics.duct.module.ModuleDefinition.FilterSlotModifiers} bonuses; {@code 64} was
     * too low and caused {@code DecoderException} on {@code duct_filter_sync} when filter modules added many lines.
     */
    static final int MAX_LIST_ENTRIES = 4096;

    static final StreamCodec<RegistryFriendlyByteBuf, List<String>> STRING_LIST =
            StreamCodec.of(
                    (buf, list) -> {
                        buf.writeVarInt(list.size());
                        for (String s : list) {
                            buf.writeUtf(s != null ? s : "", MAX_FILTER_STRING_UTF);
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        if (n < 0 || n > MAX_LIST_ENTRIES) {
                            throw new IllegalStateException("Invalid duct filter list size: " + n);
                        }
                        List<String> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(buf.readUtf(MAX_FILTER_STRING_UTF));
                        }
                        return list;
                    });

    static final StreamCodec<RegistryFriendlyByteBuf, List<Integer>> INT_LIST =
            StreamCodec.of(
                    (buf, list) -> {
                        buf.writeVarInt(list.size());
                        for (int v : list) {
                            buf.writeVarInt(Math.max(0, v));
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        if (n < 0 || n > MAX_LIST_ENTRIES) {
                            throw new IllegalStateException("Invalid duct allow-cap list size: " + n);
                        }
                        List<Integer> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(Math.max(0, buf.readVarInt()));
                        }
                        return list;
                    });

    static final StreamCodec<RegistryFriendlyByteBuf, List<Boolean>> BOOL_LIST =
            StreamCodec.of(
                    (buf, list) -> {
                        buf.writeVarInt(list.size());
                        for (Boolean v : list) {
                            buf.writeBoolean(v != null && v);
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        if (n < 0 || n > MAX_LIST_ENTRIES) {
                            throw new IllegalStateException("Invalid duct bool list size: " + n);
                        }
                        List<Boolean> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(buf.readBoolean());
                        }
                        return list;
                    });

    static final StreamCodec<RegistryFriendlyByteBuf, List<@Nullable DuctDirectionalEndpoint>> REMOTE_NODE_LIST =
            StreamCodec.of(
                    (buf, list) -> {
                        buf.writeVarInt(list.size());
                        for (@Nullable DuctDirectionalEndpoint ep : list) {
                            boolean has = ep != null;
                            buf.writeBoolean(has);
                            if (has) {
                                DuctDirectionalEndpoint.STREAM_CODEC.encode(buf, ep);
                            }
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        if (n < 0 || n > MAX_LIST_ENTRIES) {
                            throw new IllegalStateException("Invalid duct remote-node list size: " + n);
                        }
                        List<@Nullable DuctDirectionalEndpoint> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            if (buf.readBoolean()) {
                                list.add(DuctDirectionalEndpoint.STREAM_CODEC.decode(buf));
                            } else {
                                list.add(null);
                            }
                        }
                        return list;
                    });

    private DuctFilterPacketCodecs() {}
}
