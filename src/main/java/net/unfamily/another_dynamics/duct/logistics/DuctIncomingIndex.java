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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Tracks stacks currently in transit toward a destination duct (RAM only). Used for capacity checks;
 * {@link net.unfamily.another_dynamics.duct.ItemDuctBlockEntity} persists the actual buffers.
 */
public final class DuctIncomingIndex {
    private static final Map<ResourceKey<Level>, Map<BlockPos, List<ItemStack>>> BY_DIMENSION =
            new ConcurrentHashMap<>();

    private DuctIncomingIndex() {}

    public static void register(ServerLevel level, BlockPos destDuct, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        List<ItemStack> list =
                BY_DIMENSION
                        .computeIfAbsent(level.dimension(), d -> new ConcurrentHashMap<>())
                        .computeIfAbsent(destDuct.immutable(), p -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(stack.copy());
        }
    }

    public static void unregister(ServerLevel level, BlockPos destDuct, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        Map<BlockPos, List<ItemStack>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return;
        }
        List<ItemStack> list = dim.get(destDuct);
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (Iterator<ItemStack> it = list.iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() == stack.getCount()) {
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) {
                dim.remove(destDuct);
            }
        }
    }

    public static List<ItemStack> snapshot(ServerLevel level, BlockPos destDuct) {
        Map<BlockPos, List<ItemStack>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return List.of();
        }
        List<ItemStack> list = dim.get(destDuct);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            List<ItemStack> out = new ArrayList<>(list.size());
            for (ItemStack s : list) {
                out.add(s.copy());
            }
            return out;
        }
    }
}
