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
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;

import org.jetbrains.annotations.Nullable;

/**
 * Item-handler helpers for {@link DuctBlockEntity} (item transport lane). Other transport kinds get parallel helpers later.
 */
public final class DuctCapHelper {
    private DuctCapHelper() {}

    /** Per-operation item count cap from GUI/datapack batch and the item's max stack size. */
    public static int clampOperationCount(ItemStack template, int requested) {
        if (template.isEmpty() || requested <= 0) {
            return 0;
        }
        return Math.min(requested, Math.max(1, template.getMaxStackSize()));
    }

    /** Clamps {@code stack} count to {@link ItemStack#getMaxStackSize()} in place. */
    public static void normalizeStackCount(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        int max = Math.max(1, stack.getMaxStackSize());
        if (stack.getCount() > max) {
            stack.setCount(max);
        }
    }

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

    /** Items accepted by {@link #insertIntoFace} (may be less than {@code attempted} when inventory fills per stack). */
    public static int countInsertedIntoFace(
            Level level, BlockPos ductPos, Direction ductOutwardFace, ItemStack attempted) {
        if (attempted.isEmpty()) {
            return 0;
        }
        ItemStack remainder = insertIntoFace(level, ductPos, ductOutwardFace, attempted);
        return countAccepted(attempted, remainder);
    }

    public static int countInsertedIntoStorageFaces(
            Level level, BlockPos ductPos, DuctBlockEntity duct, ItemStack attempted) {
        if (attempted.isEmpty()) {
            return 0;
        }
        ItemStack remainder = insertIntoStorageFaces(level, ductPos, duct, attempted);
        return countAccepted(attempted, remainder);
    }

