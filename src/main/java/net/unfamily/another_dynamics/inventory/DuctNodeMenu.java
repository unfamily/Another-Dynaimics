package net.unfamily.another_dynamics.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.unfamily.another_dynamics.duct.SettingsCopierFeedback;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.DuctFilterListSnapshot;
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.network.SettingsCopierActionPayload;
import net.unfamily.another_dynamics.network.SettingsCopierStackSyncPayload;
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
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.module.DuctModuleHelper;
import net.unfamily.another_dynamics.duct.module.ModuleDefinition;
import net.unfamily.another_dynamics.duct.module.ModuleDefinitionRegistry;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

import org.jetbrains.annotations.Nullable;

/**
 * Duct node GUI for one {@link Direction} face (independent node configuration per side).
 */
public final class DuctNodeMenu extends AbstractContainerMenu implements UniversalDuctMenu {

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

    /** Right column X (redstone, channel, settings copier). */
    public static final int SLOT_COPY_BACKGROUND_X = 278 + SLOT_GEOMETRY_NUDGE;

    /** Gap between stacked controls in the right column. */
    public static final int COPY_COLUMN_GAP = 2;
    public static final int COPIER_ACTION_BUTTON_H = 12;

    /** Channel letter control: full 18×18 slot under redstone (former copier slot position). */
    public static final int CHANNEL_BACKGROUND_X = SLOT_COPY_BACKGROUND_X;
    public static final int CHANNEL_BACKGROUND_Y = 52 + SLOT_GEOMETRY_NUDGE;

    /** Settings copier inventory slot frame (below channel). */
    public static final int SLOT_COPY_BACKGROUND_Y = CHANNEL_BACKGROUND_Y + 18 + COPY_COLUMN_GAP;

    public static final int COPIER_SAVE_BUTTON_Y = SLOT_COPY_BACKGROUND_Y + 18 + COPY_COLUMN_GAP;
    public static final int COPIER_LOAD_BUTTON_Y =
            COPIER_SAVE_BUTTON_Y + COPIER_ACTION_BUTTON_H + COPY_COLUMN_GAP;

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
    /** Open-menu sync: any duct in the physical component has server network opaque. */
    private boolean clientComponentNetworkOpaque;
    /** Open-menu sync: {@link DuctBlockEntity#getLogicalDuctId()} for correct client-side datapack caps. */
    private final String clientDuctLogicalId;

    private final boolean includeCopierSlot;

    private final int moduleSlotCount;
    private final int machineSlotCount;
    private final ItemStackHandler copierSlot = new ItemStackHandler(1);

    /** Detects module-slot changes so {@link DuctMenuSync#EXTRACT_BATCH_CAP} can be refreshed without full menu spam. */
    private int lastModuleSlotsFingerprint;

    /** Server-side only: player that opened this menu, used to re-send filter sync on module changes. */
    private final @Nullable ServerPlayer menuPlayer;

