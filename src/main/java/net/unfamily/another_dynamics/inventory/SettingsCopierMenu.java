package net.unfamily.another_dynamics.inventory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

import org.jetbrains.annotations.Nullable;

/**
 * Single settings copier menu: hub layer and in-memory universal duct editor share one container
 * (layer switch like duct transport hub / detail, not a second {@code openMenu}).
 */
public final class SettingsCopierMenu extends AbstractContainerMenu implements UniversalDuctMenu {
    public static final int PLAYER_SLOTS_X = DuctNodeMenu.PLAYER_SLOTS_X;
    public static final int PLAYER_SLOTS_Y = DuctNodeMenu.PLAYER_SLOTS_Y;
    public static final int PLAYER_SLOT_COUNT = 36;

    public static final int ROOT_HUB = 0;
    public static final int ROOT_VIRTUAL = 1;

    private static final String UNIVERSAL_LOGICAL_ID = SettingsCopierVirtualSession.UNIVERSAL_LOGICAL_ID;

    private final InteractionHand hand;
    /** Menu slot index of the copier in the player band; {@code -1} if not in the 36 synced slots (e.g. off-hand). */
    private final int openingCopierMenuSlotIndex;
    private final ContainerData rootLayer;
    private final SimpleContainerData syncData;
    private final Direction accessFace;
    private final BlockPos ductBlockPos;
    private @Nullable SettingsCopierVirtualSession virtualSession;
    private final UniversalDuctMenuFilterBuffers filterBuffers = new UniversalDuctMenuFilterBuffers();

