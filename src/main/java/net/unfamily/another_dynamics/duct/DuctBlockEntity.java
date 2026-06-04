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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctActionScheduling;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctHandlerSlotSemantics;
import net.unfamily.another_dynamics.duct.logistics.DuctInsertProbeCache;
import net.unfamily.another_dynamics.duct.logistics.DuctItemInsertProbe;
import net.unfamily.another_dynamics.duct.logistics.DuctFluidIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctGasIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctOverflowBuffer;
import net.unfamily.another_dynamics.duct.logistics.DuctOverflowRouting;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.duct.logistics.FluidTransitShipment;
import net.unfamily.another_dynamics.duct.logistics.GasTransitShipment;
import net.unfamily.another_dynamics.duct.logistics.DuctFluidServerTick;
import net.unfamily.another_dynamics.duct.logistics.DuctGasServerTick;
import net.unfamily.another_dynamics.duct.logistics.DuctEnergyServerTick;
import net.unfamily.another_dynamics.duct.logistics.DuctHeatServerTick;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.logistics.DuctRoutingEndpointIndex;
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
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
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
 * Block entity for all duct blocks ({@link DuctBlock}, {@link FluidDuctBlock}, {@link GasDuctBlock}, universal logical ids).
 * Item logistics run here; fluid/gas/energy/heat use dedicated tick classes when enabled on the definition.
 * <p><strong>Material-lane family:</strong> item tick/routing changes often need parity in {@link
 * net.unfamily.another_dynamics.duct.logistics.DuctFluidServerTick} and {@link
 * net.unfamily.another_dynamics.duct.logistics.DuctGasServerTick}. Universal ducts share the same {@link DuctFaceLanes}
 * / {@link DuctFaceNode} layout as single-kind ducts; copier {@code all} data matches
 * {@link DuctFaceLanes#saveCopierSettings} (see {@link net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot}).
 */
public final class DuctBlockEntity extends AbstractDuctBlockEntity {
    private static final int MAX_BLOCKED_ITEM_KINDS = 5;
    /** Cancel in-transit items stuck longer than expected travel (unblocks scheduling). */
    private static final long STALE_OUTBOUND_EXTRA_TICKS = 400L;
    private static final int FACE_COUNT = 6;
    /** Max alternate destinations when {@link #canScheduleTowardFace} rejects the first routing pick (pending/cap simulation). */
    private static final int EXTRACTION_ROUTE_RETRY_CAP = 32;
    /** Throttle full-network stall drain scans (BFS destination lists) between successful drains. */
    private static final int ITEM_STALL_DRAIN_SCAN_INTERVAL = 4;
    private static final int NON_ITEM_STALL_DRAIN_SCAN_INTERVAL = 4;
    private int itemStallDrainScanCooldown;
    private int nonItemStallDrainScanCooldown;

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
     * Items still reserved on {@code sourceDuct}/{@code sourceFace} machine inventory for in-flight
     * {@link OutboundShipment}s (same type). Counts only shipments not yet extracted at schedule time; committed
     * stacks are already off the machine (same split as {@link #pendingOutboundFluidMbFromFace} vs tank probe).
     */
    public int pendingOutboundItemCountFromSource(BlockPos sourceDuct, Direction sourceFace, ItemStack kind) {
        if (kind.isEmpty()) {
            return 0;
        }
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
        s.stack = ItemStack.EMPTY;
        removeShipmentFromList(s, it);
        if (!lump.isEmpty() && physical) {
            if (!tryRefundOrStall(level, s, lump)) {
                DuctOverflowRouting.tryRefundToSourceNoDrop(
                        level, s, lump, mergeScheduleOwnerWithExtras(worldPosition, extraOverflowDucts));
            }
        }
        setChanged();
    }

    private final DuctFaceLanes[] faceLanes = new DuctFaceLanes[FACE_COUNT];
    private final IEnergyStorage[] faceEnergyBufferCaps = new IEnergyStorage[FACE_COUNT];

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
    /** Client-only cached stall mask from server (bit per face). */
    private int clientStallMask = 0;
    /** Server: network opaque for all players on this duct block. */
    private boolean networkOpaqueRendering;
    /** Client copy from update packet. */
    private boolean clientNetworkOpaque;

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
            boolean off = !isFaceTransportEnabled(d) || !faceShowsTransportIconForEnabledKinds(d);
            int row = rowBase + (off ? 1 : 0);
            int idx = row * 4 + col; // 0..15
            packed |= (idx & 0xF) << (d.ordinal() * 4);
        }
        return packed;
    }

    private boolean isFaceTransportEnabled(Direction face) {
        return DuctRedstoneLogic.isFaceTransportActive(level, worldPosition, getFaceLanes(face).redstoneMode);
    }

    /** Redstone gate plus per-face transport-kind toggle (universal duct). */
    public boolean isTransportKindEnabled(Direction face, DuctTransportKind kind) {
        if (!isFaceTransportEnabled(face)) {
            return false;
        }
        EnumSet<DuctTransportKind> kinds =
                ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        return getFaceLanes(face).isTransportKindEnabled(kind, kinds);
    }

    private boolean isMultiTransportDuct() {
        return ductDefinition().map(d -> d.enabledTransportKinds().size() > 1).orElse(false);
    }

    /**
     * Whether the node icon for this face should reflect the configured mode. On multi-transport ducts, a face still
     * touching a machine only shows destination/extract/retrieve when at least one <em>enabled</em> transport kind matches
     * the neighbor capability (e.g. energy disabled on an FE port must not show a destination icon).
     */
    private boolean faceShowsTransportIconForEnabledKinds(Direction face) {
        if (!isMultiTransportDuct()) {
            return true;
        }
        if (level == null || (getVisualStorageMask() & (1 << face.ordinal())) == 0) {
            return false;
        }
        EnumSet<DuctTransportKind> ductKinds =
                ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        BlockPos neighborPos = worldPosition.relative(face);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.isAir()) {
            return false;
        }
        DuctFaceLanes lanes = getFaceLanes(face);
        for (DuctTransportKind kind : ductKinds) {
            if (!lanes.isTransportKindEnabled(kind, ductKinds)) {
                continue;
            }
            if (attachmentMaskForTransportKind(face, neighborState, neighborPos, kind) != 0) {
                return true;
            }
        }
        return false;
    }

    private int attachmentMaskForTransportKind(
            Direction ductFace, BlockState neighborState, BlockPos neighborPos, DuctTransportKind kind) {
        DuctNetworkType net =
                switch (kind) {
                    case ITEM -> DuctNetworkType.ITEM;
                    case FLUID -> DuctNetworkType.FLUID;
                    case GAS -> DuctNetworkType.GAS;
                    case ENERGY -> DuctNetworkType.ENERGY;
                    case HEAT -> DuctNetworkType.HEAT;
                };
        return attachmentMaskForNeighbor(net, ductFace, neighborState, neighborPos);
    }

    private void ensureAllFaceTransportMasks() {
        EnumSet<DuctTransportKind> kinds =
                ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        for (Direction d : Direction.values()) {
            getFaceLanes(d).ensureTransportEnabledMask(kinds);
        }
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
            Direction d = Direction.values()[i];
            faceLanes[i] = new DuctFaceLanes(this, d, 5);
            faceEnergyBufferCaps[i] = new FaceEnergyBuffer(d);
        }
    }

    @Nullable
    public IEnergyStorage energyBufferCapability(@Nullable Direction side) {
        if (side == null) {
            return null;
        }
        boolean enabled =
                ductDefinition()
                        .map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ENERGY))
                        .orElse(false);
        if (!enabled) {
            return null;
        }
        if ((getStorageMask() & (1 << side.ordinal())) == 0) {
            return null;
        }
        return faceEnergyBufferCaps[side.ordinal()];
    }

    /** External push (batteries) into the input buffer; {@link NodeMode#NONE} does not accept. */
    public static boolean canReceiveExternalEnergyOnFace(NodeMode mode) {
        return mode != NodeMode.NONE;
    }

    /** Push FE into the output buffer (network logistics only; not exposed via {@link #canReceive()}). */
    public int depositEnergyOutputBuffer(Direction face, int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        DuctFaceLanes lanes = getFaceLanes(face);
        int cap = DuctModuleEffects.effectiveEnergyOutputBufferCapFe(this, face, energyTransportSpec());
        int stored = Math.max(0, lanes.energyOutputBufferFe);
        int accept = Math.min(maxReceive, Math.max(0, cap - stored));
        if (!simulate && accept > 0) {
            lanes.energyOutputBufferFe = stored + accept;
            setChanged();
            refreshMenuData(face);
            syncStallVisualIfNeeded();
        }
        return accept;
    }

    /** Pull FE from the input buffer (network extract / logistics). */
    public int extractEnergyInputBuffer(Direction face, int maxExtract, boolean simulate) {
        if (maxExtract <= 0) {
            return 0;
        }
        DuctFaceLanes lanes = getFaceLanes(face);
        int stored = Math.max(0, lanes.energyInputBufferFe);
        int take = Math.min(maxExtract, stored);
        if (!simulate && take > 0) {
            lanes.energyInputBufferFe = stored - take;
            setChanged();
            refreshMenuData(face);
            if (lanes.energyInputBufferFe <= 0 && lanes.energyOutputBufferFe <= 0) {
                syncStallVisualIfNeeded();
            }
        }
        return take;
    }

    public void applyEnergyBufferLimitsFromClient(Direction face, int extractLimitFe, int insertLimitFe) {
        DuctFaceLanes lanes = getFaceLanes(face);
        lanes.energyExtractBufferLimitFe = Math.max(0, extractLimitFe);
        lanes.energyInsertBufferLimitFe = Math.max(0, insertLimitFe);
        int inCap = DuctModuleEffects.effectiveEnergyInputBufferCapFe(this, face, energyTransportSpec());
        int outCap = DuctModuleEffects.effectiveEnergyOutputBufferCapFe(this, face, energyTransportSpec());
        if (lanes.energyInputBufferFe > inCap) {
            lanes.energyInputBufferFe = inCap;
        }
        if (lanes.energyOutputBufferFe > outCap) {
            lanes.energyOutputBufferFe = outCap;
        }
        setChanged();
        refreshMenuData(face);
    }

    /**
     * Face capability: {@code receiveEnergy} → input buffer; {@code extractEnergy} → output buffer (adjacent machines).
     */
    private final class FaceEnergyBuffer implements IEnergyStorage {
        private final Direction face;

        private FaceEnergyBuffer(Direction face) {
            this.face = face;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (maxReceive <= 0 || !canReceive()) {
                return 0;
            }
            DuctFaceLanes lanes = getFaceLanes(face);
            int cap = DuctModuleEffects.effectiveEnergyInputBufferCapFe(DuctBlockEntity.this, face, energyTransportSpec());
            int stored = Math.max(0, lanes.energyInputBufferFe);
            int accept = Math.min(maxReceive, Math.max(0, cap - stored));
            if (!simulate && accept > 0) {
                lanes.energyInputBufferFe = stored + accept;
                setChanged();
                refreshMenuData(face);
                syncStallVisualIfNeeded();
            }
            return accept;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (maxExtract <= 0 || !canExtract()) {
                return 0;
            }
            DuctFaceLanes lanes = getFaceLanes(face);
            int stored = Math.max(0, lanes.energyOutputBufferFe);
            int take = Math.min(maxExtract, stored);
            if (!simulate && take > 0) {
                lanes.energyOutputBufferFe = stored - take;
                setChanged();
                refreshMenuData(face);
                if (lanes.energyInputBufferFe <= 0 && lanes.energyOutputBufferFe <= 0) {
                    syncStallVisualIfNeeded();
                }
            }
            return take;
        }

        @Override
        public int getEnergyStored() {
            return Math.max(0, getFaceLanes(face).energyOutputBufferFe);
        }

        @Override
        public int getMaxEnergyStored() {
            return DuctModuleEffects.effectiveEnergyOutputBufferCapFe(DuctBlockEntity.this, face, energyTransportSpec());
        }

        @Override
        public boolean canExtract() {
            return getEnergyStored() > 0 || getMaxEnergyStored() > 0;
        }

        @Override
        public boolean canReceive() {
            return canReceiveExternalEnergyOnFace(getFaceLanes(face).nodeMode);
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

    /**
     * Shift+click on a node face with a module item when the duct definition has exactly one module slot.
     * Inserts into an empty slot, merges stackable modules, swaps compatible modules, or force-replaces incompatible ones.
     */
    public boolean tryQuickEquipModule(
            ServerPlayer player, Direction face, ItemStack held, InteractionHand hand) {
        if (moduleSlotCountForMenu() != 1 || held.isEmpty()) {
            return false;
        }
        if (net.unfamily.another_dynamics.duct.module.DuctModuleHelper.resolvedDeclarationId(held)
                .isEmpty()) {
            return false;
        }
        ensureFaceLaneModuleSlotCapacitiesMatchDefinition();
        var handler = getFaceLanes(face).moduleSlots;
        ItemStack toInsert = held.copyWithCount(1);
        ItemStack before = handler.getStackInSlot(0).copy();
        if (before.isEmpty()) {
            if (!handler.isItemValid(0, toInsert)) {
                return false;
            }
            ItemStack remainder = handler.insertItem(0, toInsert, false);
            if (!remainder.isEmpty()) {
                return false;
            }
        } else {
            ItemStack remainder = handler.insertItem(0, toInsert, false);
            if (!remainder.isEmpty()) {
                if (!player.getInventory().add(remainder)) {
                    player.drop(remainder, false);
                }
            } else if (ItemStack.matches(before, handler.getStackInSlot(0))) {
                handler.setStackInSlot(0, toInsert);
                if (!before.isEmpty()) {
                    if (!player.getInventory().add(before)) {
                        player.drop(before, false);
                    }
                }
            }
        }
        held.shrink(1);
        if (held.isEmpty()) {
            player.setItemInHand(hand, ItemStack.EMPTY);
        }
        clampAllTransportExtractBatchesForFace(face);
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return true;
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

    public boolean isNetworkOpaqueRendering() {
        return level != null && level.isClientSide ? clientNetworkOpaque : networkOpaqueRendering;
    }

    public void setNetworkOpaqueRendering(boolean opaque) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (networkOpaqueRendering == opaque) {
            return;
        }
        networkOpaqueRendering = opaque;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        requestModelDataUpdate();
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

    public RoutingMode energyRoutingForFace(Direction face, boolean extractorLane) {
        DuctFaceLanes lanes = getFaceLanes(face);
        return switch (lanes.nodeMode) {
            case RETRIEVING_EXTRACTION ->
                    extractorLane ? lanes.energyRoutingModeExtractor : lanes.energyRoutingModeRetriever;
            case RETRIEVING -> lanes.energyRoutingModeRetriever;
            case EXTRACTION, EXTRACTION_FILTERING ->
                    lanes.nodeMode.isHybrid() ? lanes.energyRoutingModeExtractor : lanes.energyRoutingMode;
            default -> lanes.energyRoutingMode;
        };
    }

    /** Mek heat routing for {@code face} (includes {@link NodeMode#NONE} pass-through). */
    public RoutingMode heatRoutingForFace(Direction face, boolean extractorLane) {
        DuctFaceLanes lanes = getFaceLanes(face);
        return switch (lanes.nodeMode) {
            case RETRIEVING_EXTRACTION ->
                    extractorLane ? lanes.heatRoutingModeExtractor : lanes.heatRoutingModeRetriever;
            case RETRIEVING -> lanes.heatRoutingModeRetriever;
            case EXTRACTION, EXTRACTION_FILTERING ->
                    lanes.nodeMode.isHybrid() ? lanes.heatRoutingModeExtractor : lanes.heatRoutingMode;
            default -> lanes.heatRoutingMode;
        };
    }

    public boolean usesEnergyOrHeatPassThroughRouting(Direction face) {
        DuctTransportKind k = menuActiveTransportKind();
        if (k != DuctTransportKind.ENERGY && k != DuctTransportKind.HEAT) {
            return false;
        }
        NodeMode nm = getFaceLanes(face).nodeMode;
        return nm == NodeMode.NONE || nm == NodeMode.FILTERING_INSERTION;
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
        if (!isTransportKindEnabled(accessFace, kind)) {
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
            if (masksChanged && level instanceof ServerLevel serverLevel) {
                DuctNetworkOpaquePropagation.onStructuralChange(serverLevel, worldPosition);
            }
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
                // No incoming reservation tracking.
            }
        }
    }

    private void unregisterOutboundFromIncomingIndex(ServerLevel sl) {
        for (OutboundShipment s : outboundShipments) {
            if (s.travelTicks >= 0 && !s.stack.isEmpty()) {
                // No incoming reservation tracking.
            }
        }
    }

    public void serverTickPipe(ServerLevel serverLevel) {
        if (isRemoved()) {
            return;
        }
        refreshFromWorld();
        // Opportunistically drain any stalled buffers back into their source handlers (items/fluids/gas).
        // This keeps ducts from staying visually/physically stalled when the attached storage becomes available again.
        drainFaceStallsToSource(serverLevel);
        tickRedstoneVisualSync(serverLevel);
        tickOutboundShipments(serverLevel);
        tickFluidTransitShipments(serverLevel);
        tickGasTransitShipments(serverLevel);
        tickOverflowBufferDrain(serverLevel);
        tickMigratedBacklogFlush(serverLevel);
        if (itemStallDrainScanCooldown > 0) {
            itemStallDrainScanCooldown--;
        }
        if (nonItemStallDrainScanCooldown > 0) {
            nonItemStallDrainScanCooldown--;
        }
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
                if (!isTransportKindEnabled(dir, DuctTransportKind.ITEM)) {
                    continue;
                }
                if (!DuctRedstoneLogic.isFaceTransportActive(serverLevel, worldPosition, lanes.redstoneMode)) {
                    continue;
                }
                int rate = DuctModuleEffects.effectiveItemActionRateTicks(this, dir, spec);
                if (node.ticksUntilAction > 0) {
                    node.ticksUntilAction--;
                    continue;
                }
                if (!DuctActionScheduling.isStaggerSlot(serverLevel, worldPosition, dir, rate)) {
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
        if ((serverLevel.getGameTime() + worldPosition.asLong()) % 600L == 0L) {
            ensureFaceLaneModuleSlotCapacitiesMatchDefinition();
        }
    }

    private void drainFaceStallsToSource(ServerLevel level) {
        boolean changed = false;
        int storageMask = getStorageMask();
        for (Direction face : Direction.values()) {
            if ((storageMask & (1 << face.ordinal())) == 0) {
                continue;
            }
            DuctFaceLanes lanes = getFaceLanes(face);
            if (!faceHasItemStallContent(lanes)) {
                continue;
            }
            if (itemStallDrainScanCooldown > 0) {
                continue;
            }
            // Items / fluids / gas: network re-send only (stall-only policy; no insert into source machine).
            if (tryDrainItemStallForFace(level, itemTransportSpec(), face, getFaceNode(face))) {
                changed = true;
                itemStallDrainScanCooldown = ITEM_STALL_DRAIN_SCAN_INTERVAL;
            }
        }
        if (changed) {
            syncStallVisualIfNeeded();
            setChanged();
        }
        tickDrainNonItemStallsToNetwork(level);
    }

    /** Retry delivering fluid/gas/energy stall buffers into the network (not only refund to the source block). */
    private void tickDrainNonItemStallsToNetwork(ServerLevel level) {
        if (nonItemStallDrainScanCooldown > 0) {
            return;
        }
        int sm = getStorageMask();
        boolean drained = false;
        for (Direction face : Direction.values()) {
            if ((sm & (1 << face.ordinal())) == 0) {
                continue;
            }
            if (!isTransportKindEnabled(face, DuctTransportKind.ENERGY) && !isTransportKindEnabled(face, DuctTransportKind.FLUID)
                    && !isTransportKindEnabled(face, DuctTransportKind.GAS)) {
                continue;
            }
            DuctFaceLanes lanes = getFaceLanes(face);
            if (isTransportKindEnabled(face, DuctTransportKind.ENERGY)
                    && (lanes.energyInputBufferFe > 0 || lanes.energyOutputBufferFe > 0)) {
                DuctEnergyServerTick.tickStallBuffersForFace(this, level, face, energyTransportSpec());
            }
            if (isTransportKindEnabled(face, DuctTransportKind.FLUID)) {
                DuctFaceLanes fl = getFaceLanes(face);
                if (DuctFluidServerTick.tryDrainFluidStallForFace(this, level, face, fl.nodeMode, fl.fluid, fluidTransportSpec())) {
                    drained = true;
                }
            }
            if (isTransportKindEnabled(face, DuctTransportKind.GAS)) {
                DuctFaceLanes gl = getFaceLanes(face);
                if (DuctGasServerTick.tryDrainGasStallForFace(this, level, face, gl.nodeMode, gl.gas, gasTransportSpec())) {
                    drained = true;
                }
            }
        }
        if (drained) {
            nonItemStallDrainScanCooldown = NON_ITEM_STALL_DRAIN_SCAN_INTERVAL;
            syncStallVisualIfNeeded();
            setChanged();
        }
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

    private boolean isOutboundShipmentStale(ServerLevel level, OutboundShipment s) {
        if (s.journeyStartGameTime <= 0L) {
            return false;
        }
        long elapsed = level.getGameTime() - s.journeyStartGameTime;
        long budget = Math.max(STALE_OUTBOUND_EXTRA_TICKS, (long) s.totalTravelTicks * 4L + 200L);
        return elapsed > budget;
    }

    /** Drops the oldest pending shipment when the distinct-kind cap blocks new pulls. */
    private void evictOldestOutboundIfKindCapFull(ServerLevel level) {
        if (distinctPendingOutboundKinds() < MAX_BLOCKED_ITEM_KINDS || outboundShipments.isEmpty()) {
            return;
        }
        int oldestIdx = 0;
        long oldestStart = Long.MAX_VALUE;
        for (int i = 0; i < outboundShipments.size(); i++) {
            OutboundShipment o = outboundShipments.get(i);
            if (o.journeyStartGameTime < oldestStart) {
                oldestStart = o.journeyStartGameTime;
                oldestIdx = i;
            }
        }
        OutboundShipment victim = outboundShipments.remove(oldestIdx);
        cancelOutboundShipment(level, victim, null);
        pushTransitSnapshotToClients(level);
    }

    private void tickOutboundShipments(ServerLevel level) {
        resolveNegativeTravelShipments(level);
        Iterator<OutboundShipment> it = outboundShipments.iterator();
        boolean transitVisualSync = false;
        boolean shipmentsDirty = false;
        while (it.hasNext()) {
            OutboundShipment s = it.next();
            if (s.stack.isEmpty()) {
                // No incoming reservation tracking.
                it.remove();
                shipmentsDirty = true;
                transitVisualSync = true;
                continue;
            }
            if (s.travelTicks > 0) {
                if (isOutboundShipmentStale(level, s)) {
                    cancelOutboundShipment(level, s, it);
                    shipmentsDirty = true;
                    transitVisualSync = true;
                    continue;
                }
                if (!resizePendingShipment(level, s, it)) {
                    shipmentsDirty = true;
                    transitVisualSync = true;
                    continue;
                }
                s.travelTicks--;
                shipmentsDirty = true;
                if (shouldPushItemTransitVisualUpdate(s)) {
                    transitVisualSync = true;
                }
                continue;
            }
            if (s.transitPhase == TransitPhase.RETURN) {
                // No incoming reservation tracking.
                if (!s.stack.isEmpty()) {
                    DuctOverflowRouting.finishReturnLegAbsorb(level, s, worldPosition);
                }
                it.remove();
                shipmentsDirty = true;
                transitVisualSync = true;
                continue;
            }
            if (!level.isLoaded(s.destDuct)) {
                continue;
            }
            boolean deliveryDeferred = false;
            int deliverPasses = 0;
            while (!deliveryDeferred && !s.stack.isEmpty() && deliverPasses < 64) {
                deliverPasses++;
                if (s.destDuct.equals(worldPosition)) {
                    NodeMode destMode = getFaceLanes(s.destFace).nodeMode;
                    boolean retrieverInbound =
                            destMode == NodeMode.RETRIEVING || destMode == NodeMode.RETRIEVING_EXTRACTION;
                    deliveryDeferred =
                            retrieverInbound
                                    ? finishRetrieverArrival(level, s, it)
                                    : finishExtractionDelivery(level, s, it);
                } else {
                    deliveryDeferred = finishExtractionDelivery(level, s, it);
                }
            }
            if (deliveryDeferred) {
                continue;
            }
            transitVisualSync = true;
        }
        if (shipmentsDirty) {
            setChanged();
        }
        if (transitVisualSync) {
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
        long edge = Math.max(0L, edgeTicksPerBlock);
        long travel = DuctPathfinder.pathTravelTicks(pathWire, edge);
        int tot = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
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
        boolean transitVisualSync = false;
        while (it.hasNext()) {
            FluidTransitShipment s = it.next();
            if (s.fluid.isEmpty()) {
                it.remove();
                transitVisualSync = true;
                continue;
            }
            if (s.travelTicks > 0) {
                if (DuctTransitTopology.firstBrokenFluidPathEdge(level, s.ductPath).isPresent()) {
                    refundBufferedFluidToSourceOrStall(level, s.sourceFace, s.fluid);
                    DuctFluidIncomingIndex.unregister(level, s.destDuct, s.fluid);
                    it.remove();
                    transitVisualSync = true;
                    continue;
                }
                if (!DuctFluidServerTick.fluidShipmentMidTransitValid(level, this, s)) {
                    refundBufferedFluidToSourceOrStall(level, s.sourceFace, s.fluid);
                    DuctFluidIncomingIndex.unregister(level, s.destDuct, s.fluid);
                    it.remove();
                    transitVisualSync = true;
                    continue;
                }
                s.travelTicks--;
                setChanged();
                if (s.travelTicks <= 0 || (Math.max(1, s.edgeTicks) > 0 && s.travelTicks % Math.max(1, s.edgeTicks) == 0)) {
                    transitVisualSync = true;
                }
                continue;
            }
            DuctFluidServerTick.tryExecutePlannedFluidTransfer(level, this, s);
            DuctFluidIncomingIndex.unregister(level, s.destDuct, s.fluid);
            it.remove();
            transitVisualSync = true;
        }
        if (transitVisualSync) {
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
        long edge = Math.max(0L, edgeTicksPerBlock);
        long travel = DuctPathfinder.pathTravelTicks(pathWire, edge);
        int tot = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
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
        boolean transitVisualSync = false;
        while (it.hasNext()) {
            GasTransitShipment s = it.next();
            if (s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
                it.remove();
                transitVisualSync = true;
                continue;
            }
            if (MekanismChemicalCompat.isEmptyStack(s.stack) || MekanismChemicalCompat.getAmount(s.stack) <= 0) {
                it.remove();
                transitVisualSync = true;
                continue;
            }

            if (s.travelTicks > 0) {
                if (DuctTransitTopology.firstBrokenGasPathEdge(level, s.ductPath).isPresent()) {
                    refundBufferedGasToSourceOrStall(level, s.sourceFace, s.stack);
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    transitVisualSync = true;
                    continue;
                }
                if (MekanismChemicalCompat.isRadioactive(s.stack)
                        && !DuctPathfinder.gasPathAllowsRadioactive(level, s.ductPath)) {
                    refundBufferedGasToSourceOrStall(level, s.sourceFace, s.stack);
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    transitVisualSync = true;
                    continue;
                }
                // Basic mid-transit validation: ensure endpoints & handlers still exist.
                if (!level.isLoaded(worldPosition) || !level.isLoaded(s.destDuct)) {
                    s.travelTicks--;
                    setChanged();
                    continue;
                }
                if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity)) {
                    refundBufferedGasToSourceOrStall(level, s.sourceFace, s.stack);
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    transitVisualSync = true;
                    continue;
                }
                if (!DuctGasServerTick.gasShipmentMidTransitValid(level, this, s)) {
                    refundBufferedGasToSourceOrStall(level, s.sourceFace, s.stack);
                    DuctGasIncomingIndex.unregister(level, s.destDuct, s.stack);
                    it.remove();
                    transitVisualSync = true;
                    continue;
                }
                s.travelTicks--;
                setChanged();
                if (s.travelTicks <= 0 || (Math.max(1, s.edgeTicks) > 0 && s.travelTicks % Math.max(1, s.edgeTicks) == 0)) {
                    transitVisualSync = true;
                }
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
                transitVisualSync = true;
            }
        }
        if (transitVisualSync) {
            syncGasTransitToClientsNow(level);
        }
    }

    private static void tryExecutePlannedGasTransfer(ServerLevel level, DuctBlockEntity sourceBe, GasTransitShipment s) {
        if (!MekanismChemicalCompat.isLoaded() || s.stack == null || MekanismChemicalCompat.isEmptyStack(s.stack)) {
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
                                    destLanes.gas.bankAllowConcatChannels(DuctFaceNode.FilterBank.RETRIEVER),
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

        Object payload = MekanismChemicalCompat.copyWithAmount(s.stack, take);
        Object left = MekanismChemicalCompat.insertExecute(destHandler, payload);
        if (!MekanismChemicalCompat.isEmptyStack(left) && MekanismChemicalCompat.getAmount(left) > 0) {
            sourceBe.refundBufferedGasToSourceOrStall(level, s.sourceFace, left);
        }
        sourceBe.setChanged();
        destBe.setChanged();
    }

    private void pushTransitSnapshotToClients(ServerLevel level) {
        level.blockEntityChanged(getBlockPos());
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    /** Client interpolates between syncs ({@link net.unfamily.another_dynamics.client.transit.DuctTransitMotion}); avoid every-tick block updates. */
    private static boolean shouldPushItemTransitVisualUpdate(OutboundShipment s) {
        if (s.travelTicks <= 0) {
            return true;
        }
        int edge = Math.max(1, s.edgeTicks);
        return s.travelTicks % edge == 0;
    }

    private static boolean faceHasItemStallContent(DuctFaceLanes lanes) {
        return DuctCapHelper.faceHasItemStallContent(lanes);
    }

    /**
     * Mid-transit resize only for {@link OutboundShipment#legacyPhysicalBuffer} saves. Modern shipments (planned or
     * extract-at-schedule) keep their scheduled count in transit — same as fluid/gas — and adapt only at delivery.
     */
    private boolean resizePendingShipment(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        if (s.transitPhase == TransitPhase.RETURN) {
            return true;
        }
        if (!s.legacyPhysicalBuffer) {
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
        // No incoming reservation tracking.

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
        List<ItemStack> prior =
                itemsAlreadyPulledFromSource
                        ? List.of()
                        : DuctIncomingIndex.snapshot(level, s.destDuct, s.destFace);
        DuctItemTransportSpec srcSpec = srcBe.itemTransportSpec();
        int batchCap = tubeOperationBatchSize(srcSpec, srcBe, s.sourceFace);
        int insLimit = Math.min(planned, Math.min(capExt, batchCap));
        int capIn;
        if (s.legacyOmniFaces) {
            capIn = DuctCapHelper.maxInsertableAfterPending(level, s.destDuct, destBe, s.stack, insLimit, prior);
        } else {
            capIn =
                    maxInsertableAfterPendingOnFaceRespectingAllowLimit(
                            level, s.destDuct, s.destFace, destBe, s.stack, insLimit, prior, false);
        }
        if (!s.legacyOmniFaces && !itemsAlreadyPulledFromSource) {
            capExt =
                    Math.min(
                            capExt,
                            capExtractableForSourceKeep(
                                    level, s.refundDuct, s.sourceFace, srcBe, s.stack, capExt));
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
        syncIncomingReservation(level, s);
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
        if (level instanceof ServerLevel sl) {
            DuctIncomingIndex.unregister(sl, s.destDuct, s.destFace, s.incomingReservationId);
        }
        if (removalIt != null) {
            removalIt.remove();
        } else {
            outboundShipments.remove(s);
        }
    }

    private void removeShipmentFromList(ServerLevel level, OutboundShipment s, @Nullable Iterator<OutboundShipment> removalIt) {
        DuctIncomingIndex.unregister(level, s.destDuct, s.destFace, s.incomingReservationId);
        if (removalIt != null) {
            removalIt.remove();
        } else {
            outboundShipments.remove(s);
        }
    }

    private static void syncIncomingReservation(ServerLevel level, OutboundShipment s) {
        if (level == null || s == null) {
            return;
        }
        DuctIncomingIndex.unregister(level, s.destDuct, s.destFace, s.incomingReservationId);
        if (s.stack != null && !s.stack.isEmpty()) {
            DuctIncomingIndex.register(level, s.destDuct, s.destFace, s.incomingReservationId, s.stack);
        }
        DuctInsertProbeCache.invalidateFace(s.destDuct, s.destFace);
    }

    /**
     * When a duct on an in-flight path is removed, stall committed stacks on the source face (owners may be other ducts
     * in the same item network).
     */
    public static void onItemDuctRemoved(ServerLevel level, BlockPos removedPos) {
        java.util.Set<BlockPos> net = itemNetworkAroundRemovedDuct(level, removedPos);
        for (BlockPos p : net) {
            if (level.getBlockEntity(p) instanceof DuctBlockEntity be) {
                be.stallInTransitThroughRemovedDuct(level, removedPos);
            }
        }
    }

    private static java.util.Set<BlockPos> itemNetworkAroundRemovedDuct(ServerLevel level, BlockPos removedPos) {
        for (Direction d : Direction.values()) {
            BlockPos n = removedPos.relative(d);
            if (DuctConnectable.isSameNetwork(level, n, DuctNetworkType.ITEM)) {
                return DuctNetworkCache.connectedDucts(level, n, DuctNetworkType.ITEM);
            }
        }
        if (DuctConnectable.isSameNetwork(level, removedPos, DuctNetworkType.ITEM)) {
            return DuctNetworkCache.connectedDucts(level, removedPos, DuctNetworkType.ITEM);
        }
        return java.util.Set.of();
    }

    private void stallInTransitThroughRemovedDuct(ServerLevel level, BlockPos removedPos) {
        Iterator<OutboundShipment> it = outboundShipments.iterator();
        boolean any = false;
        while (it.hasNext()) {
            OutboundShipment s = it.next();
            if (s.travelTicks <= 0 || s.stack.isEmpty()) {
                continue;
            }
            if (DuctTransitTopology.edgeIndexForBlockOnPath(s.ductPath, removedPos).isEmpty()) {
                continue;
            }
            cancelOutboundShipment(level, s, it);
            any = true;
        }
        if (any) {
            pushTransitSnapshotToClients(level);
            setChanged();
        }
    }

    public void dropAllStalledItems(ServerLevel level) {
        boolean any = false;
        for (Direction d : Direction.values()) {
            DuctFaceLanes lanes = getFaceLanes(d);
            for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
                ItemStack st = lanes.stalledBuffer.getStackInSlot(i);
                if (st.isEmpty()) {
                    continue;
                }
                net.minecraft.world.Containers.dropItemStack(
                        level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, st.copy());
                lanes.stalledBuffer.setStackInSlot(i, ItemStack.EMPTY);
                any = true;
            }
        }
        if (any) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            setChanged();
        }
    }

    private void refundExcessToSource(ServerLevel level, OutboundShipment s, int count) {
        ItemStack excess = s.stack.copy();
        excess.setCount(count);
        refundStackToSource(level, s, excess);
    }

    private void refundStackToSource(ServerLevel level, OutboundShipment s, ItemStack excess) {
        if (tryRefundOrStall(level, s, excess)) {
            return;
        }
    }

    /**
     * Buffer items into this face's stall slots only (never into the attached machine inventory).
     *
     * @return remainder not stored (empty when fully stalled)
     */
    public ItemStack stallOntoFace(Direction face, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        DuctFaceLanes lanes = getFaceLanes(face);
        ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(lanes.stalledBuffer, stack.copy(), false);
        if (left.isEmpty()) {
            setChanged();
            requestModelDataUpdate();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        return left;
    }

    /**
     * Stall-only refund for failed shipments: never inserts into the source machine inventory.
     *
     * @return true when the stack was fully buffered in stall slots
     */
    private boolean tryRefundOrStall(ServerLevel level, OutboundShipment s, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            return false;
        }
        ItemStack left = srcBe.stallOntoFace(s.sourceFace, stack);
        return left.isEmpty();
    }

    /**
     * Attempts to resolve stalled item stacks by re-sending along the network (never into source machine inventory).
     *
     * @return true if any stalled content changed (scheduled).
     */
    private boolean tryDrainItemStallForFace(ServerLevel level, DuctItemTransportSpec spec, Direction face, DuctFaceNode node) {
        DuctFaceLanes lanes = getFaceLanes(face);
        boolean changed = false;

        // Re-send stalled stacks toward network destinations only.
        if (!faceHasItemStallContent(lanes)) {
            return changed;
        }
        if (isFaceStalled(lanes)) {
            // Stall is full; do not schedule new pulls, but we can still schedule re-sends.
        }

        RoutingMode rm =
                lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION
                        ? node.routingModeRetriever
                        : lanes.nodeMode.isHybrid()
                                ? node.routingModeExtractor
                                : node.routingMode;
        final int rrFrozen = node.roundRobinCursor;
        boolean allowSelfOrSingle = true;
        boolean allowSelfFeed = lanes.nodeMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        Direction forbidSelfDestFace = allowSelfFeed ? null : face;
        List<DuctTargetSelector.ExtractionCandidate> candidates =
                DuctTargetSelector.listInboundDeliveryCandidatesWithoutProbe(
                        level,
                        worldPosition,
                        face,
                        rm,
                        rrFrozen,
                        node.channelLetter,
                        allowSelfOrSingle,
                        allowSelfFeed,
                        forbidSelfDestFace);
        if (candidates.isEmpty()) {
            return changed;
        }

        for (int slot = 0; slot < lanes.stalledBuffer.getSlots(); slot++) {
            ItemStack st = lanes.stalledBuffer.getStackInSlot(slot);
            if (st.isEmpty()) continue;
            int remaining = st.getCount();
            if (remaining <= 0) continue;

            // Try a few best candidates and send a batch.
            int lastSuccessfulCandIdx = 0;
            for (int candIdx = 0; candIdx < candidates.size() && candIdx < EXTRACTION_ROUTE_RETRY_CAP; candIdx++) {
                DuctTargetSelector.ExtractionCandidate cand = candidates.get(candIdx);
                BlockPos dest = cand.ductPos();
                Direction destFace = cand.face();
                if (!(level.getBlockEntity(dest) instanceof DuctBlockEntity destBe)) {
                    continue;
                }
                if (DuctInsertProbeCache.isRejected(level, dest, destFace, st)) {
                    continue;
                }
                int plannedCount = Math.min(remaining, tubeOperationBatchSize(spec, this, face));
                int destCap = maxSchedulableTowardFace(level, dest, destBe, destFace, st, plannedCount);
                if (destCap <= 0) {
                    continue;
                }

                List<BlockPos> path;
                if (dest.equals(worldPosition)) {
                    path = List.of(worldPosition);
                } else {
                    Optional<List<BlockPos>> p =
                            DuctNetworkCache.shortestPath(level, worldPosition, dest, DuctNetworkType.ITEM);
                    if (p.isEmpty()) {
                        continue;
                    }
                    path = p.get();
                }
                long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(this, face, spec);
                long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
                int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);

                ItemStack payload = st.copy();
                payload.setCount(destCap);
                OutboundShipment sh =
                        new OutboundShipment(payload, dest, destFace, travelTicks, worldPosition, face, node.channelLetter);
                sh.stack = payload.copy();
                sh.registeredIncoming = sh.stack.copy();
                sh.sourceExtractCommitted = true;
                sh.ductPath = OutboundShipment.copyPath(path);
                sh.totalTravelTicks = travelTicks;
                sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, edgeTicks);
                sh.journeyStartGameTime = level.getGameTime();
                sh.transitPhase = TransitPhase.FORWARD;
                outboundShipments.add(sh);
                DuctIncomingIndex.register(level, sh.destDuct, sh.destFace, sh.incomingReservationId, sh.stack);

                // Remove from stall slot.
                st.shrink(destCap);
                lanes.stalledBuffer.setStackInSlot(slot, st.isEmpty() ? ItemStack.EMPTY : st);
                changed = true;
                lastSuccessfulCandIdx = candIdx;
                pushTransitSnapshotToClients(level);

                if (rm == RoutingMode.ROUND_ROBIN) {
                    node.roundRobinCursor = rrFrozen + lastSuccessfulCandIdx + 1;
                }
                // Send at most one payload per tick from stall, per face.
                return true;
            }
        }

        return changed;
    }

    public void refundBufferedFluidToSourceOrStall(ServerLevel level, Direction sourceFace, FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        FluidStack left = stack.copy();
        DuctFaceLanes lanes = getFaceLanes(sourceFace);
        left = addToFluidStallBuffer(lanes, left);
        requestModelDataUpdate();
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        setChanged();
    }

    public void refundBufferedGasToSourceOrStall(ServerLevel level, Direction sourceFace, Object stack) {
        if (!MekanismChemicalCompat.isLoaded()
                || stack == null
                || MekanismChemicalCompat.isEmptyStack(stack)
                || MekanismChemicalCompat.getAmount(stack) <= 0) {
            return;
        }
        Object left = stack;
        DuctFaceLanes lanes = getFaceLanes(sourceFace);
        left = addToGasStallBuffer(level, lanes, left);
        requestModelDataUpdate();
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        setChanged();
    }

    private static FluidStack addToFluidStallBuffer(DuctFaceLanes lanes, FluidStack stack) {
        if (lanes == null || stack == null || stack.isEmpty()) {
            return FluidStack.EMPTY;
        }
        FluidStack left = stack.copy();
        for (int i = 0; i < lanes.stalledFluids.length && !left.isEmpty(); i++) {
            FluidStack cur = lanes.stalledFluids[i];
            if (cur == null || cur.isEmpty()) {
                lanes.stalledFluids[i] = left.copy();
                return FluidStack.EMPTY;
            }
            if (cur.isFluidEqual(left) && cur.getAmount() < Integer.MAX_VALUE) {
                int canAdd = Integer.MAX_VALUE - cur.getAmount();
                int add = Math.min(canAdd, left.getAmount());
                if (add > 0) {
                    lanes.stalledFluids[i].grow(add);
                    left.shrink(add);
                }
            }
        }
        return left;
    }

    private static Object addToGasStallBuffer(ServerLevel level, DuctFaceLanes lanes, Object stack) {
        if (!MekanismChemicalCompat.isLoaded()
                || lanes == null
                || stack == null
                || MekanismChemicalCompat.isEmptyStack(stack)
                || MekanismChemicalCompat.getAmount(stack) <= 0) {
            return MekanismChemicalCompat.emptyStack();
        }
        Object left = stack;
        HolderLookup.Provider regs = level.registryAccess();
        for (int i = 0; i < lanes.stalledGas.length && !MekanismChemicalCompat.isEmptyStack(left); i++) {
            if (lanes.stalledGas[i] == null || lanes.stalledGas[i].isEmpty()) {
                CompoundTag tag = new CompoundTag();
                MekanismChemicalCompat.saveGasStackToTag(left, tag);
                if (!tag.isEmpty()) {
                    lanes.stalledGas[i] = tag;
                    return MekanismChemicalCompat.emptyStack();
                }
                return left;
            }
            Object cur = MekanismChemicalCompat.loadGasStackFromTag(lanes.stalledGas[i], regs);
            if (MekanismChemicalCompat.isEmptyStack(cur)) {
                CompoundTag tag = new CompoundTag();
                MekanismChemicalCompat.saveGasStackToTag(left, tag);
                if (!tag.isEmpty()) {
                    lanes.stalledGas[i] = tag;
                    return MekanismChemicalCompat.emptyStack();
                }
                return left;
            }
            String cid = MekanismChemicalCompat.getTypeRegistryName(cur);
            String lid = MekanismChemicalCompat.getTypeRegistryName(left);
            if (cid != null && cid.equals(lid)) {
                long curAmt = MekanismChemicalCompat.getAmount(cur);
                long leftAmt = MekanismChemicalCompat.getAmount(left);
                long add = Math.min(leftAmt, Long.MAX_VALUE - curAmt);
                if (add > 0) {
                    Object merged = MekanismChemicalCompat.copyWithAmount(left, curAmt + add);
                    CompoundTag tag = new CompoundTag();
                    MekanismChemicalCompat.saveGasStackToTag(merged, tag);
                    lanes.stalledGas[i] = tag;
                    left = MekanismChemicalCompat.copyWithAmount(left, leftAmt - add);
                }
            }
        }
        return left;
    }

    public boolean hasAnyStallOnFace(Direction face) {
        return faceHasBufferedContent(face);
    }

    /** Stalled fluid or gas on this face (not items / energy / heat). */
    public boolean faceHasStalledFluidOrGas(Direction face) {
        DuctFaceLanes lanes = getFaceLanes(face);
        return hasStalledFluidInLanes(lanes) || hasStalledGasInLanes(lanes);
    }

    /** {@code true} when the held stack can receive stalled fluid (NeoForge) or gas (Mekanism). */
    public boolean heldItemCanExtractStalledMedia(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (FluidUtil.getFluidHandler(stack).isPresent()) {
            return true;
        }
        return MekanismChemicalCompat.isLoaded() && MekanismChemicalCompat.getChemicalHandlerItem(stack) != null;
    }

    /**
     * Transfer stalled fluid/gas from a face into the held tank, bucket, or cell (partial fills supported).
     *
     * @return {@code true} if any amount was moved into the held container.
     */
    public boolean tryExtractStalledMediaToHand(
            ServerLevel level, Direction face, Player player, InteractionHand hand) {
        DuctFaceLanes lanes = getFaceLanes(face);
        boolean fluid = tryClearFluidBuffer(level, lanes, player, hand, false);
        boolean gas = tryClearGasBuffer(level, lanes, player, hand, false);
        if (fluid || gas) {
            requestModelDataUpdate();
            syncStallVisualIfNeeded();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            setChanged();
        }
        return fluid || gas;
    }

    /** Any physical stall/buffer on this face (independent of redstone / transport toggles). */
    public boolean faceHasBufferedContent(Direction face) {
        DuctFaceLanes lanes = getFaceLanes(face);
        for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
            if (!lanes.stalledBuffer.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        for (var fs : lanes.stalledFluids) {
            if (fs != null && !fs.isEmpty() && fs.getAmount() > 0) {
                return true;
            }
        }
        for (var g : lanes.stalledGas) {
            if (g != null && !g.isEmpty() && (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount"))) {
                return true;
            }
        }
        if (lanes.energyInputBufferFe > 0 || lanes.energyOutputBufferFe > 0) {
            return true;
        }
        return lanes.stalledEnergyCount > 0 || lanes.stalledHeatCount > 0;
    }

    /**
     * Whether this face currently shows a live storage node with the active (non-disabled) icon row — same gates as
     * {@link #computePackedNodeIcons()}.
     */
    public boolean faceShowsActiveStorageNode(Direction face) {
        int bit = 1 << face.ordinal();
        if ((getVisualStorageMask() & bit) == 0) {
            return false;
        }
        if ((getUserDisconnectedFaceMask() & bit) != 0) {
            return false;
        }
        return isFaceTransportEnabled(face) && faceShowsTransportIconForEnabledKinds(face);
    }

    /**
     * Whether {@code node_buffer.png} should render on this face (item / fluid / gas stalled shipments only).
     * Energy and heat use instant transfer and internal buffers — no stall overlay on the node model.
     */
    public boolean faceHasVisibleStall(Direction face) {
        if (!faceShowsActiveStorageNode(face)) {
            return false;
        }
        DuctFaceLanes lanes = getFaceLanes(face);
        if (isTransportKindEnabled(face, DuctTransportKind.ITEM)) {
            for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
                if (!lanes.stalledBuffer.getStackInSlot(i).isEmpty()) {
                    return true;
                }
            }
        }
        if (isTransportKindEnabled(face, DuctTransportKind.FLUID)) {
            for (var fs : lanes.stalledFluids) {
                if (fs != null && !fs.isEmpty() && fs.getAmount() > 0) {
                    return true;
                }
            }
        }
        if (isTransportKindEnabled(face, DuctTransportKind.GAS)) {
            for (var g : lanes.stalledGas) {
                if (g != null && !g.isEmpty() && (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Sync stall overlay + node icons after buffer/stall content changes. */
    public void syncStallVisualIfNeeded() {
        syncVisualGeometryToClients();
    }

    /**
     * Shift+right-click on a node face: clear any buffered/stalled content on that face.
     *
     * <p>Items drop, energy/heat delete immediately; fluids/gases try to fill held containers, otherwise arm empty-hand
     * destruction for 5 seconds.</p>
     *
     * @return true if an action was performed (consume interaction).
     */
    public boolean tryShiftClearStalledOnFace(ServerLevel level, Direction face, Player player, InteractionHand hand) {
        DuctFaceLanes lanes = getFaceLanes(face);
        boolean didAnything = false;

        // Items: drop everything immediately.
        for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
            ItemStack st = lanes.stalledBuffer.getStackInSlot(i);
            if (st.isEmpty()) {
                continue;
            }
            net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, st.copy());
            lanes.stalledBuffer.setStackInSlot(i, ItemStack.EMPTY);
            didAnything = true;
        }

        // Energy/Heat: do not clear on shift-click (buffering is not user-clearable and never refunds to source).

        // Fluids / gas: fill held tank or bucket when possible; otherwise arm empty-hand destruction.
        didAnything |= tryClearFluidBuffer(level, lanes, player, hand, true);
        didAnything |= tryClearGasBuffer(level, lanes, player, hand, true);

        for (ItemStack overflow : overflowBuffer.viewStacks()) {
            if (!overflow.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(
                        level,
                        worldPosition.getX() + 0.5,
                        worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5,
                        overflow.copy());
                didAnything = true;
            }
        }
        if (!overflowBuffer.viewStacks().isEmpty()) {
            overflowBuffer.clear();
        }
        itemStallDrainScanCooldown = 0;

        if (didAnything) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            setChanged();
        }
        return didAnything;
    }

    private static boolean hasStalledFluidInLanes(DuctFaceLanes lanes) {
        for (FluidStack fs : lanes.stalledFluids) {
            if (fs != null && !fs.isEmpty() && fs.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStalledGasInLanes(DuctFaceLanes lanes) {
        for (var g : lanes.stalledGas) {
            if (g != null && !g.isEmpty() && (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param allowEmptyHandDestroy when {@code true} (shift-click), empty-hand can arm then destroy buffered fluid.
     */
    private boolean tryClearFluidBuffer(
            ServerLevel level, DuctFaceLanes lanes, Player player, InteractionHand hand, boolean allowEmptyHandDestroy) {
        if (!hasStalledFluidInLanes(lanes)) {
            lanes.armedClearFluidUntilGameTime = 0L;
            return false;
        }

        if (tryFillHeldFromStalledFluids(player, hand, lanes)) {
            lanes.armedClearFluidUntilGameTime = 0L;
            return true;
        }

        if (!allowEmptyHandDestroy) {
            return false;
        }

        ItemStack held = player.getItemInHand(hand);
        long now = level.getGameTime();
        if (held.isEmpty() && lanes.armedClearFluidUntilGameTime > 0 && now <= lanes.armedClearFluidUntilGameTime) {
            for (int i = 0; i < lanes.stalledFluids.length; i++) {
                lanes.stalledFluids[i] = FluidStack.EMPTY;
            }
            lanes.armedClearFluidUntilGameTime = 0L;
            return true;
        }

        lanes.armedClearFluidUntilGameTime = now + 100L;
        player.displayClientMessage(
                Component.literal("Buffered fluid: use a tank/bucket to extract, or empty-hand within 5s to destroy."),
                true);
        return true;
    }

    /**
     * @param allowEmptyHandDestroy when {@code true} (shift-click), empty-hand can arm then destroy buffered gas.
     */
    private boolean tryClearGasBuffer(
            ServerLevel level, DuctFaceLanes lanes, Player player, InteractionHand hand, boolean allowEmptyHandDestroy) {
        if (!hasStalledGasInLanes(lanes)) {
            lanes.armedClearGasUntilGameTime = 0L;
            return false;
        }

        if (tryFillHeldFromStalledGas(level, player, hand, lanes)) {
            lanes.armedClearGasUntilGameTime = 0L;
            return true;
        }

        if (!allowEmptyHandDestroy) {
            return false;
        }

        ItemStack held = player.getItemInHand(hand);
        long now = level.getGameTime();
        if (held.isEmpty() && lanes.armedClearGasUntilGameTime > 0 && now <= lanes.armedClearGasUntilGameTime) {
            for (int i = 0; i < lanes.stalledGas.length; i++) {
                lanes.stalledGas[i] = null;
            }
            lanes.armedClearGasUntilGameTime = 0L;
            return true;
        }

        lanes.armedClearGasUntilGameTime = now + 100L;
        player.displayClientMessage(
                Component.literal("Buffered gas: use a tank/cell to extract, or empty-hand within 5s to destroy."),
                true);
        return true;
    }

    /**
     * Moves as much stalled fluid as fits into the held {@link IFluidHandlerItem} (buckets, tanks, etc.).
     */
    private static boolean tryFillHeldFromStalledFluids(Player player, InteractionHand hand, DuctFaceLanes lanes) {
        boolean filledSomething = false;
        for (int i = 0; i < lanes.stalledFluids.length; i++) {
            while (true) {
                FluidStack stalled = lanes.stalledFluids[i];
                if (stalled == null || stalled.isEmpty() || stalled.getAmount() <= 0) {
                    break;
                }
                ItemStack held = player.getItemInHand(hand);
                if (held.isEmpty()) {
                    break;
                }
                IFluidHandlerItem handler = FluidUtil.getFluidHandler(held).orElse(null);
                if (handler == null) {
                    break;
                }
                int filled = handler.fill(stalled.copy(), IFluidHandler.FluidAction.EXECUTE);
                if (filled <= 0) {
                    break;
                }
                stalled.shrink(filled);
                if (stalled.isEmpty()) {
                    lanes.stalledFluids[i] = FluidStack.EMPTY;
                }
                applyFilledContainerToPlayer(player, hand, held, handler.getContainer());
                filledSomething = true;
            }
        }
        return filledSomething;
    }

    /** Mekanism chemical tanks / cells on the held stack. */
    private static boolean tryFillHeldFromStalledGas(
            ServerLevel level, Player player, InteractionHand hand, DuctFaceLanes lanes) {
        boolean filledSomething = false;
        HolderLookup.Provider regs = level.registryAccess();
        for (int i = 0; i < lanes.stalledGas.length; i++) {
            CompoundTag tag = lanes.stalledGas[i];
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            while (tag != null && !tag.isEmpty()) {
                ItemStack held = player.getItemInHand(hand);
                Object itemHandler = MekanismChemicalCompat.getChemicalHandlerItem(held);
                if (itemHandler == null) {
                    break;
                }
                Object payload = MekanismChemicalCompat.loadGasStackFromTag(tag, regs);
                if (payload == null || MekanismChemicalCompat.isEmptyStack(payload) || MekanismChemicalCompat.getAmount(payload) <= 0) {
                    lanes.stalledGas[i] = null;
                    tag = null;
                    break;
                }
                Object left = MekanismChemicalCompat.insertExecute(itemHandler, payload);
                long leftAmt = MekanismChemicalCompat.isEmptyStack(left) ? 0L : MekanismChemicalCompat.getAmount(left);
                long before = MekanismChemicalCompat.getAmount(payload);
                long inserted = Math.max(0L, before - leftAmt);
                if (inserted <= 0) {
                    break;
                }
                filledSomething = true;
                ItemStack after = MekanismChemicalCompat.getContainerItem(itemHandler, held);
                applyFilledContainerToPlayer(player, hand, held, after);
                if (leftAmt <= 0) {
                    lanes.stalledGas[i] = null;
                    tag = null;
                } else {
                    CompoundTag nt = new CompoundTag();
                    MekanismChemicalCompat.saveGasStackToTag(left, nt);
                    lanes.stalledGas[i] = nt.isEmpty() ? null : nt;
                    tag = lanes.stalledGas[i];
                }
            }
        }
        return filledSomething;
    }

    private static void applyFilledContainerToPlayer(Player player, InteractionHand hand, ItemStack before, ItemStack after) {
        if (before.getCount() == 1) {
            player.setItemInHand(hand, after);
            return;
        }
        before.shrink(1);
        if (!player.getInventory().add(after)) {
            player.drop(after, false);
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
                s.stack = ItemStack.EMPTY;
                removeShipmentFromList(level, s, it);
                setChanged();
                return false;
            }
            ItemStack chunk = s.stack.copy();
            chunk.setCount(take);
            if (!s.legacyOmniFaces) {
                List<ItemStack> priorR = List.of();
                int maxIns =
                        maxInsertableAfterPendingOnFaceRespectingAllowLimit(
                                level, s.destDuct, s.destFace, destBe, chunk, chunk.getCount(), priorR, false);
                take = Math.min(take, maxIns);
                if (take <= 0) {
                    recordDestInsertRejected(level, s.destDuct, s.destFace, chunk);
                    cancelOutboundShipment(level, s, it);
                    return false;
                }
                chunk.setCount(take);
            }
            // No incoming reservation tracking.
            NodeMode dm = destBe.getFaceLanes(s.destFace).nodeMode;
            if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                    && !destBe.passesItemFilters(s.destFace, chunk, level, DuctFaceNode.FilterBank.FILTER)) {
                recordDestInsertRejected(level, s.destDuct, s.destFace, chunk);
                cancelOutboundShipment(level, s, it);
                return false;
            }
            ItemStack toInsert = chunk.copy();
            ItemStack remainder =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, toInsert)
                            : DuctCapHelper.insertIntoFace(level, s.destDuct, s.destFace, toInsert);
            int inserted = DuctCapHelper.countAccepted(toInsert, remainder);
            if (inserted <= 0) {
                recordDestInsertRejected(level, s.destDuct, s.destFace, chunk);
                cancelOutboundShipment(level, s, it);
                return false;
            }
            DuctInsertProbeCache.cacheAccept(level, s.destDuct, s.destFace, chunk, true);
            if (inserted < planned) {
                s.stack.shrink(inserted);
                s.registeredIncoming = s.stack.copy();
                syncIncomingReservation(level, s);
            } else {
                s.stack = ItemStack.EMPTY;
                removeShipmentFromList(level, s, it);
            }
            if (!remainder.isEmpty()) {
                DuctOverflowRouting.absorbExtractionDestRemainder(level, s, destBe, remainder, worldPosition);
            }
            setChanged();
            return false;
        }

        // No incoming reservation tracking.
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            if (ductBlockPresentButBlockEntityPending(level, s.refundDuct)) {
                // No incoming reservation tracking.
                return true;
            }
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(srcBe.getFaceNode(s.sourceFace).channelLetter, s)) {
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
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
        List<ItemStack> prior = List.of();
        int capIn =
                s.legacyOmniFaces
                        ? DuctCapHelper.maxInsertableAfterPending(
                                level, s.destDuct, destBe, s.stack, insLimit, prior)
                        : maxInsertableAfterPendingOnFaceRespectingAllowLimit(
                                level, s.destDuct, s.destFace, destBe, s.stack, insLimit, prior, false);
        if (!s.legacyOmniFaces) {
            capExt =
                    Math.min(
                            capExt,
                            capExtractableForSourceKeep(
                                    level, s.refundDuct, s.sourceFace, srcBe, s.stack, capExt));
        }
        int n = Math.min(planned, Math.min(capExt, capIn));
        n = Math.min(n, moduleCap);
        if (n <= 0) {
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
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
                // No incoming reservation tracking.
                return true;
            }
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        NodeMode dm2 = destBe.getFaceLanes(s.destFace).nodeMode;
        if ((dm2 == NodeMode.FILTERING_INSERTION || dm2 == NodeMode.EXTRACTION_FILTERING)
                && !destBe.passesItemFilters(s.destFace, extracted, level, DuctFaceNode.FilterBank.FILTER)) {
            DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, extracted, worldPosition);
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        ItemStack toInsert = extracted.copy();
        ItemStack remainder =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, toInsert)
                        : DuctCapHelper.insertIntoFace(level, s.destDuct, s.destFace, toInsert);
        int inserted = DuctCapHelper.countAccepted(toInsert, remainder);
        if (inserted <= 0) {
            recordDestInsertRejected(level, s.destDuct, s.destFace, extracted);
            if (!tryRefundOrStall(level, s, extracted)) {
                DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, extracted, worldPosition);
            }
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        DuctInsertProbeCache.cacheAccept(level, s.destDuct, s.destFace, extracted, true);
        if (inserted < planned) {
            s.stack.shrink(inserted);
            s.registeredIncoming = s.stack.copy();
            syncIncomingReservation(level, s);
        } else {
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
        }
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
                s.stack = ItemStack.EMPTY;
                removeShipmentFromList(level, s, it);
                setChanged();
                return false;
            }
            ItemStack chunk = s.stack.copy();
            chunk.setCount(take);
            if (!s.legacyOmniFaces) {
                List<ItemStack> priorR = List.of();
                int maxIns =
                        maxInsertableAfterPendingOnFaceRespectingAllowLimit(
                                level, worldPosition, s.destFace, this, chunk, chunk.getCount(), priorR, false);
                take = Math.min(take, maxIns);
                if (take <= 0) {
                    recordDestInsertRejected(level, s.destDuct, s.destFace, chunk);
                    cancelOutboundShipment(level, s, it);
                    return false;
                }
                chunk.setCount(take);
            }
            // No incoming reservation tracking.
            NodeMode sm = getFaceLanes(s.destFace).nodeMode;
            if ((sm == NodeMode.FILTERING_INSERTION || sm == NodeMode.EXTRACTION_FILTERING)
                    && !passesItemFilters(s.destFace, chunk, level, DuctFaceNode.FilterBank.FILTER)) {
                cancelOutboundShipment(level, s, it);
                return false;
            }
            ItemStack toInsert = chunk.copy();
            ItemStack remainder =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, toInsert)
                            : DuctCapHelper.insertIntoFace(level, worldPosition, s.destFace, toInsert);
            int inserted = DuctCapHelper.countAccepted(toInsert, remainder);
            if (inserted <= 0) {
                recordDestInsertRejected(level, s.destDuct, s.destFace, chunk);
                cancelOutboundShipment(level, s, it);
                return false;
            }
            DuctInsertProbeCache.cacheAccept(level, worldPosition, s.destFace, chunk, true);
            if (inserted < planned) {
                s.stack.shrink(inserted);
                s.registeredIncoming = s.stack.copy();
                syncIncomingReservation(level, s);
            } else {
                s.stack = ItemStack.EMPTY;
                removeShipmentFromList(level, s, it);
            }
            if (!remainder.isEmpty()) {
                DuctOverflowRouting.absorbRetrieverDestRemainder(level, s, this, donorBe, remainder, worldPosition);
            }
            setChanged();
            return false;
        }

        // No incoming reservation tracking.
        DuctItemTransportSpec transportSpec = itemTransportSpec();
        int moduleCap = tubeOperationBatchSize(transportSpec, this, s.destFace);
        int planned = s.stack.getCount();
        int capExt =
                s.legacyOmniFaces
                        ? DuctCapHelper.countExtractableMatching(level, s.refundDuct, donorBe, s.stack, planned)
                        : DuctCapHelper.countExtractableMatchingOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, planned);
        int insLimit = Math.min(planned, Math.min(capExt, moduleCap));
        List<ItemStack> prior = List.of();
        int capIn =
                s.legacyOmniFaces
                        ? DuctCapHelper.maxInsertableAfterPending(
                                level, worldPosition, this, s.stack, insLimit, prior)
                        : maxInsertableAfterPendingOnFaceRespectingAllowLimit(
                                level, worldPosition, s.destFace, this, s.stack, insLimit, prior, false);
        if (!s.legacyOmniFaces) {
            capExt =
                    Math.min(
                            capExt,
                            capExtractableForSourceKeep(
                                    level, s.refundDuct, s.sourceFace, donorBe, s.stack, capExt));
        }
        int n = Math.min(planned, Math.min(capExt, capIn));
        n = Math.min(n, moduleCap);
        if (n <= 0) {
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
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
                // No incoming reservation tracking.
                return true;
            }
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        NodeMode sm2 = getFaceLanes(s.destFace).nodeMode;
        if ((sm2 == NodeMode.FILTERING_INSERTION || sm2 == NodeMode.EXTRACTION_FILTERING)
                && !passesItemFilters(s.destFace, extracted, level, DuctFaceNode.FilterBank.FILTER)) {
            DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, extracted, worldPosition);
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        ItemStack toInsert = extracted.copy();
        ItemStack remainder =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, toInsert)
                        : DuctCapHelper.insertIntoFace(level, worldPosition, s.destFace, toInsert);
        int inserted = DuctCapHelper.countAccepted(toInsert, remainder);
        if (inserted <= 0) {
            recordDestInsertRejected(level, worldPosition, s.destFace, extracted);
            if (!tryRefundOrStall(level, s, extracted)) {
                DuctOverflowRouting.tryRefundToSourceNoDrop(level, s, extracted, worldPosition);
            }
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
            setChanged();
            return false;
        }
        DuctInsertProbeCache.cacheAccept(level, worldPosition, s.destFace, extracted, true);
        if (inserted < planned) {
            s.stack.shrink(inserted);
            s.registeredIncoming = s.stack.copy();
            syncIncomingReservation(level, s);
        } else {
            s.stack = ItemStack.EMPTY;
            removeShipmentFromList(level, s, it);
        }
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
     * Like {@link #insertIntoStorageFacesRespectingInboundRedstone} but never pushes into faces whose attached
     * inventory supplies extraction/retrieve pulls — prevents refund overflow from duplicating source items.
     */
    public ItemStack tryInsertOverflowRefund(ServerLevel level, ItemStack stack) {
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
            if (faceIsItemExtractSource(lanes.nodeMode)) {
                continue;
            }
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

    /** Attached inventories on these faces supply items to the network; refunds must stay in stall/overflow, not re-enter. */
    public static boolean faceIsItemExtractSource(NodeMode mode) {
        return mode == NodeMode.EXTRACTION
                || mode == NodeMode.EXTRACTION_FILTERING
                || mode == NodeMode.RETRIEVING_EXTRACTION;
    }

    private void recordDestInsertRejected(ServerLevel level, BlockPos destDuct, Direction destFace, ItemStack template) {
        if (template.isEmpty()) {
            return;
        }
        DuctInsertProbeCache.recordReject(level, destDuct, destFace, template);
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
            ItemStack left = tryInsertOverflowRefund(level, b.copy());
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
            evictOldestOutboundIfKindCapFull(level);
            if (distinctPendingOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
                return;
            }
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return;
        }
        DuctFaceLanes faceLanes = getFaceLanes(face);
        if (isFaceStalled(faceLanes)) {
            return;
        }
        IItemHandler sourceHandler = DuctCapHelper.getHandlerOnFace(level, worldPosition, face);
        if (sourceHandler == null) {
            return;
        }
        /*
         * Try each source slot so a deny/allow on the *destination* FILTERING_INSERTION face does not wedge the
         * extractor on the first stack (see finishExtractionDelivery: invalid items were extracted then refunded).
         * Routing RR state is only committed when we actually schedule a shipment.
         */
        final int rrFrozen = node.roundRobinCursor;
        // Always allow destinations on this same duct (other faces). This prevents routing from ignoring local
        // attached storage just because the network has multiple ducts.
        boolean allowSelfOrSingle = true;
        boolean allowSelfFeed = faceLanes.nodeMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        Direction forbidSelfDestFace = allowSelfFeed ? null : face;
        RoutingMode rm =
                faceLanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION
                        ? node.routingModeRetriever
                        : faceLanes.nodeMode.isHybrid()
                                ? node.routingModeExtractor
                                : node.routingMode;
        boolean roundRobinRouting = rm == RoutingMode.ROUND_ROBIN;

        List<DuctTargetSelector.ExtractionCandidate> candidates =
                DuctTargetSelector.listExtractionDeliveryCandidatesWithoutProbe(
                        level,
                        worldPosition,
                        face,
                        rm,
                        rrFrozen,
                        node.channelLetter,
                        allowSelfOrSingle,
                        allowSelfFeed,
                        forbidSelfDestFace);
        if (candidates.isEmpty()) {
            return;
        }

        // Highest priority among candidates where a valid probe was found AND the destination was
        // pending-full (destCap==0 due to in-flight reservations). Any candidate with a strictly
        // lower priority is skipped to avoid bypassing a "temporarily busy" destination.
        int highestPendingFullPriority = Integer.MIN_VALUE;

        int lastSuccessfulCandIdx = 0;
        for (int candIdx = 0; candIdx < candidates.size() && candIdx < EXTRACTION_ROUTE_RETRY_CAP; candIdx++) {
            DuctTargetSelector.ExtractionCandidate cand = candidates.get(candIdx);

            // Don't fall through to a lower-priority destination when a higher-priority one had a
            // valid item probe (item type fits) but was temporarily full due to in-flight reservations.
            if (cand.priority() < highestPendingFullPriority) {
                break;
            }

            BlockPos dest = cand.ductPos();
            Direction destFace = cand.face();
            if (!(level.getBlockEntity(dest) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            NodeMode destMode = destBe.getFaceLanes(destFace).nodeMode;

            // For the best destination (priority+routing), find a compatible source item.
            ItemStack probe = ItemStack.EMPTY;
            String skipReason = "source_empty";
            ItemStack skipItem = ItemStack.EMPTY;
            for (int slot = 0; slot < sourceHandler.getSlots(); slot++) {
                if (!DuctHandlerSlotSemantics.canExtractFromSlot(sourceHandler, slot)) {
                    continue;
                }
                ItemStack p = sourceHandler.extractItem(slot, 1, true);
                if (p.isEmpty()) { continue; }
                skipReason = "extractor_filter"; skipItem = p;
                if (!passesItemFilters(face, p, level, DuctFaceNode.FilterBank.EXTRACTOR)) { continue; }
                skipReason = "can_insert"; skipItem = p;
                if (!DuctCapHelper.canInsertIntoFace(level, dest, destFace, p)) { continue; }
                skipReason = "dest_filter"; skipItem = p;
                if (destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING) {
                    if (!destBe.passesItemFilters(destFace, p, level, DuctFaceNode.FilterBank.FILTER)) { continue; }
                }
                skipReason = "keep"; skipItem = p;
                // Keep-in-storage on the source must not wedge the extractor on the first matching stack.
                // If this item cannot be extracted at all due to Keep, try the next candidate item.
                if (capExtractableForSourceKeep(level, worldPosition, face, this, p, 1) <= 0) {
                    continue;
                }
                probe = p;
                break;
            }
            if (probe.isEmpty()) {
                continue;
            }

            List<BlockPos> path;
            if (dest.equals(worldPosition)) {
                path = List.of(worldPosition);
            } else {
                Optional<List<BlockPos>> p =
                        DuctNetworkCache.shortestPath(level, worldPosition, dest, DuctNetworkType.ITEM);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }

            lastSuccessfulCandIdx = candIdx;
            int tubeBatch = tubeOperationBatchSize(spec, this, face);
            long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(this, face, spec);
            long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
            int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
            int pendingSum = pendingOutboundItemCountFromSource(worldPosition, face, probe);
            int countCap = Math.min(Integer.MAX_VALUE, Math.max(tubeBatch, pendingSum) + tubeBatch);
            int availTotal =
                    DuctCapHelper.countExtractableMatchingOnFace(
                            level, worldPosition, face, probe, countCap);
            int remainingInStorage = availTotal - pendingSum;
            if (remainingInStorage <= 0) {
                continue;
            }
            int plannedCount = Math.min(tubeBatch, remainingInStorage);
            plannedCount =
                    Math.min(
                            plannedCount,
                            capExtractableForSourceKeep(
                                    level, worldPosition, face, this, probe, plannedCount));
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack planned = probe.copy();
            planned.setCount(plannedCount);
            int destCap = maxSchedulableTowardFace(level, dest, destBe, destFace, planned, plannedCount);
            if (destCap <= 0) {
                if (!DuctIncomingIndex.snapshot(level, dest, destFace).isEmpty()
                        && cand.priority() > highestPendingFullPriority) {
                    highestPendingFullPriority = cand.priority();
                }
                continue;
            }
            if (destCap < plannedCount) {
                plannedCount = destCap;
                planned.setCount(plannedCount);
            }
            ItemStack extracted =
                    DuctCapHelper.extractMatchingUpToOnFace(
                            level, worldPosition, face, planned, plannedCount);
            if (extracted.isEmpty()) {
                continue;
            }
            OutboundShipment sh =
                    new OutboundShipment(
                            extracted, dest, destFace, travelTicks, worldPosition, face, node.channelLetter);
            sh.sourceExtractCommitted = true;
            sh.ductPath = OutboundShipment.copyPath(path);
            sh.totalTravelTicks = travelTicks;
            sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, edgeTicks);
            sh.journeyStartGameTime = level.getGameTime();
            sh.transitPhase = TransitPhase.FORWARD;
            outboundShipments.add(sh);
            DuctIncomingIndex.register(level, sh.destDuct, sh.destFace, sh.incomingReservationId, sh.stack);
            setChanged();
            pushTransitSnapshotToClients(level);
            requestModelDataUpdate();
            if (roundRobinRouting) {
                node.roundRobinCursor = rrFrozen + lastSuccessfulCandIdx + 1;
            }
            return;
        }
    }

    private void tickRetrieverPullForFace(ServerLevel level, DuctItemTransportSpec spec, Direction retrieverFace, DuctFaceNode node) {
        if (distinctPendingOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
            evictOldestOutboundIfKindCapFull(level);
            if (distinctPendingOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
                return;
            }
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return;
        }
        DuctFaceLanes retrieverLanes = getFaceLanes(retrieverFace);
        if (isFaceStalled(retrieverLanes)) {
            return;
        }
        final int rrFrozen = node.roundRobinCursor;
        RoutingMode rm =
                retrieverLanes.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
        boolean roundRobinRetriever = rm == RoutingMode.ROUND_ROBIN;
        List<DuctTargetSelector.DonorCandidate> donors =
                DuctTargetSelector.listRetrievingDonorCandidates(
                        level,
                        worldPosition,
                        retrieverFace,
                        rm,
                        rrFrozen,
                        node.channelLetter,
                        true,
                        retrieverFace);
        if (donors.isEmpty()) {
            return;
        }
        // Highest priority among donors where the donor had items AND all were pending reservation.
        // Prevents falling through to lower-priority donors when a better one is temporarily drained.
        int highestPendingDrainedPriority = Integer.MIN_VALUE;

        for (int donorIdx = 0; donorIdx < donors.size() && donorIdx < EXTRACTION_ROUTE_RETRY_CAP; donorIdx++) {
            DuctTargetSelector.DonorCandidate donorCand = donors.get(donorIdx);

            // Priority barrier: don't pull from a lower-priority donor when a higher-priority one
            // had items but all were already reserved by other in-flight retrievals.
            if (donorCand.priority() < highestPendingDrainedPriority) {
                break;
            }

            BlockPos donor = donorCand.ductPos();
            Direction donorFace = donorCand.face();
            if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            List<BlockPos> path;
            if (donor.equals(worldPosition)) {
                path = List.of(worldPosition);
            } else {
                Optional<List<BlockPos>> p =
                        DuctNetworkCache.shortestPath(level, donor, worldPosition, DuctNetworkType.ITEM);
                if (p.isEmpty()) {
                    continue;
                }
                path = p.get();
            }
            if (tryRetrieverPullFromDonorStall(
                    level,
                    spec,
                    retrieverFace,
                    node,
                    donor,
                    donorFace,
                    donorBe,
                    path,
                    donorCand,
                    rrFrozen,
                    donorIdx,
                    roundRobinRetriever)) {
                return;
            }
            IItemHandler donorHandler = DuctCapHelper.getHandlerOnFace(level, donor, donorFace);
            if (donorHandler == null || isFaceStalled(donorBe.getFaceLanes(donorFace))) {
                continue;
            }
            int slotCount = donorHandler.getSlots();
            int slotStart =
                    slotCount > 0 ? Math.floorMod(node.retrieverPullSlotCursor, slotCount) : 0;
            for (int si = 0; si < slotCount; si++) {
                int slot = (slotStart + si) % slotCount;
                if (!DuctHandlerSlotSemantics.canExtractFromSlot(donorHandler, slot)) {
                    continue;
                }
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
                // Do not wedge retriever on an item blocked by donor Keep-in-storage.
                if (capExtractableForSourceKeep(level, donor, donorFace, donorBe, probe, 1) <= 0) {
                    continue;
                }
                if (overflowBuffer.isSchedulingUnavailableForNewPulls()
                        || getOverflowBuffer().isSchedulingUnavailableForNewPulls()
                        || !DuctRedstoneLogic.isFaceTransportActive(
                                level, worldPosition, getFaceLanes(retrieverFace).redstoneMode)) {
                    continue;
                }
                int tubeBatch = tubeOperationBatchSize(spec, this, retrieverFace);
                long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(this, retrieverFace, spec);
                long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
                int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
                int pendingSum = donorBe.pendingOutboundItemCountFromSource(donor, donorFace, probe);
                int countCap = Math.min(Integer.MAX_VALUE, Math.max(tubeBatch, pendingSum) + tubeBatch);
                int availTotal =
                        DuctCapHelper.countExtractableMatchingOnFace(
                                level, donor, donorFace, probe, countCap);
                int remainingInStorage = availTotal - pendingSum;
                if (remainingInStorage <= 0) {
                    if (donorCand.priority() > highestPendingDrainedPriority) {
                        highestPendingDrainedPriority = donorCand.priority();
                    }
                    continue;
                }
                int plannedCount = Math.min(tubeBatch, remainingInStorage);
                plannedCount =
                        Math.min(
                                plannedCount,
                                capExtractableForSourceKeep(
                                        level, donor, donorFace, donorBe, probe, plannedCount));
                if (plannedCount <= 0) {
                    continue;
                }
                ItemStack planned = probe.copy();
                planned.setCount(plannedCount);
                int destCap =
                        maxSchedulableTowardFace(
                                level, worldPosition, this, retrieverFace, planned, plannedCount, true);
                if (destCap <= 0) {
                    continue;
                }
                if (destCap < plannedCount) {
                    plannedCount = destCap;
                    planned.setCount(plannedCount);
                }
                ItemStack extracted =
                        DuctCapHelper.extractMatchingUpToOnFace(
                                level, donor, donorFace, planned, plannedCount);
                if (extracted.isEmpty()) {
                    continue;
                }
                OutboundShipment sh =
                        new OutboundShipment(
                                extracted,
                                worldPosition,
                                retrieverFace,
                                travelTicks,
                                donor,
                                donorFace,
                                node.channelLetter);
                sh.sourceExtractCommitted = true;
                sh.ductPath = OutboundShipment.copyPath(path);
                sh.totalTravelTicks = travelTicks;
                sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, edgeTicks);
                sh.journeyStartGameTime = level.getGameTime();
                sh.transitPhase = TransitPhase.FORWARD;
                outboundShipments.add(sh);
                DuctIncomingIndex.register(
                        level, sh.destDuct, sh.destFace, sh.incomingReservationId, sh.stack);
                donorBe.setChanged();
                setChanged();
                pushTransitSnapshotToClients(level);
                requestModelDataUpdate();
                node.retrieverPullSlotCursor = slot + 1;
                if (roundRobinRetriever) {
                    node.roundRobinCursor = rrFrozen + donorIdx + 1;
                }
                return;
            }
        }
    }

    private static boolean isFaceStalled(DuctFaceLanes lanes) {
        int filled = 0;
        for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
            if (!lanes.stalledBuffer.getStackInSlot(i).isEmpty()) {
                filled++;
            }
        }
        return filled >= lanes.stalledBuffer.getSlots();
    }

    /** Pull one matching stack from a face stall buffer (simulate then extract). */
    private static ItemStack extractMatchingFromStallBuffer(DuctFaceLanes lanes, ItemStack template, int max) {
        if (max <= 0) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < lanes.stalledBuffer.getSlots(); i++) {
            ItemStack st = lanes.stalledBuffer.getStackInSlot(i);
            if (st.isEmpty()) {
                continue;
            }
            if (!template.isEmpty() && !ItemStack.isSameItemSameComponents(st, template)) {
                continue;
            }
            int take = Math.min(max, st.getCount());
            ItemStack out = st.copyWithCount(take);
            lanes.stalledBuffer.setStackInSlot(i, st.getCount() > take ? st.copyWithCount(st.getCount() - take) : ItemStack.EMPTY);
            return out;
        }
        return ItemStack.EMPTY;
    }

    private boolean tryRetrieverPullFromDonorStall(
            ServerLevel level,
            DuctItemTransportSpec spec,
            Direction retrieverFace,
            DuctFaceNode node,
            BlockPos donor,
            Direction donorFace,
            DuctBlockEntity donorBe,
            List<BlockPos> path,
            DuctTargetSelector.DonorCandidate donorCand,
            int rrFrozen,
            int donorIdx,
            boolean roundRobinRetriever) {
        DuctFaceLanes donorLanes = donorBe.getFaceLanes(donorFace);
        for (int si = 0; si < donorLanes.stalledBuffer.getSlots(); si++) {
            ItemStack probe = donorLanes.stalledBuffer.getStackInSlot(si);
            if (probe.isEmpty()) {
                continue;
            }
            if (!passesItemFilters(retrieverFace, probe, level, DuctFaceNode.FilterBank.RETRIEVER)) {
                continue;
            }
            if (!donorBe.passesItemFilters(donorFace, probe, level, DuctFaceNode.FilterBank.FILTER)) {
                continue;
            }
            // Stall buffer is already off the machine inventory; keep caps apply only to live handler extracts.
            int tubeBatch = tubeOperationBatchSize(spec, this, retrieverFace);
            int plannedCount = Math.min(tubeBatch, probe.getCount());
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack template = probe.copy();
            template.setCount(plannedCount);
            List<ItemStack> prior = DuctIncomingIndex.snapshot(level, worldPosition, retrieverFace);
            int capIn =
                    maxInsertableAfterPendingOnFaceRespectingAllowLimit(
                            level, worldPosition, retrieverFace, this, template, plannedCount, prior, true);
            plannedCount = Math.min(plannedCount, capIn);
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack planned = probe.copyWithCount(plannedCount);
            if (overflowBuffer.isSchedulingUnavailableForNewPulls()
                    || getOverflowBuffer().isSchedulingUnavailableForNewPulls()
                    || !DuctRedstoneLogic.isFaceTransportActive(level, worldPosition, getFaceLanes(retrieverFace).redstoneMode)) {
                continue;
            }
            ItemStack extracted = extractMatchingFromStallBuffer(donorLanes, planned, plannedCount);
            if (extracted.isEmpty()) {
                continue;
            }
            long edgeTicks = DuctModuleEffects.effectiveItemEdgeTravelTicks(this, retrieverFace, spec);
            long travel = DuctPathfinder.pathTravelTicks(path, edgeTicks);
            int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
            OutboundShipment sh =
                    new OutboundShipment(
                            extracted, worldPosition, retrieverFace, travelTicks, donor, donorFace, node.channelLetter);
            sh.stack = extracted.copy();
            sh.registeredIncoming = sh.stack.copy();
            sh.sourceExtractCommitted = true;
            sh.ductPath = OutboundShipment.copyPath(path);
            sh.totalTravelTicks = travelTicks;
            sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, edgeTicks);
            sh.journeyStartGameTime = level.getGameTime();
            sh.transitPhase = TransitPhase.FORWARD;
            outboundShipments.add(sh);
            DuctIncomingIndex.register(level, sh.destDuct, sh.destFace, sh.incomingReservationId, sh.stack);
            donorBe.setChanged();
            setChanged();
            pushTransitSnapshotToClients(level);
            requestModelDataUpdate();
            node.retrieverPullSlotCursor = si + 1;
            if (roundRobinRetriever) {
                node.roundRobinCursor = rrFrozen + donorIdx + 1;
            }
            return true;
        }
        return false;
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
        return maxSchedulableTowardFace(level, destDuct, destBe, destFace, template, want, false);
    }

    private int maxSchedulableTowardFace(
            ServerLevel level,
            BlockPos destDuct,
            DuctBlockEntity destBe,
            Direction destFace,
            ItemStack template,
            int want,
            boolean retrieverAllowBank) {
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
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, destDuct, destFace);
        ItemStack t = template.copy();
        t.setCount(want);
        int allowCap =
                retrieverAllowBank
                        ? capInsertableForRetrieverAllowLimit(
                                level, destDuct, destFace, destBe, t, want, prior)
                        : capInsertableForFilterAllowLimit(
                                level, destDuct, destFace, destBe, t, want, prior);
        if (allowCap <= 0) {
            return 0;
        }
        if (DuctInsertProbeCache.isRejected(level, destDuct, destFace, template)) {
            return 0;
        }
        IItemHandler destHandler = DuctCapHelper.getHandlerOnFace(level, destDuct, destFace);
        if (destHandler == null) {
            DuctInsertProbeCache.recordReject(level, destDuct, destFace, template);
            return 0;
        }
        ItemStack one = template.copyWithCount(1);
        if (!DuctItemInsertProbe.canAcceptOne(destHandler, one)) {
            DuctInsertProbeCache.recordReject(level, destDuct, destFace, template);
            return 0;
        }
        if (allowCap == 1) {
            DuctInsertProbeCache.cacheAccept(level, destDuct, destFace, template, true);
            return 1;
        }
        int physicalCap = DuctCapHelper.maxInsertableOnHandler(destHandler, t, allowCap);
        if (physicalCap <= 0) {
            recordDestInsertRejected(level, destDuct, destFace, template);
            return 0;
        }
        DuctInsertProbeCache.cacheAccept(level, destDuct, destFace, template, true);
        return Math.max(0, Math.min(want, Math.min(allowCap, physicalCap)));
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
        return DuctModuleEffects.effectiveItemExtractBatch(ownerDuct, face, spec);
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
            boolean hasEnergyKind = def.map(dd -> dd.enabledTransportKinds().contains(DuctTransportKind.ENERGY)).orElse(false);
            if (hasEnergyKind) {
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), L.energyRoutingMode, hasItemUp)) {
                    L.energyRoutingMode = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), L.energyRoutingModeExtractor, hasItemUp)) {
                    L.energyRoutingModeExtractor = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), L.energyRoutingModeRetriever, hasItemUp)) {
                    L.energyRoutingModeRetriever = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
            }
            boolean hasHeatKind = def.map(dd -> dd.enabledTransportKinds().contains(DuctTransportKind.HEAT)).orElse(false);
            if (hasHeatKind) {
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), L.heatRoutingMode, hasItemUp)) {
                    L.heatRoutingMode = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), L.heatRoutingModeExtractor, hasItemUp)) {
                    L.heatRoutingModeExtractor = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), L.heatRoutingModeRetriever, hasItemUp)) {
                    L.heatRoutingModeRetriever = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
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
     * Hybrid faces ({@link NodeMode#EXTRACTION_FILTERING}, {@link NodeMode#RETRIEVING_EXTRACTION}): when both enabled
     * transports were pinned to max, both follow cap changes; otherwise only pinned transports auto-track max.
     */
    public void clampAllTransportExtractBatchesForFace(Direction face) {
        if (level == null || level.isClientSide()) {
            return;
        }
        Optional<DuctDefinition> def = ductDefinition();
        NodeMode mode = getFaceLanes(face).nodeMode;
        boolean hybrid = mode == NodeMode.EXTRACTION_FILTERING || mode == NodeMode.RETRIEVING_EXTRACTION;
        boolean itemOn =
                def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ITEM)).orElse(true);
        boolean fluidOn =
                def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.FLUID)).orElse(false);
        boolean gasOn = def.map(d -> d.enabledTransportKinds().contains(DuctTransportKind.GAS)).orElse(false);
        if (!hybrid) {
            if (itemOn) {
                clampExtractAmount(getFaceNode(face), face);
            }
            if (fluidOn) {
                clampFluidExtractAmount(getFluidFaceNode(face), face);
            }
            if (gasOn) {
                clampGasExtractAmount(getGasFaceNode(face), face);
            }
            return;
        }
        DuctFaceNode itemNode = itemOn ? getFaceNode(face) : null;
        DuctFaceNode fluidNode = fluidOn ? getFluidFaceNode(face) : null;
        DuctFaceNode gasNode = gasOn ? getGasFaceNode(face) : null;
        boolean itemPinned = itemNode != null && isExtractBatchPinnedToCap(itemNode);
        boolean fluidPinned = fluidNode != null && isExtractBatchPinnedToCap(fluidNode);
        boolean gasPinned = gasNode != null && isExtractBatchPinnedToCap(gasNode);
        int pinnedCount = (itemPinned ? 1 : 0) + (fluidPinned ? 1 : 0) + (gasPinned ? 1 : 0);
        int enabledCount = (itemOn ? 1 : 0) + (fluidOn ? 1 : 0) + (gasOn ? 1 : 0);
        boolean bothSidesMax = enabledCount >= 2 && pinnedCount >= 2;
        if (itemNode != null) {
            applyExtractBatchAgainstCap(
                    itemNode, computeExtractBatchSettingCap(face), bothSidesMax || itemPinned);
        }
        if (fluidNode != null) {
            applyExtractBatchAgainstCap(
                    fluidNode, computeFluidExtractBatchSettingCap(face), bothSidesMax || fluidPinned);
        }
        if (gasNode != null) {
            applyExtractBatchAgainstCap(
                    gasNode, computeGasExtractBatchSettingCap(face), bothSidesMax || gasPinned);
        }
    }

    private static boolean isExtractBatchPinnedToCap(DuctFaceNode node) {
        int memo = node.lastExtractBatchSettingCapApplied;
        return memo > 0 && node.extractBatch >= memo;
    }

    public void refreshMenuData(Direction accessFace) {
        DuctFaceLanes faceLanes = getFaceLanes(accessFace);
        DuctFaceNode n = activeMenuFaceNode(accessFace);
        menuData.set(DuctMenuSync.NODE_MODE, faceLanes.nodeMode.ordinal());
        DuctTransportKind menuKind = menuActiveTransportKind();
        if (menuKind == DuctTransportKind.ENERGY) {
            menuData.set(DuctMenuSync.ROUTING_MODE, faceLanes.energyRoutingMode.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, faceLanes.energyRoutingModeExtractor.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, faceLanes.energyRoutingModeRetriever.ordinal());
        } else if (menuKind == DuctTransportKind.HEAT) {
            menuData.set(DuctMenuSync.ROUTING_MODE, faceLanes.heatRoutingMode.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, faceLanes.heatRoutingModeExtractor.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, faceLanes.heatRoutingModeRetriever.ordinal());
        } else {
            menuData.set(DuctMenuSync.ROUTING_MODE, n.routingMode.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, n.routingModeExtractor.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, n.routingModeRetriever.ordinal());
        }
        menuData.set(DuctMenuSync.PRIORITY, n.insertionPriority & 0xFFFF);
        menuData.set(DuctMenuSync.PRIORITY_HI, (n.insertionPriority >> 16) & 0xFFFF);
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
        if (faceLanes.nodeMode.usesRouting()
                || (menuKind == DuctTransportKind.ENERGY || menuKind == DuctTransportKind.HEAT)
                        && (faceLanes.nodeMode == NodeMode.NONE
                                || faceLanes.nodeMode == NodeMode.FILTERING_INSERTION)) {
            flags |= DuctMenuSync.FLAG_ROUTING_ACTIVE;
        }
        if (menuKind != DuctTransportKind.ENERGY
                && menuKind != DuctTransportKind.HEAT
                && faceLanes.nodeMode.usesItemFilterConfig()) {
            flags |= DuctMenuSync.FLAG_FILTERS_ACTIVE;
        }
        menuData.set(DuctMenuSync.FLAGS, flags);
        menuData.set(DuctMenuSync.ENERGY_BUF_LIMIT_EXTRACT, faceLanes.energyExtractBufferLimitFe);
        menuData.set(DuctMenuSync.ENERGY_BUF_LIMIT_INSERT, faceLanes.energyInsertBufferLimitFe);
        if (menuKind == DuctTransportKind.ENERGY) {
            DuctEnergyTransportSpec energySpec = energyTransportSpec();
            menuData.set(DuctMenuSync.ENERGY_BUF_INPUT_STORED, faceLanes.energyInputBufferFe);
            menuData.set(DuctMenuSync.ENERGY_BUF_OUTPUT_STORED, faceLanes.energyOutputBufferFe);
            menuData.set(
                    DuctMenuSync.ENERGY_BUF_INPUT_CAP,
                    DuctModuleEffects.effectiveEnergyInputBufferCapFe(this, accessFace, energySpec));
            menuData.set(
                    DuctMenuSync.ENERGY_BUF_OUTPUT_CAP,
                    DuctModuleEffects.effectiveEnergyOutputBufferCapFe(this, accessFace, energySpec));
        } else {
            menuData.set(DuctMenuSync.ENERGY_BUF_INPUT_STORED, 0);
            menuData.set(DuctMenuSync.ENERGY_BUF_OUTPUT_STORED, 0);
            menuData.set(DuctMenuSync.ENERGY_BUF_INPUT_CAP, 0);
            menuData.set(DuctMenuSync.ENERGY_BUF_OUTPUT_CAP, 0);
        }
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
        EnumSet<DuctTransportKind> kinds =
                ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        faceLanes.ensureTransportEnabledMask(kinds);
        menuData.set(DuctMenuSync.TRANSPORT_ENABLED_MASK, faceLanes.transportEnabledMask);
    }

    /** After face settings were restored outside normal menu buttons (e.g. settings copier paste). */
    public void finishFaceSettingsRestore(Direction face) {
        clampFaceFiltersToSpec();
        clampAllTransportExtractBatchesForFace(face);
        setChanged();
        refreshMenuData(face);
        syncVisualGeometryToClients();
    }

    public boolean passesItemFilters(Direction face, ItemStack stack, Level level) {
        return passesItemFilters(face, stack, level, DuctFaceNode.FilterBank.FILTER);
    }

    private static boolean destFaceUsesFilterAllowLimit(NodeMode destMode) {
        return destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING;
    }

    /**
     * Applies FILTER/RETRIEVER allow-line headroom before any simulated physical insert probe on the destination face.
     */
    private int maxInsertableAfterPendingOnFaceRespectingAllowLimit(
            ServerLevel level,
            BlockPos destDuctPos,
            Direction destFace,
            DuctBlockEntity destBe,
            ItemStack template,
            int limit,
            List<ItemStack> priorIncoming,
            boolean retrieverAllowBank) {
        if (limit <= 0 || template.isEmpty()) {
            return 0;
        }
        int allowCap =
                retrieverAllowBank
                        ? capInsertableForRetrieverAllowLimit(
                                level, destDuctPos, destFace, destBe, template, limit, priorIncoming)
                        : capInsertableForFilterAllowLimit(
                                level, destDuctPos, destFace, destBe, template, limit, priorIncoming);
        if (allowCap <= 0) {
            return 0;
        }
        return DuctCapHelper.maxInsertableAfterPendingOnFace(
                level, destDuctPos, destFace, template, allowCap, priorIncoming);
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
        List<Integer> filterConcat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER);
        if (!DuctAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(filterAllows, filterCaps)) {
            return maxFromCapacity;
        }
        int maxAdd =
                DuctAllowLimitLogic.maxAdditionalInsertAcrossAllowLines(
                        raw,
                        filterAllows,
                        filterCaps,
                        filterConcat,
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
        List<Integer> retrConcat = destNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.RETRIEVER);
        if (!DuctAllowLimitLogic.hasAnyPositiveAllowCapOnNonEmptyLine(retrAllows, retrCaps)) {
            return maxFromCapacity;
        }
        int maxAdd =
                DuctAllowLimitLogic.maxAdditionalInsertAcrossAllowLines(
                        raw,
                        retrAllows,
                        retrCaps,
                        retrConcat,
                        template,
                        priorIncoming,
                        level.registryAccess());
        if (maxAdd == Integer.MAX_VALUE) {
            return maxFromCapacity;
        }
        return Math.min(maxFromCapacity, maxAdd);
    }

    /** Caps extract count respecting Keep on the inventory attached to {@code sourceFace}. */
    private int capExtractableForSourceKeep(
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
        NodeMode mode = sourceBe.getFaceLanes(sourceFace).nodeMode;
        List<String> allowLines;
        List<Integer> keepCaps;
        List<Integer> allowConcat;
        if (mode == NodeMode.EXTRACTION
                || mode == NodeMode.EXTRACTION_FILTERING
                || mode == NodeMode.RETRIEVING_EXTRACTION) {
            allowLines = srcNode.bankAllowFilters(DuctFaceNode.FilterBank.EXTRACTOR);
            keepCaps = srcNode.bankAllowCaps(DuctFaceNode.FilterBank.EXTRACTOR);
            allowConcat = srcNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.EXTRACTOR);
        } else if (mode == NodeMode.FILTERING_INSERTION) {
            allowLines = srcNode.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
            keepCaps = srcNode.filterBankKeepCaps();
            allowConcat = srcNode.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER);
        } else {
            return maxWant;
        }
        if (!DuctAllowLimitLogic.hasAnyPositiveKeepOnNonEmptyLine(allowLines, keepCaps)) {
            return maxWant;
        }
        int cap =
                DuctAllowLimitLogic.maxExtractRespectingKeepAcrossLines(
                        h, allowLines, keepCaps, allowConcat, template, level.registryAccess());
        if (cap == Integer.MAX_VALUE) {
            return maxWant;
        }
        return Math.min(maxWant, cap);
    }

    public boolean passesItemFilters(Direction face, ItemStack stack, Level level, DuctFaceNode.FilterBank bank) {
        if (stack.isEmpty()) {
            return false;
        }
        DuctFaceLanes lanes = getFaceLanes(face);
        if (lanes.nodeMode == NodeMode.NONE) {
            return true;
        }
        DuctFaceNode node = getFaceNode(face);
        // Compute effective filter capacity so entries beyond capacity (kept for data preservation
        // when modules are temporarily removed) are not evaluated during matching.
        DuctItemTransportSpec itemSpec = itemTransportSpec();
        DuctModuleEffects.FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(this, face);
        int allowCap = DuctModuleEffects.effectiveItemAllowBank(itemSpec, lanes.nodeMode, fb);
        int denyCap = DuctModuleEffects.effectiveItemDenyBank(itemSpec, lanes.nodeMode, fb);
        // Build a lightweight view using the selected bank limited to effective capacity.
        DuctFaceNode view = new DuctFaceNode(() -> {});
        view.denyOverridesAllow = node.bankDenyOverridesAllow(bank);
        view.allowFilters.clear();
        List<String> fullAllow = node.bankAllowFilters(bank);
        view.allowFilters.addAll(fullAllow.subList(0, Math.min(fullAllow.size(), allowCap)));
        view.denyFilters.clear();
        List<String> fullDeny = node.bankDenyFilters(bank);
        view.denyFilters.addAll(fullDeny.subList(0, Math.min(fullDeny.size(), denyCap)));
        List<Integer> fullAllowConcat = node.bankAllowConcatChannels(bank);
        List<Integer> fullDenyConcat = node.bankDenyConcatChannels(bank);
        view.allowConcatChannels.addAll(
                fullAllowConcat.subList(0, Math.min(fullAllowConcat.size(), allowCap)));
        view.denyConcatChannels.addAll(
                fullDenyConcat.subList(0, Math.min(fullDenyConcat.size(), denyCap)));
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
            List<Integer> allowConcatIn,
            List<Integer> denyConcatIn,
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
        if (allowConcatIn == null) {
            allowConcatIn = List.of();
        }
        if (denyConcatIn == null) {
            denyConcatIn = List.of();
        }
        List<String> a = node.bankAllowFilters(bank);
        List<String> d = node.bankDenyFilters(bank);
        List<Integer> caps = node.bankAllowCaps(bank);
        List<Integer> caps2 = bank == DuctFaceNode.FilterBank.FILTER ? node.filterBankKeepCaps() : null;
        List<Integer> allowConcat = node.bankAllowConcatChannels(bank);
        List<Integer> denyConcat = node.bankDenyConcatChannels(bank);
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
        // Snapshot the full current lists before clearing.
        // - Positions allowIn.size()..maxA-1: client had fewer visible slots than the current capacity
        //   (e.g. module was just re-added); preserve server-side values instead of writing "".
        // - Positions beyond maxA: preserved so they survive a capacity reduction and are
        //   restored automatically when capacity is increased again.
        List<String> origA = new ArrayList<>(a);
        List<Integer> origCaps = new ArrayList<>(caps);
        List<Integer> origCaps2 = caps2 != null ? new ArrayList<>(caps2) : null;
        List<String> origD = new ArrayList<>(d);
        List<Integer> origAllowConcat = new ArrayList<>(allowConcat);
        List<Integer> origDenyConcat = new ArrayList<>(denyConcat);
        a.clear();
        d.clear();
        caps.clear();
        allowConcat.clear();
        denyConcat.clear();
        if (caps2 != null) {
            caps2.clear();
        }
        for (int i = 0; i < maxA; i++) {
            String s;
            if (i < allowIn.size()) {
                s = allowIn.get(i);
                s = s != null ? s : "";
            } else {
                s = i < origA.size() ? origA.get(i) : "";
                s = s != null ? s : "";
            }
            a.add(clampFilterLine(def, hasModule, s));
            Integer capObj;
            if (i < allowCapsIn.size()) {
                capObj = allowCapsIn.get(i);
            } else {
                capObj = i < origCaps.size() ? origCaps.get(i) : null;
            }
            int cap = capObj != null ? capObj : 0;
            caps.add(Math.max(0, cap));
            if (caps2 != null) {
                Integer capObj2;
                if (i < allowCaps2In.size()) {
                    capObj2 = allowCaps2In.get(i);
                } else {
                    capObj2 = origCaps2 != null && i < origCaps2.size() ? origCaps2.get(i) : null;
                }
                int cap2 = capObj2 != null ? capObj2 : 0;
                caps2.add(Math.max(0, cap2));
            }
            Integer concatObj;
            if (i < allowConcatIn.size()) {
                concatObj = allowConcatIn.get(i);
            } else {
                concatObj = i < origAllowConcat.size() ? origAllowConcat.get(i) : null;
            }
            int concatVal = concatObj != null ? Math.clamp(concatObj, 0, FilterConcatChannel.MAX_LETTER) : 0;
            allowConcat.add(concatVal);
        }
        for (int i = 0; i < maxD; i++) {
            String s;
            if (i < denyIn.size()) {
                s = denyIn.get(i);
                s = s != null ? s : "";
            } else {
                s = i < origD.size() ? origD.get(i) : "";
                s = s != null ? s : "";
            }
            d.add(clampFilterLine(def, hasModule, s));
            Integer concatObj;
            if (i < denyConcatIn.size()) {
                concatObj = denyConcatIn.get(i);
            } else {
                concatObj = i < origDenyConcat.size() ? origDenyConcat.get(i) : null;
            }
            int concatVal = concatObj != null ? Math.clamp(concatObj, 0, FilterConcatChannel.MAX_LETTER) : 0;
            denyConcat.add(concatVal);
        }
        // Restore entries beyond current capacity (inactive until capacity is restored).
        if (origA.size() > maxA) {
            a.addAll(origA.subList(maxA, origA.size()));
            if (origCaps.size() > maxA) caps.addAll(origCaps.subList(maxA, origCaps.size()));
            if (origAllowConcat.size() > maxA) {
                allowConcat.addAll(origAllowConcat.subList(maxA, origAllowConcat.size()));
            }
            if (caps2 != null && origCaps2 != null && origCaps2.size() > maxA) {
                caps2.addAll(origCaps2.subList(maxA, origCaps2.size()));
            }
        }
        if (origD.size() > maxD) {
            d.addAll(origD.subList(maxD, origD.size()));
            if (origDenyConcat.size() > maxD) {
                denyConcat.addAll(origDenyConcat.subList(maxD, origDenyConcat.size()));
            }
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
    /** Hybrid / extractor sub-panel: cycle {@link DuctFaceNode#routingModeExtractor} forward. */
    public static final int MENU_BUTTON_ROUTING_EXTRACTOR_FORWARD = 31;
    /** Hybrid retriever sub-panel: cycle {@link DuctFaceNode#routingModeRetriever} forward. */
    public static final int MENU_BUTTON_ROUTING_RETRIEVER_FORWARD = 32;
    public static final int MENU_BUTTON_ROUTING_EXTRACTOR_BACK = 33;
    public static final int MENU_BUTTON_ROUTING_RETRIEVER_BACK = 34;
    /** Settings copier virtual ALL: transport hub → detail (virtual node main). */
    public static final int MENU_BUTTON_ENTER_DETAIL = 48;
    public static final int MENU_BUTTON_BACK_TO_HUB = 49;
    /** Toggle {@link DuctFaceLanes#transportEnabledMask} bit for {@link DuctTransportKind#ordinal()}. */
    public static final int MENU_BUTTON_TRANSPORT_TOGGLE_BASE = 50;

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
        if (buttonId >= MENU_BUTTON_TRANSPORT_TOGGLE_BASE
                && buttonId < MENU_BUTTON_TRANSPORT_TOGGLE_BASE + DuctTransportKind.values().length) {
            DuctTransportKind k = DuctTransportKind.values()[buttonId - MENU_BUTTON_TRANSPORT_TOGGLE_BASE];
            EnumSet<DuctTransportKind> kinds =
                    ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
            if (!kinds.contains(k)) {
                return false;
            }
            getFaceLanes(accessFace).toggleTransportKind(k, kinds);
            refreshMenuData(accessFace);
            syncVisualGeometryToClients();
            return true;
        }
        DuctFaceNode node = activeMenuFaceNode(accessFace);
        DuctFaceLanes menuFaceLanes = getFaceLanes(accessFace);
        boolean changed =
                switch (buttonId) {
                    case 0 -> cycleNodeMode(menuFaceLanes, accessFace);
                    case 10 -> cycleNodeModeBackward(menuFaceLanes, accessFace);
                    case 1 -> {
                        DuctTransportKind menuKind1 = menuActiveTransportKind();
                        if (menuKind1 == DuctTransportKind.ENERGY || menuKind1 == DuctTransportKind.HEAT) {
                            yield cycleRoutingMode(node, accessFace);
                        }
                        if (usesEnergyOrHeatPassThroughRouting(accessFace)) {
                            yield cycleEligibilityMode(
                                    faceNodeForTransportKind(accessFace, menuKind1), 1);
                        }
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
                        DuctTransportKind menuKind11 = menuActiveTransportKind();
                        if (menuKind11 == DuctTransportKind.ENERGY || menuKind11 == DuctTransportKind.HEAT) {
                            yield cycleRoutingModeBackward(node, accessFace);
                        }
                        if (usesEnergyOrHeatPassThroughRouting(accessFace)) {
                            yield cycleEligibilityMode(
                                    faceNodeForTransportKind(accessFace, menuKind11), -1);
                        }
                        if (!menuFaceLanes.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield cycleRoutingModeBackward(node, accessFace);
                    }
                    case MENU_BUTTON_ROUTING_EXTRACTOR_FORWARD -> stepRoutingExtractor(node, 1, accessFace);
                    case MENU_BUTTON_ROUTING_EXTRACTOR_BACK -> stepRoutingExtractor(node, -1, accessFace);
                    case MENU_BUTTON_ROUTING_RETRIEVER_FORWARD -> stepRoutingRetriever(node, 1, accessFace);
                    case MENU_BUTTON_ROUTING_RETRIEVER_BACK -> stepRoutingRetriever(node, -1, accessFace);
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
            invalidateRoutingEndpointCache();
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

    /** Insert/donor eligibility on NONE / FILTERING_INSERTION (including flux/heat pass-through faces). */
    private static boolean cycleEligibilityMode(DuctFaceNode node, int delta) {
        DuctFaceNode.EligibilityMode cur = node.eligibilityMode;
        DuctFaceNode.EligibilityMode nxt =
                switch (cur) {
                    case BOTH ->
                            delta > 0
                                    ? DuctFaceNode.EligibilityMode.INSERT_ONLY
                                    : DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;
                    case INSERT_ONLY ->
                            delta > 0
                                    ? DuctFaceNode.EligibilityMode.RETRIEVE_ONLY
                                    : DuctFaceNode.EligibilityMode.BOTH;
                    case RETRIEVE_ONLY ->
                            delta > 0
                                    ? DuctFaceNode.EligibilityMode.BOTH
                                    : DuctFaceNode.EligibilityMode.INSERT_ONLY;
                };
        if (nxt == cur) {
            return false;
        }
        node.eligibilityMode = nxt;
        return true;
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
    private boolean stepRoutingExtractor(DuctFaceNode node, int delta, Direction accessFace) {
        return stepRoutingField(node, delta, accessFace, true);
    }

    private boolean stepRoutingRetriever(DuctFaceNode node, int delta, Direction accessFace) {
        return stepRoutingField(node, delta, accessFace, false);
    }

    private boolean stepRoutingField(
            DuctFaceNode node, int delta, Direction accessFace, boolean extractorLane) {
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasModule = faceHasAnyModule(accessFace);
        RoutingMode[] v = RoutingMode.values();
        RoutingMode cur =
                extractorLane ? node.routingModeExtractor : node.routingModeRetriever;
        RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
        if (nxt == cur) {
            return false;
        }
        if (extractorLane) {
            node.routingModeExtractor = nxt;
        } else {
            node.routingModeRetriever = nxt;
        }
        return true;
    }

    private boolean stepRouting(DuctFaceNode node, int delta, Direction accessFace) {
        DuctFaceLanes lanes = getFaceLanes(accessFace);
        NodeMode shared = lanes.nodeMode;
        if (!shared.usesRouting() && !usesEnergyOrHeatPassThroughRouting(accessFace)) {
            return false;
        }
        DuctTransportKind menuKind = menuActiveTransportKind();
        if (menuKind == DuctTransportKind.ENERGY) {
            return stepEnergyOrHeatRouting(lanes, shared, delta, accessFace, true);
        }
        if (menuKind == DuctTransportKind.HEAT) {
            return stepEnergyOrHeatRouting(lanes, shared, delta, accessFace, false);
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
            case RETRIEVING_EXTRACTION -> false;
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

    private boolean stepEnergyOrHeatRouting(
            DuctFaceLanes lanes, NodeMode shared, int delta, Direction accessFace, boolean energy) {
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasModule = faceHasAnyModule(accessFace);
        RoutingMode[] v = RoutingMode.values();
        return switch (shared) {
            case EXTRACTION_FILTERING -> {
                RoutingMode cur =
                        energy ? lanes.energyRoutingModeExtractor : lanes.heatRoutingModeExtractor;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
                if (nxt != cur) {
                    if (energy) {
                        lanes.energyRoutingModeExtractor = nxt;
                    } else {
                        lanes.heatRoutingModeExtractor = nxt;
                    }
                    yield true;
                }
                yield false;
            }
            case RETRIEVING_EXTRACTION -> false;
            case RETRIEVING -> {
                RoutingMode cur =
                        energy ? lanes.energyRoutingModeRetriever : lanes.heatRoutingModeRetriever;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
                if (nxt != cur) {
                    if (energy) {
                        lanes.energyRoutingModeRetriever = nxt;
                    } else {
                        lanes.heatRoutingModeRetriever = nxt;
                    }
                    yield true;
                }
                yield false;
            }
            default -> {
                RoutingMode cur = energy ? lanes.energyRoutingMode : lanes.heatRoutingMode;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasModule);
                if (nxt != cur) {
                    if (energy) {
                        lanes.energyRoutingMode = nxt;
                    } else {
                        lanes.heatRoutingMode = nxt;
                    }
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
        invalidateRoutingEndpointCache();
        refreshMenuData(accessFace);
    }

    private void invalidateRoutingEndpointCache() {
        if (level instanceof ServerLevel serverLevel) {
            DuctRoutingEndpointIndex.onSettingsChanged(serverLevel);
        }
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
        applyExtractBatchAgainstCap(node, cap, true);
    }

    private static void applyExtractBatchAgainstCap(DuctFaceNode node, int cap, boolean trackMaxOnCapIncrease) {
        int prev = node.extractBatch;
        int memo = node.lastExtractBatchSettingCapApplied;
        if (trackMaxOnCapIncrease && memo > 0 && prev >= memo && cap > memo) {
            node.extractBatch = cap;
        } else if (trackMaxOnCapIncrease && memo > 0 && prev >= memo && cap < memo) {
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
            // Ensure the BE update packet is sent so client refreshes node icons and stall mask immediately.
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
        if (level instanceof ServerLevel serverLevel) {
            DuctNetworkCache.invalidate(serverLevel);
        }
        syncStallVisualIfNeeded();
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
        if (level instanceof ServerLevel serverLevel) {
            DuctNetworkCache.invalidate(serverLevel);
        }
        syncStallVisualIfNeeded();
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
        if (networkOpaqueRendering) {
            tag.putBoolean("NetworkOpaque", true);
        }
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
            ensureAllFaceTransportMasks();
            setChanged();
            return;
        }

        if (tag.contains("DuctLogicalId", Tag.TAG_STRING)) {
            logicalDuctId = DuctIds.normalize(tag.getString("DuctLogicalId"));
        }
        networkOpaqueRendering = tag.getBoolean("NetworkOpaque");
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
        ensureAllFaceTransportMasks();
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
        t.putString("DuctLogicalId", logicalDuctId);
        t.putByte("PipeMask", (byte) getPipeMask());
        t.putByte("StorageMask", (byte) getStorageMask());
        t.putByte("UserDisc", (byte) getUserDisconnectedFaceMask());
        t.putByte("LatchedFaces", (byte) latchedStorageFaceMask);
        // Pack node icon indices server-side for client rendering.
        t.putInt("PackedNodeIcons", computePackedNodeIcons());
        // Pack stall mask server-side for client rendering.
        t.putInt("StallMask", computeStallMask());
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
        if (networkOpaqueRendering) {
            t.putBoolean("NetworkOpaque", true);
        }
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
        int prevStallMask = clientStallMask;
        int nextStallMask = tag.contains("StallMask", Tag.TAG_INT) ? tag.getInt("StallMask") : 0;
        clientStallMask = nextStallMask;
        if (prevStallMask != nextStallMask) {
            requestModelDataUpdate();
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        boolean prevNetworkOpaque = clientNetworkOpaque;
        clientNetworkOpaque = tag.getBoolean("NetworkOpaque");
        if (prevNetworkOpaque != clientNetworkOpaque) {
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
        // Server has full stall buffers; client only receives StallMask in the update packet.
        int stallMask =
                level != null && level.isClientSide()
                        ? filterStallMaskForActiveFaces(clientStallMask)
                        : computeStallMask();
        stallMask &= getStorageMask();
        return ModelData.builder()
                .with(DuctModelProperties.PIPE_MASK, getPipeMask())
                .with(DuctModelProperties.STORAGE_MASK, getStorageMask())
                .with(DuctModelProperties.NODE_ICONS_PACKED, packed)
                .with(DuctModelProperties.DUCT_LOGICAL_ID, logicalDuctId)
                .with(DuctModelProperties.HAS_STALL, stallMask != 0)
                .with(DuctModelProperties.STALL_MASK, stallMask)
                .with(DuctModelProperties.NETWORK_OPAQUE, isNetworkOpaqueRendering())
                .build();
    }

    private int computeStallMask() {
        int mask = 0;
        for (Direction d : Direction.values()) {
            if (faceHasVisibleStall(d)) {
                mask |= 1 << d.ordinal();
            }
        }
        return mask;
    }

    /** Client: apply server StallMask only on faces that still show an active storage node. */
    private int filterStallMaskForActiveFaces(int serverStallMask) {
        int mask = 0;
        for (Direction d : Direction.values()) {
            int bit = 1 << d.ordinal();
            if ((serverStallMask & bit) != 0 && faceShowsActiveStorageNode(d)) {
                mask |= bit;
            }
        }
        return mask;
    }

    private boolean hasAnyStallBuffered() {
        for (Direction d : Direction.values()) {
            DuctFaceLanes lanes = getFaceLanes(d);
            var buf = lanes.stalledBuffer;
            for (int i = 0; i < buf.getSlots(); i++) {
                if (!buf.getStackInSlot(i).isEmpty()) {
                    return true;
                }
            }
            for (FluidStack fs : lanes.stalledFluids) {
                if (fs != null && !fs.isEmpty() && fs.getAmount() > 0) {
                    return true;
                }
            }
            for (var g : lanes.stalledGas) {
                if (g != null && !g.isEmpty() && (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount"))) {
                    return true;
                }
            }
            if (lanes.stalledEnergyCount > 0 || lanes.stalledHeatCount > 0) {
                return true;
            }
        }
        return false;
    }
}
