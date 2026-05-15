package net.unfamily.another_dynamics.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;

/** Non-storage slot used for Settings Copier capture clicks in {@link DuctNodeMenu}. */
public final class CopySettingsSlot extends Slot {
    public CopySettingsSlot(int x, int y) {
        super(new net.minecraft.world.Container() {
            @Override
            public int getContainerSize() {
                return 1;
            }

            @Override
            public boolean isEmpty() {
                return true;
            }

            @Override
            public ItemStack getItem(int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public void setItem(int slot, ItemStack stack) {}

            @Override
            public void setChanged() {}

            @Override
            public boolean stillValid(Player player) {
                return true;
            }

            @Override
            public void clearContent() {}
        }, 0, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public ItemStack getItem() {
        return ItemStack.EMPTY;
    }

    @Override
    public void set(ItemStack stack) {}
}
