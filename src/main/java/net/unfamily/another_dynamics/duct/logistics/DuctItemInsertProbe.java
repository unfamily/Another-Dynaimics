package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Mod-agnostic simulated insert probing for attached {@link IItemHandler} instances. Avoids
 * {@link DuctHandlerSlotSemantics#roleForSlot} on the insert path (extract/keep/limit counting still use it).
 */
public final class DuctItemInsertProbe {
    public static final int STANDARD_SLOT_THRESHOLD = 54;
    public static final int BULK_PROBE_BUDGET = 24;
    public static final int MATCH_SCAN_CAP = 16;
    public static final int EMPTY_SCAN_CAP = 8;
    public static final int SIMULATE_CALL_CIRCUIT_BREAKER = 64;

    private DuctItemInsertProbe() {}

    /**
     * Whether {@code h} can absorb at least one unit of {@code probe} (count forced to 1).
     */
    public static boolean canAcceptOne(IItemHandler h, ItemStack probe) {
        if (probe.isEmpty() || h == null) {
            return false;
        }
        ProbeCounter counter = new ProbeCounter();
        ItemStack one = probe.copyWithCount(1);
        if (tryVirtualTail(h, one, counter)) {
            return true;
        }
        int slots = h.getSlots();
        if (slots <= STANDARD_SLOT_THRESHOLD) {
            return standardCanAccept(h, one, counter);
        }
        return bulkCanAccept(h, one, counter);
    }

    /**
     * Simulated insert capacity up to {@code limit} on {@code h} without mutating it.
     */
    public static int estimateMaxInsertable(IItemHandler h, ItemStack template, int limit) {
        if (limit <= 0 || template.isEmpty() || h == null) {
            return 0;
        }
        if (!canAcceptOne(h, template)) {
            return 0;
        }
        if (h.getSlots() <= STANDARD_SLOT_THRESHOLD) {
            return standardEstimateMaxInsertable(h, template, limit);
        }
        return bulkEstimateMaxInsertable(h, template, limit);
    }

    private static boolean tryVirtualTail(IItemHandler h, ItemStack one, ProbeCounter counter) {
        int slots = h.getSlots();
        if (slots <= 0) {
            return false;
        }
        int tail = slots - 1;
        if (!h.getStackInSlot(tail).isEmpty()) {
            return false;
        }
        ItemStack left = trackedInsert(h, tail, one.copy(), true, counter);
        return left.isEmpty();
    }

    private static boolean standardCanAccept(IItemHandler h, ItemStack one, ProbeCounter counter) {
        ItemStack remaining = one.copy();
        for (int i = 0; i < h.getSlots() && !remaining.isEmpty(); i++) {
            if (counter.exceeded()) {
                return false;
            }
            remaining = trackedInsert(h, i, remaining, true, counter);
        }
        return remaining.isEmpty();
    }

    private static boolean bulkCanAccept(IItemHandler h, ItemStack one, ProbeCounter counter) {
        int attempts = 0;
        for (int slot : bulkCandidateSlots(h, one)) {
            if (counter.exceeded() || attempts >= BULK_PROBE_BUDGET) {
                return false;
            }
            ItemStack left = trackedInsert(h, slot, one.copy(), true, counter);
            attempts++;
            if (left.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int standardEstimateMaxInsertable(IItemHandler h, ItemStack template, int limit) {
        int lo = 0;
        int hi = limit;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (standardCanInsertCountByChunking(h, template, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static boolean standardCanInsertCountByChunking(IItemHandler h, ItemStack template, int totalCount) {
        if (totalCount <= 0) {
            return true;
        }
        ProbeCounter counter = new ProbeCounter();
        int remaining = totalCount;
        int chunkSize = Math.max(1, template.getMaxStackSize());
        while (remaining > 0) {
            if (counter.exceeded()) {
                return false;
            }
            int n = Math.min(remaining, chunkSize);
            ItemStack chunk = template.copyWithCount(n);
            ItemStack left = simulateDirectInsert(h, chunk, counter);
            if (!left.isEmpty()) {
                return false;
            }
            remaining -= n;
        }
        return true;
    }

    private static int bulkEstimateMaxInsertable(IItemHandler h, ItemStack template, int limit) {
        Integer slot = findFirstBulkAcceptingSlot(h, template);
        if (slot == null) {
            return 0;
        }
        ProbeCounter counter = new ProbeCounter();
        int total = 0;
        int chunkSize = Math.max(1, template.getMaxStackSize());
        int remaining = limit;
        while (remaining > 0 && total < limit) {
            if (counter.exceeded() || counter.calls >= BULK_PROBE_BUDGET) {
                break;
            }
            int n = Math.min(remaining, chunkSize);
            ItemStack chunk = template.copyWithCount(n);
            ItemStack left = trackedInsert(h, slot, chunk, true, counter);
            int accepted = n - (left.isEmpty() ? 0 : left.getCount());
            if (accepted <= 0) {
                break;
            }
            total += accepted;
            remaining -= accepted;
            if (accepted < n) {
                break;
            }
        }
        return total;
    }

    private static Integer findFirstBulkAcceptingSlot(IItemHandler h, ItemStack template) {
        ProbeCounter counter = new ProbeCounter();
        ItemStack one = template.copyWithCount(1);
        if (tryVirtualTail(h, one, counter)) {
            return h.getSlots() - 1;
        }
        for (int slot : bulkCandidateSlots(h, template)) {
            if (counter.exceeded() || counter.calls >= BULK_PROBE_BUDGET) {
                return null;
            }
            ItemStack left = trackedInsert(h, slot, one.copy(), true, counter);
            if (left.isEmpty()) {
                return slot;
            }
        }
        return null;
    }

    private static List<Integer> bulkCandidateSlots(IItemHandler h, ItemStack template) {
        List<Integer> candidates = new ArrayList<>();
        int slots = h.getSlots();
        if (slots <= 0) {
            return candidates;
        }
        int tail = slots - 1;
        if (h.getStackInSlot(tail).isEmpty()) {
            candidates.add(tail);
        }
        int matchCount = 0;
        int emptyCount = 0;
        for (int i = 0; i < slots; i++) {
            if (i == tail && candidates.contains(tail)) {
                continue;
            }
            ItemStack inSlot = h.getStackInSlot(i);
            if (!inSlot.isEmpty()
                    && ItemStack.isSameItemSameComponents(inSlot, template)
                    && matchCount < MATCH_SCAN_CAP) {
                candidates.add(i);
                matchCount++;
            } else if (inSlot.isEmpty() && emptyCount < EMPTY_SCAN_CAP) {
                candidates.add(i);
                emptyCount++;
            }
            if (matchCount >= MATCH_SCAN_CAP && emptyCount >= EMPTY_SCAN_CAP) {
                break;
            }
        }
        return candidates;
    }

    private static ItemStack simulateDirectInsert(IItemHandler h, ItemStack stack, ProbeCounter counter) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < h.getSlots() && !remaining.isEmpty(); i++) {
            if (counter.exceeded()) {
                return remaining;
            }
            remaining = trackedInsert(h, i, remaining, true, counter);
        }
        return remaining;
    }

    private static ItemStack trackedInsert(
            IItemHandler h, int slot, ItemStack stack, boolean simulate, ProbeCounter counter) {
        counter.calls++;
        return h.insertItem(slot, stack, simulate);
    }

    private static final class ProbeCounter {
        int calls;

        boolean exceeded() {
            return calls >= SIMULATE_CALL_CIRCUIT_BREAKER;
        }
    }
}
