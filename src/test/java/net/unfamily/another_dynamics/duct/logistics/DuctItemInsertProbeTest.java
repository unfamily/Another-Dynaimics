package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuctItemInsertProbeTest {
    @Test
    void standardHandlerAcceptsIntoPartialStack() {
        ItemStackHandler handler = new ItemStackHandler(54);
        handler.setStackInSlot(10, new ItemStack(Items.COBBLESTONE, 32));
        ItemStack probe = new ItemStack(Items.COBBLESTONE, 1);

        assertTrue(DuctItemInsertProbe.canAcceptOne(handler, probe));
        assertEquals(32, DuctItemInsertProbe.estimateMaxInsertable(handler, probe, 64));
    }

    @Test
    void bulkVirtualTailAcceptsWithoutFullScan() {
        VirtualTailHandler handler = new VirtualTailHandler(200);
        ItemStack probe = new ItemStack(Items.IRON_INGOT, 1);

        assertTrue(DuctItemInsertProbe.canAcceptOne(handler, probe));
        assertEquals(1, handler.insertCalls);
        assertEquals(199, handler.lastSlot);
    }

    @Test
    void bulkMergeUsesMatchingSlotFirst() {
        TailBlockedMergeHandler handler = new TailBlockedMergeHandler(200);
        handler.setStackInSlot(199, new ItemStack(Items.STONE, 1));
        handler.setStackInSlot(42, new ItemStack(Items.GOLD_INGOT, 8));
        ItemStack probe = new ItemStack(Items.GOLD_INGOT, 1);

        assertTrue(DuctItemInsertProbe.canAcceptOne(handler, probe));
        assertTrue(handler.insertCalls <= 4, "expected bounded merge probe, got " + handler.insertCalls);
        assertEquals(42, handler.lastSlot);
    }

    @Test
    void bulkEstimateCapsAtLimit() {
        VirtualTailHandler handler = new VirtualTailHandler(120);
        ItemStack probe = new ItemStack(Items.DIAMOND, 1);

        assertTrue(DuctItemInsertProbe.canAcceptOne(handler, probe));
        assertEquals(16, DuctItemInsertProbe.estimateMaxInsertable(handler, probe, 16));
    }

    @Test
    void rejectsWhenNoSlotAccepts() {
        RejectAllHandler handler = new RejectAllHandler(80);
        ItemStack probe = new ItemStack(Items.COBBLESTONE, 1);

        assertFalse(DuctItemInsertProbe.canAcceptOne(handler, probe));
        assertEquals(0, DuctItemInsertProbe.estimateMaxInsertable(handler, probe, 64));
    }

    /** Only the last slot accepts inserts (Deep Drawer-like virtual tail). */
    private static class VirtualTailHandler extends ItemStackHandler {
        int insertCalls;
        int lastSlot = -1;

        VirtualTailHandler(int slots) {
            super(slots);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            insertCalls++;
            lastSlot = slot;
            if (slot != getSlots() - 1) {
                return stack;
            }
            return super.insertItem(slot, stack, simulate);
        }
    }

    private static class TailBlockedMergeHandler extends ItemStackHandler {
        int insertCalls;
        int lastSlot = -1;

        TailBlockedMergeHandler(int slots) {
            super(slots);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            insertCalls++;
            lastSlot = slot;
            ItemStack in = getStackInSlot(slot);
            if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, stack)) {
                return super.insertItem(slot, stack, simulate);
            }
            return stack;
        }
    }

    private static final class RejectAllHandler extends ItemStackHandler {
        RejectAllHandler(int slots) {
            super(slots);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }
    }
}
