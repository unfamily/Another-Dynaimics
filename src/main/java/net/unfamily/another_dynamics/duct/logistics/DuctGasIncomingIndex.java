package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks reserved incoming chemical stacks toward a destination duct (RAM only). Same idea as
 * {@link DuctFluidIncomingIndex}: stores stack copies so filter-line matching works for tags/macros.
 */
public final class DuctGasIncomingIndex {
    private DuctGasIncomingIndex() {}

    private static final Map<ServerLevel, Map<BlockPos, List<Object>>> BY_LEVEL = new ConcurrentHashMap<>();

    public static void register(ServerLevel level, BlockPos destDuct, Object stack) {
        if (level == null || destDuct == null || stack == null || MekanismChemicalCompat.isEmptyStack(stack)) {
            return;
        }
        Object copy = MekanismChemicalCompat.copyWithAmount(stack, MekanismChemicalCompat.getAmount(stack));
        if (MekanismChemicalCompat.isEmptyStack(copy) || MekanismChemicalCompat.getAmount(copy) <= 0) {
            return;
        }
        List<Object> list =
                BY_LEVEL
                        .computeIfAbsent(level, __ -> new ConcurrentHashMap<>())
                        .computeIfAbsent(destDuct.immutable(), __ -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(copy);
        }
    }

    public static void unregister(ServerLevel level, BlockPos destDuct, Object stack) {
        if (level == null || destDuct == null || stack == null || MekanismChemicalCompat.isEmptyStack(stack)) {
            return;
        }
        String id = MekanismChemicalCompat.getTypeRegistryName(stack);
        long amt = MekanismChemicalCompat.getAmount(stack);
        if (id == null || id.isEmpty() || amt <= 0) {
            return;
        }
        Map<BlockPos, List<Object>> dim = BY_LEVEL.get(level);
        if (dim == null) {
            return;
        }
        List<Object> list = dim.get(destDuct);
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (Iterator<Object> it = list.iterator(); it.hasNext(); ) {
                Object s = it.next();
                if (samePendingEntry(s, id, amt)) {
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) {
                dim.remove(destDuct);
            }
        }
        if (dim.isEmpty()) {
            BY_LEVEL.remove(level);
        }
    }

    private static boolean samePendingEntry(Object s, String id, long amt) {
        if (s == null || MekanismChemicalCompat.isEmptyStack(s)) {
            return false;
        }
        String sid = MekanismChemicalCompat.getTypeRegistryName(s);
        return sid != null && sid.equals(id) && MekanismChemicalCompat.getAmount(s) == amt;
    }

    public static List<Object> snapshot(ServerLevel level, BlockPos destDuct) {
        Map<BlockPos, List<Object>> dim = BY_LEVEL.get(level);
        if (dim == null) {
            return List.of();
        }
        List<Object> list = dim.get(destDuct);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object s : list) {
                if (s != null && !MekanismChemicalCompat.isEmptyStack(s)) {
                    out.add(MekanismChemicalCompat.copyWithAmount(s, MekanismChemicalCompat.getAmount(s)));
                }
            }
            return out;
        }
    }
}
