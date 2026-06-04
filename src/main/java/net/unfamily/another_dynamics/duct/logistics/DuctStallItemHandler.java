package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Per-face stall buffer: respects each item's {@link ItemStack#getMaxStackSize()} (unlike vanilla
 * {@link ItemStackHandler} slot limit 64 for all items).
 */
public final class DuctStallItemHandler extends ItemStackHandler {
    public DuctStallItemHandler(int size) {
        super(Math.max(1, size));
    }

    @Override
    public int getSlotLimit(int slot) {
        ItemStack cur = getStackInSlot(slot);
        if (!cur.isEmpty()) {
            return Math.min(super.getSlotLimit(slot), cur.getMaxStackSize());
        }
        return super.getSlotLimit(slot);
    }

    @Override
    protected int getStackLimit(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return super.getStackLimit(slot, stack);
        }
        return Math.min(super.getSlotLimit(slot), stack.getMaxStackSize());
    }
}
