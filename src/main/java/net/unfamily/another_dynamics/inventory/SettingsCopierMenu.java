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
import net.unfamily.another_dynamics.duct.DuctGuiLayout;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportRegistry;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.DuctFilterListSnapshot;
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.minecraft.world.SimpleContainer;
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
    public static final int ROOT_IMPORT = 2;

    public static final int IMPORT_SOURCE_SLOT = 0;
    public static final int IMPORT_SECOND_COPIER_SLOT = 1;
    public static final int PLAYER_SLOT_START = 2;

    /** Import screen layout (gui-local pixels; shared with {@link net.unfamily.another_dynamics.client.gui.SettingsCopierScreen}). */
    public static final int IMPORT_MARGIN = 8;
    public static final int IMPORT_CHANNEL_Y = 20;
    public static final int IMPORT_BACK_W = 56;
    public static final int IMPORT_CHANNEL_W = 124;
    public static final int IMPORT_BTN_H = 14;
    public static final int IMPORT_TOOLBAR_BTN_GAP = 4;
    public static final int IMPORT_BACK_Y = IMPORT_CHANNEL_Y + IMPORT_BTN_H + IMPORT_TOOLBAR_BTN_GAP;
    public static final int IMPORT_NAME_W = 110;
    public static final int IMPORT_SLOT_GAP_BELOW_TOOLBAR = 8;
    public static final int IMPORT_SLOT_GAP_ABOVE_LABEL = 4;
    public static final int IMPORT_LABEL_GAP_ABOVE_BOX = 2;
    public static final int IMPORT_EXECUTE_GAP_BELOW_NAMES = 10;

    public static final int IMPORT_SECTION_Y =
            IMPORT_BACK_Y + IMPORT_BTN_H + IMPORT_SLOT_GAP_BELOW_TOOLBAR;
    public static final int IMPORT_SOURCE_GUI_X = IMPORT_MARGIN + (IMPORT_NAME_W - 18) / 2;
    public static final int IMPORT_SOURCE_GUI_Y = IMPORT_SECTION_Y;
    public static final int IMPORT_PRIMARY_NAME_X = IMPORT_MARGIN;
    public static final int IMPORT_LABEL_Y = IMPORT_SECTION_Y + 18 + IMPORT_SLOT_GAP_ABOVE_LABEL;
    public static final int IMPORT_PRIMARY_NAME_Y = IMPORT_LABEL_Y + 9 + IMPORT_LABEL_GAP_ABOVE_BOX;
    public static final int IMPORT_EXECUTE_Y = IMPORT_PRIMARY_NAME_Y + IMPORT_BTN_H + IMPORT_EXECUTE_GAP_BELOW_NAMES;

    public static int importSecondaryNameX(int guiWidth) {
        return guiWidth - IMPORT_NAME_W - IMPORT_MARGIN;
    }

    public static int importSecondCopierSlotX(int guiWidth) {
        return importSecondaryNameX(guiWidth) + (IMPORT_NAME_W - 18) / 2;
    }

    public static int importChannelButtonX(int guiWidth) {
        return (guiWidth - IMPORT_CHANNEL_W) / 2;
    }

    public static int importBackButtonX(int guiWidth) {
        return (guiWidth - IMPORT_BACK_W) / 2;
    }

    private static final String UNIVERSAL_LOGICAL_ID = SettingsCopierVirtualSession.UNIVERSAL_LOGICAL_ID;

    private final Player owner;
    private final InteractionHand hand;
    /** Menu slot index of the copier in the player band; {@code -1} if not in the 36 synced slots (e.g. off-hand). */
    private final int openingCopierMenuSlotIndex;
    private final ContainerData rootLayer;
    private final SimpleContainerData syncData;
    private final Direction accessFace;
    private final BlockPos ductBlockPos;
    private @Nullable SettingsCopierVirtualSession virtualSession;
    private final UniversalDuctMenuFilterBuffers filterBuffers = new UniversalDuctMenuFilterBuffers();
    private final SimpleContainer importContainer = new SimpleContainer(2);
    /** Server: channel selected in import GUI (synced from client). */
    private int importChannelOrdinal;
    /** Client-only: second copier slot visible when preview has inverted Pipez filters. */
    boolean clientImportNeedsSecondCopier;

    /**
     * Client-only optimistic material kind ({@code -1} = read from copier NBT).
     * Cleared when entering virtual editor or receiving stack sync.
     */
    private int clientFilterMaterialKindOrdinal = -1;

    public void setClientImportNeedsSecondCopier(boolean needsSecond) {
        this.clientImportNeedsSecondCopier = needsSecond;
    }

    public boolean clientImportNeedsSecondCopier() {
        return clientImportNeedsSecondCopier;
    }

    public FilterImportChannel getActiveImportChannel() {
        FilterImportChannel[] values = FilterImportChannel.values();
        if (importChannelOrdinal < 0 || importChannelOrdinal >= values.length) {
            return FilterImportChannel.ITEM;
        }
        return values[importChannelOrdinal];
    }

    public void setImportChannelOrdinal(int ordinal) {
        FilterImportChannel[] values = FilterImportChannel.values();
        if (values.length == 0) {
            importChannelOrdinal = 0;
            return;
        }
        importChannelOrdinal = Math.max(0, Math.min(values.length - 1, ordinal));
    }

    /**
     * Server: whether the second copier slot accepts items for the active import channel.
     * Client uses {@link #clientImportNeedsSecondCopier} for visibility instead.
     */
    public boolean serverImportNeedsSecondCopier() {
        if (!isImportLayer()) {
            return false;
        }
        ItemStack source = getImportSourceStack();
        if (source.isEmpty()) {
            return false;
        }
        return FilterImportRegistry.preview(source, getActiveImportChannel(), owner.level().registryAccess())
                .map(preview -> preview.needsSecondCopier())
                .orElse(false);
    }

    /** Server: eject second copier slot when it is no longer required or source upgrade was removed. */
    public void reconcileImportSecondSlot(Player player) {
        if (player.level().isClientSide() || !isImportLayer()) {
            return;
        }
        if (!serverImportNeedsSecondCopier()) {
            ejectImportSlot(player, IMPORT_SECOND_COPIER_SLOT);
        }
    }

    public static SettingsCopierMenu createClient(int containerId, Inventory playerInventory, FriendlyByteBuf extra) {
        int ho = extra.readByte() & 0xFF;
        InteractionHand hand = ho == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        int bandSlot = extra.readVarInt();
        return new SettingsCopierMenu(containerId, playerInventory, hand, bandSlot);
    }

    /** Server menu (resolves locked player band slot locally). */
    public SettingsCopierMenu(int containerId, Inventory playerInventory, InteractionHand hand) {
        this(containerId, playerInventory, hand, Integer.MIN_VALUE);
    }

    private SettingsCopierMenu(
            int containerId, Inventory playerInventory, InteractionHand hand, int openingBandSlotFromSync) {
        super(ModMenuTypes.SETTINGS_COPIER_HUB.get(), containerId);
        this.owner = playerInventory.player;
        this.hand = hand;
        this.rootLayer = new SimpleContainerData(1);
        this.syncData = new SimpleContainerData(DuctMenuSync.COUNT);
        this.accessFace = SettingsCopierVirtualSession.VIRTUAL_FACE;
        this.ductBlockPos = BlockPos.ZERO;
        this.rootLayer.set(0, ROOT_HUB);
        this.openingCopierMenuSlotIndex =
                openingBandSlotFromSync != Integer.MIN_VALUE
                        ? openingBandSlotFromSync
                        : resolveOpeningCopierMenuSlot(playerInventory, hand, playerInventory.player);
        initClientSyncDefaults(playerInventory);
        addSlot(
                new FilterImportSourceSlot(
                        this, importContainer, 0, IMPORT_SOURCE_GUI_X, IMPORT_SOURCE_GUI_Y));
        addSlot(
                new FilterImportSecondCopierSlot(
                        this,
                        importContainer,
                        1,
                        importSecondCopierSlotX(DuctGuiLayout.NODE_TEXTURE_WIDTH),
                        IMPORT_SOURCE_GUI_Y));
        importContainer.addListener(container -> {
            if (owner.level().isClientSide() || !isImportLayer()) {
                return;
            }
            reconcileImportSecondSlot(owner);
        });
        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y, openingCopierMenuSlotIndex);
        addDataSlots(rootLayer);
        addDataSlots(syncData);
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

    public boolean isImportLayer() {
        return rootLayer.get(0) == ROOT_IMPORT;
    }

    public int rootLayerValue() {
        return rootLayer.get(0);
    }

    public void enterImport(ServerPlayer player) {
        if (!isHubLayer()) {
            return;
        }
        importChannelOrdinal = 0;
        rootLayer.set(0, ROOT_IMPORT);
        broadcastChanges();
    }

    public void returnToHubFromImport(ServerPlayer player) {
        if (!isImportLayer()) {
            return;
        }
        ejectImportContainerContents(player);
        rootLayer.set(0, ROOT_HUB);
        broadcastChanges();
    }

    public ItemStack getImportSourceStack() {
        return getSlot(IMPORT_SOURCE_SLOT).getItem();
    }

    public ItemStack getImportSecondCopierStack() {
        return getSlot(IMPORT_SECOND_COPIER_SLOT).getItem();
    }

    public void setImportSecondCopierStack(ItemStack stack) {
        getSlot(IMPORT_SECOND_COPIER_SLOT).set(stack);
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
            clearClientFilterListMaterialKindOverride();
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
        clientFilterMaterialKindOrdinal = -1;
        virtualSession = new SettingsCopierVirtualSession(player, hand, copier, syncData);
        rootLayer.set(0, ROOT_VIRTUAL);
        broadcastChanges();
    }

    /** Client: material kind for FILTER virtual editor (from NBT or last cycle). */
    public FilterListMaterialKind clientFilterListMaterialKind(Player player) {
        if (clientFilterMaterialKindOrdinal >= 0) {
            return FilterListMaterialKind.fromOrdinal(clientFilterMaterialKindOrdinal);
        }
        ItemStack copier = copierStack(player);
        return DuctFaceSettingsSnapshot.readFromCopier(copier)
                .map(DuctFilterListSnapshot::getMaterialKind)
                .orElse(FilterListMaterialKind.NONE);
    }

    public void setClientFilterListMaterialKind(FilterListMaterialKind kind) {
        clientFilterMaterialKindOrdinal = kind.ordinal();
    }

    public void clearClientFilterListMaterialKindOverride() {
        clientFilterMaterialKindOrdinal = -1;
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
    public List<Integer> getClientAllowConcatChannels(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowConcatChannels(bank);
    }

    @Override
    public List<Integer> getClientDenyConcatChannels(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyConcatChannels(bank);
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
            List<Integer> allowConcat,
            List<Integer> denyConcat,
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
                allowConcat,
                denyConcat,
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
            List<Integer> allowConcat,
            List<Integer> denyConcat,
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
                allowConcat,
                denyConcat,
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
        if (!player.level().isClientSide()) {
            ejectImportContainerContents(player);
        }
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

    /** Returns import-slot items to the player inventory, or drops them at the player's feet. */
    private void ejectImportContainerContents(Player player) {
        for (int i = 0; i < importContainer.getContainerSize(); i++) {
            ejectImportSlot(player, i);
        }
    }

    private void ejectImportSlot(Player player, int containerIndex) {
        if (containerIndex < 0 || containerIndex >= importContainer.getContainerSize()) {
            return;
        }
        ItemStack stack = importContainer.getItem(containerIndex);
        if (stack.isEmpty()) {
            return;
        }
        ItemStack copy = stack.copy();
        importContainer.setItem(containerIndex, ItemStack.EMPTY);
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (isHubLayer()) {
            return ItemStack.EMPTY;
        }
        if (isImportLayer()) {
            return quickMoveStackImport(player, index);
        }
        int lockedMenu =
                openingCopierMenuSlotIndex >= 0
                        ? openingCopierMenuSlotIndex + PLAYER_SLOT_START
                        : -1;
        if (index == lockedMenu) {
            return ItemStack.EMPTY;
        }
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (!moveItemStackTo(stack, PLAYER_SLOT_START, PLAYER_SLOT_START + PLAYER_SLOT_COUNT, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    /**
     * Index 0–35 within the player band added by {@link #addPlayerInventory} (main 0–26, hotbar 27–35).
     * {@code -1} if the copier is not in that band (e.g. off-hand).
     */
    public static int resolveOpeningCopierMenuSlot(Inventory inv, InteractionHand openHand, Player player) {
        if (openHand != InteractionHand.MAIN_HAND) {
            return -1;
        }
        ItemStack held = player.getItemInHand(openHand);
        if (held.isEmpty() || !(held.getItem() instanceof SettingsCopierItem)) {
            return -1;
        }
        int selected = inv.selected;
        if (selected >= 0
                && selected < inv.getContainerSize()
                && ItemStack.isSameItemSameComponents(held, inv.getItem(selected))) {
            return menuSlotIndexForPlayerInventoryIndex(selected);
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (ItemStack.isSameItemSameComponents(held, inv.getItem(i))) {
                return menuSlotIndexForPlayerInventoryIndex(i);
            }
        }
        return menuSlotIndexForPlayerInventoryIndex(selected);
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

    private ItemStack quickMoveStackImport(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (index == IMPORT_SOURCE_SLOT) {
            if (!moveItemStackTo(
                    stack,
                    PLAYER_SLOT_START,
                    PLAYER_SLOT_START + PLAYER_SLOT_COUNT,
                    true)) {
                return ItemStack.EMPTY;
            }
        } else if (index == IMPORT_SECOND_COPIER_SLOT) {
            if (!moveItemStackTo(
                    stack,
                    PLAYER_SLOT_START,
                    PLAYER_SLOT_START + PLAYER_SLOT_COUNT,
                    true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (FilterImportRegistry.canImport(stack, player.level().registryAccess())) {
                if (!getSlot(IMPORT_SOURCE_SLOT).hasItem()) {
                    if (!moveItemStackTo(stack, IMPORT_SOURCE_SLOT, IMPORT_SOURCE_SLOT + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!getSlot(IMPORT_SECOND_COPIER_SLOT).hasItem()
                        && stack.getItem() instanceof SettingsCopierItem) {
                    if (!moveItemStackTo(
                            stack, IMPORT_SECOND_COPIER_SLOT, IMPORT_SECOND_COPIER_SLOT + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    return ItemStack.EMPTY;
                }
            } else if (stack.getItem() instanceof SettingsCopierItem
                    && !getSlot(IMPORT_SECOND_COPIER_SLOT).hasItem()) {
                if (!moveItemStackTo(stack, IMPORT_SECOND_COPIER_SLOT, IMPORT_SECOND_COPIER_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    private abstract static class ImportSlotBase extends Slot {
        protected final SettingsCopierMenu copierMenu;

        ImportSlotBase(SettingsCopierMenu copierMenu, SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
            this.copierMenu = copierMenu;
        }

        @Override
        public boolean isActive() {
            return copierMenu.isImportLayer();
        }

        @Override
        public boolean mayPickup(Player player) {
            return isActive() && super.mayPickup(player);
        }
    }

    private static final class FilterImportSourceSlot extends ImportSlotBase {
        FilterImportSourceSlot(SettingsCopierMenu menu, SimpleContainer container, int index, int x, int y) {
            super(menu, container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (!isActive()) {
                return false;
            }
            return FilterImportRegistry.canImport(stack, copierMenu.owner.level().registryAccess());
        }
    }

    private static final class FilterImportSecondCopierSlot extends ImportSlotBase {
        FilterImportSecondCopierSlot(SettingsCopierMenu menu, SimpleContainer container, int index, int x, int y) {
            super(menu, container, index, x, y);
        }

        @Override
        public boolean isActive() {
            if (!copierMenu.isImportLayer()) {
                return false;
            }
            if (copierMenu.owner.level().isClientSide()) {
                return copierMenu.clientImportNeedsSecondCopier;
            }
            return copierMenu.serverImportNeedsSecondCopier();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && stack.getItem() instanceof SettingsCopierItem;
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
