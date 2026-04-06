package net.unfamily.another_dynamics.inventory;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

import org.jetbrains.annotations.Nullable;

/**
 * Duct node GUI for one {@link Direction} face (independent node configuration per side).
 */
public final class DuctNodeMenu extends AbstractContainerMenu {
    public static final int MACHINE_SLOTS = 6;
    public static final int UPGRADE_SLOT_COUNT = 5;
    public static final int COPY_SETTINGS_SLOT = 5;

    public static final int PLAYER_SLOTS_X = 80;
    public static final int PLAYER_SLOTS_Y = 158;
    private static final int HOTBAR_GAP = 4;

    public static final int SLOT_UPGRADE_X = 14;
    public static final int SLOT_UPGRADE_Y0 = 32;

    public static final int SLOT_COPY_X = 278;
    public static final int SLOT_COPY_Y = 52;

    private static final int REDSTONE_BUTTON_SIZE = 16;
    public static final int REDSTONE_GUI_X = SLOT_COPY_X + (18 - REDSTONE_BUTTON_SIZE) / 2;
    public static final int REDSTONE_GUI_Y = 32;

    private final ContainerLevelAccess access;
    private final ContainerData syncData;
    private final @Nullable DuctBlockEntity linkedBlockEntity;
    private final Direction accessFace;

    public DuctNodeMenu(int containerId, Inventory playerInventory, DuctBlockEntity be, Direction accessFace) {
        this(
                containerId,
                playerInventory,
                be.getFaceNode(accessFace).guiSlots,
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                be.getMenuData(),
                be,
                accessFace);
        be.refreshMenuData(accessFace);
    }

    public static DuctNodeMenu clientMenu(int containerId, Inventory playerInventory) {
        return new DuctNodeMenu(
                containerId,
                playerInventory,
                new ItemStackHandler(MACHINE_SLOTS),
                ContainerLevelAccess.NULL,
                new SimpleContainerData(DuctMenuSync.COUNT),
                null,
                Direction.DOWN);
    }

    private DuctNodeMenu(
            int containerId,
            Inventory playerInventory,
            ItemStackHandler nodeSlots,
            ContainerLevelAccess access,
            ContainerData syncData,
            @Nullable DuctBlockEntity linkedBlockEntity,
            Direction accessFace) {
        super(ModMenuTypes.DUCT_NODE.get(), containerId);
        this.access = access;
        this.syncData = syncData;
        this.linkedBlockEntity = linkedBlockEntity;
        this.accessFace = accessFace;

        for (int i = 0; i < UPGRADE_SLOT_COUNT; i++) {
            int y = SLOT_UPGRADE_Y0 + i * 18;
            addSlot(new SlotItemHandler(nodeSlots, i, SLOT_UPGRADE_X, y));
        }
        addSlot(new SlotItemHandler(nodeSlots, COPY_SETTINGS_SLOT, SLOT_COPY_X, SLOT_COPY_Y));

        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y);
        addDataSlots(syncData);
    }

    public Direction getAccessFace() {
        return accessFace;
    }

    public ContainerData getSyncData() {
        return syncData;
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

    public static int playerSlotStart() {
        return MACHINE_SLOTS;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.ITEM_DUCT.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (linkedBlockEntity == null || linkedBlockEntity.isRemoved()) {
            return false;
        }
        return linkedBlockEntity.handleMenuButtonClick(player, id, accessFace);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int playerFirst = playerSlotStart();
        int playerLast = this.slots.size();

        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < MACHINE_SLOTS) {
                if (!moveItemStackTo(stack, playerFirst, playerLast, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, MACHINE_SLOTS, false)) {
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