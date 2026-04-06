package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.ItemDuctBlockEntity;

/**
 * ItemHandler access for ducts facing external storage.
 */
public final class DuctCapHelper {
    private DuctCapHelper() {}

    public static ItemStack insertIntoStorageFaces(Level level, BlockPos ductPos, ItemDuctBlockEntity duct, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        int mask = duct.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            remaining = ItemHandlerHelper.insertItemStacked(h, remaining, false);
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    /**
     * Extract up to {@code max} items from the first non-empty storage face (in direction order).
     */
    public static ItemStack extractFromStorageFaces(Level level, BlockPos ductPos, ItemDuctBlockEntity duct, int max) {
        if (max <= 0) {
            return ItemStack.EMPTY;
        }
        int mask = duct.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack inSlot = h.getStackInSlot(slot);
                if (inSlot.isEmpty()) {
                    continue;
                }
                ItemStack extracted = h.extractItem(slot, max, false);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Simulate-only: first extractable item (1 unit) from attached storage. */
    public static Optional<ItemStack> simulateExtractOne(Level level, BlockPos ductPos, ItemDuctBlockEntity duct) {
        int mask = duct.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack sim = h.extractItem(slot, 1, true);
                if (!sim.isEmpty()) {
                    return Optional.of(sim);
                }
            }
        }
        return Optional.empty();
    }

    public static boolean canInsertIntoStorageFaces(Level level, BlockPos ductPos, ItemDuctBlockEntity duct, ItemStack probe) {
        if (probe.isEmpty()) {
            return true;
        }
        ItemStack sim = probe.copy();
        int mask = duct.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            sim = ItemHandlerHelper.insertItemStacked(h, sim, true);
            if (sim.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether stacks can be inserted one after another through the same face order as
     * {@link #insertIntoStorageFaces}, using cloned handlers so the real world is untouched.
     */
    public static boolean canInsertStacksSequentially(
            Level level, BlockPos ductPos, ItemDuctBlockEntity duct, List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return true;
        }
        List<ItemStackHandler> virtuals = new ArrayList<>();
        int mask = duct.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            virtuals.add(copyHandlerForSimulation(h));
        }
        if (virtuals.isEmpty()) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            for (ItemStackHandler v : virtuals) {
                remaining = ItemHandlerHelper.insertItemStacked(v, remaining, false);
                if (remaining.isEmpty()) {
                    break;
                }
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static ItemStackHandler copyHandlerForSimulation(IItemHandler h) {
        ItemStackHandler c = new ItemStackHandler(h.getSlots());
        for (int i = 0; i < h.getSlots(); i++) {
            c.setStackInSlot(i, h.getStackInSlot(i).copy());
        }
        return c;
    }
}
