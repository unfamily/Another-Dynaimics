package net.unfamily.another_dynamics.inventory;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Menu slots that delegate to the item or fluid {@link net.unfamily.another_dynamics.duct.DuctFaceNode#guiSlots} for the
 * face, depending on {@link DuctBlockEntity#menuActiveTransportKind()}.
 */
public final class DuctMenuActiveLaneSlots extends ItemStackHandler {
    private final DuctBlockEntity duct;
    private final net.minecraft.core.Direction face;

    public DuctMenuActiveLaneSlots(DuctBlockEntity duct, net.minecraft.core.Direction face) {
        super(DuctNodeMenu.MACHINE_SLOTS);
        this.duct = duct;
        this.face = face;
    }

    private ItemStackHandler backing() {
        return duct.activeMenuFaceNode(face).guiSlots;
    }

    @Override
    public int getSlots() {
        return backing().getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return backing().getStackInSlot(slot);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        backing().setStackInSlot(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return backing().getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return backing().isItemValid(slot, stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return backing().insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return backing().extractItem(slot, amount, simulate);
    }

    @Override
    public void setSize(int size) {
        backing().setSize(size);
    }
}
