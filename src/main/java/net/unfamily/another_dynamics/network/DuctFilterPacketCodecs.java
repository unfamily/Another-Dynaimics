package net.unfamily.another_dynamics.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

final class DuctFilterPacketCodecs {
    /** Per-entry UTF-8 cap (NBT {@code ?} filters from ghost slot can be long). */
    static final int MAX_FILTER_STRING_UTF = 16_384;
    static final int MAX_LIST_ENTRIES = 64;

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

    private DuctFilterPacketCodecs() {}
}
