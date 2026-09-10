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
    /** Cap for light {@link #canAcceptOne} walks (not used for capacity estimates). */
    public static final int SIMULATE_CALL_CIRCUIT_BREAKER = 64;
    /** Hard ceiling on slot visits during a full-handler capacity estimate. */
    public static final int ESTIMATE_SLOT_WALK_CAP = 4096;

    private DuctItemInsertProbe() {}

    /**
     * Whether {@code h} can absorb at least one unit of {@code probe} (count forced to 1).
     */
    public static boolean canAcceptOne(IItemHandler h, ItemStack probe) {
        if (probe.isEmpty() || h == null) {
            return false;
        }
        try {
            ProbeCounter counter = new ProbeCounter(SIMULATE_CALL_CIRCUIT_BREAKER);
            ItemStack one = probe.copyWithCount(1);
            if (tryVirtualTail(h, one, counter)) {
                return true;
            }
            int slots = h.getSlots();
            if (slots <= STANDARD_SLOT_THRESHOLD) {
                return standardCanAccept(h, one, counter);
            }
            return bulkCanAccept(h, one, counter);
        } finally {
            // Sequential Buffer tracks simulate need across slot walks; reset so the next probe starts clean.
            net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity
                    .clearSimulatedItemNeedConsumed();
        }
    }

    /**
     * Simulated insert capacity up to {@code limit} on {@code h} without mutating it.
     * Uses a single leftover-shrinking pass across slots ({@code simulate=true}); independent
     * per-chunk restarts would over-report free space.
     */
    public static int estimateMaxInsertable(IItemHandler h, ItemStack template, int limit) {
        if (limit <= 0 || template.isEmpty() || h == null) {
            return 0;
        }
        if (!canAcceptOne(h, template)) {
            return 0;
        }
        try {
            int budget = estimateCallBudget(h.getSlots());
            ProbeCounter counter = new ProbeCounter(budget);
            ItemStack leftover = insertDirect(h, template.copyWithCount(limit), true, counter);
            int accepted = limit - leftover.getCount();
            return Math.max(0, accepted);
        } finally {
            net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity
                    .clearSimulatedItemNeedConsumed();
        }
    }

    private static int estimateCallBudget(int slots) {
        if (slots <= 0) {
            return 1;
        }
        long budget = (long) slots + 1L;
        if (budget > ESTIMATE_SLOT_WALK_CAP) {
            return ESTIMATE_SLOT_WALK_CAP;
        }
        return (int) budget;
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

    private static ItemStack insertDirect(
            IItemHandler h, ItemStack stack, boolean simulate, ProbeCounter counter) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < h.getSlots() && !remaining.isEmpty(); i++) {
            if (counter.exceeded()) {
                return remaining;
            }
            remaining = trackedInsert(h, i, remaining, simulate, counter);
        }
        return remaining;
    }

    private static ItemStack trackedInsert(
            IItemHandler h, int slot, ItemStack stack, boolean simulate, ProbeCounter counter) {
        counter.calls++;
        return h.insertItem(slot, stack, simulate);
    }

    private static final class ProbeCounter {
        final int maxCalls;
        int calls;

        ProbeCounter(int maxCalls) {
            this.maxCalls = Math.max(1, maxCalls);
        }

        boolean exceeded() {
            return calls >= maxCalls;
        }
    }
}
