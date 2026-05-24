package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Classifies inventory slots for attached machine handlers: input-only (insert), output-only (extract), or both.
 * Runtime insert/extract checks use simulate calls on the real {@link ItemStack} template, not role guesses alone.
 */
public final class DuctHandlerSlotSemantics {
    public enum SlotRole {
        INPUT,
        OUTPUT,
        BOTH
    }

    private DuctHandlerSlotSemantics() {}

    public static SlotRole roleForSlot(IItemHandler handler, int slot) {
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return SlotRole.BOTH;
        }
        ItemStack inSlot = handler.getStackInSlot(slot);
        boolean canIn = canInsertOne(handler, slot);
        boolean canEx = canExtractOne(handler, slot);
        if (canIn && canEx) {
            return SlotRole.BOTH;
        }
        if (canIn) {
            return SlotRole.INPUT;
        }
        if (canEx) {
            return SlotRole.OUTPUT;
        }
        // Non-empty but both probes failed: allow both so logistics is not wedged on odd handlers.
        if (!inSlot.isEmpty()) {
            return SlotRole.BOTH;
        }
        return SlotRole.BOTH;
    }

    /**
     * Whether logistics may pull from this slot. Uses {@link SlotRole} only — do not require a successful
     * {@code extractItem(..., 1, true)} probe here; many handlers reject single-item simulation while still accepting
     * larger extracts, which would cap batches to one item.
     */
    public static boolean canExtractFromSlot(IItemHandler handler, int slot) {
        if (handler == null || slot < 0 || slot >= handler.getSlots()) {
            return false;
        }
        if (handler.getStackInSlot(slot).isEmpty()) {
            return false;
        }
        return roleForSlot(handler, slot) != SlotRole.INPUT;
    }

    public static boolean canInsertIntoSlot(IItemHandler handler, int slot, ItemStack template) {
        if (template.isEmpty() || handler == null || slot < 0 || slot >= handler.getSlots()) {
            return false;
        }
        ItemStack probe = template.copyWithCount(1);
        ItemStack left = handler.insertItem(slot, probe, true);
        return left.isEmpty() || left.getCount() < probe.getCount();
    }

    private static boolean canInsertOne(IItemHandler handler, int slot) {
        ItemStack inSlot = handler.getStackInSlot(slot);
        ItemStack probe =
                inSlot.isEmpty()
                        ? new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 1)
                        : inSlot.copyWithCount(1);
        ItemStack left = handler.insertItem(slot, probe, true);
        return left.isEmpty() || left.getCount() < probe.getCount();
    }

    private static boolean canExtractOne(IItemHandler handler, int slot) {
        ItemStack inSlot = handler.getStackInSlot(slot);
        if (inSlot.isEmpty()) {
            return false;
        }
        ItemStack sim = handler.extractItem(slot, 1, true);
        return !sim.isEmpty();
    }
}
