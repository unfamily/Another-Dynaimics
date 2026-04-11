package net.unfamily.another_dynamics.inventory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.module.DuctFaceModuleItemHandler;

/**
 * Virtual menu handler: module indices map to {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#moduleSlots};
 * the last index is the per-lane copy slot ({@link net.unfamily.another_dynamics.duct.DuctFaceNode#guiSlots} slot 0).
 */
public final class DuctMenuActiveLaneSlots extends ItemStackHandler {
    private final DuctBlockEntity duct;
    private final net.minecraft.core.Direction face;
    private final int moduleSlotCount;

    public DuctMenuActiveLaneSlots(DuctBlockEntity duct, net.minecraft.core.Direction face, int moduleSlotCount) {
        super(Math.max(0, moduleSlotCount) + 1);
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
        return moduleSlotCount + 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().getStackInSlot(slot);
        }
        if (slot == moduleSlotCount) {
            return copyHandler().getStackInSlot(0);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (slot >= 0 && slot < moduleSlotCount) {
            moduleHandler().setStackInSlot(slot, stack);
        } else if (slot == moduleSlotCount) {
            copyHandler().setStackInSlot(0, stack);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().getSlotLimit(slot);
        }
        if (slot == moduleSlotCount) {
            return copyHandler().getSlotLimit(0);
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
        if (slot == moduleSlotCount) {
            // Copy slot is extract-only in the GUI; do not suggest large merge limits for inserts.
            return 0;
        }
        return 0;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().isItemValid(slot, stack);
        }
        if (slot == moduleSlotCount) {
            // Copy/reference slot: no player insertion (extraction still works).
            return false;
        }
        return false;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().insertItem(slot, stack, simulate);
        }
        if (slot == moduleSlotCount) {
            return stack;
        }
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot >= 0 && slot < moduleSlotCount) {
            return moduleHandler().extractItem(slot, amount, simulate);
        }
        if (slot == moduleSlotCount) {
            return copyHandler().extractItem(0, amount, simulate);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setSize(int size) {
        // Size is fixed from duct definition for this menu instance.
    }
}
