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
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

import org.jetbrains.annotations.Nullable;

/**
 * Item-handler helpers for {@link DuctBlockEntity} (item transport lane). Other transport kinds get parallel helpers later.
 */
public final class DuctCapHelper {
    private DuctCapHelper() {}

    @Nullable
    public static IItemHandler getHandlerOnFace(Level level, BlockPos ductPos, Direction ductOutwardFace) {
        BlockPos adj = ductPos.relative(ductOutwardFace);
        return level.getCapability(Capabilities.ItemHandler.BLOCK, adj, ductOutwardFace.getOpposite());
    }

    public static ItemStack insertIntoFace(
            Level level, BlockPos ductPos, Direction ductOutwardFace, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, ductOutwardFace);
        if (h == null) {
            return stack.copy();
        }
        return ItemHandlerHelper.insertItemStacked(h, stack.copy(), false);
    }

    public static Optional<ItemStack> simulateExtractOneOnFace(Level level, BlockPos ductPos, Direction face) {
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            return Optional.empty();
        }
        for (int slot = 0; slot < h.getSlots(); slot++) {
            ItemStack sim = h.extractItem(slot, 1, true);
            if (!sim.isEmpty()) {
                return Optional.of(sim);
            }
        }
        return Optional.empty();
    }

    public static boolean canInsertIntoFace(Level level, BlockPos ductPos, Direction face, ItemStack probe) {
        if (probe.isEmpty()) {
            return true;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            return false;
        }
        return simulateInsertIntoHandler(h, probe.copy()).isEmpty();
    }

    public static int countExtractableMatchingOnFace(
            Level level, BlockPos ductPos, Direction face, ItemStack template, int max) {
        if (max <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < h.getSlots(); slot++) {
            ItemStack inSlot = h.getStackInSlot(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) {
                continue;
            }
            int want = max - total;
            if (want <= 0) {
                return max;
            }
            ItemStack sim = h.extractItem(slot, want, true);
            if (!sim.isEmpty()) {
                total += sim.getCount();
                if (total >= max) {
                    return max;
                }
            }
        }
        return total;
    }

    public static ItemStack extractMatchingUpToOnFace(
            Level level, BlockPos ductPos, Direction face, ItemStack template, int maxCount) {
        if (maxCount <= 0 || template.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int need = Math.min(maxCount, template.getCount());
        if (need <= 0) {
            return ItemStack.EMPTY;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < h.getSlots(); slot++) {
            ItemStack inSlot = h.getStackInSlot(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) {
                continue;
            }
            int take = Math.min(need, inSlot.getCount());
            if (take <= 0) {
                continue;
            }
            ItemStack ex = h.extractItem(slot, take, false);
            if (ex.isEmpty()) {
                continue;
            }
            if (result.isEmpty()) {
                result = ex;
            } else {
                result.grow(ex.getCount());
            }
            need -= ex.getCount();
            if (need <= 0) {
                return result;
            }
        }
        return result;
    }

    public static int maxInsertableAfterPendingOnFace(
            Level level,
            BlockPos ductPos,
            Direction face,
            ItemStack template,
            int limit,
            List<ItemStack> priorPending) {
        if (limit <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            return 0;
        }
        // Apply all in-flight pending into a virtual copy of the destination handler, then measure how many more
        // `template` fit (up to `limit`). Avoids subtracting pending from a single-stack probe capped at maxStackSize,
        // which wrongly returned 0 when many batches were in transit (e.g. 8×8 pending vs physicalCap≤64).
        ItemStackHandler virtual = simulateInventoryAfterPending(h, priorPending);
        int physicalCap = simulateMaxInsertableIntoHandler(virtual, template, limit);
        return Math.max(0, Math.min(limit, physicalCap));
    }

    public static ItemStack insertIntoStorageFaces(Level level, BlockPos ductPos, DuctBlockEntity duct, ItemStack stack) {
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
    public static ItemStack extractFromStorageFaces(Level level, BlockPos ductPos, DuctBlockEntity duct, int max) {
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
    public static Optional<ItemStack> simulateExtractOne(Level level, BlockPos ductPos, DuctBlockEntity duct) {
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

    public static boolean canInsertIntoStorageFaces(Level level, BlockPos ductPos, DuctBlockEntity duct, ItemStack probe) {
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
     * How many items matching {@code template} (same item + components) could be extracted from attached storage, up to {@code max}.
     */
    public static int countExtractableMatching(
            Level level, BlockPos ductPos, DuctBlockEntity duct, ItemStack template, int max) {
        if (max <= 0 || template.isEmpty()) {
            return 0;
        }
        int total = 0;
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
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) {
                    continue;
                }
                int want = max - total;
                if (want <= 0) {
                    return max;
                }
                ItemStack sim = h.extractItem(slot, want, true);
                if (!sim.isEmpty()) {
                    total += sim.getCount();
                    if (total >= max) {
                        return max;
                    }
                }
            }
        }
        return total;
    }

    /**
     * Extract up to {@code maxCount} items matching {@code template} from attached storage (same item + components).
     */
    public static ItemStack extractMatchingUpTo(
            Level level, BlockPos ductPos, DuctBlockEntity duct, ItemStack template, int maxCount) {
        if (maxCount <= 0 || template.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int need = Math.min(maxCount, template.getCount());
        if (need <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
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
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) {
                    continue;
                }
                int take = Math.min(need, inSlot.getCount());
                if (take <= 0) {
                    continue;
                }
                ItemStack ex = h.extractItem(slot, take, false);
                if (ex.isEmpty()) {
                    continue;
                }
                if (result.isEmpty()) {
                    result = ex;
                } else {
                    result.grow(ex.getCount());
                }
                need -= ex.getCount();
                if (need <= 0) {
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * Max count (0..{@code limit}) for {@code template} inserted <strong>after</strong> {@code priorPending} in one sequential pass.
     */
    public static int maxInsertableAfterPending(
            Level level,
            BlockPos ductPos,
            DuctBlockEntity duct,
            ItemStack template,
            int limit,
            List<ItemStack> priorPending) {
        if (limit <= 0 || template.isEmpty()) {
            return 0;
        }
        int cap = Math.min(limit, template.getMaxStackSize());
        int lo = 0;
        int hi = cap;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            List<ItemStack> order = new ArrayList<>(priorPending.size() + 1);
            for (ItemStack p : priorPending) {
                order.add(p.copy());
            }
            ItemStack last = template.copy();
            last.setCount(mid);
            order.add(last);
            if (canInsertStacksSequentially(level, ductPos, duct, order)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public static boolean canInsertStacksSequentially(
            Level level, BlockPos ductPos, DuctBlockEntity duct, List<ItemStack> stacks) {
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

    /**
     * Snapshot of {@code h} with {@code priorPending} stacks merged in (same order as
     * {@link #maxInsertableAfterPendingOnFace}).
     */
    public static ItemStackHandler simulateInventoryAfterPending(IItemHandler h, List<ItemStack> priorPending) {
        ItemStackHandler v = copyHandlerForSimulation(h);
        if (priorPending != null) {
            for (ItemStack p : priorPending) {
                if (p.isEmpty()) {
                    continue;
                }
                ItemHandlerHelper.insertItemStacked(v, p.copy(), false);
            }
        }
        return v;
    }

    private static ItemStackHandler copyHandlerForSimulation(IItemHandler h) {
        ItemStackHandler c = new ItemStackHandler(h.getSlots());
        for (int i = 0; i < h.getSlots(); i++) {
            c.setStackInSlot(i, h.getStackInSlot(i).copy());
        }
        return c;
    }

    /**
     * Simulate insertion into a real handler (respects slot validity/filters) without mutating it.
     */
    private static ItemStack simulateInsertIntoHandler(IItemHandler h, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (int i = 0; i < h.getSlots() && !remaining.isEmpty(); i++) {
            remaining = h.insertItem(i, remaining, true);
        }
        return remaining;
    }

    private static int simulateMaxInsertableIntoHandler(IItemHandler h, ItemStack template, int limit) {
        if (limit <= 0 || template.isEmpty()) {
            return 0;
        }
        int cap = Math.min(limit, template.getMaxStackSize());
        int lo = 0;
        int hi = cap;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            ItemStack test = template.copy();
            test.setCount(mid);
            if (simulateInsertIntoHandler(h, test).isEmpty()) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
