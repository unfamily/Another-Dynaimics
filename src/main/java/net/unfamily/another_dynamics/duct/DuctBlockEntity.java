package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctFluidIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctGasIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctOverflowBuffer;
import net.unfamily.another_dynamics.duct.logistics.DuctOverflowRouting;
import net.unfamily.another_dynamics.duct.logistics.FluidTransitShipment;
import net.unfamily.another_dynamics.duct.logistics.GasTransitShipment;
import net.unfamily.another_dynamics.duct.logistics.DuctFluidServerTick;
import net.unfamily.another_dynamics.duct.logistics.DuctGasServerTick;
import net.unfamily.another_dynamics.duct.logistics.DuctEnergyServerTick;
import net.unfamily.another_dynamics.duct.logistics.DuctHeatServerTick;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.logistics.DuctTargetSelector;
import net.unfamily.another_dynamics.duct.logistics.DuctTransitTopology;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;
import net.unfamily.another_dynamics.duct.logistics.TransitPhase;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.integration.mekanism.MekanismHeatCompat;
import net.unfamily.another_dynamics.client.transit.DuctFluidTransitClientState;
import net.unfamily.another_dynamics.client.transit.DuctGasTransitClientState;
import net.unfamily.another_dynamics.client.transit.DuctTransitClientState;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.fluids.FluidStack;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.jetbrains.annotations.Nullable;

/**
 * Block entity for {@link DuctBlock}. Up to six independent <strong>item</strong> nodes (one per {@link Direction})
 * when that face touches item storage; additional transport kinds can be composed here for hybrid ducts.
 */
public final class DuctBlockEntity extends AbstractDuctBlockEntity {
    private static final int MAX_BLOCKED_ITEM_KINDS = 5;
    private static final int FACE_COUNT = 6;
    /** Max alternate destinations when {@link #canScheduleTowardFace} rejects the first routing pick (pending/cap simulation). */
    private static final int EXTRACTION_ROUTE_RETRY_CAP = 32;

    /**
     * After chunk load, {@link Level#getBlockState} can see an {@link AbstractDuctBlock} before the
     * {@link DuctBlockEntity} is attached; avoid treating that as a removed/broken duct.
     */
    private static boolean ductBlockPresentButBlockEntityPending(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockState(pos).getBlock() instanceof AbstractDuctBlock
                && !(level.getBlockEntity(pos) instanceof DuctBlockEntity);
    }

