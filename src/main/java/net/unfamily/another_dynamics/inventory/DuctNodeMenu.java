package net.unfamily.another_dynamics.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.ItemDuctBlockEntity;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

/**
 * Duct node configuration: player inventory only for now (node slots later).
 * Origin (80, 158) matches {@code textures/gui/background/node.png}.
 */
public final class DuctNodeMenu extends AbstractContainerMenu {
    public static final int PLAYER_SLOTS_X = 80;
    public static final int PLAYER_SLOTS_Y = 158;
    private static final int HOTBAR_GAP = 4;

    private final ContainerLevelAccess access;

    public DuctNodeMenu(int containerId, Inventory playerInventory, ItemDuctBlockEntity be) {
        super(ModMenuTypes.DUCT_NODE.get(), containerId);
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());
        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y);
    }

    public static DuctNodeMenu clientMenu(int containerId, Inventory playerInventory) {
        return new DuctNodeMenu(containerId, playerInventory);
    }

    private DuctNodeMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.DUCT_NODE.get(), containerId);
        this.access = ContainerLevelAccess.NULL;
        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y);
    }

    private void addPlayerInventory(Inventory inv, int startX, int startY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9 + 9;
                this.addSlot(new Slot(inv, index, startX + col * 18, startY + row * 18));
            }
        }
        int hotbarY = startY + 3 * 18 + HOTBAR_GAP;
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col, startX + col * 18, hotbarY));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.ITEM_DUCT.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < 27) {
                if (!this.moveItemStackTo(stack, 27, 36, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, 27, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return result;
    }
}
