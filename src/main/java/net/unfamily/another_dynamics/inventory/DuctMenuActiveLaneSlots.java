package net.unfamily.another_dynamics.inventory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.module.DuctFaceModuleItemHandler;

/**
 * Virtual menu handler: module indices map to {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#moduleSlots}.
 * The copy-settings slot is a separate {@link CopySettingsSlot} in {@link DuctNodeMenu}.
 */
public final class DuctMenuActiveLaneSlots extends ItemStackHandler {
    private final DuctBlockEntity duct;
    private final net.minecraft.core.Direction face;
    private final int moduleSlotCount;

    public DuctMenuActiveLaneSlots(DuctBlockEntity duct, net.minecraft.core.Direction face, int moduleSlotCount) {
        super(Math.max(0, moduleSlotCount));
        this.duct = duct;
        this.face = face;
        this.moduleSlotCount = Math.max(0, moduleSlotCount);
    }

    public int moduleSlotCount() {
        return moduleSlotCount;
    }

    private DuctFaceModuleItemHandler moduleHandler() {
        return duct.getFaceLanes(face).moduleSlots;
    }

    @Override
    public int getSlots() {
        return moduleSlotCount;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().getStackInSlot(slot);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (slot >= 0 && slot < moduleSlotCount) {
            moduleHandler().setStackInSlot(slot, stack);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().getSlotLimit(slot);
        }
        return 0;
    }

    /**
     * Stack-aware limit for module slots ({@link net.neoforged.neoforge.items.SlotItemHandler#getMaxStackSize(ItemStack)}
     * ignores {@link net.neoforged.neoforge.items.ItemStackHandler#getStackLimit(int, ItemStack)}).
     */
    public int moduleInsertLimit(int slot, ItemStack stack) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().stackInsertLimit(slot, stack);
        }
        return 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().isItemValid(slot, stack);
        }
        return false;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().insertItem(slot, stack, simulate);
        }
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().extractItem(slot, amount, simulate);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setSize(int size) {
        // Size is fixed from duct definition for this menu instance.
    }
}