    /**
     * Sum of planned (items still in source) stack sizes for the same source duct face and item — avoids queuing more
     * of the same kind than the chest can cover.
     */
    private int pendingPlannedExtractCountOnSource(BlockPos sourceDuct, Direction sourceFace, ItemStack kind) {
        int sum = 0;
        for (OutboundShipment o : outboundShipments) {
            if (o.stack.isEmpty()) {
                continue;
            }
            if (!o.refundDuct.equals(sourceDuct) || o.sourceFace != sourceFace) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(o.stack, kind)) {
                continue;
            }
            if (o.sourceExtractCommitted || o.legacyPhysicalBuffer) {
                continue;
            }
            sum += o.stack.getCount();
        }
        return sum;
    }

    /**
     * Removes a task from the queue: clears incoming reservations. Refunds only when items were already extracted
     * ({@link OutboundShipment#sourceExtractCommitted} / legacy physical buffer). Planned-only tasks drop the plan only.
     */
    private void cancelOutboundShipment(
            ServerLevel level, OutboundShipment s, @Nullable Iterator<OutboundShipment> it, BlockPos... extraOverflowDucts) {
        boolean physical = s.legacyPhysicalBuffer || s.sourceExtractCommitted;
        ItemStack lump = s.stack.isEmpty() ? ItemStack.EMPTY : s.stack.copy();
        DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
        DuctIncomingIndex.unregister(level, s.refundDuct, s.registeredIncoming.copy());
        s.stack = ItemStack.EMPTY;
        removeShipmentFromList(s, it);
        if (!lump.isEmpty() && physical) {
            DuctOverflowRouting.tryRefundToSourceNoDrop(
                    level, s, lump, mergeScheduleOwnerWithExtras(worldPosition, extraOverflowDucts));
        }
        setChanged();
    }

    private final DuctFaceLanes[] faceLanes = new DuctFaceLanes[FACE_COUNT];

    /** {@link DuctDefinition#logicalId()} for this placed block (item component + NBT). */
    private String logicalDuctId = DuctIds.DEFAULT_LOGICAL_ID;

    private final List<OutboundShipment> outboundShipments = new ArrayList<>();
    /** Visual-only fluid packets along a path; owned by the extracting duct (same sync model as {@link #outboundShipments}). */
    private final List<FluidTransitShipment> fluidTransitShipments = new ArrayList<>();
    private final List<GasTransitShipment> gasTransitShipments = new ArrayList<>();
    private final List<ItemStack> migratedStorageBacklog = new ArrayList<>();
    private final DuctOverflowBuffer overflowBuffer = new DuctOverflowBuffer();

    private final SimpleContainerData menuData = new SimpleContainerData(DuctMenuSync.COUNT);

    /** GUI: index into {@link #orderedMenuTransportKinds()} for the open duct node menu. */
    private int menuTransportKindIndex;
    /** 0 = transport hub (picker only), 1 = full node UI; synced as {@link DuctMenuSync#MENU_VIEW_LAYER}. */
    private int menuUiLayer = 1;

    /**
     * Faces that have ever had a live item-storage neighbor while loaded; preserves per-face settings in NBT when the
     * inventory is removed. Does not add node voxels (those follow {@link #getStorageMask()} only).
     */
    private int latchedStorageFaceMask;

    /** Client-only cached icon pack from server; avoids relying on full face-node sync for rendering. */
    private int clientPackedNodeIcons = defaultPackedNodeIcons();

    /**
     * Server-only: {@link AbstractDuctBlockEntity#refreshFromWorld()} does not send block updates when only neighbor
     * redstone changes (masks unchanged), but {@link #computePackedNodeIcons()} depends on world power for low/high faces.
     */
    private boolean redstonePoweredSyncInitialized;
    private boolean lastDuctPoweredPackedIcons;

    private static int defaultPackedNodeIcons() {
        // Default to NONE (col=3,row=0 => idx=3) for all faces until the first server update arrives.
        int packed = 0;
        for (Direction d : Direction.values()) {
            packed |= (3 & 0xF) << (d.ordinal() * 4);
        }
        return packed;
    }

    private int computePackedNodeIcons() {
        int packed = 0;
        int sm = getVisualStorageMask();
        for (Direction d : Direction.values()) {
            int bit = 1 << d.ordinal();
            if ((sm & bit) == 0) {
                continue;
            }
            DuctFaceLanes lanes = getFaceLanes(d);
            int col =
                    switch (lanes.nodeMode) {
                        case EXTRACTION -> 0;
                        case FILTERING_INSERTION -> 1;
                        case RETRIEVING -> 2;
                        case NONE -> 3;
                        case EXTRACTION_FILTERING -> 0;
                        case RETRIEVING_EXTRACTION -> 1;
                    };
            int rowBase =
                    switch (lanes.nodeMode) {
                        case EXTRACTION, FILTERING_INSERTION, RETRIEVING, NONE -> 0;
                        case EXTRACTION_FILTERING, RETRIEVING_EXTRACTION -> 2;
                    };
            boolean off = !isFaceTransportEnabled(d);
            int row = rowBase + (off ? 1 : 0);
            int idx = row * 4 + col; // 0..15
            packed |= (idx & 0xF) << (d.ordinal() * 4);
        }
        return packed;
    }

    private boolean isFaceTransportEnabled(Direction face) {
        return DuctRedstoneLogic.isFaceTransportActive(level, worldPosition, getFaceLanes(face).redstoneMode);
    }

    /**
     * True if any visible storage face uses world redstone for its on/off icon row (ignored/disabled do not depend on power).
     */
    private boolean anyFaceUsesWorldRedstoneForPackedIcons() {
        int sm = getVisualStorageMask();
        for (Direction d : Direction.values()) {
            if ((sm & (1 << d.ordinal())) == 0) {
                continue;
            }
            int rm = getFaceLanes(d).redstoneMode;
            if (rm == 1 || rm == 2) {
                return true;
            }
        }
        return false;
    }

    private void tickRedstoneVisualSync(ServerLevel serverLevel) {
        if (!anyFaceUsesWorldRedstoneForPackedIcons()) {
            return;
        }
        boolean powered = DuctRedstoneLogic.isDuctPowered(serverLevel, worldPosition);
        if (!redstonePoweredSyncInitialized) {
            redstonePoweredSyncInitialized = true;
            lastDuctPoweredPackedIcons = powered;
            syncVisualGeometryToClients();
            return;
        }
        if (powered != lastDuctPoweredPackedIcons) {
            lastDuctPoweredPackedIcons = powered;
            syncVisualGeometryToClients();
        }
    }

    public DuctBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DUCT.get(), pos, state);
        for (int i = 0; i < FACE_COUNT; i++) {
            faceLanes[i] = new DuctFaceLanes(this, Direction.values()[i], 5);
        }
    }

    public String getLogicalDuctId() {
        return logicalDuctId;
    }

    public void setLogicalDuctId(String id) {
        String n = DuctIds.normalize(id);
        if (n.equals(logicalDuctId)) {
            return;
        }
        logicalDuctId = n;
        ensureFaceLaneModuleSlotCapacitiesMatchDefinition();
        setChanged();
        requestModelDataUpdate();
    }

    /** Clamped datapack module column size (not including copy slot). */
    public int moduleSlotCountForMenu() {
        return DuctGuiLayout.clampModuleSlotCount(
                ductDefinition().map(DuctDefinition::moduleSlotCount).orElse(5));
    }

    public void ensureFaceLaneModuleSlotCapacitiesMatchDefinition() {
        int want = moduleSlotCountForMenu();
        for (Direction d : Direction.values()) {
            getFaceLanes(d).resizeModuleSlots(want);
        }
    }

    public DuctItemTransportSpec itemTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::itemTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::itemDuctTransportSpec);
    }

    public DuctFluidTransportSpec fluidTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::fluidTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::fluidDuctTransportSpec);
    }

    public DuctGasTransportSpec gasTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::gasTransportOrFallback)
                .orElseGet(DuctGasTransportSpec::fallback);
    }

    public DuctEnergyTransportSpec energyTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::energyTransportOrFallback)
                .orElseGet(DuctEnergyTransportSpec::fallback);
    }

    public DuctHeatTransportSpec heatTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::heatTransportOrFallback)
                .orElseGet(DuctHeatTransportSpec::fallback);
    }

    public Optional<DuctDefinition> ductDefinition() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId);
    }

    public boolean ductAlwaysOpaqueRendering() {
        return ductDefinition().map(DuctDefinition::alwaysOpaqueRendering).orElse(false);
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        String id = input.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (id != null && !id.isEmpty()) {
            logicalDuctId = DuctIds.normalize(id);
            setChanged();
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(ModDataComponents.DUCT_LOGICAL_ID.get(), logicalDuctId);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide && !outboundShipments.isEmpty()) {
            DuctTransitClientState.syncFromOutboundShipments(worldPosition, outboundShipments, level);
        }
    }

    public DuctFaceLanes getFaceLanes(Direction dir) {
        return faceLanes[dir.ordinal()];
    }

    /** For hardcoded drops: expose latched faces without leaking field access. */
    public int getLatchedStorageFaceMaskForLoot() {
        return latchedStorageFaceMask;
    }

    /** For hardcoded drops: serialize only face-node configuration (no shipments/buffers). */
    public ListTag saveFaceNodesForLoot(HolderLookup.Provider registries) {
        ListTag faces = new ListTag();
        for (Direction d : Direction.values()) {
            CompoundTag ft = new CompoundTag();
            getFaceLanes(d).save(registries, ft);
            faces.add(ft);
        }
        return faces;
    }

    /**
     * Adds non-empty module stacks and per-lane GUI (copy) stacks for every face — used by {@link AbstractDuctBlock#getDrops}
     * so the block item stays lightweight (logical id only) while upgrades remain as separate loot.
     */
    public void appendUpgradeAndGuiDrops(
            List<ItemStack> out, @SuppressWarnings("unused") HolderLookup.Provider registries) {
        for (Direction d : Direction.values()) {
            DuctFaceLanes lanes = getFaceLanes(d);
            ItemStackHandler modules = lanes.moduleSlots;
            for (int i = 0; i < modules.getSlots(); i++) {
                ItemStack s = modules.getStackInSlot(i);
                if (!s.isEmpty()) {
                    out.add(s.copy());
                }
            }
            appendNonEmptyGuiSlots(lanes.item.guiSlots, out);
            appendNonEmptyGuiSlots(lanes.fluid.guiSlots, out);
            appendNonEmptyGuiSlots(lanes.gas.guiSlots, out);
        }
    }

    private static void appendNonEmptyGuiSlots(ItemStackHandler gui, List<ItemStack> out) {
        for (int i = 0; i < gui.getSlots(); i++) {
            ItemStack s = gui.getStackInSlot(i);
            if (!s.isEmpty()) {
                out.add(s.copy());
            }
        }
    }

    public DuctFaceNode getFaceNode(Direction dir) {
        return faceLanes[dir.ordinal()].item;
    }

    public DuctFaceNode getFluidFaceNode(Direction dir) {
        return faceLanes[dir.ordinal()].fluid;
    }

    public DuctFaceNode getGasFaceNode(Direction dir) {
        return faceLanes[dir.ordinal()].gas;
    }

    public List<DuctTransportKind> orderedMenuTransportKinds() {
        return DuctDefinition.orderedMenuTransportKinds(ductDefinition());
    }

    public DuctTransportKind menuActiveTransportKind() {
        List<DuctTransportKind> ord = orderedMenuTransportKinds();
        if (ord.size() == 1) {
            return ord.getFirst();
        }
        int i = Math.floorMod(menuTransportKindIndex, ord.size());
        return ord.get(i);
    }

    public DuctFaceNode activeMenuFaceNode(Direction face) {
        return switch (menuActiveTransportKind()) {
            case FLUID -> getFluidFaceNode(face);
            case GAS -> getGasFaceNode(face);
            case ENERGY, HEAT -> getFaceNode(face);
            case ITEM -> getFaceNode(face);
        };
    }

    /** Face node for the transport tab that owns filter lines, routing, channel, and machine slots for that lane. */
    public DuctFaceNode faceNodeForTransportKind(Direction face, DuctTransportKind kind) {
        return switch (kind) {
            case FLUID -> getFluidFaceNode(face);
            case GAS -> getGasFaceNode(face);
            case ENERGY, HEAT -> getFaceNode(face);
            case ITEM -> getFaceNode(face);
        };
    }

    /** Called when opening the node menu: first transport kind index and hub vs detail layer. */
    public void prepareMenuOpenState() {
        menuTransportKindIndex = 0;
        menuUiLayer = orderedMenuTransportKinds().size() > 1 ? 0 : 1;
    }

    /** @deprecated use {@link #prepareMenuOpenState()} */
    @Deprecated
    public void resetMenuTransportKindForOpen() {
        prepareMenuOpenState();
    }

    public int menuUiLayer() {
        return menuUiLayer;
    }

    public boolean isMenuHubLayer() {
        return menuUiLayer == 0;
    }

    public boolean setMenuTransportKindFromPicker(ServerPlayer player, Direction accessFace, DuctTransportKind kind) {
        List<DuctTransportKind> ord = orderedMenuTransportKinds();
        int idx = ord.indexOf(kind);
        if (idx < 0) {
            return false;
        }
        menuTransportKindIndex = idx;
        menuUiLayer = 1;
        setChanged();
        refreshMenuData(accessFace);
        ModNetwork.sendFilterSyncToPlayer(player, this, accessFace);
        syncVisualGeometryToClients();
        return true;
    }

    public boolean returnMenuToHub(ServerPlayer player, Direction accessFace) {
        if (orderedMenuTransportKinds().size() <= 1) {
            return false;
        }
        menuUiLayer = 0;
        setChanged();
        refreshMenuData(accessFace);
        syncVisualGeometryToClients();
        return true;
    }

    public boolean cycleMenuTransportKind(ServerPlayer player, Direction accessFace) {
        List<DuctTransportKind> ord = orderedMenuTransportKinds();
        if (ord.size() <= 1) {
            return false;
        }
        menuTransportKindIndex = (menuTransportKindIndex + 1) % ord.size();
        setChanged();
        refreshMenuData(accessFace);
        ModNetwork.sendFilterSyncToPlayer(player, this, accessFace);
        return true;
    }

    public int computeFluidExtractBatchSettingCap(Direction face) {
        return fluidTransportSpec().extractBatchSettingCapMb(getFluidExtractBatchModuleBonus(face));
    }

    private int getFluidExtractBatchModuleBonus(Direction face) {
        return DuctModuleEffects.fluidExtractBatchBonusMb(this, face, fluidTransportSpec());
    }

    public DuctOverflowBuffer getOverflowBuffer() {
        return overflowBuffer;
    }

    public SimpleContainerData getMenuData() {
        return menuData;
    }

    public Component getScreenTitle() {
        return Component.translatable(DuctIds.nodeScreenTranslationKey(logicalDuctId));
    }

    @Override
    protected EnumSet<DuctNetworkType> ductNetworkTypesForGeometry() {
        // Geometry + attachment discovery must follow the logical duct definition (not the physical block id),
        // otherwise a universal block would attach to item/fluid/gas storage simultaneously.
        EnumSet<DuctNetworkType> out = EnumSet.noneOf(DuctNetworkType.class);
        EnumSet<DuctTransportKind> kinds =
                ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        if (kinds.contains(DuctTransportKind.ITEM)) {
            out.add(DuctNetworkType.ITEM);
        }
        if (kinds.contains(DuctTransportKind.FLUID)) {
            out.add(DuctNetworkType.FLUID);
        }
        if (kinds.contains(DuctTransportKind.GAS) && MekanismChemicalCompat.isLoaded()) {
            out.add(DuctNetworkType.GAS);
        }
        if (kinds.contains(DuctTransportKind.ENERGY)) {
            out.add(DuctNetworkType.ENERGY);
        }
        if (kinds.contains(DuctTransportKind.HEAT)
                && MekanismHeatCompat.isLoaded()
                && MekanismHeatCompat.isHeatCapabilityAvailable()) {
            out.add(DuctNetworkType.HEAT);
        }
        if (out.isEmpty()) {
            out.add(DuctNetworkType.ITEM);
        }
        return out;
    }

    /**
     * Faces that still accept GUI / synced field updates: live storage or latched settings (no extra collision voxels).
     */
    public int getSettingsFaceMask() {
        return getStorageMask() | latchedStorageFaceMask;
    }

    @Override
    protected void mergePersistentStorageFaceLatch(int previousWorldStorageMask, int newWorldStorageMask) {
        int merged = latchedStorageFaceMask | previousWorldStorageMask | newWorldStorageMask;
        if (merged == latchedStorageFaceMask) {
            return;
        }
        latchedStorageFaceMask = merged;
        if (level != null && !level.isClientSide()) {
            setChanged();
        }
    }

    @Override
    protected int attachmentMaskForNeighbor(
            DuctNetworkType net, Direction dir, BlockState neighborState, BlockPos neighborPos) {
        if (level == null || neighborState.isAir()) {
            return 0;
        }
        if (net == DuctNetworkType.ITEM) {
            IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, neighborPos, dir.getOpposite());
            if (cap != null && cap.getSlots() > 0) {
                return 1 << dir.ordinal();
            }
        } else if (net == DuctNetworkType.FLUID) {
            var fh = level.getCapability(Capabilities.FluidHandler.BLOCK, neighborPos, dir.getOpposite());
            if (fh != null) {
                return 1 << dir.ordinal();
            }
        } else if (net == DuctNetworkType.GAS) {
            if (!MekanismChemicalCompat.isLoaded()) {
                return 0;
            }
            Object ch = MekanismChemicalCompat.getChemicalHandlerAt(level, neighborPos, dir.getOpposite());
            if (ch != null) {
                return 1 << dir.ordinal();
            }
        } else if (net == DuctNetworkType.ENERGY) {
            var eh = level.getCapability(Capabilities.EnergyStorage.BLOCK, neighborPos, dir.getOpposite());
            if (eh != null) {
                return 1 << dir.ordinal();
            }
        } else if (net == DuctNetworkType.HEAT) {
            if (!MekanismHeatCompat.isHeatCapabilityAvailable()) {
                return 0;
            }
            if (MekanismHeatCompat.getHeatHandler(level, neighborPos, dir.getOpposite()) != null) {
                return 1 << dir.ordinal();
            }
        }
        return 0;
    }

    @Override
    protected void onAfterConnectionRefresh(boolean masksChanged) {
        if (level != null && !level.isClientSide()) {
            enforcePipeSegmentBehavior();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide() && level instanceof ServerLevel sl) {
            registerOutboundInIncomingIndex(sl);
            for (FluidTransitShipment s : fluidTransitShipments) {
                if (!s.fluid.isEmpty()) {
                    DuctFluidIncomingIndex.register(sl, s.destDuct, s.fluid);
                }
            }
            if (MekanismChemicalCompat.isLoaded()) {
                for (GasTransitShipment s : gasTransitShipments) {
                    if (s.stack != null && !MekanismChemicalCompat.isEmptyStack(s.stack)) {
                        DuctGasIncomingIndex.register(sl, s.destDuct, s.stack);
                    }
                }
            }
            if (clampAllFacesToDatapackRestrictions()) {
                syncVisualGeometryToClients();
            }
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide() && level instanceof ServerLevel sl) {
            unregisterOutboundFromIncomingIndex(sl);
        }
        if (level != null && level.isClientSide()) {
            BlockPos p = worldPosition.immutable();
            DuctFluidTransitClientState.removeAt(p);
            DuctGasTransitClientState.removeAt(p);
            DuctTransitClientState.removeAt(p);
        }
        super.setRemoved();
    }

    private void registerOutboundInIncomingIndex(ServerLevel sl) {
        for (OutboundShipment s : outboundShipments) {
            if (s.travelTicks >= 0 && !s.stack.isEmpty()) {
                DuctIncomingIndex.register(sl, s.destDuct, s.registeredIncoming.copy());
            }
        }
    }

    private void unregisterOutboundFromIncomingIndex(ServerLevel sl) {
        for (OutboundShipment s : outboundShipments) {
            if (s.travelTicks >= 0 && !s.stack.isEmpty()) {
                DuctIncomingIndex.unregister(sl, s.destDuct, s.registeredIncoming.copy());
            }
        }
    }

    public void serverTickPipe(ServerLevel serverLevel) {
        if (isRemoved()) {
            return;
        }
        refreshFromWorld();
        tickRedstoneVisualSync(serverLevel);
        tickOutboundShipments(serverLevel);
        tickFluidTransitShipments(serverLevel);
        tickGasTransitShipments(serverLevel);
        tickOverflowBufferDrain(serverLevel);
        tickMigratedBacklogFlush(serverLevel);
        boolean wantsItem =
                ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ITEM)).orElse(true);
        if (wantsItem && isStorageAttachmentNode()) {
            DuctItemTransportSpec spec = itemTransportSpec();
            int sm = getStorageMask();
            for (Direction dir : Direction.values()) {
                if ((sm & (1 << dir.ordinal())) == 0) {
                    continue;
                }
                DuctFaceLanes lanes = getFaceLanes(dir);
                DuctFaceNode node = getFaceNode(dir);
                if (!isFaceTransportEnabled(dir)) {
                    continue;
                }
                int rate = DuctModuleEffects.effectiveItemActionRateTicks(this, dir, spec);
                if (node.ticksUntilAction > 0) {
                    node.ticksUntilAction--;
                    setChanged();
                    continue;
                }
                node.ticksUntilAction = rate - 1;
                if (lanes.nodeMode == NodeMode.EXTRACTION || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING) {
                    tickExtractionPullForFace(serverLevel, spec, dir, node);
                } else if (lanes.nodeMode == NodeMode.RETRIEVING) {
                    tickRetrieverPullForFace(serverLevel, spec, dir, node);
                } else if (lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                    tickRetrieverPullForFace(serverLevel, spec, dir, node);
                    tickExtractionPullForFace(serverLevel, spec, dir, node);
                }
            }
        }

        DuctFluidServerTick.tick(this, serverLevel);
        DuctGasServerTick.tick(this, serverLevel);
        DuctEnergyServerTick.tick(this, serverLevel);
        DuctHeatServerTick.tick(this, serverLevel);
    }

    private void resolveNegativeTravelShipments(ServerLevel level) {
        Iterator<OutboundShipment> it = outboundShipments.iterator();
        while (it.hasNext()) {
            OutboundShipment s = it.next();
            if (s.travelTicks >= 0) {
                continue;
            }
            if (s.stack.isEmpty()) {
                it.remove();
                setChanged();
                continue;
            }
            cancelOutboundShipment(level, s, it);
        }
    }

    private void tickOutboundShipments(ServerLevel level) {
        resolveNegativeTravelShipments(level);
        Iterator<OutboundShipment> it = outboundShipments.iterator();
        boolean dirty = false;
        while (it.hasNext()) {
            OutboundShipment s = it.next();
            if (s.stack.isEmpty()) {
                DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
                it.remove();
                setChanged();
                dirty = true;
                continue;
            }
            if (s.travelTicks > 0) {
                if (!resizePendingShipment(level, s, it)) {
                    dirty = true;
                    continue;
                }
                OptionalInt broken = DuctTransitTopology.firstBrokenPathEdge(level, s.ductPath);
                if (broken.isPresent()) {
                    cancelOutboundShipment(level, s, it, DuctTransitTopology.currentDuctPos(s));
                    dirty = true;
                    continue;
                }
                s.travelTicks--;
                setChanged();
                dirty = true;
                continue;
            }
            if (s.transitPhase == TransitPhase.RETURN) {
                DuctIncomingIndex.unregister(level, s.refundDuct, s.registeredIncoming.copy());
                if (!s.stack.isEmpty()) {
                    DuctOverflowRouting.finishReturnLegAbsorb(level, s, worldPosition);
                }
                it.remove();
                setChanged();
                dirty = true;
                continue;
            }
            if (!level.isLoaded(s.destDuct)) {
                continue;
            }
            boolean deliveryDeferred = false;
            int deliverPasses = 0;
            while (!deliveryDeferred && !s.stack.isEmpty() && deliverPasses < 64) {
                deliverPasses++;
                deliveryDeferred =
                        s.destDuct.equals(worldPosition)
                                ? finishRetrieverArrival(level, s, it)
                                : finishExtractionDelivery(level, s, it);
            }
            if (deliveryDeferred) {
                continue;
            }
            dirty = true;
        }
        if (dirty) {
            pushTransitSnapshotToClients(level);
        }
    }

    /**
     * Queues a planned fluid transfer along {@code path}: liquid moves only when {@code travelTicks} elapses, after
     * per-tick revalidation (same idea as item {@link OutboundShipment}).
     */
    /**
     * Planned fluid still scheduled to drain from {@code sourceFace} (matches fluid components, sums mB).
     */
    public int pendingOutboundFluidMbFromFace(Direction sourceFace, FluidStack kind) {
        if (kind.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (FluidTransitShipment s : fluidTransitShipments) {
            if (s.sourceFace != sourceFace || s.fluid.isEmpty()) {
                continue;
            }
            if (!FluidStack.isSameFluidSameComponents(s.fluid, kind)) {
                continue;
            }
            sum += s.fluid.getAmount();
        }
        return sum;
    }

    /**
     * Planned gas amount still scheduled to leave {@code sourceFace} for the same chemical type as {@code probe}.
     */
    public long pendingOutboundGasAmountFromFace(Direction sourceFace, Object probe) {
        if (probe == null || MekanismChemicalCompat.isEmptyStack(probe)) {
            return 0L;
        }
        String id = MekanismChemicalCompat.getTypeRegistryName(probe);
        if (id == null || id.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (GasTransitShipment s : gasTransitShipments) {
            if (s.sourceFace != sourceFace || s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
                continue;
            }
            String sid = MekanismChemicalCompat.getTypeRegistryName(s.stack);
            if (id.equals(sid)) {
                sum += MekanismChemicalCompat.getAmount(s.stack);
            }
        }
        return sum;
    }

    public void scheduleFluidTransitPending(
            ServerLevel level,
            FluidStack plannedFluid,
            List<BlockPos> path,
            Direction sourceFace,
            Direction destStorageFace,
            BlockPos destDuct,
            DuctFluidTransportSpec spec,
            long edgeTicksPerBlock) {
        if (path == null || path.isEmpty() || plannedFluid.isEmpty()) {
            return;
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(path);
        long edge = Math.max(1L, edgeTicksPerBlock);
        long travel = DuctPathfinder.pathTravelTicks(pathWire, edge);
        int tot = (int) Math.min(Math.max(1L, travel), Integer.MAX_VALUE);
        int edgeI = (int) Math.min(edge, Integer.MAX_VALUE);
        FluidTransitShipment added =
                new FluidTransitShipment(
                        plannedFluid.copy(),
                        pathWire,
                        tot,
                        tot,
                        edgeI,
                        level.getGameTime(),
                        sourceFace,
                        destStorageFace,
                        destDuct);
        fluidTransitShipments.add(added);
        DuctFluidIncomingIndex.register(level, destDuct, plannedFluid);
        setChanged();
        pushTransitSnapshotToClients(level);
    }

    private void tickFluidTransitShipments(ServerLevel level) {
        if (fluidTransitShipments.isEmpty()) {
            return;
        }
        Iterator<FluidTransitShipment> it = fluidTransitShipments.iterator();
        boolean dirty = false;
        while (it.hasNext()) {
            FluidTransitShipment s = it.next();
            if (s.fluid.isEmpty()) {
                it.remove();
                dirty = true;
                continue;
            }
            if (s.travelTicks > 0) {
                if (DuctTransitTopology.firstBrokenFluidPathEdge(level, s.ductPath).isPresent()) {
                    DuctFluidIncomingIndex.unregister(level, s.destDuct, s.fluid);
                    it.remove();
                    dirty = true;
                    continue;
                }
                if (!DuctFluidServerTick.fluidShipmentMidTransitValid(level, this, s)) {
                    DuctFluidIncomingIndex.unregister(level, s.destDuct, s.fluid);
                    it.remove();
                    dirty = true;
                    continue;
                }
                s.travelTicks--;
                dirty = true;
                continue;
            }
            DuctFluidServerTick.tryExecutePlannedFluidTransfer(level, this, s);
            DuctFluidIncomingIndex.unregister(level, s.destDuct, s.fluid);
            it.remove();
            dirty = true;
        }
        if (dirty) {
            setChanged();
            pushTransitSnapshotToClients(level);
        }
    }

    public void scheduleGasTransitPending(
            ServerLevel level,
            Object plannedStack,
            List<BlockPos> path,
            Direction sourceFace,
            Direction destStorageFace,
            BlockPos destDuct,
            DuctGasTransportSpec spec,
            long edgeTicksPerBlock) {
        if (!MekanismChemicalCompat.isLoaded()) {
            return;
        }
        if (path == null || path.isEmpty() || plannedStack == null || MekanismChemicalCompat.isEmptyStack(plannedStack)) {
            return;
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(path);
        long edge = Math.max(1L, edgeTicksPerBlock);
        long travel = DuctPathfinder.pathTravelTicks(pathWire, edge);
        int tot = (int) Math.min(Math.max(1L, travel), Integer.MAX_VALUE);
        int edgeI = (int) Math.min(edge, Integer.MAX_VALUE);
        GasTransitShipment gsh =
                new GasTransitShipment(
                        plannedStack,
                        pathWire,
                        tot,
                        tot,
                        edgeI,
                        level.getGameTime(),
                        sourceFace,
                        destStorageFace,
                        destDuct);
        gasTransitShipments.add(gsh);
        if (!MekanismChemicalCompat.isEmptyStack(plannedStack) && MekanismChemicalCompat.getAmount(plannedStack) > 0) {
            DuctGasIncomingIndex.register(level, destDuct, plannedStack);
        }
        setChanged();
        pushTransitSnapshotToClients(level);
    }

    private void syncGasTransitToClientsNow(ServerLevel level) {
        setChanged();
        pushTransitSnapshotToClients(level);
    }

    private void tickGasTransitShipments(ServerLevel level) {
        if (gasTransitShipments.isEmpty()) {
            return;
        }
        Iterator<GasTransitShipment> it = gasTransitShipments.iterator();
        boolean progressDirty = false;
        while (it.hasNext()) {
            GasTransitShipment s = it.next();
            if (s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
                it.remove();
                syncGasTransitToClientsNow(level);
                continue;
            }
            if (MekanismChemicalCompat.isEmptyStack(s.stack) || MekanismChemicalCompat.getAmount(s.stack) <= 0) {
                it.remove();
                syncGasTransitToClientsNow(level);
                continue;
            }

            if (s.travelTicks > 0) {
                if (DuctTransitTopology.firstBrokenGasPathEdge(level, s.ductPath).isPresent()) {
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    syncGasTransitToClientsNow(level);
                    continue;
                }
                if (MekanismChemicalCompat.isRadioactive(s.stack)
                        && !DuctPathfinder.gasPathAllowsRadioactive(level, s.ductPath)) {
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    syncGasTransitToClientsNow(level);
                    continue;
                }
                // Basic mid-transit validation: ensure endpoints & handlers still exist.
                if (!level.isLoaded(worldPosition) || !level.isLoaded(s.destDuct)) {
                    s.travelTicks--;
                    progressDirty = true;
                    continue;
                }
                if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity)) {
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    syncGasTransitToClientsNow(level);
                    continue;
                }
                if (!DuctGasServerTick.gasShipmentMidTransitValid(level, this, s)) {
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    syncGasTransitToClientsNow(level);
                    continue;
                }
                s.travelTicks--;
                progressDirty = true;
                continue;
            }

            try {
                tryExecutePlannedGasTransfer(level, this, s);
            } catch (Throwable t) {
                // Avoid "ticking block entity" hard-disable if compat throws.
                net.unfamily.another_dynamics.AnotherDynamicsMod.LOGGER.error(
                        "Gas shipment execute failed at {} (ductId={})", worldPosition, logicalDuctId, t);
            } finally {
                DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                it.remove();
                syncGasTransitToClientsNow(level);
            }
        }
        if (progressDirty) {
            syncGasTransitToClientsNow(level);
        }
    }

    private static void tryExecutePlannedGasTransfer(ServerLevel level, DuctBlockEntity sourceBe, GasTransitShipment s) {
        if (!MekanismChemicalCompat.isLoaded() || s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
            return;
        }
        BlockPos srcPos = sourceBe.getBlockPos();
        Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, srcPos, s.sourceFace);
        if (srcHandler == null) {
            return;
        }
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            return;
        }
        BlockPos destPos = destBe.getBlockPos();
        Direction df = s.destFace;
        int dsm = destBe.getStorageMask();
        if ((dsm & (1 << df.ordinal())) == 0) {
            return;
        }

        DuctFaceLanes destLanes = destBe.getFaceLanes(df);
        if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
            return;
        }
        NodeMode dm = destLanes.nodeMode;
        if (dm != NodeMode.NONE
                && dm != NodeMode.FILTERING_INSERTION
                && dm != NodeMode.EXTRACTION_FILTERING
                && dm != NodeMode.RETRIEVING
                && dm != NodeMode.RETRIEVING_EXTRACTION) {
            return;
        }

        Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, destPos, df);
        if (destHandler == null) {
            return;
        }

        long want = MekanismChemicalCompat.getAmount(s.stack);
        if (want <= 0) {
            return;
        }

        DuctFaceNode srcNodeGas = sourceBe.getFaceLanes(s.sourceFace).gas;
        NodeMode sm = sourceBe.getFaceLanes(s.sourceFace).nodeMode;
        boolean donorSource = sm == NodeMode.NONE || sm == NodeMode.FILTERING_INSERTION;
        DuctFaceNode.FilterBank srcCapBank =
                donorSource ? DuctFaceNode.FilterBank.FILTER : DuctFaceNode.FilterBank.EXTRACTOR;
        if (!donorSource
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        srcNodeGas, DuctFaceNode.FilterBank.EXTRACTOR, s.stack, level)) {
            return;
        }
        if (donorSource
                && sm == NodeMode.FILTERING_INSERTION
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        srcNodeGas, DuctFaceNode.FilterBank.FILTER, s.stack, level)) {
            return;
        }

        Object toMoveProbe = MekanismChemicalCompat.copyWithAmount(s.stack, want);
        long keepCap =
                DuctGasAllowLimitLogic.maxExtractRespectingKeep(
                        srcHandler,
                        srcNodeGas.bankAllowFilters(srcCapBank),
                        srcNodeGas.bankAllowCaps(srcCapBank),
                        toMoveProbe,
                        level.registryAccess());
        if (keepCap != Long.MAX_VALUE && MekanismChemicalCompat.getAmount(toMoveProbe) > keepCap) {
            return;
        }

        if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destLanes.gas, DuctFaceNode.FilterBank.FILTER, s.stack, level)) {
            return;
        }
        if ((dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION)
                && !DuctGasFilterLogic.passesGasFiltersForBank(
                        destLanes.gas, DuctFaceNode.FilterBank.RETRIEVER, s.stack, level)) {
            return;
        }

        Object toInsertSim = MekanismChemicalCompat.copyWithAmount(s.stack, want);
        long canInsert = MekanismChemicalCompat.simulateInsert(destHandler, toInsertSim);
        if (canInsert <= 0) {
            return;
        }
        if (dm == NodeMode.RETRIEVING || dm == NodeMode.RETRIEVING_EXTRACTION) {
            canInsert =
                    Math.min(
                            canInsert,
                            DuctGasAllowLimitLogic.maxAdditionalInsertForAllowLine(
                                    destHandler,
                                    destLanes.gas.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER),
                                    destLanes.gas.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER),
                                    toInsertSim,
                                    level.registryAccess(),
                                    (line, reg) ->
                                            DuctGasAllowLimitLogic.countMatchingInStacks(
                                                    DuctGasIncomingIndex.snapshot(level, destPos), line, reg)));
        }
        if (canInsert <= 0) {
            return;
        }
        long take = Math.min(want, canInsert);

        // Execute extraction then insertion; if insertion leaves remainder, attempt to re-insert into source.
        Object extracted = MekanismChemicalCompat.extractAny(srcHandler, take);
        if (MekanismChemicalCompat.isEmptyStack(extracted)) {
            return;
        }
        Object left = MekanismChemicalCompat.insertExecute(destHandler, extracted);
        if (!MekanismChemicalCompat.isEmptyStack(left) && MekanismChemicalCompat.getAmount(left) > 0) {
            MekanismChemicalCompat.insertExecute(srcHandler, left);
        }
        sourceBe.setChanged();
        destBe.setChanged();
    }

    private void pushTransitSnapshotToClients(ServerLevel level) {
        level.blockEntityChanged(getBlockPos());
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    /**
     * Mid-transit resize for legacy saves that still have items extracted at schedule time. Planned-only shipments skip
     * this (capacity is fixed at schedule; delivery adapts counts).
     */
    private boolean resizePendingShipment(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        if (s.transitPhase == TransitPhase.RETURN) {
            return true;
        }
        if (!s.legacyPhysicalBuffer && !s.sourceExtractCommitted) {
            return true;
        }
        if (!level.isLoaded(s.destDuct) || !level.isLoaded(s.refundDuct)) {
            return true;
        }
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            if (ductBlockPresentButBlockEntityPending(level, s.destDuct)) {
                return true;
            }
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            if (ductBlockPresentButBlockEntityPending(level, s.refundDuct)) {
                return true;
            }
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(destBe.getFaceNode(s.destFace).channelLetter, s)
                || !DuctChannelPolicy.faceMatchesShipment(srcBe.getFaceNode(s.sourceFace).channelLetter, s)) {
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!DuctRedstoneLogic.isFaceTransportActive(level, s.destDuct, destBe.getFaceLanes(s.destFace).redstoneMode)) {
            cancelOutboundShipment(level, s, it);
            return false;
        }
        DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());

        int planned = s.stack.getCount();
        boolean itemsAlreadyPulledFromSource = s.legacyPhysicalBuffer || s.sourceExtractCommitted;
        int capExt;
        if (s.legacyOmniFaces) {
            capExt =
                    itemsAlreadyPulledFromSource
                            ? planned
                            : DuctCapHelper.countExtractableMatching(level, s.refundDuct, srcBe, s.stack, planned);
        } else {
            capExt =
                    itemsAlreadyPulledFromSource
                            ? planned
                            : DuctCapHelper.countExtractableMatchingOnFace(
                                    level, s.refundDuct, s.sourceFace, s.stack, planned);
        }
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, s.destDuct);
        DuctItemTransportSpec srcSpec = srcBe.itemTransportSpec();
        int batchCap = tubeOperationBatchSize(srcSpec, srcBe, s.sourceFace);
        int insLimit = Math.min(planned, Math.min(capExt, batchCap));
        int capIn;
        if (s.legacyOmniFaces) {
            capIn = DuctCapHelper.maxInsertableAfterPending(level, s.destDuct, destBe, s.stack, insLimit, prior);
        } else {
            capIn =
                    DuctCapHelper.maxInsertableAfterPendingOnFace(
                            level, s.destDuct, s.destFace, s.stack, insLimit, prior);
        }
        if (!s.legacyOmniFaces) {
            capIn =
                    capInsertableForFilterAllowLimit(
                            level, s.destDuct, s.destFace, destBe, s.stack, capIn, prior);
            if (!itemsAlreadyPulledFromSource) {
                DuctFaceNode.FilterBank srcAllowBank =
                        s.destDuct.equals(worldPosition)
                                ? DuctFaceNode.FilterBank.RETRIEVER
                                : DuctFaceNode.FilterBank.EXTRACTOR;
                capExt =
                        Math.min(
                                capExt,
                                capExtractableForAllowKeep(
                                        level,
                                        s.refundDuct,
                                        s.sourceFace,
                                        srcBe,
                                        srcAllowBank,
                                        s.stack,
                                        capExt));
            }
        }
        int newPlanned = Math.min(planned, Math.min(capExt, capIn));

        if (newPlanned <= 0) {
            if (s.sourceExtractCommitted && planned > 0) {
                cancelOutboundShipment(level, s, it);
                return false;
            }
            if (s.legacyPhysicalBuffer && planned > 0) {
                refundExcessToSource(level, s, planned);
            }
            cancelOutboundShipment(level, s, it);
            return false;
        }

        if (itemsAlreadyPulledFromSource && newPlanned < planned) {
            ItemStack excess = s.stack.copy();
            excess.setCount(planned - newPlanned);
            refundStackToSource(level, s, excess);
        }
        if (newPlanned != planned) {
            s.stack.setCount(newPlanned);
            setChanged();
        }
        s.registeredIncoming = s.stack.copy();
        DuctIncomingIndex.register(level, s.destDuct, s.registeredIncoming.copy());
        setChanged();
        return true;
    }

    private static BlockPos[] mergeScheduleOwnerWithExtras(BlockPos scheduleOwner, BlockPos[] extras) {
        if (extras == null || extras.length == 0) {
            return new BlockPos[] {scheduleOwner};
        }
        BlockPos[] merged = new BlockPos[1 + extras.length];
        merged[0] = scheduleOwner;
        System.arraycopy(extras, 0, merged, 1, extras.length);
        return merged;
    }

    private void removeShipmentFromList(OutboundShipment s, @Nullable Iterator<OutboundShipment> removalIt) {
        if (removalIt != null) {
            removalIt.remove();
        } else {
            outboundShipments.remove(s);
        }
    }

    private void refundExcessToSource(ServerLevel level, OutboundShipment s, int count) {
        ItemStack excess = s.stack.copy();
        excess.setCount(count);
        refundStackToSource(level, s, excess);
    }

    private void refundStackToSource(ServerLevel level, OutboundShipment s, ItemStack excess) {
        DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, excess, worldPosition);
        if (level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe) {
            srcBe.setChanged();
        }
    }

    /**
     * @return {@code true} if delivery was deferred (world/block entity not ready); {@code false} if resolved this tick.
     */
    private boolean finishExtractionDelivery(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            if (ductBlockPresentButBlockEntityPending(level, s.destDuct)) {
                return true;
            }
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(destBe.getFaceNode(s.destFace).channelLetter, s)) {
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!DuctRedstoneLogic.isFaceTransportActive(level, s.destDuct, destBe.getFaceLanes(s.destFace).redstoneMode)) {
            cancelOutboundShipment(level, s, it);
            return false;
        }

        if (s.legacyPhysicalBuffer || s.sourceExtractCommitted) {
            DuctBlockEntity srcSettings =
                    level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity dbe ? dbe : this;
            DuctItemTransportSpec transportSpec = itemTransportSpec();
            int chunkCap = tubeOperationBatchSize(transportSpec, srcSettings, s.sourceFace);
            int planned = s.stack.getCount();
            int take = Math.min(planned, chunkCap);
            if (take <= 0) {
                it.remove();
                s.stack = ItemStack.EMPTY;
                setChanged();
                return false;
            }
            ItemStack chunk = s.stack.copy();
            chunk.setCount(take);
            if (!s.legacyOmniFaces) {
                List<ItemStack> priorR = DuctIncomingIndex.snapshot(level, s.destDuct);
                int maxIns =
                        DuctCapHelper.maxInsertableAfterPendingOnFace(
                                level, s.destDuct, s.destFace, chunk, chunk.getCount(), priorR);
                maxIns =
                        capInsertableForFilterAllowLimit(
                                level, s.destDuct, s.destFace, destBe, chunk, maxIns, priorR);
                take = Math.min(take, maxIns);
                if (take <= 0) {
                    cancelOutboundShipment(level, s, it);
                    return false;
                }
                chunk.setCount(take);
            }
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            NodeMode dm = destBe.getFaceLanes(s.destFace).nodeMode;
            if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                    && !destBe.passesItemFilters(s.destFace, chunk, level, DuctFaceNode.FilterBank.FILTER)) {
                cancelOutboundShipment(level, s, it);
                return false;
            }
            ItemStack remainder =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, chunk.copy())
                            : DuctCapHelper.insertIntoFace(level, s.destDuct, s.destFace, chunk.copy());
            if (take < planned) {
                s.stack.shrink(take);
                s.registeredIncoming = s.stack.copy();
                DuctIncomingIndex.register(level, s.destDuct, s.registeredIncoming.copy());
            } else {
                it.remove();
                s.stack = ItemStack.EMPTY;
            }
            if (!remainder.isEmpty()) {
                DuctOverflowRouting.absorbExtractionDestRemainder(level, s, destBe, remainder, worldPosition);
            }
            setChanged();
            return false;
        }

        DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            if (ductBlockPresentButBlockEntityPending(level, s.refundDuct)) {
                DuctIncomingIndex.register(level, s.destDuct, s.registeredIncoming.copy());
                return true;
            }
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(srcBe.getFaceNode(s.sourceFace).channelLetter, s)) {
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        DuctItemTransportSpec transportSpec = srcBe.itemTransportSpec();
        int moduleCap = tubeOperationBatchSize(transportSpec, srcBe, s.sourceFace);
        int planned = s.stack.getCount();
        int capExt =
                s.legacyOmniFaces
                        ? DuctCapHelper.countExtractableMatching(level, s.refundDuct, srcBe, s.stack, planned)
                        : DuctCapHelper.countExtractableMatchingOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, planned);
        int insLimit = Math.min(planned, Math.min(capExt, moduleCap));
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, s.destDuct);
        int capIn =
                s.legacyOmniFaces
                        ? DuctCapHelper.maxInsertableAfterPending(
                                level, s.destDuct, destBe, s.stack, insLimit, prior)
                        : DuctCapHelper.maxInsertableAfterPendingOnFace(
                                level, s.destDuct, s.destFace, s.stack, insLimit, prior);
        if (!s.legacyOmniFaces) {
            capIn =
                    capInsertableForFilterAllowLimit(
                            level, s.destDuct, s.destFace, destBe, s.stack, capIn, prior);
            capExt =
                    Math.min(
                            capExt,
                            capExtractableForAllowKeep(
                                    level,
                                    s.refundDuct,
                                    s.sourceFace,
                                    srcBe,
                                    DuctFaceNode.FilterBank.EXTRACTOR,
                                    s.stack,
                                    capExt));
        }
        int n = Math.min(planned, Math.min(capExt, capIn));
        n = Math.min(n, moduleCap);
        if (n <= 0) {
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        ItemStack toExt = s.stack.copy();
        toExt.setCount(n);
        ItemStack extracted =
                s.legacyOmniFaces
                        ? DuctCapHelper.extractMatchingUpTo(level, s.refundDuct, srcBe, toExt, n)
                        : DuctCapHelper.extractMatchingUpToOnFace(level, s.refundDuct, s.sourceFace, toExt, n);
        if (extracted.isEmpty()) {
            if (ductBlockPresentButBlockEntityPending(level, s.refundDuct)) {
                DuctIncomingIndex.register(level, s.destDuct, s.registeredIncoming.copy());
                return true;
            }
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        NodeMode dm2 = destBe.getFaceLanes(s.destFace).nodeMode;
        if ((dm2 == NodeMode.FILTERING_INSERTION || dm2 == NodeMode.EXTRACTION_FILTERING)
                && !destBe.passesItemFilters(s.destFace, extracted, level, DuctFaceNode.FilterBank.FILTER)) {
            DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, extracted, worldPosition);
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        int delivered = extracted.getCount();
        boolean partialRemainder = delivered < planned;
        if (!partialRemainder) {
            it.remove();
            s.stack = ItemStack.EMPTY;
        } else {
            s.stack.shrink(delivered);
            s.registeredIncoming = s.stack.copy();
            DuctIncomingIndex.register(level, s.destDuct, s.registeredIncoming.copy());
        }
        ItemStack remainder =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, extracted.copy())
                        : DuctCapHelper.insertIntoFace(level, s.destDuct, s.destFace, extracted.copy());
        if (!remainder.isEmpty()) {
            DuctOverflowRouting.absorbExtractionDestRemainder(level, s, destBe, remainder, worldPosition);
        }
        setChanged();
        return false;
    }

    /**
     * @return {@code true} if arrival handling was deferred (world/block entity not ready); {@code false} if resolved.
     */
    private boolean finishRetrieverArrival(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        if (!DuctRedstoneLogic.isFaceTransportActive(level, worldPosition, getFaceLanes(s.destFace).redstoneMode)) {
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity donorBe)) {
            if (ductBlockPresentButBlockEntityPending(level, s.refundDuct)) {
                return true;
            }
            cancelOutboundShipment(level, s, it);
            return false;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(donorBe.getFaceNode(s.sourceFace).channelLetter, s)
                || !DuctChannelPolicy.faceMatchesShipment(getFaceNode(s.destFace).channelLetter, s)) {
            cancelOutboundShipment(level, s, it);
            return false;
        }

        if (s.legacyPhysicalBuffer || s.sourceExtractCommitted) {
            DuctItemTransportSpec transportSpec = itemTransportSpec();
            int chunkCap = tubeOperationBatchSize(transportSpec, this, s.destFace);
            int planned = s.stack.getCount();
            int take = Math.min(planned, chunkCap);
            if (take <= 0) {
                it.remove();
                s.stack = ItemStack.EMPTY;
                setChanged();
                return false;
            }
            ItemStack chunk = s.stack.copy();
            chunk.setCount(take);
            if (!s.legacyOmniFaces) {
                List<ItemStack> priorR = DuctIncomingIndex.snapshot(level, worldPosition);
                int maxIns =
                        DuctCapHelper.maxInsertableAfterPendingOnFace(
                                level, worldPosition, s.destFace, chunk, chunk.getCount(), priorR);
                maxIns =
                        capInsertableForFilterAllowLimit(
                                level, worldPosition, s.destFace, this, chunk, maxIns, priorR);
                take = Math.min(take, maxIns);
                if (take <= 0) {
                    cancelOutboundShipment(level, s, it);
                    return false;
                }
                chunk.setCount(take);
            }
            DuctIncomingIndex.unregister(level, worldPosition, s.registeredIncoming.copy());
            NodeMode sm = getFaceLanes(s.destFace).nodeMode;
            if ((sm == NodeMode.FILTERING_INSERTION || sm == NodeMode.EXTRACTION_FILTERING)
                    && !passesItemFilters(s.destFace, chunk, level, DuctFaceNode.FilterBank.FILTER)) {
                cancelOutboundShipment(level, s, it);
                return false;
            }
            ItemStack remainder =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, chunk.copy())
                            : DuctCapHelper.insertIntoFace(level, worldPosition, s.destFace, chunk.copy());
            if (take < planned) {
                s.stack.shrink(take);
                s.registeredIncoming = s.stack.copy();
                DuctIncomingIndex.register(level, worldPosition, s.registeredIncoming.copy());
            } else {
                it.remove();
                s.stack = ItemStack.EMPTY;
            }
            if (!remainder.isEmpty()) {
                DuctOverflowRouting.absorbRetrieverDestRemainder(level, s, this, donorBe, remainder, worldPosition);
            }
            setChanged();
            return false;
        }

        DuctIncomingIndex.unregister(level, worldPosition, s.registeredIncoming.copy());
        DuctItemTransportSpec transportSpec = itemTransportSpec();
        int moduleCap = tubeOperationBatchSize(transportSpec, this, s.destFace);
        int planned = s.stack.getCount();
        int capExt =
                s.legacyOmniFaces
                        ? DuctCapHelper.countExtractableMatching(level, s.refundDuct, donorBe, s.stack, planned)
                        : DuctCapHelper.countExtractableMatchingOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, planned);
        int insLimit = Math.min(planned, Math.min(capExt, moduleCap));
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, worldPosition);
        int capIn =
                s.legacyOmniFaces
                        ? DuctCapHelper.maxInsertableAfterPending(
                                level, worldPosition, this, s.stack, insLimit, prior)
                        : DuctCapHelper.maxInsertableAfterPendingOnFace(
                                level, worldPosition, s.destFace, s.stack, insLimit, prior);
        if (!s.legacyOmniFaces) {
            capIn =
                    capInsertableForFilterAllowLimit(
                            level, worldPosition, s.destFace, this, s.stack, capIn, prior);
            capExt =
                    Math.min(
                            capExt,
                            capExtractableForAllowKeep(
                                    level,
                                    s.refundDuct,
                                    s.sourceFace,
                                    donorBe,
                                    DuctFaceNode.FilterBank.RETRIEVER,
                                    s.stack,
                                    capExt));
        }
        int n = Math.min(planned, Math.min(capExt, capIn));
        n = Math.min(n, moduleCap);
        if (n <= 0) {
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        ItemStack toExt = s.stack.copy();
        toExt.setCount(n);
        ItemStack extracted =
                s.legacyOmniFaces
                        ? DuctCapHelper.extractMatchingUpTo(level, s.refundDuct, donorBe, toExt, n)
                        : DuctCapHelper.extractMatchingUpToOnFace(level, s.refundDuct, s.sourceFace, toExt, n);
        if (extracted.isEmpty()) {
            if (ductBlockPresentButBlockEntityPending(level, s.refundDuct)) {
                DuctIncomingIndex.register(level, worldPosition, s.registeredIncoming.copy());
                return true;
            }
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        NodeMode sm2 = getFaceLanes(s.destFace).nodeMode;
        if ((sm2 == NodeMode.FILTERING_INSERTION || sm2 == NodeMode.EXTRACTION_FILTERING)
                && !passesItemFilters(s.destFace, extracted, level, DuctFaceNode.FilterBank.FILTER)) {
            DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, extracted, worldPosition);
            it.remove();
            s.stack = ItemStack.EMPTY;
            setChanged();
            return false;
        }
        int delivered = extracted.getCount();
        boolean partialRemainder = delivered < planned;
        if (!partialRemainder) {
            it.remove();
            s.stack = ItemStack.EMPTY;
        } else {
            s.stack.shrink(delivered);
            s.registeredIncoming = s.stack.copy();
            DuctIncomingIndex.register(level, worldPosition, s.registeredIncoming.copy());
        }
        ItemStack remainder =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, extracted.copy())
                        : DuctCapHelper.insertIntoFace(level, worldPosition, s.destFace, extracted.copy());
        if (!remainder.isEmpty()) {
            DuctOverflowRouting.absorbRetrieverDestRemainder(level, s, this, donorBe, remainder, worldPosition);
        }
        setChanged();
        return false;
    }

    private void tickOverflowBufferDrain(ServerLevel level) {
        if (level.isClientSide()) {
            return;
        }
        overflowBuffer.tickTryDrainOne(level, this);
    }

    /**
     * Like {@link DuctCapHelper#insertIntoStorageFaces} but skips network-inbound faces that are gated off by redstone.
     */
    public ItemStack tryInsertIntoAllStorageFacesRespectingRules(ServerLevel level, ItemStack stack) {
        return insertIntoStorageFacesRespectingInboundRedstone(level, stack);
    }

    private ItemStack insertIntoStorageFacesRespectingInboundRedstone(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        int mask = getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((mask & (1 << dir.ordinal())) == 0) {
                continue;
            }
            DuctFaceLanes lanes = getFaceLanes(dir);
            if (DuctTargetSelector.isNetworkInboundDeliveryMode(lanes.nodeMode)
                    && !isFaceTransportEnabled(dir)) {
                continue;
            }
            BlockPos adj = worldPosition.relative(dir);
            IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, adj, dir.getOpposite());
            if (h == null) {
                continue;
            }
            remaining = ItemHandlerHelper.insertItemStacked(h, remaining, false);
            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    private void tickMigratedBacklogFlush(ServerLevel level) {
        if (migratedStorageBacklog.isEmpty()) {
            return;
        }
        Iterator<ItemStack> it = migratedStorageBacklog.iterator();
        while (it.hasNext()) {
            ItemStack b = it.next();
            if (b.isEmpty()) {
                it.remove();
                setChanged();
                continue;
            }
            ItemStack left = insertIntoStorageFacesRespectingInboundRedstone(level, b.copy());
            if (left.isEmpty()) {
                it.remove();
                setChanged();
            } else if (left.getCount() != b.getCount()) {
                b.setCount(left.getCount());
                setChanged();
            }
        }
    }

    private void tickExtractionPullForFace(ServerLevel level, DuctItemTransportSpec spec, Direction face, DuctFaceNode node) {
        if (distinctPendingOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
            return;
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return;
        }
        boolean singleDuctNetwork = DuctPathfinder.connectedDucts(level, worldPosition, DuctNetworkType.ITEM).size() == 1;
        DuctFaceLanes faceLanes = getFaceLanes(face);
        IItemHandler sourceHandler = DuctCapHelper.getHandlerOnFace(level, worldPosition, face);
        if (sourceHandler == null) {
            return;
        }
        /*
         * Try each source slot so a deny/allow on the *destination* FILTERING_INSERTION face does not wedge the
         * extractor on the first stack (see finishExtractionDelivery: invalid items were extracted then refunded).
         * Routing RR state is only committed when we actually schedule a shipment.
         */
        for (int slot = 0; slot < sourceHandler.getSlots(); slot++) {
            final int rrFrozen = node.roundRobinCursor;
            boolean allowSelf = faceLanes.nodeMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
            boolean allowSelfOrSingle = allowSelf || singleDuctNetwork;
            RoutingMode rm =
                    faceLanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION
                            ? node.routingModeRetriever
                            : faceLanes.nodeMode.isHybrid()
                                    ? node.routingModeExtractor
                                    : node.routingMode;
            boolean roundRobinRouting = rm == RoutingMode.ROUND_ROBIN;
            boolean scheduledThisSlot = false;
            int lastSuccessfulCandIdx = 0;
            // One outbound task per rate tick for this face: count is min(batch, source, dest, filters, allow/keep).
            ItemStack probe = sourceHandler.extractItem(slot, 1, true);
            if (probe.isEmpty()) {
                continue;
            }
            if (!passesItemFilters(face, probe, level, DuctFaceNode.FilterBank.EXTRACTOR)) {
                continue;
            }
            int[] rrProbe = new int[] {rrFrozen};
            List<DuctTargetSelector.ExtractionCandidate> candidates =
                    DuctTargetSelector.listExtractionDeliveryCandidates(
                            level,
                            worldPosition,
                            probe,
                            rm,
                            rrProbe[0],
                            node.channelLetter,
                            allowSelfOrSingle,
                            allowSelf ? null : (allowSelfOrSingle ? face : null));
            if (candidates.isEmpty()) {
                continue;
            }
            for (int candIdx = 0; candIdx < candidates.size() && candIdx < EXTRACTION_ROUTE_RETRY_CAP; candIdx++) {
                DuctTargetSelector.ExtractionCandidate cand = candidates.get(candIdx);
                BlockPos dest = cand.ductPos();
                Direction destFace = cand.face();
                List<BlockPos> path;
                if (dest.equals(worldPosition)) {
                    path = List.of(worldPosition);
                } else {
                    Optional<List<BlockPos>> p =
                            DuctPathfinder.shortestPath(level, worldPosition, dest, spec, DuctNetworkType.ITEM);
                    if (p.isEmpty()) {
                        continue;
                    }
                    path = p.get();
                }
                if (!(level.getBlockEntity(dest) instanceof DuctBlockEntity destBe)) {
                    continue;
                }
                NodeMode destMode = destBe.getFaceLanes(destFace).nodeMode;
                if (destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING) {
                    if (!destBe.passesItemFilters(destFace, probe, level, DuctFaceNode.FilterBank.FILTER)) {
                        continue;
                    }
                }
                int tubeBatch = tubeOperationBatchSize(spec, this, face);
                int availTotal =
                        DuctCapHelper.countExtractableMatchingOnFace(
                                level, worldPosition, face, probe, Integer.MAX_VALUE);
                if (availTotal <= 0) {
                    break;
                }
                int pendingSum = pendingPlannedExtractCountOnSource(worldPosition, face, probe);
                int remainingInStorage = availTotal - pendingSum;
                if (remainingInStorage <= 0) {
                    break;
                }
                int plannedCount = Math.min(tubeBatch, remainingInStorage);
                plannedCount =
                        Math.min(
                                plannedCount,
                                capExtractableForAllowKeep(
                                        level,
                                        worldPosition,
                                        face,
                                        this,
                                        DuctFaceNode.FilterBank.EXTRACTOR,
                                        probe,
                                        plannedCount));
                if (plannedCount <= 0) {
                    continue;
                }
                ItemStack planned = probe.copy();
                planned.setCount(plannedCount);
                int destCap = maxSchedulableTowardFace(level, dest, destBe, destFace, planned, plannedCount);
                if (destCap <= 0) {
                    continue;
                }
                if (destCap < plannedCount) {
                    plannedCount = destCap;
                    planned.setCount(plannedCount);
                }
                lastSuccessfulCandIdx = candIdx;
                long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(this, face, spec);
                long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
                int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
                OutboundShipment sh =
                        new OutboundShipment(planned, dest, destFace, travelTicks, worldPosition, face, node.channelLetter);
                sh.ductPath = OutboundShipment.copyPath(path);
                sh.totalTravelTicks = travelTicks;
                sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, edgeTicks);
                sh.journeyStartGameTime = level.getGameTime();
                sh.transitPhase = TransitPhase.FORWARD;
                outboundShipments.add(sh);
                DuctIncomingIndex.register(level, dest, sh.registeredIncoming.copy());
                setChanged();
                pushTransitSnapshotToClients(level);
                scheduledThisSlot = true;
                break;
            }
            if (scheduledThisSlot && roundRobinRouting) {
                node.roundRobinCursor = rrFrozen + lastSuccessfulCandIdx + 1;
            }
            if (scheduledThisSlot) {
                return;
            }
        }
    }

    private void tickRetrieverPullForFace(ServerLevel level, DuctItemTransportSpec spec, Direction retrieverFace, DuctFaceNode node) {
        if (distinctPendingOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
            return;
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return;
        }
        DuctFaceLanes retrieverLanes = getFaceLanes(retrieverFace);
        final int rrFrozen = node.roundRobinCursor;
        RoutingMode rm =
                retrieverLanes.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        boolean singleDuctNetwork = DuctPathfinder.connectedDucts(level, worldPosition, DuctNetworkType.ITEM).size() == 1;
        List<DuctTargetSelector.DonorCandidate> donors =
                DuctTargetSelector.listRetrievingDonorCandidates(
                        level,
                        worldPosition,
                        retrieverFace,
                        rm,
                        rrFrozen,
                        node.channelLetter,
                        singleDuctNetwork,
                        singleDuctNetwork ? retrieverFace : null);
        if (donors.isEmpty()) {
            return;
        }
        for (int donorIdx = 0; donorIdx < donors.size() && donorIdx < EXTRACTION_ROUTE_RETRY_CAP; donorIdx++) {
            DuctTargetSelector.DonorCandidate donorCand = donors.get(donorIdx);
            BlockPos donor = donorCand.ductPos();
            Direction donorFace = donorCand.face();
            List<BlockPos> path;
            if (donor.equals(worldPosition)) {
                path = List.of(worldPosition);
            } else {
                Optional<List<BlockPos>> p =
                        DuctPathfinder.shortestPath(level, donor, worldPosition, spec, DuctNetworkType.ITEM);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }
            if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            IItemHandler donorHandler = DuctCapHelper.getHandlerOnFace(level, donor, donorFace);
            if (donorHandler == null) {
                continue;
            }
            for (int slot = 0; slot < donorHandler.getSlots(); slot++) {
                // One outbound task per rate tick on this retriever face: count is min(batch, donor, retriever caps).
                ItemStack probe = donorHandler.extractItem(slot, 1, true);
                if (probe.isEmpty()) {
                    continue;
                }
                if (!passesItemFilters(retrieverFace, probe, level, DuctFaceNode.FilterBank.RETRIEVER)) {
                    continue;
                }
                // In RETRIEVING, the destination is governed by RETRIEVER filters, while the donor is governed by FILTER caps/filters.
                if (!donorBe.passesItemFilters(donorFace, probe, level, DuctFaceNode.FilterBank.FILTER)) {
                    continue;
                }
                int tubeBatch = tubeOperationBatchSize(spec, this, retrieverFace);
                int availTotal =
                        DuctCapHelper.countExtractableMatchingOnFace(
                                level, donor, donorFace, probe, Integer.MAX_VALUE);
                if (availTotal <= 0) {
                    continue;
                }
                int pendingSum = pendingPlannedExtractCountOnSource(donor, donorFace, probe);
                int remainingInStorage = availTotal - pendingSum;
                if (remainingInStorage <= 0) {
                    continue;
                }
                int plannedCount = Math.min(tubeBatch, remainingInStorage);
                plannedCount =
                        Math.min(
                                plannedCount,
                                capExtractableForFilterKeep(
                                        level, donor, donorFace, donorBe, probe, plannedCount));
                if (plannedCount > 0) {
                    ItemStack template = probe.copy();
                    template.setCount(plannedCount);
                    List<ItemStack> prior = DuctIncomingIndex.snapshot(level, worldPosition);
                    int capIn =
                            DuctCapHelper.maxInsertableAfterPendingOnFace(
                                    level, worldPosition, retrieverFace, template, plannedCount, prior);
                    capIn =
                            capInsertableForRetrieverAllowLimit(
                                    level, worldPosition, retrieverFace, this, template, capIn, prior);
                    plannedCount = Math.min(plannedCount, capIn);
                }
                if (plannedCount <= 0) {
                    continue;
                }
                ItemStack planned = probe.copy();
                planned.setCount(plannedCount);
                if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
                    continue;
                }
                if (getOverflowBuffer().isSchedulingUnavailableForNewPulls()) {
                    continue;
                }
                if (!DuctRedstoneLogic.isFaceTransportActive(level, worldPosition, getFaceLanes(retrieverFace).redstoneMode)) {
                    continue;
                }
                long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(this, retrieverFace, spec);
                long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
                int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
                OutboundShipment sh =
                        new OutboundShipment(
                                planned, worldPosition, retrieverFace, travelTicks, donor, donorFace, node.channelLetter);
                sh.ductPath = OutboundShipment.copyPath(path);
                sh.totalTravelTicks = travelTicks;
                sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, edgeTicks);
                sh.journeyStartGameTime = level.getGameTime();
                sh.transitPhase = TransitPhase.FORWARD;
                outboundShipments.add(sh);
                DuctIncomingIndex.register(level, worldPosition, sh.registeredIncoming.copy());
                setChanged();
                pushTransitSnapshotToClients(level);
                if (roundRobinRetriever) {
                    node.roundRobinCursor = rrFrozen + donorIdx + 1;
                }
                return;
            }
        }
    }

    /**
     * Returns how many items can be scheduled toward a destination face right now, respecting destination capacity,
     * inbound redstone, overflow buffers, and FILTER-bank allow-line limits.
     */
    private int maxSchedulableTowardFace(
            ServerLevel level,
            BlockPos destDuct,
            DuctBlockEntity destBe,
            Direction destFace,
            ItemStack template,
            int want) {
        if (want <= 0 || template.isEmpty()) {
            return 0;
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return 0;
        }
        if (destBe.getOverflowBuffer().isSchedulingUnavailableForNewPulls()) {
            return 0;
        }
        if (!DuctRedstoneLogic.isFaceTransportActive(level, destDuct, destBe.getFaceLanes(destFace).redstoneMode)) {
            return 0;
        }
        ItemStack t = template.copy();
        t.setCount(want);
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, destDuct);
        int capInsert =
                DuctCapHelper.maxInsertableAfterPendingOnFace(
                        level, destDuct, destFace, t, want, prior);
        int afterAllow = capInsertableForFilterAllowLimit(level, destDuct, destFace, destBe, t, capInsert, prior);
        return Math.max(0, Math.min(want, afterAllow));
    }

    private int distinctPendingOutboundKinds() {
        List<ItemStack> kinds = new ArrayList<>();
        for (OutboundShipment s : outboundShipments) {
            if (s.stack.isEmpty()) {
                continue;
            }
            if (!containsItemKind(kinds, s.stack)) {
                kinds.add(s.stack);
            }
        }
        return kinds.size();
    }

    private static boolean containsItemKind(List<ItemStack> kinds, ItemStack probe) {
        for (ItemStack k : kinds) {
            if (ItemStack.isSameItemSameComponents(k, probe)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Items per extract/delivery step for {@code ownerDuct}'s {@code face}. Uses {@link DuctFaceNode#extractBatch}
     * when {@code > 0} (value set on the tube in the GUI / NBT), otherwise datapack {@code batch.default}; then
     * clamped by {@link #computeExtractBatchSettingCap} (datapack max / default rules). Does not use chest stack
     * size, total items in storage, or item max stack size.
     */
    private int tubeOperationBatchSize(DuctItemTransportSpec spec, DuctBlockEntity ownerDuct, Direction face) {
        if (ownerDuct == null) {
            return datapackBatchDefaultOrFallback(spec);
        }
        DuctFaceNode node = ownerDuct.getFaceNode(face);
        int req = node.extractBatch <= 0 ? spec.batchDefault() : node.extractBatch;
        int cap = ownerDuct.computeExtractBatchSettingCap(face);
        return Math.max(1, Math.min(Math.max(0, req), cap));
    }

    private static int datapackBatchDefaultOrFallback(DuctItemTransportSpec spec) {
        int d = spec.batchDefault();
        if (d <= 0) {
            d = DuctItemTransportSpec.fallback().batchDefault();
        }
        return Math.max(1, d);
    }

    /**
     * Maximum extract/retrieve batch the player may store on this face: {@code batch.default} plus module bonuses,
     * then limited by datapack {@code batch.max} when that value is {@code >= 0}. When {@code max} is negative,
     * only default + modules applies (not unlimited).
     */
    public int computeExtractBatchSettingCap(Direction face) {
        return itemTransportSpec().extractBatchSettingCap(getExtractBatchModuleBonus(face));
    }

    /** Per-face module column contribution to extract batch (item lane). */
    private int getExtractBatchModuleBonus(Direction face) {
        if (!DuctFeaturePolicy.isUsable(
                ductDefinition().orElse(null),
                DuctFeatureKeys.SPECIAL_MODULES,
                faceHasAnyModule(face))) {
            return 0;
        }
        return net.unfamily.another_dynamics.duct.module.DuctModuleEffects.itemExtractBatchBonus(this, face, itemTransportSpec());
    }

    private boolean faceHasAnyModule(Direction face) {
        ItemStackHandler upg = getFaceLanes(face).moduleSlots;
        for (int i = 0; i < upg.getSlots(); i++) {
            if (!upg.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private long getGasExtractBatchModuleBonus(Direction face) {
        return DuctModuleEffects.gasExtractBatchBonus(this, face, gasTransportSpec());
    }

    public int computeGasExtractBatchSettingCap(Direction face) {
        long cap = gasTransportSpec().extractBatchSettingCap(getGasExtractBatchModuleBonus(face));
        return (int) Math.min(cap, Integer.MAX_VALUE);
    }

    private static String clampFilterLine(Optional<DuctDefinition> def, boolean hasModule, String line) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        String fk = DuctFeatureKeys.filterSyntaxKey(line);
        if (fk != null && !DuctFeaturePolicy.isUsable(def.orElse(null), fk, hasModule)) {
            return "";
        }
        return line;
    }

    private void scrubFilterListsForPolicy(Direction face, Optional<DuctDefinition> def, boolean hasModule) {
        DuctFaceNode n = getFaceNode(face);
        scrubFilterListsForNode(def, hasModule, n);
    }

    private void scrubFilterListsForPolicyFluid(Direction face, Optional<DuctDefinition> def, boolean hasModule) {
        scrubFilterListsForNode(def, hasModule, getFluidFaceNode(face));
    }

    private void scrubFilterListsForPolicyGas(Direction face, Optional<DuctDefinition> def, boolean hasModule) {
        scrubFilterListsForNode(def, hasModule, getGasFaceNode(face));
    }

    private static void scrubFilterListsForNode(Optional<DuctDefinition> def, boolean hasModule, DuctFaceNode n) {
        scrubList(def, hasModule, n.allowFilters);
        scrubList(def, hasModule, n.denyFilters);
        scrubList(def, hasModule, n.allowFiltersExtractor);
        scrubList(def, hasModule, n.denyFiltersExtractor);
        scrubList(def, hasModule, n.allowFiltersRetriever);
        scrubList(def, hasModule, n.denyFiltersRetriever);
        scrubList(def, hasModule, n.allowFiltersFilter);
        scrubList(def, hasModule, n.denyFiltersFilter);
    }

    private static void scrubList(Optional<DuctDefinition> def, boolean hasModule, List<String> list) {
        for (int i = 0; i < list.size(); i++) {
            list.set(i, clampFilterLine(def, hasModule, list.get(i)));
        }
    }

    /**
     * Clamps modes, routing, channel, and filter lines to {@link DuctDefinition} restrictions. Returns true if any
     * face data changed.
     */
    private boolean clampAllFacesToDatapackRestrictions() {
        Optional<DuctDefinition> def = ductDefinition();
        boolean any = false;
        for (Direction d : Direction.values()) {
            DuctFaceLanes L = getFaceLanes(d);
            boolean hasItemUp = faceHasAnyModule(d);
            boolean hasFluidUp = faceHasAnyModule(d);
            boolean hasGasUp = faceHasAnyModule(d);
            boolean modeUnlock = hasItemUp || hasFluidUp || hasGasUp;
            if (!DuctFeaturePolicy.isModeUsable(def.orElse(null), L.nodeMode, modeUnlock)) {
                L.nodeMode = NodeMode.NONE;
                any = true;
            }
            DuctFaceNode n = L.item;
            if (L.nodeMode.usesRouting()) {
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), n.routingMode, hasItemUp)) {
                    n.routingMode = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), n.routingModeExtractor, hasItemUp)) {
                    n.routingModeExtractor = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), n.routingModeRetriever, hasItemUp)) {
                    n.routingModeRetriever = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (L.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                    n.routingMode = n.routingModeExtractor;
                }
            }
            if (!DuctFeaturePolicy.isUsable(def.orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, hasItemUp)) {
                if (n.channelLetter != 1) {
                    n.channelLetter = 1;
                    any = true;
                }
            }
            int nonEmptyBefore = countNonEmptyLines(n);
            scrubFilterListsForPolicy(d, def, hasItemUp);
            if (countNonEmptyLines(n) != nonEmptyBefore) {
                any = true;
            }

            DuctFaceNode fn = L.fluid;
            if (L.nodeMode.usesRouting()) {
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), fn.routingMode, hasFluidUp)) {
                    fn.routingMode = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), fn.routingModeExtractor, hasFluidUp)) {
                    fn.routingModeExtractor = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), fn.routingModeRetriever, hasFluidUp)) {
                    fn.routingModeRetriever = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (L.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                    fn.routingMode = fn.routingModeExtractor;
                }
            }
            if (!DuctFeaturePolicy.isUsable(def.orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, hasFluidUp)) {
                if (fn.channelLetter != 1) {
                    fn.channelLetter = 1;
                    any = true;
                }
            }
            int nonEmptyFluid = countNonEmptyLines(fn);
            scrubFilterListsForPolicyFluid(d, def, hasFluidUp);
            if (countNonEmptyLines(fn) != nonEmptyFluid) {
                any = true;
            }

            DuctFaceNode gn = L.gas;
            if (L.nodeMode.usesRouting()) {
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), gn.routingMode, hasGasUp)) {
                    gn.routingMode = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), gn.routingModeExtractor, hasGasUp)) {
                    gn.routingModeExtractor = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), gn.routingModeRetriever, hasGasUp)) {
                    gn.routingModeRetriever = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (L.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                    gn.routingMode = gn.routingModeExtractor;
                }
            }
            if (!DuctFeaturePolicy.isUsable(def.orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, hasGasUp)) {
                if (gn.channelLetter != 1) {
                    gn.channelLetter = 1;
                    any = true;
                }
            }
            int nonEmptyGas = countNonEmptyLines(gn);
            scrubFilterListsForPolicyGas(d, def, hasGasUp);
            if (countNonEmptyLines(gn) != nonEmptyGas) {
                any = true;
            }
        }
        if (any) {
            clampFaceFiltersToSpec();
            for (Direction d : Direction.values()) {
                if (getFaceLanes(d).nodeMode.usesExtractBatchField()) {
                    clampExtractAmount(getFaceNode(d), d);
                    clampFluidExtractAmount(getFluidFaceNode(d), d);
                    clampGasExtractAmount(getGasFaceNode(d), d);
                }
            }
            setChanged();
        }
        return any;
    }

    private static int countNonEmptyLines(DuctFaceNode n) {
        int c = 0;
        c += countNonEmptyInList(n.allowFilters);
        c += countNonEmptyInList(n.denyFilters);
        c += countNonEmptyInList(n.allowFiltersExtractor);
        c += countNonEmptyInList(n.denyFiltersExtractor);
        c += countNonEmptyInList(n.allowFiltersRetriever);
        c += countNonEmptyInList(n.denyFiltersRetriever);
        c += countNonEmptyInList(n.allowFiltersFilter);
        c += countNonEmptyInList(n.denyFiltersFilter);
        return c;
    }

    private static int countNonEmptyInList(List<String> list) {
        int n = 0;
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Shared module column on {@code face} changed: clamp item/fluid/gas stored batch amounts to current module caps.
     */
    public void clampAllTransportExtractBatchesForFace(Direction face) {
        if (level == null || level.isClientSide()) {
            return;
        }
        Optional<DuctDefinition> def = ductDefinition();
        if (def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ITEM)).orElse(true)) {
            clampExtractAmount(getFaceNode(face), face);
        }
        if (def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.FLUID)).orElse(false)) {
            clampFluidExtractAmount(getFluidFaceNode(face), face);
        }
        if (def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.GAS)).orElse(false)) {
            clampGasExtractAmount(getGasFaceNode(face), face);
        }
    }

    public void refreshMenuData(Direction accessFace) {
        DuctFaceLanes faceLanes = getFaceLanes(accessFace);
        DuctFaceNode n = activeMenuFaceNode(accessFace);
        menuData.set(DuctMenuSync.NODE_MODE, faceLanes.nodeMode.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE, n.routingMode.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, n.routingModeExtractor.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, n.routingModeRetriever.ordinal());
        menuData.set(DuctMenuSync.PRIORITY, n.insertionPriority);
        menuData.set(DuctMenuSync.AMOUNT_FIELD, n.extractBatch);
        menuData.set(
                DuctMenuSync.EXTRACT_BATCH_CAP,
                switch (menuActiveTransportKind()) {
                    case FLUID -> computeFluidExtractBatchSettingCap(accessFace);
                    case GAS -> computeGasExtractBatchSettingCap(accessFace);
                    case ENERGY, HEAT -> 0;
                    case ITEM -> computeExtractBatchSettingCap(accessFace);
                });
        menuData.set(DuctMenuSync.CHANNEL, n.channelLetter);
        menuData.set(DuctMenuSync.REDSTONE_MODE, faceLanes.redstoneMode);
        menuData.set(DuctMenuSync.ELIGIBILITY_MODE, n.eligibilityMode.ordinal());
        int denyOverSync =
                switch (faceLanes.nodeMode) {
                    case FILTERING_INSERTION -> n.denyOverridesAllowFilter ? 1 : 0;
                    case EXTRACTION -> n.denyOverridesAllowExtractor ? 1 : 0;
                    case RETRIEVING -> n.denyOverridesAllowRetriever ? 1 : 0;
                    default -> n.denyOverridesAllow ? 1 : 0;
                };
        menuData.set(DuctMenuSync.DENY_OVERRIDES_ALLOW, denyOverSync);
        int flags = 0;
        if (faceLanes.nodeMode.usesRouting()) {
            flags |= DuctMenuSync.FLAG_ROUTING_ACTIVE;
        }
        if (menuActiveTransportKind() != DuctTransportKind.ENERGY
                && menuActiveTransportKind() != DuctTransportKind.HEAT
                && faceLanes.nodeMode.usesItemFilterConfig()) {
            flags |= DuctMenuSync.FLAG_FILTERS_ACTIVE;
        }
        menuData.set(DuctMenuSync.FLAGS, flags);
        menuData.set(DuctMenuSync.POS_X, worldPosition.getX());
        menuData.set(DuctMenuSync.POS_Y, worldPosition.getY());
        menuData.set(DuctMenuSync.POS_Z, worldPosition.getZ());
        menuData.set(DuctMenuSync.ACCESS_FACE, accessFace.ordinal());
        // Legacy hashes (kept for existing UI bits); hybrid GUI uses explicit filter sync payloads per bank.
        menuData.set(DuctMenuSync.FILTER_HASH_ALLOW, DuctFilterLogic.listHash(n.allowFilters));
        menuData.set(DuctMenuSync.FILTER_HASH_DENY, DuctFilterLogic.listHash(n.denyFilters));
        menuData.set(DuctMenuSync.SELF_FEED, n.selfFeed ? 1 : 0);
        menuData.set(DuctMenuSync.ACTIVE_TRANSPORT_KIND, menuActiveTransportKind().ordinal());
        menuData.set(DuctMenuSync.TRANSPORT_KIND_COUNT, orderedMenuTransportKinds().size());
        menuData.set(DuctMenuSync.MENU_VIEW_LAYER, menuUiLayer);
    }

    public boolean passesItemFilters(Direction face, ItemStack stack, Level level) {
        return passesItemFilters(face, stack, level, DuctFaceNode.FilterBank.FILTER);
    }

    private static boolean destFaceUsesFilterAllowLimit(NodeMode destMode) {
        return destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING;
    }

    /**
     * Further caps insert count for FILTER-bank allow-line limits (0 = unlimited) on filtering insertion faces.
     */
    private int capInsertableForFilterAllowLimit(
            Level level,
            BlockPos destDuctPos,
            Direction destFace,
            DuctBlockEntity destBe,
            ItemStack template,
            int maxFromCapacity,
            List<ItemStack> priorIncoming) {
        if (maxFromCapacity <= 0 || template.isEmpty()) {
            return 0;
        }
        NodeMode dm = destBe.getFaceLanes(destFace).nodeMode;
        if (!destFaceUsesFilterAllowLimit(dm)) {
            return maxFromCapacity;
        }
        IItemHandler raw = DuctCapHelper.getHandlerOnFace(level, destDuctPos, destFace);
        if (raw == null) {
            return maxFromCapacity;
        }
        DuctFaceNode destNode = destBe.getFaceNode(destFace);
        List<String> filterAllows = destNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
        List<Integer> filterCaps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.FILTER);
        if (!DuctAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(filterAllows, filterCaps)) {
            return maxFromCapacity;
        }
        int maxAdd =
                DuctAllowLimitLogic.maxAdditionalInsertForAllowLine(
                        raw,
                        filterAllows,
                        filterCaps,
                        template,
                        priorIncoming,
                        level.registryAccess());
        if (maxAdd == Integer.MAX_VALUE) {
            return maxFromCapacity;
        }
        return Math.min(maxFromCapacity, maxAdd);
    }

    /**
     * Further caps insert count for RETRIEVER-bank allow-line limits (0 = unlimited) on retrieving faces.
     * Uses inventory state after simulating in-flight inbound items.
     */
    private int capInsertableForRetrieverAllowLimit(
            Level level,
            BlockPos destDuctPos,
            Direction destFace,
            DuctBlockEntity destBe,
            ItemStack template,
            int maxFromCapacity,
            List<ItemStack> priorIncoming) {
        if (maxFromCapacity <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler raw = DuctCapHelper.getHandlerOnFace(level, destDuctPos, destFace);
        if (raw == null) {
            return maxFromCapacity;
        }
        DuctFaceNode destNode = destBe.getFaceNode(destFace);
        List<String> retrAllows = destNode.bankAllowFilters(DuctFaceNode.FilterBank.RETRIEVER);
        List<Integer> retrCaps = destNode.bankAllowCaps(DuctFaceNode.FilterBank.RETRIEVER);
        if (!DuctAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(retrAllows, retrCaps)) {
            return maxFromCapacity;
        }
        int maxAdd =
                DuctAllowLimitLogic.maxAdditionalInsertForAllowLine(
                        raw,
                        retrAllows,
                        retrCaps,
                        template,
                        priorIncoming,
                        level.registryAccess());
        if (maxAdd == Integer.MAX_VALUE) {
            return maxFromCapacity;
        }
        return Math.min(maxFromCapacity, maxAdd);
    }

    /** Caps how many items may be extracted while respecting per-allow-line Keep on the source face. */
    private int capExtractableForAllowKeep(
            Level level,
            BlockPos sourceDuctPos,
            Direction sourceFace,
            DuctBlockEntity sourceBe,
            DuctFaceNode.FilterBank allowBank,
            ItemStack template,
            int maxWant) {
        if (maxWant <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler h = DuctCapHelper.getHandlerOnFace(level, sourceDuctPos, sourceFace);
        if (h == null) {
            return maxWant;
        }
        DuctFaceNode srcNode = sourceBe.getFaceNode(sourceFace);
        int cap =
                DuctAllowLimitLogic.maxExtractRespectingKeep(
                        h,
                        srcNode.bankAllowFilters(allowBank),
                        srcNode.bankAllowCaps(allowBank),
                        template,
                        level.registryAccess());
        if (cap == Integer.MAX_VALUE) {
            return maxWant;
        }
        return Math.min(maxWant, cap);
    }

    /** Same as {@link #capExtractableForAllowKeep} but uses FILTER.keep caps on the source node. */
    private int capExtractableForFilterKeep(
            Level level,
            BlockPos sourceDuctPos,
            Direction sourceFace,
            DuctBlockEntity sourceBe,
            ItemStack template,
            int maxWant) {
        if (maxWant <= 0 || template.isEmpty()) {
            return 0;
        }
        IItemHandler h = DuctCapHelper.getHandlerOnFace(level, sourceDuctPos, sourceFace);
        if (h == null) {
            return maxWant;
        }
        DuctFaceNode srcNode = sourceBe.getFaceNode(sourceFace);
        int cap =
                DuctAllowLimitLogic.maxExtractRespectingKeep(
                        h,
                        srcNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER),
                        srcNode.filterBankKeepCaps(),
                        template,
                        level.registryAccess());
        if (cap == Integer.MAX_VALUE) {
            return maxWant;
        }
        return Math.min(maxWant, cap);
    }

    public boolean passesItemFilters(Direction face, ItemStack stack, Level level, DuctFaceNode.FilterBank bank) {
        if (stack.isEmpty()) {
            return false;
        }
        if (getFaceLanes(face).nodeMode == NodeMode.NONE) {
            return true;
        }
        DuctFaceNode node = getFaceNode(face);
        // Build a lightweight view using the selected bank, so existing matching logic stays unchanged.
        DuctFaceNode view = new DuctFaceNode(() -> {});
        view.denyOverridesAllow = node.bankDenyOverridesAllow(bank);
        view.allowFilters.clear();
        view.allowFilters.addAll(node.bankAllowFilters(bank));
        view.denyFilters.clear();
        view.denyFilters.addAll(node.bankDenyFilters(bank));
        return DuctFilterLogic.passesItemFilters(view, stack, level);
    }

    public void clampFaceFiltersToSpec() {
        DuctItemTransportSpec itemSpec = itemTransportSpec();
        DuctFluidTransportSpec fluidSpec = fluidTransportSpec();
        DuctGasTransportSpec gasSpec = gasTransportSpec();
        for (Direction d : Direction.values()) {
            getFaceLanes(d).clampFilterSizes(itemSpec, fluidSpec, gasSpec);
        }
    }

    public void applyServerFilterConfig(
            ServerPlayer player,
            Direction face,
            DuctTransportKind laneKind,
            DuctFaceNode.FilterBank bank,
            List<String> allowIn,
            List<String> denyIn,
            List<Integer> allowCapsIn,
            List<Integer> allowCaps2In,
            boolean denyOverridesAllow) {
        if (level == null || level.isClientSide) {
            return;
        }
        DuctItemTransportSpec itemSpec = itemTransportSpec();
        DuctFluidTransportSpec fluidSpec = fluidTransportSpec();
        DuctGasTransportSpec gasSpec = gasTransportSpec();
        DuctFaceLanes faceLanes = getFaceLanes(face);
        DuctFaceNode node = faceNodeForTransportKind(face, laneKind);
        if (!faceLanes.nodeMode.usesItemFilterConfig()) {
            return;
        }
        NodeMode sharedMode = faceLanes.nodeMode;
        DuctModuleEffects.FilterSlotBonuses filterBonuses = DuctModuleEffects.filterSlotBonuses(this, face);
        if (allowCapsIn == null) {
            allowCapsIn = List.of();
        }
        if (allowCaps2In == null) {
            allowCaps2In = List.of();
        }
        List<String> a = node.bankAllowFilters(bank);
        List<String> d = node.bankDenyFilters(bank);
        List<Integer> caps = node.bankAllowCaps(bank);
        List<Integer> caps2 = bank == DuctFaceNode.FilterBank.FILTER ? node.filterBankKeepCaps() : null;
        a.clear();
        d.clear();
        caps.clear();
        if (caps2 != null) {
            caps2.clear();
        }
        int maxA;
        int maxD;
        if (laneKind == DuctTransportKind.FLUID) {
            maxA = DuctModuleEffects.effectiveFluidAllowBank(fluidSpec, sharedMode, filterBonuses);
            maxD = DuctModuleEffects.effectiveFluidDenyBank(fluidSpec, sharedMode, filterBonuses);
        } else if (laneKind == DuctTransportKind.GAS) {
            maxA = DuctModuleEffects.effectiveGasAllowBank(gasSpec, sharedMode, filterBonuses);
            maxD = DuctModuleEffects.effectiveGasDenyBank(gasSpec, sharedMode, filterBonuses);
        } else {
            maxA = DuctModuleEffects.effectiveItemAllowBank(itemSpec, sharedMode, filterBonuses);
            maxD = DuctModuleEffects.effectiveItemDenyBank(itemSpec, sharedMode, filterBonuses);
        }
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasModule =
                switch (laneKind) {
                    case FLUID -> faceHasAnyModule(face);
                    case GAS -> faceHasAnyModule(face);
                    case ENERGY, HEAT -> false;
                    case ITEM -> faceHasAnyModule(face);
                };
        for (int i = 0; i < maxA; i++) {
            String s = i < allowIn.size() ? allowIn.get(i) : "";
            s = s != null ? s : "";
            a.add(clampFilterLine(def, hasModule, s));
            Integer capObj = i < allowCapsIn.size() ? allowCapsIn.get(i) : null;
            int cap = capObj != null ? capObj : 0;
            caps.add(Math.max(0, cap));
            if (caps2 != null) {
                Integer capObj2 = i < allowCaps2In.size() ? allowCaps2In.get(i) : null;
                int cap2 = capObj2 != null ? capObj2 : 0;
                caps2.add(Math.max(0, cap2));
            }
        }
        for (int i = 0; i < maxD; i++) {
            String s = i < denyIn.size() ? denyIn.get(i) : "";
            s = s != null ? s : "";
            d.add(clampFilterLine(def, hasModule, s));
        }
        if (DuctFeaturePolicy.isUsable(def.orElse(null), DuctFeatureKeys.listPrecedenceKey(sharedMode, bank), hasModule)) {
            node.setBankDenyOverridesAllow(bank, denyOverridesAllow);
        }
        if (laneKind == DuctTransportKind.FLUID) {
            node.clampFilterSizes(fluidSpec, sharedMode, filterBonuses);
        } else if (laneKind == DuctTransportKind.GAS) {
            node.clampFilterSizes(gasSpec, sharedMode, filterBonuses);
        } else if (laneKind == DuctTransportKind.ENERGY || laneKind == DuctTransportKind.HEAT) {
            node.clampFilterSizes(itemSpec, sharedMode, filterBonuses);
        } else {
            node.clampFilterSizes(itemSpec, sharedMode, filterBonuses);
        }
        setChanged();
        refreshMenuData(face);
        ModNetwork.sendFilterSyncToPlayer(player, this, face);
        syncVisualGeometryToClients();
    }

    public void toggleListLogicFromClient(
            ServerPlayer player, Direction face, DuctTransportKind laneKind, DuctFaceNode.FilterBank bank) {
        if (level == null || level.isClientSide) {
            return;
        }
        DuctFaceLanes faceLanes = getFaceLanes(face);
        DuctFaceNode node = faceNodeForTransportKind(face, laneKind);
        if (!faceLanes.nodeMode.usesItemFilterConfig()) {
            return;
        }
        boolean hasModule =
                switch (laneKind) {
                    case FLUID -> faceHasAnyModule(face);
                    case GAS -> faceHasAnyModule(face);
                    case ENERGY, HEAT -> false;
                    case ITEM -> faceHasAnyModule(face);
                };
        if (!DuctFeaturePolicy.isUsable(
                ductDefinition().orElse(null),
                DuctFeatureKeys.listPrecedenceKey(faceLanes.nodeMode, bank),
                hasModule)) {
            return;
        }
        node.setBankDenyOverridesAllow(bank, !node.bankDenyOverridesAllow(bank));
        setChanged();
        refreshMenuData(face);
        ModNetwork.sendFilterSyncToPlayer(player, this, face);
    }

    public void setSelfFeedFromClient(ServerPlayer player, Direction face, boolean enabled) {
        if (level == null || level.isClientSide) {
            return;
        }
        NodeMode shared = getFaceLanes(face).nodeMode;
        if (shared != NodeMode.EXTRACTION_FILTERING && shared != NodeMode.RETRIEVING_EXTRACTION) {
            return;
        }
        activeMenuFaceNode(face).selfFeed = enabled;
        setChanged();
        refreshMenuData(face);
    }

    public static final int MENU_BUTTON_TRANSPORT_KIND_BASE = 40;
    public static final int MENU_BUTTON_BACK_TO_HUB = 49;

    public boolean handleMenuButtonClick(Player player, int buttonId, Direction accessFace) {
        if (level == null || level.isClientSide) {
            return false;
        }
        if (buttonId >= MENU_BUTTON_TRANSPORT_KIND_BASE
                && buttonId < MENU_BUTTON_TRANSPORT_KIND_BASE + DuctTransportKind.values().length) {
            if (player instanceof ServerPlayer sp) {
                DuctTransportKind k = DuctTransportKind.values()[buttonId - MENU_BUTTON_TRANSPORT_KIND_BASE];
                return setMenuTransportKindFromPicker(sp, accessFace, k);
            }
            return false;
        }
        if (buttonId == MENU_BUTTON_BACK_TO_HUB) {
            return player instanceof ServerPlayer sp && returnMenuToHub(sp, accessFace);
        }
        DuctFaceNode node = activeMenuFaceNode(accessFace);
        DuctFaceLanes menuFaceLanes = getFaceLanes(accessFace);
        boolean changed =
                switch (buttonId) {
                    case 0 -> cycleNodeMode(menuFaceLanes, accessFace);
                    case 10 -> cycleNodeModeBackward(menuFaceLanes, accessFace);
                    case 1 -> {
                        if (!menuFaceLanes.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield cycleRoutingMode(node, accessFace);
                    }
                    case 2 -> {
                        menuFaceLanes.redstoneMode = (menuFaceLanes.redstoneMode + 1) % 4;
                        yield true;
                    }
                    case 11 -> {
                        if (!menuFaceLanes.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield cycleRoutingModeBackward(node, accessFace);
                    }
                    case 12 -> {
                        menuFaceLanes.redstoneMode = Math.floorMod(menuFaceLanes.redstoneMode - 1, 4);
                        yield true;
                    }
                    case 4 -> {
                        boolean up =
                                switch (menuActiveTransportKind()) {
                                    case FLUID -> faceHasAnyModule(accessFace);
                                    case GAS -> faceHasAnyModule(accessFace);
                                    case ENERGY, HEAT -> false;
                                    case ITEM -> faceHasAnyModule(accessFace);
                                };
                        if (!DuctFeaturePolicy.isUsable(
                                ductDefinition().orElse(null),
                                DuctFeatureKeys.SPECIAL_CHANNEL,
                                up)) {
                            yield false;
                        }
                        node.channelLetter = node.channelLetter >= 26 ? 1 : node.channelLetter + 1;
                        yield true;
                    }
                    case 5 -> {
                        boolean up =
                                switch (menuActiveTransportKind()) {
                                    case FLUID -> faceHasAnyModule(accessFace);
                                    case GAS -> faceHasAnyModule(accessFace);
                                    case ENERGY, HEAT -> false;
                                    case ITEM -> faceHasAnyModule(accessFace);
                                };
                        if (!DuctFeaturePolicy.isUsable(
                                ductDefinition().orElse(null),
                                DuctFeatureKeys.SPECIAL_CHANNEL,
                                up)) {
                            yield false;
                        }
                        node.channelLetter = node.channelLetter <= 1 ? 26 : node.channelLetter - 1;
                        yield true;
                    }
                    case 13 -> {
                        boolean up =
                                switch (menuActiveTransportKind()) {
                                    case FLUID -> faceHasAnyModule(accessFace);
                                    case GAS -> faceHasAnyModule(accessFace);
                                    case ENERGY, HEAT -> false;
                                    case ITEM -> faceHasAnyModule(accessFace);
                                };
                        if (!DuctFeaturePolicy.isUsable(
                                ductDefinition().orElse(null),
                                DuctFeatureKeys.SPECIAL_CHANNEL,
                                up)) {
                            yield false;
                        }
                        node.channelLetter = 1;
                        yield true;
                    }
                    case 30 -> false;
                    default -> false;
                };
        if (changed) {
            setChanged();
            refreshMenuData(accessFace);
            syncVisualGeometryToClients();
        }
        return changed;
    }

    private boolean cycleNodeMode(DuctFaceLanes lanes, Direction accessFace) {
        NodeMode[] order =
                new NodeMode[] {
                    NodeMode.NONE,
                    NodeMode.EXTRACTION,
                    NodeMode.FILTERING_INSERTION,
                    NodeMode.RETRIEVING,
                    NodeMode.EXTRACTION_FILTERING,
                    NodeMode.RETRIEVING_EXTRACTION
                };
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasModule =
                faceHasAnyModule(accessFace)
                        || faceHasAnyModule(accessFace)
                        || faceHasAnyModule(accessFace);
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == lanes.nodeMode) {
                idx = i;
                break;
            }
        }
        for (int off = 1; off <= order.length; off++) {
            NodeMode cand = order[(idx + off) % order.length];
            if (DuctFeaturePolicy.isModeUsable(def.orElse(null), cand, hasModule)) {
                if (cand != lanes.nodeMode) {
                    lanes.nodeMode = cand;
                    onSharedModeChanged(lanes, accessFace);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean cycleNodeModeBackward(DuctFaceLanes lanes, Direction accessFace) {
        NodeMode[] order =
                new NodeMode[] {
                    NodeMode.NONE,
                    NodeMode.EXTRACTION,
                    NodeMode.FILTERING_INSERTION,
                    NodeMode.RETRIEVING,
                    NodeMode.EXTRACTION_FILTERING,
                    NodeMode.RETRIEVING_EXTRACTION
                };
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasModule =
                faceHasAnyModule(accessFace)
                        || faceHasAnyModule(accessFace)
                        || faceHasAnyModule(accessFace);
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == lanes.nodeMode) {
                idx = i;
                break;
            }
        }
        for (int off = 1; off <= order.length; off++) {
            NodeMode cand = order[Math.floorMod(idx - off, order.length)];
            if (DuctFeaturePolicy.isModeUsable(def.orElse(null), cand, hasModule)) {
                if (cand != lanes.nodeMode) {
                    lanes.nodeMode = cand;
                    onSharedModeChanged(lanes, accessFace);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean cycleRoutingMode(DuctFaceNode node, Direction accessFace) {
        return stepRouting(node, 1, accessFace);
    }

    private boolean cycleRoutingModeBackward(DuctFaceNode node, Direction accessFace) {
        return stepRouting(node, -1, accessFace);
    }

    private static RoutingMode nextUsableRouting(
            RoutingMode current,
            RoutingMode[] v,
            int delta,
            Optional<DuctDefinition> def,
            boolean hasModule) {
        int idx = current.ordinal();
        int dir = delta > 0 ? 1 : -1;
        for (int step = 1; step <= v.length; step++) {
            int ni = Math.floorMod(idx + dir * step, v.length);
            if (DuctFeaturePolicy.isRoutingUsable(def.orElse(null), v[ni], hasModule)) {
                return v[ni];
            }
        }
        return current;
    }

    /**
     * Cycles the routing field that applies to the current node mode (including hybrid extract vs retrieve).
     */
    private boolean stepRouting(DuctFaceNode node, int delta, Direction accessFace) {
        NodeMode shared = getFaceLanes(accessFace).nodeMode;
        if (!shared.usesRouting()) {
            return false;
        }
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasModule =
                isFluidLaneNode(node)
                        ? faceHasAnyModule(accessFace)
                        : isGasLaneNode(node)
                                ? faceHasAnyModule(accessFace)
                                : faceHasAnyModule(accessFace);
        RoutingMode[] v = RoutingMode.values();
        return switch (shared) {
            case EXTRACTION_FILTERING -> {
                RoutingMode cur = node.routingModeExtractor;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
                if (nxt != cur) {
                    node.routingModeExtractor = nxt;
                    yield true;
                }
                yield false;
            }
            case RETRIEVING_EXTRACTION -> {
                RoutingMode cur = node.routingModeRetriever;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
                if (nxt != cur) {
                    node.routingModeRetriever = nxt;
                    node.routingModeExtractor = nxt;
                    yield true;
                }
                yield false;
            }
            default -> {
                RoutingMode cur = node.routingMode;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
                if (nxt != cur) {
                    node.routingMode = nxt;
                    yield true;
                }
                yield false;
            }
        };
    }

    private void onSharedModeChanged(DuctFaceLanes lanes, Direction face) {
        if (!lanes.nodeMode.usesExtractBatchField()) {
            return;
        }
        Optional<DuctDefinition> def = ductDefinition();
        if (def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ITEM)).orElse(true)) {
            DuctFaceNode itemNode = lanes.item;
            if (itemNode.extractBatch <= 0) {
                itemNode.extractBatch = itemTransportSpec().batchDefault();
            }
            clampExtractAmount(itemNode, face);
        }
        if (def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.FLUID)).orElse(false)) {
            DuctFaceNode fluidNode = lanes.fluid;
            if (fluidNode.extractBatch <= 0) {
                fluidNode.extractBatch = fluidTransportSpec().batchDefaultMb();
            }
            clampFluidExtractAmount(fluidNode, face);
        }
        if (def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.GAS)).orElse(false)) {
            DuctFaceNode gasNode = lanes.gas;
            if (gasNode.extractBatch <= 0) {
                long defAmt = gasTransportSpec().batchDefault();
                gasNode.extractBatch = (int) Math.min(defAmt, Integer.MAX_VALUE);
            }
            clampGasExtractAmount(gasNode, face);
        }
    }

    private boolean isFluidLaneNode(DuctFaceNode node) {
        for (Direction d : Direction.values()) {
            if (getFluidFaceNode(d) == node) {
                return true;
            }
        }
        return false;
    }

    private boolean isGasLaneNode(DuctFaceNode node) {
        for (Direction d : Direction.values()) {
            if (getGasFaceNode(d) == node) {
                return true;
            }
        }
        return false;
    }

    public void applyClientFieldUpdate(
            Direction accessFace,
            int transportKindOrdinal,
            int insertionPriority,
            int extractBatch,
            int eligibilityModeOrdinal) {
        if (level == null || level.isClientSide) {
            return;
        }
        DuctTransportKind[] vals = DuctTransportKind.values();
        DuctTransportKind kind =
                vals[Mth.clamp(transportKindOrdinal, 0, vals.length - 1)];
        DuctFaceNode node = faceNodeForTransportKind(accessFace, kind);
        node.insertionPriority = insertionPriority;
        node.extractBatch = Math.max(0, extractBatch);
        node.eligibilityMode = DuctFaceNode.EligibilityMode.fromOrdinal(eligibilityModeOrdinal);
        if (kind == DuctTransportKind.FLUID) {
            clampFluidExtractAmount(node, accessFace);
        } else if (kind == DuctTransportKind.GAS) {
            clampGasExtractAmount(node, accessFace);
        } else {
            clampExtractAmount(node, accessFace);
        }
        setChanged();
        refreshMenuData(accessFace);
    }

    private void clampExtractAmount(DuctFaceNode node, Direction face) {
        applyExtractBatchAgainstCap(node, computeExtractBatchSettingCap(face));
    }

    private void clampFluidExtractAmount(DuctFaceNode node, Direction face) {
        applyExtractBatchAgainstCap(node, computeFluidExtractBatchSettingCap(face));
    }

    private void clampGasExtractAmount(DuctFaceNode node, Direction face) {
        applyExtractBatchAgainstCap(node, computeGasExtractBatchSettingCap(face));
    }

    /**
     * Clamps {@link DuctFaceNode#extractBatch} to {@code cap}. If the stored amount was pinned to the previous cap and
     * {@code cap} increases (e.g. new module), raises the amount to the new cap so the duct stays at "max". If
     * {@code cap} decreases and the amount was pinned to the old cap, sets the amount to the new cap (same as clamp when
     * {@code prev > cap}).
     */
    private static void applyExtractBatchAgainstCap(DuctFaceNode node, int cap) {
        int prev = node.extractBatch;
        int memo = node.lastExtractBatchSettingCapApplied;
        if (memo > 0 && prev >= memo && cap > memo) {
            node.extractBatch = cap;
        } else if (memo > 0 && prev >= memo && cap < memo) {
            node.extractBatch = cap;
        } else {
            node.extractBatch = Mth.clamp(prev, 0, cap);
        }
        node.lastExtractBatchSettingCapApplied = cap;
    }

    private void enforcePipeSegmentBehavior() {
        int sm = getStorageMask();
        boolean any = false;
        for (Direction dir : Direction.values()) {
            int bit = 1 << dir.ordinal();
            if ((sm & bit) != 0) {
                continue;
            }
            if ((latchedStorageFaceMask & bit) != 0) {
                continue;
            }
            DuctFaceLanes L = getFaceLanes(dir);
            if (!pipeSegmentInteriorFaceIsDefault(L)) {
                L.resetPipeSegmentDefaults();
                any = true;
            }
        }
        if (any) {
            setChanged();
            syncVisualGeometryToClients();
        }
    }

    private static boolean pipeSegmentInteriorFaceIsDefault(DuctFaceLanes L) {
        if (L.nodeMode != NodeMode.NONE) {
            return false;
        }
        DuctFaceNode n = L.item;
        DuctFaceNode fn = L.fluid;
        return n.insertionPriority == 0
                && n.extractBatch == 0
                && n.roundRobinCursor == 0
                && fn.insertionPriority == 0
                && fn.extractBatch == 0
                && fn.roundRobinCursor == 0;
    }

    private void syncVisualGeometryToClients() {
        requestModelDataUpdate();
        if (level != null && !level.isClientSide()) {
            // Ensure the BE update packet is sent so client can refresh PackedNodeIcons immediately.
            level.blockEntityChanged(getBlockPos());
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /** Old saves without {@code LatchedFaces}: infer latched bits from non-default per-face data. */
    private int inferLatchBitsFromLoadedFaceNodes() {
        int m = 0;
        for (Direction d : Direction.values()) {
            if (faceSuggestsPersistedStorageNode(getFaceLanes(d))) {
                m |= 1 << d.ordinal();
            }
        }
        return m;
    }

    private static boolean faceSuggestsPersistedStorageNode(DuctFaceLanes lanes) {
        if (lanes.nodeMode != NodeMode.NONE) {
            return true;
        }
        if (lanes.redstoneMode == 1 || lanes.redstoneMode == 2) {
            return true;
        }
        return laneSuggestsPersistedStorageNode(lanes.item)
                || laneSuggestsPersistedStorageNode(lanes.fluid)
                || laneSuggestsPersistedStorageNode(lanes.gas);
    }

    private static boolean laneSuggestsPersistedStorageNode(DuctFaceNode n) {
        if (n.insertionPriority != 0
                || n.extractBatch != 0
                || n.roundRobinCursor != 0
                || n.ticksUntilAction != 0) {
            return true;
        }
        if (n.channelLetter != 1) {
            return true;
        }
        if (n.routingMode != RoutingMode.NEAREST_FIRST) {
            return true;
        }
        if (!n.denyOverridesAllow) {
            return true;
        }
        for (String s : n.allowFilters) {
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        for (String s : n.denyFilters) {
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        for (int i = 0; i < n.guiSlots.getSlots(); i++) {
            if (!n.guiSlots.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void applyWrenchDisconnect(Level level, Direction face) {
        orUserDisconnectedFace(face);
        BlockPos npos = worldPosition.relative(face);
        if (level.getBlockEntity(npos) instanceof DuctBlockEntity neighbor) {
            neighbor.orUserDisconnectedFace(face.getOpposite());
            neighbor.setChanged();
            neighbor.refreshFromWorld();
        }
        setChanged();
        refreshFromWorld();
        propagateNeighborRefreshAfterWrench(level, npos);
    }

    public void tryReconnectFace(Level level, Direction hitFace) {
        if (!clearUserDisconnectedFace(hitFace)) {
            return;
        }
        BlockPos npos = worldPosition.relative(hitFace);
        if (level.getBlockEntity(npos) instanceof DuctBlockEntity neighbor) {
            neighbor.clearUserDisconnectedFace(hitFace.getOpposite());
            neighbor.setChanged();
            neighbor.refreshFromWorld();
        }
        setChanged();
        refreshFromWorld();
        propagateNeighborRefreshAfterWrench(level, npos);
    }

    private void propagateNeighborRefreshAfterWrench(Level level, BlockPos neighborPos) {
        BlockState st = getBlockState();
        if (st.getBlock() instanceof AbstractDuctBlock ad) {
            AbstractDuctBlock.refreshAdjacentDuctBlockEntities(level, worldPosition, ad.ductNetworkTypes());
        }
        BlockState ns = level.getBlockState(neighborPos);
        if (ns.getBlock() instanceof AbstractDuctBlock nad) {
            AbstractDuctBlock.refreshAdjacentDuctBlockEntities(level, neighborPos, nad.ductNetworkTypes());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("PipeMask", (byte) getPipeMask());
        tag.putByte("StorageMask", (byte) getStorageMask());
        tag.putByte("UserDisc", (byte) getUserDisconnectedFaceMask());
        tag.putByte("LatchedFaces", (byte) latchedStorageFaceMask);
        ListTag faces = new ListTag();
        for (Direction d : Direction.values()) {
            CompoundTag ft = new CompoundTag();
            getFaceLanes(d).save(registries, ft);
            faces.add(ft);
        }
        tag.put("FaceNodes", faces);
        tag.putString("DuctLogicalId", logicalDuctId);
        ListTag out = new ListTag();
        for (OutboundShipment s : outboundShipments) {
            out.add(s.save(registries));
        }
        tag.put("DuctOutbound", out);
        ListTag fOut = new ListTag();
        for (FluidTransitShipment s : fluidTransitShipments) {
            if (s.fluid.isEmpty()) {
                continue;
            }
            CompoundTag ft = new CompoundTag();
            ft.put("Fluid", (CompoundTag) s.fluid.save(registries));
            ft.putInt("Tot", s.totalTravelTicks);
            ft.putInt("Tr", s.travelTicks);
            ft.putInt("Ed", s.edgeTicks);
            ft.putLong("J0", s.journeyStartGameTime);
            ft.putByte("SrcF", (byte) s.sourceFace.ordinal());
            ft.putByte("DstF", (byte) s.destFace.ordinal());
            ft.putInt("DestX", s.destDuct.getX());
            ft.putInt("DestY", s.destDuct.getY());
            ft.putInt("DestZ", s.destDuct.getZ());
            ListTag path = new ListTag();
            for (BlockPos p : s.ductPath) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("X", p.getX());
                pt.putInt("Y", p.getY());
                pt.putInt("Z", p.getZ());
                path.add(pt);
            }
            ft.put("Path", path);
            fOut.add(ft);
        }
        tag.put("DuctFluidTransit", fOut);
        ListTag gOut = new ListTag();
        for (GasTransitShipment s : gasTransitShipments) {
            if (s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
                continue;
            }
            CompoundTag gt = new CompoundTag();
            MekanismChemicalCompat.saveGasStackToTag(s.stack, gt);
            if (!gt.contains("ChemId")) {
                continue;
            }
            gt.putInt("Tot", s.totalTravelTicks);
            gt.putInt("Tr", s.travelTicks);
            gt.putInt("Ed", s.edgeTicks);
            gt.putLong("J0", s.journeyStartGameTime);
            gt.putByte("SrcF", (byte) s.sourceFace.ordinal());
            gt.putByte("DstF", (byte) s.destFace.ordinal());
            gt.putInt("DestX", s.destDuct.getX());
            gt.putInt("DestY", s.destDuct.getY());
            gt.putInt("DestZ", s.destDuct.getZ());
            ListTag path = new ListTag();
            for (BlockPos p : s.ductPath) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("X", p.getX());
                pt.putInt("Y", p.getY());
                pt.putInt("Z", p.getZ());
                path.add(pt);
            }
            gt.put("Path", path);
            gOut.add(gt);
        }
        tag.put("DuctGasTransit", gOut);
        ListTag bl = new ListTag();
        for (ItemStack b : migratedStorageBacklog) {
            CompoundTag bt = new CompoundTag();
            b.save(registries, bt);
            bl.add(bt);
        }
        tag.put("DuctBacklog", bl);
        CompoundTag ov = new CompoundTag();
        overflowBuffer.save(registries, ov);
        tag.put("DuctOverflow", ov);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        // Placement from dropped item: BlockEntityTag carries only DuctConfig (no PipeMask).
        // Never use this fast path when world-persisted logistics / buffers exist, or if PipeMask exists under any
        // NBT type — otherwise chunk reload can skip DuctOutbound and wipe in-flight item tasks.
        boolean tagHasPipeMask = tag.contains("PipeMask");
        boolean hasPersistedRuntime =
                tag.contains("DuctOutbound")
                        || tag.contains("DuctFluidTransit")
                        || tag.contains("DuctGasTransit")
                        || tag.contains("DuctBacklog")
                        || tag.contains("DuctOverflow");
        if (tag.contains("DuctConfig", Tag.TAG_COMPOUND) && !tagHasPipeMask && !hasPersistedRuntime) {
            CompoundTag cfg = tag.getCompound("DuctConfig");
            if (cfg.contains("DuctLogicalId")) {
                logicalDuctId = DuctIds.normalize(cfg.getString("DuctLogicalId"));
            }
            ensureFaceLaneModuleSlotCapacitiesMatchDefinition();
            if (cfg.contains("UserDisc", Tag.TAG_BYTE)) {
                setUserDisconnectedFaceMaskForLoad(cfg.getByte("UserDisc") & 0xFF);
            }
            if (cfg.contains("LatchedFaces", Tag.TAG_BYTE)) {
                latchedStorageFaceMask = cfg.getByte("LatchedFaces") & 0xFF;
            }
            if (cfg.contains("FaceNodes", Tag.TAG_LIST)) {
                ListTag list = cfg.getList("FaceNodes", Tag.TAG_COMPOUND);
                for (int i = 0; i < FACE_COUNT && i < list.size(); i++) {
                    getFaceLanes(Direction.values()[i]).load(registries, list.getCompound(i));
                }
            }
            clampFaceFiltersToSpec();
            setChanged();
            return;
        }

        if (tag.contains("DuctLogicalId", Tag.TAG_STRING)) {
            logicalDuctId = DuctIds.normalize(tag.getString("DuctLogicalId"));
        }
        ensureFaceLaneModuleSlotCapacitiesMatchDefinition();

        setConnectionMasksForLoad(tag.getByte("PipeMask") & 0xFF, tag.getByte("StorageMask") & 0xFF);
        if (tag.contains("FaceNodes", Tag.TAG_LIST)) {
            ListTag list = tag.getList("FaceNodes", Tag.TAG_COMPOUND);
            for (int i = 0; i < FACE_COUNT && i < list.size(); i++) {
                getFaceLanes(Direction.values()[i]).load(registries, list.getCompound(i));
            }
        } else {
            for (Direction d : Direction.values()) {
                getFaceLanes(d).loadFromLegacyRootTag(registries, tag);
            }
        }
        if (tag.contains("LatchedFaces", Tag.TAG_BYTE)) {
            latchedStorageFaceMask = tag.getByte("LatchedFaces") & 0xFF;
        } else {
            latchedStorageFaceMask = (tag.getByte("StorageMask") & 0xFF) | inferLatchBitsFromLoadedFaceNodes();
        }
        outboundShipments.clear();
        if (tag.contains("DuctOutbound", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DuctOutbound", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                outboundShipments.add(OutboundShipment.load(registries, list.getCompound(i)));
            }
        }
        fluidTransitShipments.clear();
        if (tag.contains("DuctFluidTransit", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DuctFluidTransit", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag ft = list.getCompound(i);
                FluidStack fs = FluidStack.EMPTY;
                if (ft.contains("Fluid", Tag.TAG_COMPOUND)) {
                    fs = FluidStack.parse(registries, ft.getCompound("Fluid")).orElse(FluidStack.EMPTY);
                }
                if (fs.isEmpty()) {
                    continue;
                }
                ListTag plist = ft.getList("Path", Tag.TAG_COMPOUND);
                ArrayList<BlockPos> path = new ArrayList<>(plist.size());
                for (int j = 0; j < plist.size(); j++) {
                    CompoundTag pt = plist.getCompound(j);
                    path.add(new BlockPos(pt.getInt("X"), pt.getInt("Y"), pt.getInt("Z")));
                }
                Direction srcFace = Direction.values()[ft.getByte("SrcF") & 0xFF];
                Direction dstFace = Direction.values()[ft.getByte("DstF") & 0xFF];
                BlockPos destDuct = new BlockPos(ft.getInt("DestX"), ft.getInt("DestY"), ft.getInt("DestZ"));
                int tot = ft.getInt("Tot");
                int tr = ft.getInt("Tr");
                int ed = ft.getInt("Ed");
                long j0 = ft.getLong("J0");
                FluidTransitShipment fsh =
                        new FluidTransitShipment(fs, List.copyOf(path), tot, tr, ed, j0, srcFace, dstFace, destDuct);
                fluidTransitShipments.add(fsh);
            }
        }
        gasTransitShipments.clear();
        if (tag.contains("DuctGasTransit", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DuctGasTransit", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag gt = list.getCompound(i);
                Object gStack = MekanismChemicalCompat.loadGasStackFromTag(gt, registries);
                if (gStack == null || MekanismChemicalCompat.isEmptyStack(gStack)) {
                    continue;
                }
                ListTag plist = gt.getList("Path", Tag.TAG_COMPOUND);
                ArrayList<BlockPos> path = new ArrayList<>(plist.size());
                for (int j = 0; j < plist.size(); j++) {
                    CompoundTag pt = plist.getCompound(j);
                    path.add(new BlockPos(pt.getInt("X"), pt.getInt("Y"), pt.getInt("Z")));
                }
                Direction srcFace = Direction.values()[gt.getByte("SrcF") & 0xFF];
                Direction dstFace = Direction.values()[gt.getByte("DstF") & 0xFF];
                BlockPos destDuct = new BlockPos(gt.getInt("DestX"), gt.getInt("DestY"), gt.getInt("DestZ"));
                int tot = gt.getInt("Tot");
                int tr = gt.getInt("Tr");
                int ed = gt.getInt("Ed");
                long j0 = gt.getLong("J0");
                GasTransitShipment gsh =
                        new GasTransitShipment(
                                gStack, List.copyOf(path), tot, tr, ed, j0, srcFace, dstFace, destDuct);
                gasTransitShipments.add(gsh);
            }
        }
        migratedStorageBacklog.clear();
        if (tag.contains("DuctBacklog", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DuctBacklog", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                ItemStack.parse(registries, list.getCompound(i)).ifPresent(migratedStorageBacklog::add);
            }
        }
        if (tag.contains("DuctOverflow", Tag.TAG_COMPOUND)) {
            overflowBuffer.load(registries, tag.getCompound("DuctOverflow"));
        }
        setUserDisconnectedFaceMaskForLoad(tag.contains("UserDisc", Tag.TAG_BYTE) ? tag.getByte("UserDisc") & 0xFF : 0);
        requestModelDataUpdate();
        clampFaceFiltersToSpec();
        for (Direction d : Direction.values()) {
            if (getFaceLanes(d).nodeMode.usesExtractBatchField()) {
                clampExtractAmount(getFaceNode(d), d);
                clampFluidExtractAmount(getFluidFaceNode(d), d);
            }
        }
        if (level != null && level.isClientSide()) {
            // Chunk NBT has DuctOutbound but not TransitV1; BER reads DuctTransitClientState only.
            DuctTransitClientState.syncFromOutboundShipments(worldPosition, outboundShipments, level);
            DuctFluidTransitClientState.syncFromFluidShipments(worldPosition, fluidTransitShipments, level);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.putByte("PipeMask", (byte) getPipeMask());
        t.putByte("StorageMask", (byte) getStorageMask());
        t.putByte("UserDisc", (byte) getUserDisconnectedFaceMask());
        t.putByte("LatchedFaces", (byte) latchedStorageFaceMask);
        // Pack node icon indices server-side for client rendering.
        t.putInt("PackedNodeIcons", computePackedNodeIcons());
        ListTag transitList = new ListTag();
        for (OutboundShipment s : outboundShipments) {
            if (s.stack.isEmpty()) {
                continue;
            }
            ItemStack oneForWire = s.stack.copy();
            oneForWire.setCount(1);
            CompoundTag st = new CompoundTag();
            oneForWire.save(registries, st);
            CompoundTag item = new CompoundTag();
            item.put("Stack", st);
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(s.stack.getItem());
            if (itemId == null) {
                itemId = BuiltInRegistries.ITEM.getResourceKey(s.stack.getItem())
                        .map(ResourceKey::location)
                        .orElse(null);
            }
            if (itemId != null) {
                item.putString("VizId", itemId.toString());
            }
            item.putByte("Ph", (byte) TransitPhase.FORWARD.ordinal());
            item.putInt("Tot", s.totalTravelTicks);
            item.putInt("Tr", s.travelTicks);
            item.putInt("Ed", s.edgeTicks);
            item.putLong("J0", s.journeyStartGameTime);
            item.putByte("SrcF", (byte) s.sourceFace.ordinal());
            item.putByte("DstF", (byte) s.destFace.ordinal());
            ListTag path = new ListTag();
            for (BlockPos p : s.ductPath) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("X", p.getX());
                pt.putInt("Y", p.getY());
                pt.putInt("Z", p.getZ());
                path.add(pt);
            }
            item.put("Path", path);
            transitList.add(item);
        }
        // Always send (possibly empty) so clients clear visuals when the last shipment completes.
        t.put("TransitV1", transitList);
        ListTag fluidTransitList = new ListTag();
        for (FluidTransitShipment s : fluidTransitShipments) {
            if (s.fluid.isEmpty()) {
                continue;
            }
            CompoundTag ft = new CompoundTag();
            FluidStack wireFluid = s.fluid.copy();
            if (wireFluid.getAmount() > 1000) {
                wireFluid.setAmount(1000);
            }
            ft.put("Fluid", (CompoundTag) wireFluid.save(registries));
            ft.putInt("Tot", s.totalTravelTicks);
            ft.putInt("Tr", s.travelTicks);
            ft.putInt("Ed", s.edgeTicks);
            ft.putLong("J0", s.journeyStartGameTime);
            ft.putByte("SrcF", (byte) s.sourceFace.ordinal());
            ft.putByte("DstF", (byte) s.destFace.ordinal());
            ListTag fpath = new ListTag();
            for (BlockPos p : s.ductPath) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("X", p.getX());
                pt.putInt("Y", p.getY());
                pt.putInt("Z", p.getZ());
                fpath.add(pt);
            }
            ft.put("Path", fpath);
            fluidTransitList.add(ft);
        }
        t.put("FluidTransitV1", fluidTransitList);
        ListTag gasTransitList = new ListTag();
        for (GasTransitShipment s : gasTransitShipments) {
            if (s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
                continue;
            }
            long amt = MekanismChemicalCompat.getAmount(s.stack);
            if (amt <= 0) {
                continue;
            }
            CompoundTag gt = new CompoundTag();
            gt.putInt("Tint", MekanismChemicalCompat.getTint(s.stack));
            gt.putLong("Amt", amt);
            gt.putInt("Tot", s.totalTravelTicks);
            gt.putInt("Tr", s.travelTicks);
            gt.putInt("Ed", s.edgeTicks);
            gt.putLong("J0", s.journeyStartGameTime);
            gt.putByte("SrcF", (byte) s.sourceFace.ordinal());
            gt.putByte("DstF", (byte) s.destFace.ordinal());
            ListTag gpath = new ListTag();
            for (BlockPos p : s.ductPath) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("X", p.getX());
                pt.putInt("Y", p.getY());
                pt.putInt("Z", p.getZ());
                gpath.add(pt);
            }
            gt.put("Path", gpath);
            gasTransitList.add(gt);
        }
        t.put("GasTransitV1", gasTransitList);
        t.putString("DuctLogicalId", logicalDuctId);
        return t;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (tag.contains("PipeMask", Tag.TAG_BYTE) && tag.contains("StorageMask", Tag.TAG_BYTE)) {
            setConnectionMasksForLoad(tag.getByte("PipeMask") & 0xFF, tag.getByte("StorageMask") & 0xFF);
            requestModelDataUpdate();
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        if (tag.contains("DuctLogicalId", Tag.TAG_STRING)) {
            logicalDuctId = DuctIds.normalize(tag.getString("DuctLogicalId"));
            requestModelDataUpdate();
        }
        if (tag.contains("LatchedFaces", Tag.TAG_BYTE)) {
            latchedStorageFaceMask = tag.getByte("LatchedFaces") & 0xFF;
        }
        if (tag.contains("UserDisc", Tag.TAG_BYTE)) {
            setUserDisconnectedFaceMaskForLoad(tag.getByte("UserDisc") & 0xFF);
            requestModelDataUpdate();
        }
        int previousPacked = clientPackedNodeIcons;
        int nextPacked = tag.contains("PackedNodeIcons", Tag.TAG_INT) ? tag.getInt("PackedNodeIcons") : defaultPackedNodeIcons();
        clientPackedNodeIcons = nextPacked;
        if (previousPacked != nextPacked) {
            // Force chunk re-bake client-side only when icon pack changes.
            requestModelDataUpdate();
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        if (level != null && level.isClientSide) {
            // ItemStack components need the live client world registry; packet Provider often yields empty parse.
            DuctTransitClientState.onDuctUpdateTag(
                    worldPosition, tag, level.registryAccess(), level.getGameTime());
            DuctFluidTransitClientState.applyDuctUpdateTag(
                    worldPosition, tag, level.registryAccess(), level.getGameTime());
            DuctGasTransitClientState.applyDuctUpdateTag(
                    worldPosition, tag, level.registryAccess(), level.getGameTime());
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        handleUpdateTag(packet.getTag(), registries);
    }

    @Override
    public ModelData getModelData() {
        int packed;
        if (level != null && level.isClientSide) {
            packed = clientPackedNodeIcons;
        } else {
            packed = computePackedNodeIcons();
        }
        return ModelData.builder()
                .with(DuctModelProperties.PIPE_MASK, getPipeMask())
                .with(DuctModelProperties.STORAGE_MASK, getStorageMask())
                .with(DuctModelProperties.NODE_ICONS_PACKED, packed)
                .with(DuctModelProperties.DUCT_LOGICAL_ID, logicalDuctId)
                .build();
    }
}
