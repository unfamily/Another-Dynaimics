package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Tracks reserved incoming fluid amounts toward a destination duct (RAM only). Used with planned-only transfers:
 * fluids stay in source storage until delivery; this index prevents oversubscribing destination allow-line limits.
 */
public final class DuctFluidIncomingIndex {
    private static final Map<ResourceKey<Level>, Map<BlockPos, List<FluidStack>>> BY_DIMENSION =
            new ConcurrentHashMap<>();

    private DuctFluidIncomingIndex() {}

    public static void register(ServerLevel level, BlockPos destDuct, FluidStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        List<FluidStack> list =
                BY_DIMENSION
                        .computeIfAbsent(level.dimension(), d -> new ConcurrentHashMap<>())
                        .computeIfAbsent(destDuct.immutable(), p -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(stack.copy());
        }
    }

    public static void unregister(ServerLevel level, BlockPos destDuct, FluidStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        Map<BlockPos, List<FluidStack>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return;
        }
        List<FluidStack> list = dim.get(destDuct);
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (Iterator<FluidStack> it = list.iterator(); it.hasNext(); ) {
                FluidStack s = it.next();
                if (FluidStack.isSameFluidSameComponents(s, stack) && s.getAmount() == stack.getAmount()) {
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) {
                dim.remove(destDuct);
            }
        }
    }

    public static List<FluidStack> snapshot(ServerLevel level, BlockPos destDuct) {
        Map<BlockPos, List<FluidStack>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return List.of();
        }
        List<FluidStack> list = dim.get(destDuct);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            List<FluidStack> out = new ArrayList<>(list.size());
            for (FluidStack s : list) {
                out.add(s.copy());
            }
            return out;
        }
    }
}