    public static int countAccepted(ItemStack attempted, ItemStack remainder) {
        if (attempted.isEmpty()) {
            return 0;
        }
        int left = remainder.isEmpty() ? 0 : remainder.getCount();
        return Math.max(0, attempted.getCount() - left);
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

    public static boolean outboundStallHasContent(DuctFaceLanes lanes) {
        for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
            if (!lanes.stalledBuffer.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean inboundStallHasContent(DuctFaceLanes lanes) {
        for (int i = 0; i < lanes.inboundStallBuffer.getSlots(); i++) {
            if (!lanes.inboundStallBuffer.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean faceHasItemStallContent(DuctFaceLanes lanes) {
        return outboundStallHasContent(lanes) || inboundStallHasContent(lanes);
    }

    /**
     * Cheap donor pre-check for retriever routing lists: attached handler has any extractable stack, or the face stall
     * buffer is non-empty. Full filter/insert probes run only when a donor is actually visited.
     */
    public static boolean donorMaySupplyRetriever(Level level, BlockPos donorPos, Direction donorFace, DuctBlockEntity donorBe) {
        DuctFaceLanes lanes = donorBe.getFaceLanes(donorFace);
        if (outboundStallHasContent(lanes) || inboundStallHasContent(lanes)) {
            return true;
        }
        IItemHandler h = getHandlerOnFace(level, donorPos, donorFace);
        if (h == null) {
            return false;
        }
        for (int slot = 0; slot < h.getSlots(); slot++) {
            if (!DuctHandlerSlotSemantics.canExtractFromSlot(h, slot)) {
                continue;
            }
            if (!h.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * First stack on {@code donorFace} that can be retrieved: passes retriever/donor filter banks and can insert into the
     * retriever inventory face. Unlike {@link #simulateExtractOneOnFace}, skips slots that only fail filter rules.
     */
    public static Optional<ItemStack> findRetrievableProbeOnFace(
            Level level,
            BlockPos donorPos,
            Direction donorFace,
            DuctBlockEntity donorBe,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            DuctBlockEntity retrieverBe) {
        IItemHandler h = getHandlerOnFace(level, donorPos, donorFace);
        if (h == null) {
            return Optional.empty();
        }
        for (int slot = 0; slot < h.getSlots(); slot++) {
            ItemStack probe = h.extractItem(slot, 1, true);
            if (probe.isEmpty()) {
                continue;
            }
            if (!retrieverBe.passesItemFilters(
                    retrieverInventoryFace, probe, level, DuctFaceNode.FilterBank.RETRIEVER)) {
                continue;
            }
            if (!donorBe.passesItemFilters(donorFace, probe, level, DuctFaceNode.FilterBank.FILTER)) {
                continue;
            }
            if (!canInsertIntoFace(level, retrieverPos, retrieverInventoryFace, probe)) {
                continue;
            }
            return Optional.of(probe);
        }
        return Optional.empty();
    }

    /**
     * Stall-buffer counterpart to {@link #findRetrievableProbeOnFace}: donor has no extractable stack on the attached
     * handler but still holds retrievable items in the duct face stall slots.
     */
    public static Optional<ItemStack> findRetrievableProbeInStall(
            Level level,
            BlockPos donorPos,
            Direction donorFace,
            DuctBlockEntity donorBe,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            DuctBlockEntity retrieverBe) {
        DuctFaceLanes lanes = donorBe.getFaceLanes(donorFace);
        for (int slot = 0; slot < lanes.stalledBuffer.getSlots(); slot++) {
            ItemStack probe = lanes.stalledBuffer.getStackInSlot(slot);
            if (probe.isEmpty()) {
                continue;
            }
            if (!retrieverBe.passesItemFilters(
                    retrieverInventoryFace, probe, level, DuctFaceNode.FilterBank.RETRIEVER)) {
                continue;
            }
            if (!donorBe.passesItemFilters(donorFace, probe, level, DuctFaceNode.FilterBank.FILTER)) {
                continue;
            }
            if (!canInsertIntoFace(level, retrieverPos, retrieverInventoryFace, probe)) {
                continue;
            }
            return Optional.of(probe);
        }
        return Optional.empty();
    }

    public static boolean canInsertIntoFace(Level level, BlockPos ductPos, Direction face, ItemStack probe) {
        if (probe.isEmpty()) {
            return true;
        }
        if (level != null && !level.isClientSide()) {
            if (DuctInsertProbeCache.isRejected(level, ductPos, face, probe)) {
                return false;
            }
            Boolean cached = DuctInsertProbeCache.getCachedAccept(level, ductPos, face, probe);
            if (cached != null) {
                return cached;
            }
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            if (level != null && !level.isClientSide()) {
                DuctInsertProbeCache.recordReject(level, ductPos, face, probe);
            }
            return false;
        }
        // Destination selection should only require "can insert something", not "can insert the whole batch in one call".
        // Drawers-like inventories may accept only part of a larger probe stack (or expose large capacities through a
        // small slot count), so probing with count=1 is the most compatible.
        ItemStack one = probe.copyWithCount(1);
        boolean accepted = DuctItemInsertProbe.canAcceptOne(h, one);
        if (level != null && !level.isClientSide()) {
            if (accepted) {
                DuctInsertProbeCache.cacheAccept(level, ductPos, face, probe, true);
            } else {
                DuctInsertProbeCache.recordReject(level, ductPos, face, probe);
            }
        }
        return accepted;
    }

    public static int countExtractableMatchingOnFace(
            Level level, BlockPos ductPos, Direction face, ItemStack template, int max) {
        if (max <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        return countExtractableMatchingFromHandler(h, template, max);
    }

    /**
     * Simulate-only counterpart to {@link #extractMatchingFromHandler}: sums matching items across every
     * extractable slot (and repeated simulate calls per slot when the handler caps one pull).
     */
    public static int countExtractableMatchingFromHandler(IItemHandler h, ItemStack template, int max) {
        if (max <= 0 || template.isEmpty() || h == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < h.getSlots() && total < max; slot++) {
            if (!DuctHandlerSlotSemantics.canExtractFromSlot(h, slot)) {
                continue;
            }
            int want = max - total;
            while (want > 0) {
                ItemStack inSlot = h.getStackInSlot(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) {
                    break;
                }
                int take = Math.min(want, inSlot.getCount());
                if (take <= 0) {
                    break;
                }
                ItemStack sim = h.extractItem(slot, take, true);
                if (sim.isEmpty()) {
                    break;
                }
                total += sim.getCount();
                want -= sim.getCount();
                if (sim.getCount() < take) {
                    break;
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
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        if (h == null) {
            return ItemStack.EMPTY;
        }
        return extractMatchingFromHandler(h, template, maxCount);
    }

    /**
     * Extract up to {@code maxCount} matching items in one logical operation, walking every extractable slot on
     * {@code h} in order. When a handler caps one {@link IItemHandler#extractItem} call (e.g. vanilla stack size), the
     * same slot is polled again until it is empty or {@code maxCount} is reached, then the next matching slot is used.
     */
    public static ItemStack extractMatchingFromHandler(IItemHandler h, ItemStack template, int maxCount) {
        if (maxCount <= 0 || template.isEmpty() || h == null) {
            return ItemStack.EMPTY;
        }
        int need = clampOperationCount(template, maxCount);
        if (need <= 0) {
            return ItemStack.EMPTY;
        }
        int perStackMax = Math.max(1, template.getMaxStackSize());
        ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < h.getSlots() && need > 0; slot++) {
            if (!DuctHandlerSlotSemantics.canExtractFromSlot(h, slot)) {
                continue;
            }
            while (need > 0) {
                ItemStack inSlot = h.getStackInSlot(slot);
                if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, template)) {
                    break;
                }
                int take = Math.min(need, Math.min(inSlot.getCount(), perStackMax));
                if (take <= 0) {
                    break;
                }
                ItemStack ex = h.extractItem(slot, take, false);
                if (ex.isEmpty()) {
                    break;
                }
                normalizeStackCount(ex);
                if (result.isEmpty()) {
                    result = ex.copy();
                } else {
                    int merged = Math.min(perStackMax, result.getCount() + ex.getCount());
                    result.setCount(merged);
                }
                need -= ex.getCount();
                if (result.getCount() >= perStackMax) {
                    need = 0;
                    break;
                }
                if (ex.getCount() < take) {
                    break;
                }
            }
        }
        normalizeStackCount(result);
        return result;
    }

    /**
     * Simulated insert capacity on a face handler without subtracting in-flight {@link DuctIncomingIndex}
     * reservations (items are not in the destination yet). Use for scheduling; delivery still re-simulates.
     */
    public static int maxInsertableOnFace(
            Level level, BlockPos ductPos, Direction face, ItemStack template, int limit) {
        if (limit <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler h = getHandlerOnFace(level, ductPos, face);
        return maxInsertableOnHandler(h, template, limit);
    }

    public static int maxInsertableOnHandler(IItemHandler h, ItemStack template, int limit) {
        if (limit <= 0 || template.isEmpty() || h == null) {
            return 0;
        }
        return DuctItemInsertProbe.estimateMaxInsertable(h, template, limit);
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
        // Measure insert capacity on the real handler using repeated simulated inserts (chunked by max stack size).
        // Do not copy into an ItemStackHandler: drawers-like inventories expose large storage through a small slot count,
        // and a plain ItemStackHandler snapshot would clamp to vanilla stack limits and under-estimate capacity.
        int physicalCap = DuctItemInsertProbe.estimateMaxInsertable(h, template, limit);
        int pendingSame = countSameItemCount(priorPending, template);
        return Math.max(0, Math.min(limit, physicalCap - pendingSame));
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
            if (total >= max) {
                return max;
            }
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            total += countExtractableMatchingFromHandler(h, template, max - total);
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
        int need = maxCount;
        ItemStack result = ItemStack.EMPTY;
        int mask = duct.getStorageMask();
        for (Direction dir : Direction.values()) {
            if (need <= 0) {
                break;
            }
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            BlockPos adj = ductPos.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            ItemStack fromFace = extractMatchingFromHandler(h, template, need);
            if (fromFace.isEmpty()) {
                continue;
            }
            if (result.isEmpty()) {
                result = fromFace;
            } else {
                result.setCount(result.getCount() + fromFace.getCount());
            }
            need -= fromFace.getCount();
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
        // Do not cap `hi` at template.getMaxStackSize(): stacked handlers may accept >64 in one logical insert pass.
        int hi = limit;
        int lo = 0;
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

    private static int countSameItemCount(@Nullable List<ItemStack> stacks, ItemStack template) {
        if (stacks == null || stacks.isEmpty() || template.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (ItemStack s : stacks) {
            if (s.isEmpty()) {
                continue;
            }
            if (ItemStack.isSameItemSameComponents(s, template)) {
                sum += s.getCount();
            }
        }
        return Math.max(0, sum);
    }
}