    private final UniversalDuctMenuFilterBuffers filterBuffers = new UniversalDuctMenuFilterBuffers();

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
                net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation.componentHasNetworkOpaque(
                        be.getLevel(), be.getBlockPos()),
                be.getLogicalDuctId(),
                be.moduleSlotCountForMenu(),
                true);
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
        boolean componentNetworkOpaque = extraData.readBoolean();
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
                componentNetworkOpaque,
                logicalId,
                ug,
                true);
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
            boolean clientComponentNetworkOpaque,
            String clientDuctLogicalId,
            int moduleSlotCount,
            boolean includeCopierSlot) {
        super(ModMenuTypes.DUCT_NODE.get(), containerId);
        this.includeCopierSlot = includeCopierSlot;
        this.access = access;
        this.syncData = syncData;
        this.linkedBlockEntity = linkedBlockEntity;
        this.accessFace = accessFace;
        this.ductBlockPos = ductBlockPos;
        this.clientDuctAlwaysOpaqueLock = clientDuctAlwaysOpaqueLock;
        this.clientComponentNetworkOpaque = clientComponentNetworkOpaque;
        this.clientDuctLogicalId = clientDuctLogicalId;
        this.moduleSlotCount = Math.max(0, moduleSlotCount);
        this.machineSlotCount = this.moduleSlotCount + (includeCopierSlot ? 1 : 0);
        this.menuPlayer = (!playerInventory.player.level().isClientSide() && playerInventory.player instanceof ServerPlayer sp) ? sp : null;

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
                            return super.isActive();
                        }
                    });
        }
        if (includeCopierSlot) {
            addSlot(
                    new SlotItemHandler(copierSlot, 0, SLOT_COPY_X, SLOT_COPY_Y) {
                        @Override
                        public boolean mayPlace(ItemStack stack) {
                            return !stack.isEmpty() && stack.getItem() instanceof SettingsCopierItem;
                        }
                    });
        }

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

    public boolean isClientComponentNetworkOpaque() {
        return clientComponentNetworkOpaque;
    }

    /** Client: refresh network-opaque hint after server block updates. */
    public void refreshClientComponentNetworkOpaque(net.minecraft.world.level.Level level) {
        if (level == null || !level.isClientSide()) {
            return;
        }
        clientComponentNetworkOpaque =
                net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation.componentHasNetworkOpaque(
                        level, ductBlockPos);
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
        return includeCopierSlot ? moduleSlotCount : -1;
    }

    public ItemStackHandler copierSlot() {
        return copierSlot;
    }

    /** Client: apply authoritative copier stack from server after Save. */
    public void applyClientCopierStack(ItemStack stack) {
        ItemStack copy = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        copierSlot.setStackInSlot(0, copy);
        int idx = copySettingsSlotIndex();
        if (idx >= 0 && idx < slots.size()) {
            slots.get(idx).set(copy);
        }
    }

    private void commitCopierSnapshot(ServerPlayer player, ItemStack base, CompoundTag snapshot) {
        ItemStack updated = base.copy();
        DuctFaceSettingsSnapshot.writeToCopier(updated, snapshot);
        copierSlot.setStackInSlot(0, updated);
        ModNetwork.sendSettingsCopierStackSync(player, updated);
    }

    /**
     * Module column is always interactive: shared modules affect energy/heat (e.g. increment modules with
     * {@code affects[].for=energy}) as well as item/fluid/gas. Slot acceptance is still enforced by
     * {@link net.neoforged.neoforge.items.SlotItemHandler#mayPlace(ItemStack)} / {@code isItemValid}.
     */
    public boolean moduleSlotsInteractive() {
        return true;
    }

    /**
     * @param hybridFilterContext Kept for API compatibility; effective caps follow synced {@link NodeMode} and module
     *     slots (same rules as server {@link net.unfamily.another_dynamics.duct.DuctFaceNode#clampFilterSizes}).
     */
    @SuppressWarnings("unused")
    public int filterAllowCap(boolean hybridFilterContext) {
        if (clientEditingEnergyOrHeatLane()) {
            return 0;
        }
        NodeMode nm = NodeMode.fromOrdinal(syncData.get(DuctMenuSync.NODE_MODE));
        DuctModuleEffects.FilterSlotBonuses fb = clientFilterSlotBonusesFromModuleColumn();
        if (clientEditingFluidLane()) {
            var s = clientFluidTransportSpec();
            return DuctModuleEffects.effectiveFluidAllowBank(s, nm, fb);
        }
        if (clientEditingGasLane()) {
            var s = clientGasTransportSpec();
            return DuctModuleEffects.effectiveGasAllowBank(s, nm, fb);
        }
        var s = clientItemTransportSpec();
        return DuctModuleEffects.effectiveItemAllowBank(s, nm, fb);
    }

    @SuppressWarnings("unused")
    public int filterDenyCap(boolean hybridFilterContext) {
        if (clientEditingEnergyOrHeatLane()) {
            return 0;
        }
        NodeMode nm = NodeMode.fromOrdinal(syncData.get(DuctMenuSync.NODE_MODE));
        DuctModuleEffects.FilterSlotBonuses fb = clientFilterSlotBonusesFromModuleColumn();
        if (clientEditingFluidLane()) {
            var s = clientFluidTransportSpec();
            return DuctModuleEffects.effectiveFluidDenyBank(s, nm, fb);
        }
        if (clientEditingGasLane()) {
            var s = clientGasTransportSpec();
            return DuctModuleEffects.effectiveGasDenyBank(s, nm, fb);
        }
        var s = clientItemTransportSpec();
        return DuctModuleEffects.effectiveItemDenyBank(s, nm, fb);
    }

    /**
     * Client: sum filter slot adds from non-empty module column slots (matches server
     * {@link DuctModuleEffects#filterSlotBonuses}).
     */
    public DuctModuleEffects.FilterSlotBonuses clientFilterSlotBonusesFromModuleColumn() {
        int ia = 0, idn = 0, iah = 0, idnh = 0;
        int fa = 0, fd = 0, fah = 0, fdh = 0;
        int ga = 0, gd = 0, gah = 0, gdh = 0;
        for (int i = 0; i < moduleSlotCount; i++) {
            ItemStack s = getSlot(i).getItem();
            if (s.isEmpty()) {
                continue;
            }
            var modId = DuctModuleHelper.resolvedDeclarationId(s);
            if (modId.isEmpty()) {
                continue;
            }
            ModuleDefinition def = ModuleDefinitionRegistry.get(modId.get()).orElse(null);
            if (def == null) {
                continue;
            }
            ModuleDefinition.FilterSlotModifiers fi = def.filterSlotsItem();
            ia += fi.allowSlotAdd();
            idn += fi.denySlotAdd();
            iah += fi.allowHybridSlotAdd();
            idnh += fi.denyHybridSlotAdd();
            ModuleDefinition.FilterSlotModifiers ff = def.filterSlotsFluid();
            fa += ff.allowSlotAdd();
            fd += ff.denySlotAdd();
            fah += ff.allowHybridSlotAdd();
            fdh += ff.denyHybridSlotAdd();
            ModuleDefinition.FilterSlotModifiers fg = def.filterSlotsGas();
            ga += fg.allowSlotAdd();
            gd += fg.denySlotAdd();
            gah += fg.allowHybridSlotAdd();
            gdh += fg.denyHybridSlotAdd();
        }
        return new DuctModuleEffects.FilterSlotBonuses(
                new DuctModuleEffects.FilterSlotBonuses.PerKind(ia, idn, iah, idnh),
                new DuctModuleEffects.FilterSlotBonuses.PerKind(fa, fd, fah, fdh),
                new DuctModuleEffects.FilterSlotBonuses.PerKind(ga, gd, gah, gdh));
    }

    public List<String> getClientAllowFilters(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowFilters(filterTransportKindOrdinal(), bank);
    }

    public List<String> getClientDenyFilters(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyFilters(filterTransportKindOrdinal(), bank);
    }

    public boolean getClientDenyOverridesAllow(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyOverridesAllow(filterTransportKindOrdinal(), bank);
    }

    public List<Integer> getClientAllowCaps(net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowCaps(filterTransportKindOrdinal(), bank);
    }

    public List<Integer> getClientFilterKeepCaps() {
        return filterBuffers.getClientFilterKeepCaps(filterTransportKindOrdinal());
    }

    @Override
    public List<Integer> getClientExtractorLimitCaps() {
        return filterBuffers.getClientExtractorLimitCaps(filterTransportKindOrdinal());
    }

    @Override
    public @Nullable List<Integer> getClientAllowCaps2(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowCaps2(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<Integer> getClientAllowConcatChannels(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowConcatChannels(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<Integer> getClientDenyConcatChannels(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyConcatChannels(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<@Nullable DuctDirectionalEndpoint> getClientAllowRemoteNodes(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowRemoteNodes(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<@Nullable DuctDirectionalEndpoint> getClientDenyRemoteNodes(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyRemoteNodes(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<Boolean> getClientAllowRemoteIgnoreChannel(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowRemoteIgnoreChannel(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<Boolean> getClientDenyRemoteIgnoreChannel(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyRemoteIgnoreChannel(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<Boolean> getClientAllowRemoteAnyFace(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientAllowRemoteAnyFace(filterTransportKindOrdinal(), bank);
    }

    @Override
    public List<Boolean> getClientDenyRemoteAnyFace(DuctFaceNode.FilterBank bank) {
        return filterBuffers.getClientDenyRemoteAnyFace(filterTransportKindOrdinal(), bank);
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
            List<@Nullable DuctDirectionalEndpoint> allowRemote,
            List<@Nullable DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowRemoteIgnoreChannel,
            List<Boolean> denyRemoteIgnoreChannel,
            List<Boolean> allowRemoteAnyFace,
            List<Boolean> denyRemoteAnyFace,
            boolean denyOverridesAllow) {
        filterBuffers.receiveFilterSync(
                ductBlockPos,
                accessFace,
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
                allowRemote,
                denyRemote,
                allowRemoteIgnoreChannel,
                denyRemoteIgnoreChannel,
                allowRemoteAnyFace,
                denyRemoteAnyFace,
                denyOverridesAllow);
    }

    @Override
    public boolean clientFiltersHydrated() {
        return filterBuffers.clientFiltersHydrated(filterTransportKindOrdinal());
    }

    @Override
    public boolean clientFiltersDirty() {
        return filterBuffers.clientFiltersDirty(filterTransportKindOrdinal());
    }

    @Override
    public boolean clientFiltersHydrated(int transportKindOrdinal) {
        return filterBuffers.clientFiltersHydrated(transportKindOrdinal);
    }

    @Override
    public boolean clientFiltersDirty(int transportKindOrdinal) {
        return filterBuffers.clientFiltersDirty(transportKindOrdinal);
    }

    @Override
    public void markClientFiltersDirty() {
        filterBuffers.markClientFiltersDirty(filterTransportKindOrdinal());
    }

    @Override
    public boolean shouldPushClientFiltersOnClose() {
        return filterBuffers.shouldPushOnClose(filterTransportKindOrdinal());
    }

    @Override
    public boolean shouldPushFiltersForTransport(int transportKindOrdinal) {
        return filterBuffers.shouldPushOnClose(transportKindOrdinal);
    }

    @Override
    public void pushAllFilterBanksForTransport(int transportKindOrdinal) {
        if (!filterBuffers.shouldPushOnClose(transportKindOrdinal)) {
            filterBuffers.logPushState(transportKindOrdinal, "pushAll_skip_not_dirty_or_hydrated");
            return;
        }
        filterBuffers.logPushState(transportKindOrdinal, "pushAll_start");
        ClientFilterLaneMirror mirror = filterBuffers.mirrorForTransport(transportKindOrdinal);
        for (net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank :
                net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank.values()) {
            List<Integer> caps2List = mirror.allowCaps2(bank);
            List<Integer> caps2 =
                    caps2List != null ? new ArrayList<>(caps2List) : List.of();
            FilterSyncDebugLog.clientPush(
                    "DuctNodeMenu.pushAll",
                    ductBlockPos,
                    accessFace,
                    transportKindOrdinal,
                    bank.ordinal(),
                    mirror.allowFilters(bank),
                    mirror.denyFilters(bank),
                    true);
            ModNetwork.sendFilterUpdate(
                    ductBlockPos,
                    accessFace,
                    transportKindOrdinal,
                    bank.ordinal(),
                    new ArrayList<>(mirror.allowFilters(bank)),
                    new ArrayList<>(mirror.denyFilters(bank)),
                    new ArrayList<>(mirror.allowCaps(bank)),
                    caps2,
                    new ArrayList<>(mirror.allowConcat(bank)),
                    new ArrayList<>(mirror.denyConcat(bank)),
                    new ArrayList<>(mirror.allowRemote(bank)),
                    new ArrayList<>(mirror.denyRemote(bank)),
                    new ArrayList<>(mirror.allowRemoteIgnoreChannel(bank)),
                    new ArrayList<>(mirror.denyRemoteIgnoreChannel(bank)),
                    new ArrayList<>(mirror.allowRemoteAnyFace(bank)),
                    new ArrayList<>(mirror.denyRemoteAnyFace(bank)),
                    mirror.denyOverridesAllow(bank));
        }
    }

    @Override
    public void reorderClientFilterBankForTransport(
            int transportKindOrdinal,
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank,
            net.minecraft.core.RegistryAccess registryAccess) {
        filterBuffers.reorderFilterBank(transportKindOrdinal, bank, registryAccess);
    }

    /** Keep deny-overrides flag aligned with synced {@link DuctMenuSync#DENY_OVERRIDES_ALLOW} on client. */
    public void updateClientDenyOverridesFromSync() {
        if (linkedBlockEntity == null) {
            filterBuffers
                    .mirrorForTransport(filterTransportKindOrdinal())
                    .setDenyOverridesAllow(
                            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank.FILTER,
                            syncData.get(DuctMenuSync.DENY_OVERRIDES_ALLOW) != 0);
        }
    }

    public void ensureClientFilterBufferSizes(boolean hybridFilterContext) {
        if (clientEditingEnergyOrHeatLane()) {
            return;
        }
        filterBuffers.ensureClientFilterBufferSizes(
                filterTransportKindOrdinal(),
                syncData,
                clientDuctLogicalId,
                moduleSlotCount,
                i -> {
                    if (linkedBlockEntity != null) {
                        return linkedBlockEntity
                                .getFaceLanes(accessFace)
                                .moduleSlots
                                .getStackInSlot(i);
                    }
                    return ItemStack.EMPTY;
                },
                false,
                hybridFilterContext);
    }

    @Override
    public void pushFilterConfigToServer(
            net.unfamily.another_dynamics.duct.DuctFaceNode.FilterBank bank,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<@Nullable DuctDirectionalEndpoint> allowRemote,
            List<@Nullable DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowRemoteIgnoreChannel,
            List<Boolean> denyRemoteIgnoreChannel,
            List<Boolean> allowRemoteAnyFace,
            List<Boolean> denyRemoteAnyFace,
            boolean denyOverridesAllow,
            boolean editingAllowList) {
        filterBuffers.logPushState(filterTransportKindOrdinal(), "before_push");
        FilterSyncDebugLog.clientPush(
                "DuctNodeMenu",
                ductBlockPos,
                accessFace,
                filterTransportKindOrdinal(),
                bank.ordinal(),
                allow,
                deny,
                editingAllowList);
        ModNetwork.sendFilterUpdate(
                ductBlockPos,
                accessFace,
                filterTransportKindOrdinal(),
                bank.ordinal(),
                allow,
                deny,
                allowCaps,
                allowCaps2,
                allowConcat,
                denyConcat,
                allowRemote,
                denyRemote,
                allowRemoteIgnoreChannel,
                denyRemoteIgnoreChannel,
                allowRemoteAnyFace,
                denyRemoteAnyFace,
                denyOverridesAllow);
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
                if (menuPlayer != null && !linkedBlockEntity.isMenuHubLayer()) {
                    ModNetwork.sendFilterSyncToPlayer(menuPlayer, linkedBlockEntity, accessFace);
                }
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

    /**
     * Server: copy or paste the settings copier in {@link #copierSlot} for the given GUI context.
     */
    public boolean handleSettingsCopierAction(ServerPlayer player, SettingsCopierActionPayload payload) {
        if (linkedBlockEntity == null
                || linkedBlockEntity.isRemoved()
                || linkedBlockEntity.getLevel() == null
                || linkedBlockEntity.getLevel().isClientSide()) {
            return false;
        }
        ItemStack copier = copierSlot.getStackInSlot(0);
        if (copier.isEmpty() || !(copier.getItem() instanceof SettingsCopierItem)) {
            SettingsCopierFeedback.notifyPasteFailed(player);
            return false;
        }
        var registries = linkedBlockEntity.getLevel().registryAccess();
        boolean copy = payload.action() == SettingsCopierActionPayload.ACTION_COPY;
        if (payload.viewKind() == SettingsCopierActionPayload.VIEW_MAIN) {
            if (copy) {
                CompoundTag snapshot = DuctFaceSettingsSnapshot.capture(linkedBlockEntity, accessFace, registries);
                commitCopierSnapshot(player, copier, snapshot);
                SettingsCopierFeedback.notifyCopied(player);
                return true;
            }
            if (SettingsCopierStoreKind.getMode(copier) != SettingsCopierStoreKind.ALL) {
                SettingsCopierFeedback.notifyWrongMode(player);
                return false;
            }
            var data = DuctFaceSettingsSnapshot.readFromCopier(copier);
            if (data.isEmpty()
                    || !DuctFaceSettingsSnapshot.apply(
                            linkedBlockEntity, accessFace, data.get(), registries, player)) {
                SettingsCopierFeedback.notifyPasteFailed(player);
                return false;
            }
            ModNetwork.sendFilterSyncToPlayer(player, linkedBlockEntity, accessFace);
            SettingsCopierFeedback.notifyPasted(player);
            return true;
        }
        if (payload.viewKind() != SettingsCopierActionPayload.VIEW_FILTER_LIST) {
            return false;
        }
        DuctTransportKind[] kinds = DuctTransportKind.values();
        DuctTransportKind guiLaneKind =
                kinds[Mth.clamp(payload.transportKindOrdinal(), 0, kinds.length - 1)];
        DuctFaceNode.FilterBank bank =
                DuctFaceNode.FilterBank.values()[
                        Mth.clamp(
                                payload.filterBankOrdinal(),
                                0,
                                DuctFaceNode.FilterBank.values().length - 1)];
        boolean allowList = payload.allowDeny() == SettingsCopierActionPayload.LIST_ALLOW;
        if (copy) {
            FilterListMaterialKind materialKind = FilterListMaterialKind.fromTransportKind(guiLaneKind);
            DuctFaceNode node = linkedBlockEntity.faceNodeForTransportKind(accessFace, guiLaneKind);
            CompoundTag snapshot =
                    DuctFilterListSnapshot.captureList(node, bank, allowList, materialKind);
            commitCopierSnapshot(player, copier, snapshot);
            SettingsCopierFeedback.notifyCopied(player);
            return true;
        }
        if (SettingsCopierStoreKind.getMode(copier) != SettingsCopierStoreKind.FILTER) {
            SettingsCopierFeedback.notifyWrongMode(player);
            return false;
        }
        var data = DuctFaceSettingsSnapshot.readFromCopier(copier);
        if (data.isEmpty()) {
            SettingsCopierFeedback.notifyPasteFailed(player);
            return false;
        }
        FilterListMaterialKind materialKind = DuctFilterListSnapshot.getMaterialKind(data.get());
        if (materialKind == FilterListMaterialKind.NONE) {
            SettingsCopierFeedback.notifyPasteKindNotSet(player);
            return false;
        }
        if (!materialKind.canPasteToDuctFace(linkedBlockEntity, accessFace)) {
            SettingsCopierFeedback.notifyPasteKindMismatch(player, materialKind);
            return false;
        }
        DuctTransportKind laneKind = materialKind.toTransportKind();
        DuctFaceNode node = linkedBlockEntity.faceNodeForTransportKind(accessFace, laneKind);
        if (!DuctFilterListSnapshot.applyToList(node, bank, allowList, data.get())) {
            SettingsCopierFeedback.notifyPasteFailed(player);
            return false;
        }
        linkedBlockEntity.clampFaceFiltersToSpec();
        linkedBlockEntity.setChanged();
        linkedBlockEntity.refreshMenuData(accessFace);
        ModNetwork.sendFilterSyncToPlayer(player, linkedBlockEntity, accessFace);
        SettingsCopierFeedback.notifyPasted(player);
        return true;
    }

    @Override
    public void removed(Player player) {
        // super.removed() already returns menu slot stacks (including the copier slot) to the player once.
        super.removed(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        int playerFirst = playerSlotStart();
        int playerLast = this.slots.size();
        int copierIndex = copySettingsSlotIndex();

        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        if (copierIndex >= 0 && index == copierIndex) {
            if (!moveItemStackTo(stack, playerFirst, playerLast, true)) {
                return ItemStack.EMPTY;
            }
        } else if (copierIndex >= 0 && index < copierIndex) {
            if (!moveItemStackTo(stack, playerFirst, playerLast, true)) {
                return ItemStack.EMPTY;
            }
        } else if (copierIndex >= 0 && stack.getItem() instanceof SettingsCopierItem) {
            if (!moveItemStackTo(stack, copierIndex, copierIndex + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (copierIndex > 0 && !moveItemStackTo(stack, 0, copierIndex, false)) {
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
}