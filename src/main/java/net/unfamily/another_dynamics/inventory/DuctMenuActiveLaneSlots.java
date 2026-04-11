package net.unfamily.another_dynamics.inventory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Virtual menu handler: upgrade indices map to {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#upgradeSlots};
 * the last index is the per-lane copy slot ({@link net.unfamily.another_dynamics.duct.DuctFaceNode#guiSlots} slot 0).
 */
public final class DuctMenuActiveLaneSlots extends ItemStackHandler {
    private final DuctBlockEntity duct;
    private final net.minecraft.core.Direction face;
    private final int upgradeSlotCount;

    public DuctMenuActiveLaneSlots(DuctBlockEntity duct, net.minecraft.core.Direction face, int upgradeSlotCount) {
        super(Math.max(0, upgradeSlotCount) + 1);
        this.duct = duct;
        this.face = face;
        this.upgradeSlotCount = Math.max(0, upgradeSlotCount);
    }

    public int upgradeSlotCount() {
        return upgradeSlotCount;
    }

    private ItemStackHandler upgradeHandler() {
        return duct.getFaceLanes(face).upgradeSlots;
    }

    private ItemStackHandler copyHandler() {
        // On the multi-transport hub, {@code menuActiveTransportKind} is the first tab until the player picks one; the
        // copy slot must still target a stable lane so inserts/extracts and sync stay consistent with server logic.
        if (duct.isMenuHubLayer()) {
            return duct.getFaceNode(face).guiSlots;
        }
        return duct.activeMenuFaceNode(face).guiSlots;
    }

    @Override
    public int getSlots() {
        return upgradeSlotCount + 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot >= 0 && slot < upgradeSlotCount) {
            return upgradeHandler().getStackInSlot(slot);
        }
        if (slot == upgradeSlotCount) {
            return copyHandler().getStackInSlot(0);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (slot >= 0 && slot < upgradeSlotCount) {
            upgradeHandler().setStackInSlot(slot, stack);
        } else if (slot == upgradeSlotCount) {
            copyHandler().setStackInSlot(0, stack);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        if (slot >= 0 && slot < upgradeSlotCount) {
            return upgradeHandler().getSlotLimit(slot);
        }
        if (slot == upgradeSlotCount) {
            return copyHandler().getSlotLimit(0);
        }
        return 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot >= 0 && slot < upgradeSlotCount) {
            return upgradeHandler().isItemValid(slot, stack);
        }
        if (slot == upgradeSlotCount) {
            return copyHandler().isItemValid(0, stack);
        }
        return false;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot >= 0 && slot < upgradeSlotCount) {
            return upgradeHandler().insertItem(slot, stack, simulate);
        }
        if (slot == upgradeSlotCount) {
            return copyHandler().insertItem(0, stack, simulate);
        }
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot >= 0 && slot < upgradeSlotCount) {
            return upgradeHandler().extractItem(slot, amount, simulate);
        }
        if (slot == upgradeSlotCount) {
            return copyHandler().extractItem(0, amount, simulate);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setSize(int size) {
        // Size is fixed from duct definition for this menu instance.
    }
}
