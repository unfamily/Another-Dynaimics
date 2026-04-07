package net.unfamily.another_dynamics.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.network.ModNetwork;
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
    /** Matches player inventory position on {@code node.png} (moved +13px down for filter list space). */
    public static final int PLAYER_SLOTS_Y = 171;
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
    /** World position of this duct (authoritative on client from open-menu extra data; avoids relying on sync-before-packet). */
    private final BlockPos ductBlockPos;

    /** Detects upgrade-slot changes so {@link DuctMenuSync#EXTRACT_BATCH_CAP} can be refreshed without full menu spam. */
    private int lastUpgradeSlotsFingerprint;

    /** Client-side filter cache (filled by {@link #receiveFilterSync}). */
    private final List<String> clientAllowFiltersExtractor = new ArrayList<>();
    private final List<String> clientDenyFiltersExtractor = new ArrayList<>();
    private boolean clientDenyOverridesAllowExtractor = true;

    private final List<String> clientAllowFiltersRetriever = new ArrayList<>();
    private final List<String> clientDenyFiltersRetriever = new ArrayList<>();
    private boolean clientDenyOverridesAllowRetriever = true;

    private final List<String> clientAllowFiltersFilter = new ArrayList<>();
    private final List<String> clientDenyFiltersFilter = new ArrayList<>();
    private boolean clientDenyOverridesAllowFilter = true;

    public DuctNodeMenu(int containerId, Inventory playerInventory, DuctBlockEntity be, Direction accessFace) {
        this(
                containerId,
                playerInventory,
                be.getFaceNode(accessFace).guiSlots,
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                be.getMenuData(),
                be,
                accessFace,
                be.getBlockPos());
        be.clampFaceFiltersToSpec();
        be.refreshMenuData(accessFace);
        if (!be.getLevel().isClientSide() && playerInventory.player instanceof ServerPlayer sp) {
            ModNetwork.sendFilterSyncToPlayer(sp, be, accessFace);
        }
    }

    /**
     * Client-side factory: extra payload is {@code BlockPos} then face ordinal (matches {@link
     * net.unfamily.another_dynamics.duct.DuctBlock#openDuctMenu}).
     */
    public static DuctNodeMenu createClient(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        BlockPos pos = extraData.readBlockPos();
        int fo = extraData.readByte() & 0xFF;
        Direction face = Direction.values()[Mth.clamp(fo, 0, Direction.values().length - 1)];
        return new DuctNodeMenu(
                containerId,
                playerInventory,
                new ItemStackHandler(MACHINE_SLOTS),
                ContainerLevelAccess.NULL,
                new SimpleContainerData(DuctMenuSync.COUNT),
                null,
                face,
                pos);
    }

    private DuctNodeMenu(
            int containerId,
            Inventory playerInventory,
            ItemStackHandler nodeSlots,
            ContainerLevelAccess access,
            ContainerData syncData,
            @Nullable DuctBlockEntity linkedBlockEntity,
            Direction accessFace,
            BlockPos ductBlockPos) {
        super(ModMenuTypes.DUCT_NODE.get(), containerId);
        this.access = access;
        this.syncData = syncData;
        this.linkedBlockEntity = linkedBlockEntity;
        this.accessFace = accessFace;
        this.ductBlockPos = ductBlockPos;

        for (int i = 0; i < UPGRADE_SLOT_COUNT; i++) {
            int y = SLOT_UPGRADE_Y0 + i * 18;
            addSlot(new SlotItemHandler(nodeSlots, i, SLOT_UPGRADE_X, y));
        }
        addSlot(new SlotItemHandler(nodeSlots, COPY_SETTINGS_SLOT, SLOT_COPY_X, SLOT_COPY_Y));

        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y);
        addDataSlots(syncData);

        if (linkedBlockEntity != null && linkedBlockEntity.getLevel() != null && !linkedBlockEntity.getLevel().isClientSide()) {
            lastUpgradeSlotsFingerprint = upgradeSlotsFingerprint();
        } else {
            lastUpgradeSlotsFingerprint = 0;
        }
    }

    public Direction getAccessFace() {
        return accessFace;
    }

    public BlockPos getDuctBlockPos() {
        return ductBlockPos;
    }

    public ContainerData getSyncData() {
        return syncData;
    }

    public int filterAllowCap() {
        return DuctDefinitionRegistry.itemDuctTransportSpec().filterAllowSlots();
    }

    public int filterDenyCap() {
        return DuctDefinitionRegistry.itemDuctTransportSpec().filterDenySlots();
    }

    public List<String> getClientAllowFilters(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientAllowFiltersExtractor;
            case RETRIEVER -> clientAllowFiltersRetriever;
            case FILTER -> clientAllowFiltersFilter;
        };
    }

    public List<String> getClientDenyFilters(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientDenyFiltersExtractor;
            case RETRIEVER -> clientDenyFiltersRetriever;
            case FILTER -> clientDenyFiltersFilter;
        };
    }

    public boolean getClientDenyOverridesAllow(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientDenyOverridesAllowExtractor;
            case RETRIEVER -> clientDenyOverridesAllowRetriever;
            case FILTER -> clientDenyOverridesAllowFilter;
        };
    }

    public void receiveFilterSync(
            BlockPos pos,
            Direction face,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            boolean denyOverridesAllow) {
        if (!ductBlockPos.equals(pos) || accessFace != face) {
            return;
        }
        net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank =
                net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank.values()[
                        Mth.clamp(
                                filterBankOrdinal,
                                0,
                                net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank.values().length - 1)];
        List<String> a = getClientAllowFilters(bank);
        List<String> d = getClientDenyFilters(bank);
        a.clear();
        a.addAll(allow);
        d.clear();
        d.addAll(deny);
        switch (bank) {
            case EXTRACTOR -> clientDenyOverridesAllowExtractor = denyOverridesAllow;
            case RETRIEVER -> clientDenyOverridesAllowRetriever = denyOverridesAllow;
            case FILTER -> clientDenyOverridesAllowFilter = denyOverridesAllow;
        }
    }

    /** Keep {@link #clientDenyOverridesAllow} aligned with synced {@link DuctMenuSync#DENY_OVERRIDES_ALLOW} on client. */
    public void updateClientDenyOverridesFromSync() {
        if (linkedBlockEntity == null) {
            // Legacy single sync: keep FILTER bank aligned for non-hybrid UI paths.
            clientDenyOverridesAllowFilter = syncData.get(DuctMenuSync.DENY_OVERRIDES_ALLOW) != 0;
        }
    }

    public void ensureClientFilterBufferSizes() {
        int maxA = Math.max(0, filterAllowCap()) / 2;
        int maxD = Math.max(0, filterDenyCap()) / 2;
        clampClientList(clientAllowFiltersExtractor, maxA);
        clampClientList(clientDenyFiltersExtractor, maxD);
        clampClientList(clientAllowFiltersRetriever, maxA);
        clampClientList(clientDenyFiltersRetriever, maxD);
        clampClientList(clientAllowFiltersFilter, maxA);
        clampClientList(clientDenyFiltersFilter, maxD);
    }

    public void pushFilterConfigToServer(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank,
            List<String> allow,
            List<String> deny,
            boolean denyOverridesAllow) {
        ModNetwork.sendFilterUpdate(ductBlockPos, accessFace, bank.ordinal(), allow, deny, denyOverridesAllow);
    }

    private static void clampClientList(List<String> list, int max) {
        while (list.size() < max) {
            list.add("");
        }
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
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

    private int upgradeSlotsFingerprint() {
        if (linkedBlockEntity == null) {
            return 0;
        }
        int fp = 1;
        var stacks = linkedBlockEntity.getFaceNode(accessFace).guiSlots;
        for (int i = 0; i < UPGRADE_SLOT_COUNT; i++) {
            fp = 31 * fp + stacks.getStackInSlot(i).hashCode();
        }
        return fp;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (linkedBlockEntity != null && linkedBlockEntity.getLevel() != null && !linkedBlockEntity.getLevel().isClientSide()) {
            int fp = upgradeSlotsFingerprint();
            if (fp != lastUpgradeSlotsFingerprint) {
                lastUpgradeSlotsFingerprint = fp;
                linkedBlockEntity
                        .getMenuData()
                        .set(DuctMenuSync.EXTRACT_BATCH_CAP, linkedBlockEntity.computeExtractBatchSettingCap(accessFace));
            }
        }
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