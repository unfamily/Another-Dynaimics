package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Tracks pending incoming gas amounts to prevent oversubscribing destination limits.
 *
 * <p>Server-RAM only. Key is the destination duct pos.</p>
 */
public final class DuctGasIncomingIndex {
    private DuctGasIncomingIndex() {}

    private static final HashMap<ServerLevel, HashMap<BlockPos, HashMap<String, Long>>> BY_LEVEL = new HashMap<>();

    public static void register(ServerLevel level, BlockPos destDuct, String chemicalId, long amount) {
        if (level == null || destDuct == null || chemicalId == null || chemicalId.isEmpty() || amount <= 0) {
            return;
        }
        HashMap<BlockPos, HashMap<String, Long>> lvl = BY_LEVEL.computeIfAbsent(level, __ -> new HashMap<>());
        HashMap<String, Long> map = lvl.computeIfAbsent(destDuct.immutable(), __ -> new HashMap<>());
        map.merge(chemicalId, amount, Long::sum);
    }

    public static void unregister(ServerLevel level, BlockPos destDuct, String chemicalId, long amount) {
        if (level == null || destDuct == null || chemicalId == null || chemicalId.isEmpty() || amount <= 0) {
            return;
        }
        HashMap<BlockPos, HashMap<String, Long>> lvl = BY_LEVEL.get(level);
        if (lvl == null) {
            return;
        }
        HashMap<String, Long> map = lvl.get(destDuct);
        if (map == null) {
            return;
        }
        Long cur = map.get(chemicalId);
        if (cur == null) {
            return;
        }
        long next = cur - amount;
        if (next <= 0) {
            map.remove(chemicalId);
        } else {
            map.put(chemicalId, next);
        }
        if (map.isEmpty()) {
            lvl.remove(destDuct);
        }
        if (lvl.isEmpty()) {
            BY_LEVEL.remove(level);
        }
    }

    /** Snapshot pending amounts as stacks (id+amount), for matching against allow-lines. */
    public static List<PendingGas> snapshot(ServerLevel level, BlockPos destDuct) {
        if (level == null || destDuct == null) {
            return List.of();
        }
        HashMap<BlockPos, HashMap<String, Long>> lvl = BY_LEVEL.get(level);
        if (lvl == null) {
            return List.of();
        }
        HashMap<String, Long> map = lvl.get(destDuct);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        ArrayList<PendingGas> out = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                out.add(new PendingGas(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    public record PendingGas(String chemicalId, long amount) {}
}