    public SettingsCopierMenu(int containerId, Inventory playerInventory, InteractionHand hand) {
        super(ModMenuTypes.SETTINGS_COPIER_HUB.get(), containerId);
        this.hand = hand;
        this.rootLayer = new SimpleContainerData(1);
        this.syncData = new SimpleContainerData(DuctMenuSync.COUNT);
        this.accessFace = SettingsCopierVirtualSession.VIRTUAL_FACE;
        this.ductBlockPos = BlockPos.ZERO;
        this.rootLayer.set(0, ROOT_HUB);
        this.openingCopierMenuSlotIndex = resolveOpeningCopierMenuSlot(playerInventory, hand);
        initClientSyncDefaults(playerInventory);
        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y, openingCopierMenuSlotIndex);
        addDataSlots(rootLayer);
        addDataSlots(syncData);
    }

    public static SettingsCopierMenu createClient(int containerId, Inventory playerInventory, FriendlyByteBuf extra) {
        int ho = extra.readByte() & 0xFF;
        InteractionHand hand = ho == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return new SettingsCopierMenu(containerId, playerInventory, hand);
    }

    private void initClientSyncDefaults(Inventory playerInventory) {
        List<DuctTransportKind> ordKinds =
                DuctDefinition.orderedMenuTransportKinds(
                        DuctDefinitionRegistry.getByLogicalId(UNIVERSAL_LOGICAL_ID));
        syncData.set(DuctMenuSync.TRANSPORT_KIND_COUNT, ordKinds.size());
        ItemStack copier = playerInventory.player.getItemInHand(hand);
        boolean filterEditor = SettingsCopierStoreKind.getMode(copier) == SettingsCopierStoreKind.FILTER;
        syncData.set(DuctMenuSync.MENU_VIEW_LAYER, filterEditor ? 1 : (ordKinds.size() > 1 ? 0 : 1));
        if (!ordKinds.isEmpty()) {
            syncData.set(DuctMenuSync.ACTIVE_TRANSPORT_KIND, ordKinds.getFirst().ordinal());
        }
    }

    public InteractionHand getHand() {
        return hand;
    }

    public int openingCopierMenuSlotIndex() {
        return openingCopierMenuSlotIndex;
    }

    public boolean isHubLayer() {
        return rootLayer.get(0) == ROOT_HUB;
    }

    public boolean isVirtualLayer() {
        return rootLayer.get(0) == ROOT_VIRTUAL;
    }

    public int rootLayerValue() {
        return rootLayer.get(0);
    }

    @Nullable
    public SettingsCopierVirtualSession virtualSession() {
        return virtualSession;
    }

    public ItemStack copierStack(Player player) {
        return player.getItemInHand(hand);
    }

    /** Client: apply authoritative copier stack after hub actions (mode, rename). */
    public void applyClientCopierStack(Player player, ItemStack stack) {
        if (player.level().isClientSide()) {
            player.setItemInHand(hand, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
    }

    public SettingsCopierStoreKind storeKind(Player player) {
        if (virtualSession != null) {
            return virtualSession.storeKind();
        }
        return SettingsCopierStoreKind.getMode(player.getItemInHand(hand));
    }

    /** Server: open virtual universal duct editor inside this menu. */
    public void enterVirtual(ServerPlayer player) {
        if (!isHubLayer()) {
            return;
        }
        ItemStack copier = copierStack(player);
        if (copier.isEmpty() || !(copier.getItem() instanceof SettingsCopierItem)) {
            return;
        }
        virtualSession = new SettingsCopierVirtualSession(player, hand, copier, syncData);
        rootLayer.set(0, ROOT_VIRTUAL);
        broadcastChanges();
    }

    /** Server: persist virtual state and return to hub layer without closing the container. */
    public void returnToHub(ServerPlayer player) {
        if (!isVirtualLayer() || virtualSession == null) {
            return;
        }
        ItemStack copier = virtualSession.getCopierStack();
        if (!copier.isEmpty()) {
            virtualSession.persistToCopier(copier);
            player.setItemInHand(hand, copier);
            ModNetwork.sendSettingsCopierStackSync(player, copier);
        }
        virtualSession = null;
        rootLayer.set(0, ROOT_HUB);
        broadcastChanges();
    }

    @Override
    public boolean isSettingsCopierVirtualEditor() {
        return isVirtualLayer();
    }

    @Override
    public ContainerData getSyncData() {
        return syncData;
    }

    @Override
    public String getClientDuctLogicalId() {
        return UNIVERSAL_LOGICAL_ID;
    }

    @Override
    public boolean isDuctAlwaysOpaqueLocked() {
        return false;
    }

    @Override
    public BlockPos getDuctBlockPos() {
        return ductBlockPos;
    }

    @Override
    public Direction getAccessFace() {
        return accessFace;
    }

    @Override
    public int moduleSlotCount() {
        return 0;
    }

    @Override
    public int copySettingsSlotIndex() {
        return -1;
    }

    @Override
    public int filterAllowCap(boolean hybridFilterContext) {
        return SettingsCopierVirtualSession.UNLIMITED_FILTER_LINES;
    }

    @Override
    public int filterDenyCap(boolean hybridFilterContext) {
        return SettingsCopierVirtualSession.UNLIMITED_FILTER_LINES;
    }

    @Override
    public List<String> getClientAllowFilters(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowFilters(bank);
    }

    @Override
    public List<String> getClientDenyFilters(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyFilters(bank);
    }

    @Override
    public boolean getClientDenyOverridesAllow(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyOverridesAllow(bank);
    }

    @Override
    public List<Integer> getClientAllowCaps(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowCaps(bank);
    }

    @Override
    public List<Integer> getClientFilterKeepCaps() {
        return filterBuffers.getClientFilterKeepCaps();
    }

    @Override
    public void receiveFilterSync(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            boolean denyOverridesAllow) {
        filterBuffers.receiveFilterSync(
                ductBlockPos,
                accessFace,
                syncData,
                pos,
                face,
                transportKindOrdinal,
                filterBankOrdinal,
                allow,
                deny,
                allowCaps,
                allowCaps2,
                denyOverridesAllow);
    }

    @Override
    public void ensureClientFilterBufferSizes(boolean hybridFilterContext) {
        filterBuffers.ensureClientFilterBufferSizes(
                syncData,
                UNIVERSAL_LOGICAL_ID,
                0,
                i -> ItemStack.EMPTY,
                true,
                hybridFilterContext);
    }

    @Override
    public void pushFilterConfigToServer(
            DuctFaceNode.FilterBank bank,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            boolean denyOverridesAllow,
            boolean editingAllowList) {
        if (virtualSession != null) {
            DuctTransportKind[] kinds = DuctTransportKind.values();
            int tk = syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
            virtualSession.noteFilterListContext(
                    kinds[Mth.clamp(tk, 0, kinds.length - 1)], bank, editingAllowList);
        }
        ModNetwork.sendFilterUpdate(
                ductBlockPos,
                accessFace,
                syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                bank.ordinal(),
                allow,
                deny,
                allowCaps,
                allowCaps2,
                denyOverridesAllow);
    }

    @Override
    public boolean stillValid(Player player) {
        ItemStack stack = copierStack(player);
        return player.isAlive() && stack.getItem() instanceof SettingsCopierItem;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (virtualSession != null) {
            return virtualSession.handleMenuButton(id);
        }
        return false;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player.level().isClientSide() || virtualSession == null || !(player instanceof ServerPlayer sp)) {
            return;
        }
        ItemStack copier = virtualSession.getCopierStack();
        if (!copier.isEmpty()) {
            virtualSession.persistToCopier(copier);
            sp.setItemInHand(hand, copier);
            ModNetwork.sendSettingsCopierStackSync(sp, copier);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (isHubLayer() || index == openingCopierMenuSlotIndex) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (!moveItemStackTo(stack, 0, PLAYER_SLOT_COUNT, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    private static int resolveOpeningCopierMenuSlot(Inventory inv, InteractionHand openHand) {
        if (openHand != InteractionHand.MAIN_HAND) {
            return -1;
        }
        return menuSlotIndexForPlayerInventoryIndex(inv.selected);
    }

    static int menuSlotIndexForPlayerInventoryIndex(int playerInvIndex) {
        if (playerInvIndex >= 9 && playerInvIndex < 36) {
            return playerInvIndex - 9;
        }
        if (playerInvIndex >= 0 && playerInvIndex < 9) {
            return 27 + playerInvIndex;
        }
        return -1;
    }

    private void addPlayerInventory(Inventory inv, int startX, int startY, int lockedMenuSlot) {
        int menuSlot = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9 + 9;
                this.addSlot(new LockedCopierPlayerSlot(
                        inv, index, startX + col * 18, startY + row * 18, menuSlot == lockedMenuSlot));
                menuSlot++;
            }
        }
        int hotbarY = startY + 3 * 18 + 4;
        for (int col = 0; col < 9; col++) {
            this.addSlot(new LockedCopierPlayerSlot(
                    inv, col, startX + col * 18, hotbarY, menuSlot == lockedMenuSlot));
            menuSlot++;
        }
    }

    /** Blocks pickup/place on the menu slot that held the opening settings copier. */
    private static final class LockedCopierPlayerSlot extends Slot {
        private final boolean copierLocked;

        LockedCopierPlayerSlot(Inventory inv, int index, int x, int y, boolean copierLocked) {
            super(inv, index, x, y);
            this.copierLocked = copierLocked;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !copierLocked && super.mayPickup(player);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !copierLocked && super.mayPlace(stack);
        }
    }
}
