package net.unfamily.another_dynamics.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctGuiLayout;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctGasTransportSpec;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

import org.jetbrains.annotations.Nullable;

/**
 * Duct node GUI for one {@link Direction} face (independent node configuration per side).
 */
public final class DuctNodeMenu extends AbstractContainerMenu {
    /** @deprecated Prefer {@link #machineSlotCount()} (per-menu). */
    @Deprecated
    public static final int MACHINE_SLOTS = 6;

    public static final int PLAYER_SLOTS_X = 80;
    /** Matches player inventory position on {@code node.png} (moved +13px down for filter list space). */
    public static final int PLAYER_SLOTS_Y = 171;
    private static final int HOTBAR_GAP = 4;

    /** Fine alignment vs {@code node.png} slot art (right and down). */
    private static final int SLOT_GEOMETRY_NUDGE = 2;

    /**
     * Where {@code SINGLE_SLOT} is blitted for the module column (aligned to {@code node.png}); do not shift this for
     * container content — see {@link #SLOT_MODULE_X} / {@link #SLOT_MODULE_Y0}.
     */
    public static final int SLOT_MODULE_BACKGROUND_X = 14 + SLOT_GEOMETRY_NUDGE;
    /** Top of module column slot art; must match {@link DuctGuiLayout#MODULE_COLUMN_FIRST_SLOT_Y}. */
    public static final int SLOT_MODULE_BACKGROUND_Y0 = DuctGuiLayout.MODULE_COLUMN_FIRST_SLOT_Y;

    /** Copy column: {@code SINGLE_SLOT} blit position (node texture alignment). */
    public static final int SLOT_COPY_BACKGROUND_X = 278 + SLOT_GEOMETRY_NUDGE;
    public static final int SLOT_COPY_BACKGROUND_Y = 52 + SLOT_GEOMETRY_NUDGE;

    /**
     * Container {@link Slot} origins for module/copy: one pixel right and down from the slot frame art so items and
     * interaction sit in the content layer.
     */
    private static final int SLOT_CONTENT_LAYER_DX = 1;
    private static final int SLOT_CONTENT_LAYER_DY = 1;

    public static final int SLOT_MODULE_X = SLOT_MODULE_BACKGROUND_X + SLOT_CONTENT_LAYER_DX;
    public static final int SLOT_MODULE_Y0 = SLOT_MODULE_BACKGROUND_Y0 + SLOT_CONTENT_LAYER_DY;

    public static final int SLOT_COPY_X = SLOT_COPY_BACKGROUND_X + SLOT_CONTENT_LAYER_DX;
    public static final int SLOT_COPY_Y = SLOT_COPY_BACKGROUND_Y + SLOT_CONTENT_LAYER_DY;

    private static final int REDSTONE_BUTTON_SIZE = 16;
    /** Centered on copy column slot art, not the offset container slot. */
    public static final int REDSTONE_GUI_X = SLOT_COPY_BACKGROUND_X + (18 - REDSTONE_BUTTON_SIZE) / 2;
    public static final int REDSTONE_GUI_Y = 32;

    private final ContainerLevelAccess access;
    private final ContainerData syncData;
    private final @Nullable DuctBlockEntity linkedBlockEntity;
    private final Direction accessFace;
    /** World position of this duct (authoritative on client from open-menu extra data; avoids relying on sync-before-packet). */
    private final BlockPos ductBlockPos;
    /**
     * From server open-menu payload: {@link net.unfamily.another_dynamics.duct.DuctDefinition#alwaysOpaqueRendering()}
     * locks the opaque toggle.
     */
    private final boolean clientDuctAlwaysOpaqueLock;
    /** Open-menu sync: {@link DuctBlockEntity#getLogicalDuctId()} for correct client-side datapack caps. */
    private final String clientDuctLogicalId;

    private final int moduleSlotCount;
    private final int machineSlotCount;

    /** Detects module-slot changes so {@link DuctMenuSync#EXTRACT_BATCH_CAP} can be refreshed without full menu spam. */
    private int lastModuleSlotsFingerprint;

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

    private final List<Integer> clientAllowCapsExtractor = new ArrayList<>();
    private final List<Integer> clientAllowCapsRetriever = new ArrayList<>();
    private final List<Integer> clientAllowCapsFilter = new ArrayList<>(); // FILTER.limit
    private final List<Integer> clientAllowCapsFilter2 = new ArrayList<>(); // FILTER.keep

    public DuctNodeMenu(int containerId, Inventory playerInventory, DuctBlockEntity be, Direction accessFace) {
        this(
                containerId,
                playerInventory,
                new DuctMenuActiveLaneSlots(be, accessFace, be.moduleSlotCountForMenu()),
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                be.getMenuData(),
                be,
                accessFace,
                be.getBlockPos(),
                be.ductAlwaysOpaqueRendering(),
                be.getLogicalDuctId(),
                be.moduleSlotCountForMenu());
        be.clampFaceFiltersToSpec();
        be.refreshMenuData(accessFace);
        if (!be.getLevel().isClientSide() && playerInventory.player instanceof ServerPlayer sp) {
            if (!be.isMenuHubLayer()) {
                ModNetwork.sendFilterSyncToPlayer(sp, be, accessFace);
            }
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
        boolean alwaysOpaqueLock = extraData.readBoolean();
        String logicalId = extraData.readUtf();
        if (logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        int menuLayer = extraData.readByte() & 0xFF;
        if (menuLayer > 1) {
            menuLayer = 1;
        }
        int ug = DuctGuiLayout.clampModuleSlotCount(extraData.readByte() & 0xFF);
        SimpleContainerData clientData = new SimpleContainerData(DuctMenuSync.COUNT);
        clientData.set(DuctMenuSync.MENU_VIEW_LAYER, menuLayer);
        List<DuctTransportKind> ordKinds =
                DuctDefinition.orderedMenuTransportKinds(DuctDefinitionRegistry.getByLogicalId(logicalId));
        clientData.set(DuctMenuSync.TRANSPORT_KIND_COUNT, ordKinds.size());
        if (!ordKinds.isEmpty()) {
            clientData.set(DuctMenuSync.ACTIVE_TRANSPORT_KIND, ordKinds.getFirst().ordinal());
        }
        Level lvl = playerInventory.player.level();
        ItemStackHandler nodeSlots;
        BlockEntity be = lvl.getBlockEntity(pos);
        if (be instanceof DuctBlockEntity dbe) {
            nodeSlots = new DuctMenuActiveLaneSlots(dbe, face, ug);
        } else {
            nodeSlots = new ItemStackHandler(ug + 1);
        }
        return new DuctNodeMenu(
                containerId,
                playerInventory,
                nodeSlots,
                ContainerLevelAccess.NULL,
                clientData,
                null,
                face,
                pos,
                alwaysOpaqueLock,
                logicalId,
                ug);
    }

    private DuctNodeMenu(
            int containerId,
            Inventory playerInventory,
            ItemStackHandler nodeSlots,
            ContainerLevelAccess access,
            ContainerData syncData,
            @Nullable DuctBlockEntity linkedBlockEntity,
            Direction accessFace,
            BlockPos ductBlockPos,
            boolean clientDuctAlwaysOpaqueLock,
            String clientDuctLogicalId,
            int moduleSlotCount) {
        super(ModMenuTypes.DUCT_NODE.get(), containerId);
        this.access = access;
        this.syncData = syncData;
        this.linkedBlockEntity = linkedBlockEntity;
        this.accessFace = accessFace;
        this.ductBlockPos = ductBlockPos;
        this.clientDuctAlwaysOpaqueLock = clientDuctAlwaysOpaqueLock;
        this.clientDuctLogicalId = clientDuctLogicalId;
        this.moduleSlotCount = Math.max(0, moduleSlotCount);
        this.machineSlotCount = this.moduleSlotCount + 1;

        for (int i = 0; i < this.moduleSlotCount; i++) {
            int y = SLOT_MODULE_Y0 + i * 18;
            int slotIndex = i;
            addSlot(
                    new SlotItemHandler(nodeSlots, i, SLOT_MODULE_X, y) {
                        @Override
                        public int getMaxStackSize(ItemStack stack) {
                            if (stack.isEmpty()) {
                                return super.getMaxStackSize();
                            }
                            if (nodeSlots instanceof DuctMenuActiveLaneSlots lanes) {
                                return lanes.moduleInsertLimit(slotIndex, stack);
                            }
                            return Math.min(stack.getMaxStackSize(), nodeSlots.getSlotLimit(slotIndex));
                        }

                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            if (stack.isEmpty()) {
                                return true;
                            }
                            return nodeSlots.isItemValid(slotIndex, stack);
                        }

                        @Override
                        public boolean isActive() {
                            return super.isActive() && moduleSlotsInteractive();
                        }
                    });
        }
        addSlot(
                new SlotItemHandler(nodeSlots, this.moduleSlotCount, SLOT_COPY_X, SLOT_COPY_Y) {
                    @Override
                    public int getMaxStackSize(ItemStack stack) {
                        if (stack.isEmpty()) {
                            return super.getMaxStackSize();
                        }
                        if (nodeSlots instanceof DuctMenuActiveLaneSlots lanes) {
                            return lanes.moduleInsertLimit(moduleSlotCount, stack);
                        }
                        return Math.min(stack.getMaxStackSize(), nodeSlots.getSlotLimit(moduleSlotCount));
                    }

                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return stack.isEmpty() || nodeSlots.isItemValid(moduleSlotCount, stack);
                    }

                    @Override
                    public boolean isActive() {
                        return super.isActive();
                    }
                });

        addPlayerInventory(playerInventory, PLAYER_SLOTS_X, PLAYER_SLOTS_Y);
        addDataSlots(syncData);

        if (linkedBlockEntity != null && linkedBlockEntity.getLevel() != null && !linkedBlockEntity.getLevel().isClientSide()) {
            lastModuleSlotsFingerprint = moduleSlotsFingerprint();
        } else {
            lastModuleSlotsFingerprint = 0;
        }
    }

    public Direction getAccessFace() {
        return accessFace;
    }

    public BlockPos getDuctBlockPos() {
        return ductBlockPos;
    }

    /** Server menu only: linked duct BE (client factory passes {@code null}). */
    public @Nullable DuctBlockEntity linkedDuctBlockEntity() {
        return linkedBlockEntity;
    }

    public boolean isDuctAlwaysOpaqueLocked() {
        return clientDuctAlwaysOpaqueLock;
    }

    public String getClientDuctLogicalId() {
        return clientDuctLogicalId;
    }

    private DuctItemTransportSpec clientItemTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(clientDuctLogicalId)
                .map(DuctDefinition::itemTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::itemDuctTransportSpec);
    }

    private DuctFluidTransportSpec clientFluidTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(clientDuctLogicalId)
                .map(DuctDefinition::fluidTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::fluidDuctTransportSpec);
    }

    private DuctGasTransportSpec clientGasTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(clientDuctLogicalId)
                .map(DuctDefinition::gasTransportOrFallback)
                .orElseGet(DuctGasTransportSpec::fallback);
    }

    private boolean clientEditingFluidLane() {
        if (linkedBlockEntity != null
                && linkedBlockEntity.getLevel() != null
                && !linkedBlockEntity.getLevel().isClientSide()) {
            return linkedBlockEntity.menuActiveTransportKind() == DuctTransportKind.FLUID;
        }
        return syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.FLUID.ordinal();
    }

    private boolean clientEditingGasLane() {
        if (linkedBlockEntity != null
                && linkedBlockEntity.getLevel() != null
                && !linkedBlockEntity.getLevel().isClientSide()) {
            return linkedBlockEntity.menuActiveTransportKind() == DuctTransportKind.GAS;
        }
        return syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.GAS.ordinal();
    }

    private boolean clientEditingEnergyOrHeatLane() {
        if (linkedBlockEntity != null
                && linkedBlockEntity.getLevel() != null
                && !linkedBlockEntity.getLevel().isClientSide()) {
            DuctTransportKind k = linkedBlockEntity.menuActiveTransportKind();
            return k == DuctTransportKind.ENERGY || k == DuctTransportKind.HEAT;
        }
        int o = syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
        return o == DuctTransportKind.ENERGY.ordinal() || o == DuctTransportKind.HEAT.ordinal();
    }

    private int menuTransportKindOrdinalForPackets() {
        if (linkedBlockEntity != null
                && linkedBlockEntity.getLevel() != null
                && !linkedBlockEntity.getLevel().isClientSide()) {
            return linkedBlockEntity.menuActiveTransportKind().ordinal();
        }
        return syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
    }

    public ContainerData getSyncData() {
        return syncData;
    }

    public int moduleSlotCount() {
        return moduleSlotCount;
    }

    public int machineSlotCount() {
        return machineSlotCount;
    }

    public int copySettingsSlotIndex() {
        return moduleSlotCount;
    }

    /**
     * Energy/heat lanes do not use module slots; copy remains usable. Multi-transport hub uses the first sync kind
     * ordinal until a tab is picked — treat hub as non-energy for module interaction so shared modules work.
     */
    public boolean moduleSlotsInteractive() {
        if (isMultiTransportHubMainLayer()) {
            return true;
        }
        return !clientEditingEnergyOrHeatLane();
    }

    private boolean isMultiTransportHubMainLayer() {
        if (linkedBlockEntity != null
                && linkedBlockEntity.getLevel() != null
                && !linkedBlockEntity.getLevel().isClientSide()) {
            return linkedBlockEntity.isMenuHubLayer() && linkedBlockEntity.orderedMenuTransportKinds().size() > 1;
        }
        return syncData.get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1
                && syncData.get(DuctMenuSync.MENU_VIEW_LAYER) == 0;
    }

    /**
     * @param hybridFilterContext {@code true} when the open GUI is editing a hybrid face sub-panel
     *     ({@code Extr/Filt} or {@code Retr/Extr}); uses datapack {@code filter.allow_hybrid}/{@code deny_hybrid}.
     */
    public int filterAllowCap(boolean hybridFilterContext) {
        if (clientEditingEnergyOrHeatLane()) {
            return 0;
        }
        if (clientEditingFluidLane()) {
            var s = clientFluidTransportSpec();
            return hybridFilterContext ? s.filterAllowHybridSlots() : s.filterAllowSlots();
        }
        if (clientEditingGasLane()) {
            var s = clientGasTransportSpec();
            return hybridFilterContext ? s.filterAllowHybridSlots() : s.filterAllowSlots();
        }
        var s = clientItemTransportSpec();
        return hybridFilterContext ? s.filterAllowHybridSlots() : s.filterAllowSlots();
    }

    public int filterDenyCap(boolean hybridFilterContext) {
        if (clientEditingEnergyOrHeatLane()) {
            return 0;
        }
        if (clientEditingFluidLane()) {
            var s = clientFluidTransportSpec();
            return hybridFilterContext ? s.filterDenyHybridSlots() : s.filterDenySlots();
        }
        if (clientEditingGasLane()) {
            var s = clientGasTransportSpec();
            return hybridFilterContext ? s.filterDenyHybridSlots() : s.filterDenySlots();
        }
        var s = clientItemTransportSpec();
        return hybridFilterContext ? s.filterDenyHybridSlots() : s.filterDenySlots();
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

    public List<Integer> getClientAllowCaps(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientAllowCapsExtractor;
            case RETRIEVER -> clientAllowCapsRetriever;
            case FILTER -> clientAllowCapsFilter;
        };
    }

    public List<Integer> getClientFilterKeepCaps() {
        return clientAllowCapsFilter2;
    }

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
        if (!ductBlockPos.equals(pos) || accessFace != face) {
            return;
        }
        if (transportKindOrdinal != syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND)) {
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
        List<Integer> caps = getClientAllowCaps(bank);
        a.clear();
        a.addAll(allow);
        d.clear();
        d.addAll(deny);
        caps.clear();
        if (allowCaps != null) {
            for (Integer v : allowCaps) {
                caps.add(Math.max(0, v != null ? v : 0));
            }
        }
        if (bank == net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank.FILTER) {
            clientAllowCapsFilter2.clear();
            if (allowCaps2 != null) {
                for (Integer v : allowCaps2) {
                    clientAllowCapsFilter2.add(Math.max(0, v != null ? v : 0));
                }
            }
        }
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

    public void ensureClientFilterBufferSizes(boolean hybridFilterContext) {
        int maxA = Math.max(0, filterAllowCap(hybridFilterContext));
        int maxD = Math.max(0, filterDenyCap(hybridFilterContext));
        clampClientList(clientAllowFiltersExtractor, maxA);
        clampClientList(clientDenyFiltersExtractor, maxD);
        clampClientList(clientAllowFiltersRetriever, maxA);
        clampClientList(clientDenyFiltersRetriever, maxD);
        clampClientList(clientAllowFiltersFilter, maxA);
        clampClientList(clientDenyFiltersFilter, maxD);
        clampClientIntList(clientAllowCapsExtractor, maxA);
        clampClientIntList(clientAllowCapsRetriever, maxA);
        clampClientIntList(clientAllowCapsFilter, maxA);
        clampClientIntList(clientAllowCapsFilter2, maxA);
    }

    public void pushFilterConfigToServer(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            boolean denyOverridesAllow) {
        ModNetwork.sendFilterUpdate(
                ductBlockPos,
                accessFace,
                menuTransportKindOrdinalForPackets(),
                bank.ordinal(),
                allow,
                deny,
                allowCaps,
                allowCaps2,
                denyOverridesAllow);
    }

    private static void clampClientList(List<String> list, int max) {
        while (list.size() < max) {
            list.add("");
        }
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }

    private static void clampClientIntList(List<Integer> list, int max) {
        while (list.size() < max) {
            list.add(0);
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

    public int playerSlotStart() {
        return machineSlotCount;
    }

    private int moduleSlotsFingerprint() {
        if (linkedBlockEntity == null) {
            return 0;
        }
        int fp = 1;
        var stacks = linkedBlockEntity.getFaceLanes(accessFace).moduleSlots;
        for (int i = 0; i < stacks.getSlots(); i++) {
            fp = 31 * fp + stacks.getStackInSlot(i).hashCode();
        }
        return fp;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (linkedBlockEntity != null && linkedBlockEntity.getLevel() != null && !linkedBlockEntity.getLevel().isClientSide()) {
            int fp = moduleSlotsFingerprint();
            if (fp != lastModuleSlotsFingerprint) {
                lastModuleSlotsFingerprint = fp;
                linkedBlockEntity.clampAllTransportExtractBatchesForFace(accessFace);
                linkedBlockEntity.refreshMenuData(accessFace);
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (linkedBlockEntity != null && !linkedBlockEntity.isRemoved()) {
            BlockPos pos = linkedBlockEntity.getBlockPos();
            Level pl = player.level();
            if (!pl.isClientSide()
                    && pl.getBlockEntity(pos) == linkedBlockEntity
                    && ModBlocks.isDuctBlock(pl.getBlockState(pos).getBlock())
                    && player.canInteractWithBlock(pos, 4.0)) {
                return true;
            }
        }
        return stillValid(access, player, ModBlocks.DUCT.get())
                || stillValid(access, player, ModBlocks.FLUID_DUCT.get())
                || stillValid(access, player, ModBlocks.ITEM_FLUID_DUCT.get())
                || (ModBlocks.GAS_DUCT != null && stillValid(access, player, ModBlocks.GAS_DUCT.get()));
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
        int copyIx = copySettingsSlotIndex();

        if (!moduleSlotsInteractive()) {
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) {
                return ItemStack.EMPTY;
            }
            if (index >= 0 && index < moduleSlotCount) {
                return ItemStack.EMPTY;
            }
            if (index == copyIx) {
                ItemStack stack = slot.getItem();
                ItemStack result = stack.copy();
                if (!moveItemStackTo(stack, playerFirst, playerLast, true)) {
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
                return result;
            }
            if (index < machineSlotCount) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = slot.getItem();
            ItemStack result = stack.copy();
            if (!moveItemStackTo(stack, copyIx, machineSlotCount, false)) {
                if (index < playerFirst + 27) {
                    if (!moveItemStackTo(stack, playerFirst + 27, playerLast, true)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    if (!moveItemStackTo(stack, playerFirst, playerFirst + 27, false)) {
                        return ItemStack.EMPTY;
                    }
                }
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
            return result;
        }

        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < machineSlotCount) {
                if (!moveItemStackTo(stack, playerFirst, playerLast, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, machineSlotCount, false)) {
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