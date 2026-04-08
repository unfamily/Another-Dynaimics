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
import net.unfamily.another_dynamics.duct.logistics.DuctOverflowBuffer;
import net.unfamily.another_dynamics.duct.logistics.DuctOverflowRouting;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.logistics.DuctTargetSelector;
import net.unfamily.another_dynamics.duct.logistics.DuctTransitTopology;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;
import net.unfamily.another_dynamics.duct.logistics.TransitPhase;
import net.unfamily.another_dynamics.client.transit.DuctTransitClientState;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import java.util.ArrayList;
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

    private final DuctFaceNode[] faceNodes = new DuctFaceNode[FACE_COUNT];

    /** {@link DuctDefinition#logicalId()} for this placed block (item component + NBT). */
    private String logicalDuctId = DuctIds.DEFAULT_LOGICAL_ID;

    private final List<OutboundShipment> outboundShipments = new ArrayList<>();
    private final List<ItemStack> migratedStorageBacklog = new ArrayList<>();
    private final DuctOverflowBuffer overflowBuffer = new DuctOverflowBuffer();

    private final SimpleContainerData menuData = new SimpleContainerData(DuctMenuSync.COUNT);

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
            DuctFaceNode n = getFaceNode(d);
            int col =
                    switch (n.nodeMode) {
                        case EXTRACTION -> 0;
                        case FILTERING_INSERTION -> 1;
                        case RETRIEVING -> 2;
                        case NONE -> 3;
                        case EXTRACTION_FILTERING -> 0;
                        case RETRIEVING_EXTRACTION -> 1;
                    };
            int rowBase =
                    switch (n.nodeMode) {
                        case EXTRACTION, FILTERING_INSERTION, RETRIEVING, NONE -> 0;
                        case EXTRACTION_FILTERING, RETRIEVING_EXTRACTION -> 2;
                    };
            boolean off = !isNodeEnabledByRedstone(n);
            int row = rowBase + (off ? 1 : 0);
            int idx = row * 4 + col; // 0..15
            packed |= (idx & 0xF) << (d.ordinal() * 4);
        }
        return packed;
    }

    private boolean isNodeEnabledByRedstone(DuctFaceNode n) {
        return DuctRedstoneLogic.isItemNodeActive(level, worldPosition, n);
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
            int rm = getFaceNode(d).redstoneMode;
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
            faceNodes[i] = new DuctFaceNode(this::setChanged);
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
        setChanged();
        requestModelDataUpdate();
    }

    public DuctItemTransportSpec itemTransportSpec() {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::itemTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::itemDuctTransportSpec);
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

    public DuctFaceNode getFaceNode(Direction dir) {
        return faceNodes[dir.ordinal()];
    }

    public DuctOverflowBuffer getOverflowBuffer() {
        return overflowBuffer;
    }

    public SimpleContainerData getMenuData() {
        return menuData;
    }

    public Component getScreenTitle() {
        return Component.translatable("container.another_dynamics.duct_node");
    }

    @Override
    protected DuctNetworkType networkType() {
        return DuctNetworkType.ITEM;
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
    protected int attachmentMaskForNeighbor(Direction dir, BlockState neighborState, BlockPos neighborPos) {
        if (level == null || neighborState.isAir()) {
            return 0;
        }
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, neighborPos, dir.getOpposite());
        if (cap != null && cap.getSlots() > 0) {
            return 1 << dir.ordinal();
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
        tickOverflowBufferDrain(serverLevel);
        tickMigratedBacklogFlush(serverLevel);
        if (!isStorageAttachmentNode()) {
            return;
        }

        DuctItemTransportSpec spec = itemTransportSpec();
        int rate = spec.clampedRateTicks(spec.rateDefaultTicks());
        int sm = getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            DuctFaceNode node = getFaceNode(dir);
            if (!isNodeEnabledByRedstone(node)) {
                continue;
            }
            if (node.ticksUntilAction > 0) {
                node.ticksUntilAction--;
                setChanged();
                continue;
            }
            node.ticksUntilAction = rate - 1;
            if (node.nodeMode == NodeMode.EXTRACTION || node.nodeMode == NodeMode.EXTRACTION_FILTERING) {
                tickExtractionPullForFace(serverLevel, spec, dir, node);
            } else if (node.nodeMode == NodeMode.RETRIEVING) {
                tickRetrieverPullForFace(serverLevel, spec, dir, node);
            } else if (node.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                tickRetrieverPullForFace(serverLevel, spec, dir, node);
                tickExtractionPullForFace(serverLevel, spec, dir, node);
            }
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
        if (!DuctRedstoneLogic.isItemNodeActive(level, s.destDuct, destBe.getFaceNode(s.destFace))) {
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
        int capIn;
        if (s.legacyOmniFaces) {
            capIn =
                    DuctCapHelper.maxInsertableAfterPending(
                            level, s.destDuct, destBe, s.stack, Math.min(planned, capExt), prior);
        } else {
            capIn =
                    DuctCapHelper.maxInsertableAfterPendingOnFace(
                            level, s.destDuct, s.destFace, s.stack, Math.min(planned, capExt), prior);
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
        if (!DuctRedstoneLogic.isItemNodeActive(level, s.destDuct, destBe.getFaceNode(s.destFace))) {
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
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            NodeMode dm = destBe.getFaceNode(s.destFace).nodeMode;
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
        DuctItemTransportSpec transportSpec = itemTransportSpec();
        DuctFaceNode srcNode = srcBe.getFaceNode(s.sourceFace);
        int srcTubeBatch = srcNode.extractBatch > 0 ? srcNode.extractBatch : transportSpec.batchDefault();
        if (srcTubeBatch <= 0) {
            srcTubeBatch = DuctItemTransportSpec.fallback().batchDefault();
        }
        int moduleCap = srcTubeBatch;
        int planned = s.stack.getCount();
        int capExt =
                s.legacyOmniFaces
                        ? DuctCapHelper.countExtractableMatching(level, s.refundDuct, srcBe, s.stack, planned)
                        : DuctCapHelper.countExtractableMatchingOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, planned);
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, s.destDuct);
        int capIn =
                s.legacyOmniFaces
                        ? DuctCapHelper.maxInsertableAfterPending(
                                level, s.destDuct, destBe, s.stack, Math.min(planned, capExt), prior)
                        : DuctCapHelper.maxInsertableAfterPendingOnFace(
                                level, s.destDuct, s.destFace, s.stack, Math.min(planned, capExt), prior);
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
        NodeMode dm2 = destBe.getFaceNode(s.destFace).nodeMode;
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
        if (!DuctRedstoneLogic.isItemNodeActive(level, worldPosition, getFaceNode(s.destFace))) {
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
            DuctIncomingIndex.unregister(level, worldPosition, s.registeredIncoming.copy());
            NodeMode sm = getFaceNode(s.destFace).nodeMode;
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
        DuctFaceNode retrieverNode = getFaceNode(s.destFace);
        int srcTubeBatch = retrieverNode.extractBatch > 0 ? retrieverNode.extractBatch : transportSpec.batchDefault();
        if (srcTubeBatch <= 0) {
            srcTubeBatch = DuctItemTransportSpec.fallback().batchDefault();
        }
        int moduleCap = srcTubeBatch;
        int planned = s.stack.getCount();
        int capExt =
                s.legacyOmniFaces
                        ? DuctCapHelper.countExtractableMatching(level, s.refundDuct, donorBe, s.stack, planned)
                        : DuctCapHelper.countExtractableMatchingOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, planned);
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, worldPosition);
        int capIn =
                s.legacyOmniFaces
                        ? DuctCapHelper.maxInsertableAfterPending(
                                level, worldPosition, this, s.stack, Math.min(planned, capExt), prior)
                        : DuctCapHelper.maxInsertableAfterPendingOnFace(
                                level, worldPosition, s.destFace, s.stack, Math.min(planned, capExt), prior);
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
        NodeMode sm2 = getFaceNode(s.destFace).nodeMode;
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
            DuctFaceNode n = getFaceNode(dir);
            if (DuctTargetSelector.isNetworkInboundDeliveryMode(n.nodeMode)
                    && !DuctRedstoneLogic.isItemNodeActive(level, worldPosition, n)) {
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
            ItemStack probe = sourceHandler.extractItem(slot, 1, true);
            if (probe.isEmpty()) {
                continue;
            }
            if (!passesItemFilters(face, probe, level, DuctFaceNode.FilterBank.EXTRACTOR)) {
                continue;
            }
            int[] rrProbe = new int[] {node.roundRobinCursor};
            boolean allowSelf = node.nodeMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
            RoutingMode rm =
                    node.nodeMode == NodeMode.RETRIEVING_EXTRACTION
                            ? node.routingModeRetriever
                            : node.nodeMode.isHybrid()
                                    ? node.routingModeExtractor
                                    : node.routingMode;
            Optional<DuctTargetSelector.ExtractionRouting> routeOpt =
                    DuctTargetSelector.selectExtractionDelivery(
                            level, worldPosition, probe, rm, rrProbe, node.channelLetter, allowSelf, null);
            if (routeOpt.isEmpty()) {
                continue;
            }
            DuctTargetSelector.ExtractionRouting route = routeOpt.get();
            List<BlockPos> path = route.path();
            BlockPos dest = path.get(path.size() - 1);
            if (!(level.getBlockEntity(dest) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            Direction destFace = route.destStorageFace();
            NodeMode destMode = destBe.getFaceNode(destFace).nodeMode;
            if ((destMode == NodeMode.FILTERING_INSERTION || destMode == NodeMode.EXTRACTION_FILTERING)
                    && !destBe.passesItemFilters(destFace, probe, level, DuctFaceNode.FilterBank.FILTER)) {
                continue;
            }
            /* batch = valore impostato sul tubo (GUI/NBT), oppure default datapack se 0 */
            int tubeBatch = node.extractBatch > 0 ? node.extractBatch : spec.batchDefault();
            if (tubeBatch <= 0) {
                tubeBatch = DuctItemTransportSpec.fallback().batchDefault();
            }
            /*
             * Conta tutti gli item estraibili (non cappato al batch): serve per calcolare quanti
             * sono già "prenotati" in transito e quanti restano liberi nello storage.
             */
            int availTotal =
                    DuctCapHelper.countExtractableMatchingOnFace(
                            level, worldPosition, face, probe, Integer.MAX_VALUE);
            if (availTotal <= 0) {
                continue;
            }
            /* Se c'è già abbastanza pianificato in transito per coprire tutti gli item, skip. */
            int pendingSum = pendingPlannedExtractCountOnSource(worldPosition, face, probe);
            int remainingInStorage = availTotal - pendingSum;
            if (remainingInStorage <= 0) {
                continue;
            }
            /* Una sola spedizione per tick: min(batch, rimanente) */
            int plannedCount = Math.min(tubeBatch, remainingInStorage);
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack planned = probe.copy();
            planned.setCount(plannedCount);
            if (!canScheduleTowardFace(level, dest, destBe, destFace, planned, spec)) {
                continue;
            }
            node.roundRobinCursor = rrProbe[0];
            long travel = DuctPathfinder.pathTravelTicks(path, spec);
            int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
            OutboundShipment sh =
                    new OutboundShipment(planned, dest, destFace, travelTicks, worldPosition, face, node.channelLetter);
            sh.ductPath = OutboundShipment.copyPath(path);
            sh.totalTravelTicks = travelTicks;
            sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, DuctPathfinder.edgeTravelTicks(spec));
            sh.journeyStartGameTime = level.getGameTime();
            sh.transitPhase = TransitPhase.FORWARD;
            outboundShipments.add(sh);
            DuctIncomingIndex.register(level, dest, sh.registeredIncoming.copy());
            setChanged();
            pushTransitSnapshotToClients(level);
            return;
        }
    }

    private void tickRetrieverPullForFace(ServerLevel level, DuctItemTransportSpec spec, Direction retrieverFace, DuctFaceNode node) {
        if (distinctPendingOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
            return;
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return;
        }
        int[] rr = new int[] {node.roundRobinCursor};
        RoutingMode rm = node.nodeMode.isHybrid() ? node.routingModeRetriever : node.routingMode;
        Optional<DuctTargetSelector.RetrieverRouting> routeOpt =
                DuctTargetSelector.selectRetrievingDonorPath(
                        level, worldPosition, retrieverFace, rm, rr, node.channelLetter);
        node.roundRobinCursor = rr[0];
        if (routeOpt.isEmpty()) {
            return;
        }
        DuctTargetSelector.RetrieverRouting route = routeOpt.get();
        List<BlockPos> path = route.path();
        BlockPos donor = route.donorPos();
        Direction donorFace = route.donorStorageFace();
        if (!(level.getBlockEntity(donor) instanceof DuctBlockEntity donorBe)) {
            return;
        }
        IItemHandler donorHandler = DuctCapHelper.getHandlerOnFace(level, donor, donorFace);
        if (donorHandler == null) {
            return;
        }
        /*
         * Retriever face filters AND donor (source inventory) duct face filters both apply: a deny on the chest side
         * blocks retrieval even when the retriever allow list would permit the item. Scan slots so we do not stick on
         * the first stack when another slot is legal.
         */
        for (int slot = 0; slot < donorHandler.getSlots(); slot++) {
            ItemStack probe = donorHandler.extractItem(slot, 1, true);
            if (probe.isEmpty()) {
                continue;
            }
            if (!passesItemFilters(retrieverFace, probe, level, DuctFaceNode.FilterBank.RETRIEVER)) {
                continue;
            }
            if (!donorBe.passesItemFilters(donorFace, probe, level, DuctFaceNode.FilterBank.RETRIEVER)) {
                continue;
            }
            /* batch = valore impostato sul tubo retriever */
            int tubeBatch = node.extractBatch > 0 ? node.extractBatch : spec.batchDefault();
            if (tubeBatch <= 0) {
                tubeBatch = DuctItemTransportSpec.fallback().batchDefault();
            }
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
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack planned = probe.copy();
            planned.setCount(plannedCount);
            if (!canScheduleTowardFace(level, worldPosition, this, retrieverFace, planned, spec)) {
                continue;
            }
            long travel = DuctPathfinder.pathTravelTicks(path, spec);
            int travelTicks = (int) Math.min(Math.max(0L, travel), Integer.MAX_VALUE);
            OutboundShipment sh =
                    new OutboundShipment(
                            planned, worldPosition, retrieverFace, travelTicks, donor, donorFace, node.channelLetter);
            sh.ductPath = OutboundShipment.copyPath(path);
            sh.totalTravelTicks = travelTicks;
            sh.edgeTicks = (int) Math.min(Integer.MAX_VALUE, DuctPathfinder.edgeTravelTicks(spec));
            sh.journeyStartGameTime = level.getGameTime();
            sh.transitPhase = TransitPhase.FORWARD;
            outboundShipments.add(sh);
            DuctIncomingIndex.register(level, worldPosition, sh.registeredIncoming.copy());
            setChanged();
            pushTransitSnapshotToClients(level);
            return;
        }
    }

    private boolean canScheduleTowardFace(
            ServerLevel level,
            BlockPos destDuct,
            DuctBlockEntity destBe,
            Direction destFace,
            ItemStack addition,
            DuctItemTransportSpec transportSpec) {
        if (addition.isEmpty()) {
            return false;
        }
        if (overflowBuffer.isSchedulingUnavailableForNewPulls()) {
            return false;
        }
        if (destBe.getOverflowBuffer().isSchedulingUnavailableForNewPulls()) {
            return false;
        }
        if (!DuctRedstoneLogic.isItemNodeActive(level, destDuct, destBe.getFaceNode(destFace))) {
            return false;
        }
        List<ItemStack> prior = DuctIncomingIndex.snapshot(level, destDuct);
        int cap =
                DuctCapHelper.maxInsertableAfterPendingOnFace(
                        level, destDuct, destFace, addition, addition.getCount(), prior);
        return cap >= addition.getCount();
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
     * Maximum extract/retrieve batch the player may store on this face: {@code batch.default} plus upgrade bonuses,
     * then limited by datapack {@code batch.max} when that value is {@code >= 0}. When {@code max} is negative,
     * only default + upgrades applies (not unlimited).
     */
    public int computeExtractBatchSettingCap(Direction face) {
        return itemTransportSpec().extractBatchSettingCap(getExtractBatchUpgradeBonus(face));
    }

    /** Per-face upgrade slots that raise extract batch; extend when upgrade items exist. */
    private int getExtractBatchUpgradeBonus(Direction face) {
        if (!DuctFeaturePolicy.isUsable(
                ductDefinition().orElse(null),
                DuctFeatureKeys.SPECIAL_UPGRADES,
                faceHasUpgradeSlots(face))) {
            return 0;
        }
        DuctFaceNode node = getFaceNode(face);
        int bonus = 0;
        for (int i = 0; i < DuctNodeMenu.UPGRADE_SLOT_COUNT; i++) {
            if (!node.guiSlots.getStackInSlot(i).isEmpty()) {
                // Future: parse upgrade item stats (e.g. +8 per tier).
            }
        }
        return bonus;
    }

    private boolean faceHasUpgradeSlots(Direction face) {
        DuctFaceNode node = getFaceNode(face);
        for (int i = 0; i < DuctNodeMenu.UPGRADE_SLOT_COUNT; i++) {
            if (!node.guiSlots.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String clampFilterLine(Optional<DuctDefinition> def, boolean hasUpgrade, String line) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        String fk = DuctFeatureKeys.filterSyntaxKey(line);
        if (fk != null && !DuctFeaturePolicy.isUsable(def.orElse(null), fk, hasUpgrade)) {
            return "";
        }
        return line;
    }

    private void scrubFilterListsForPolicy(Direction face, Optional<DuctDefinition> def, boolean hasUpgrade) {
        DuctFaceNode n = getFaceNode(face);
        scrubList(def, hasUpgrade, n.allowFilters);
        scrubList(def, hasUpgrade, n.denyFilters);
        scrubList(def, hasUpgrade, n.allowFiltersExtractor);
        scrubList(def, hasUpgrade, n.denyFiltersExtractor);
        scrubList(def, hasUpgrade, n.allowFiltersRetriever);
        scrubList(def, hasUpgrade, n.denyFiltersRetriever);
        scrubList(def, hasUpgrade, n.allowFiltersFilter);
        scrubList(def, hasUpgrade, n.denyFiltersFilter);
    }

    private static void scrubList(Optional<DuctDefinition> def, boolean hasUpgrade, List<String> list) {
        for (int i = 0; i < list.size(); i++) {
            list.set(i, clampFilterLine(def, hasUpgrade, list.get(i)));
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
            boolean hasUpgrade = faceHasUpgradeSlots(d);
            DuctFaceNode n = getFaceNode(d);
            if (!DuctFeaturePolicy.isModeUsable(def.orElse(null), n.nodeMode, hasUpgrade)) {
                n.nodeMode = NodeMode.NONE;
                any = true;
            }
            if (n.nodeMode.usesRouting()) {
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), n.routingMode, hasUpgrade)) {
                    n.routingMode = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), n.routingModeExtractor, hasUpgrade)) {
                    n.routingModeExtractor = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (!DuctFeaturePolicy.isRoutingUsable(def.orElse(null), n.routingModeRetriever, hasUpgrade)) {
                    n.routingModeRetriever = RoutingMode.NEAREST_FIRST;
                    any = true;
                }
                if (n.nodeMode == NodeMode.EXTRACTION_FILTERING || n.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                    n.routingMode = n.routingModeExtractor;
                }
            }
            if (!DuctFeaturePolicy.isUsable(def.orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, hasUpgrade)) {
                if (n.channelLetter != 1) {
                    n.channelLetter = 1;
                    any = true;
                }
            }
            int nonEmptyBefore = countNonEmptyLines(n);
            scrubFilterListsForPolicy(d, def, hasUpgrade);
            if (countNonEmptyLines(n) != nonEmptyBefore) {
                any = true;
            }
        }
        if (any) {
            clampFaceFiltersToSpec();
            for (Direction d : Direction.values()) {
                DuctFaceNode n = getFaceNode(d);
                if (n.nodeMode.usesExtractBatchField()) {
                    clampExtractAmount(n, d);
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

    public void refreshMenuData(Direction accessFace) {
        DuctFaceNode n = getFaceNode(accessFace);
        menuData.set(DuctMenuSync.NODE_MODE, n.nodeMode.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE, n.routingMode.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, n.routingModeExtractor.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, n.routingModeRetriever.ordinal());
        menuData.set(DuctMenuSync.PRIORITY, n.insertionPriority);
        menuData.set(DuctMenuSync.AMOUNT_FIELD, n.extractBatch);
        menuData.set(DuctMenuSync.EXTRACT_BATCH_CAP, computeExtractBatchSettingCap(accessFace));
        menuData.set(DuctMenuSync.CHANNEL, n.channelLetter);
        menuData.set(DuctMenuSync.REDSTONE_MODE, n.redstoneMode);
        int denyOverSync =
                switch (n.nodeMode) {
                    case FILTERING_INSERTION -> n.denyOverridesAllowFilter ? 1 : 0;
                    case EXTRACTION -> n.denyOverridesAllowExtractor ? 1 : 0;
                    case RETRIEVING -> n.denyOverridesAllowRetriever ? 1 : 0;
                    default -> n.denyOverridesAllow ? 1 : 0;
                };
        menuData.set(DuctMenuSync.DENY_OVERRIDES_ALLOW, denyOverSync);
        int flags = 0;
        if (n.nodeMode.usesRouting()) {
            flags |= DuctMenuSync.FLAG_ROUTING_ACTIVE;
        }
        if (n.nodeMode.usesItemFilterConfig()) {
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
    }

    public boolean passesItemFilters(Direction face, ItemStack stack, Level level) {
        return passesItemFilters(face, stack, level, DuctFaceNode.FilterBank.FILTER);
    }

    public boolean passesItemFilters(Direction face, ItemStack stack, Level level, DuctFaceNode.FilterBank bank) {
        if (stack.isEmpty()) {
            return false;
        }
        DuctFaceNode node = getFaceNode(face);
        if (node.nodeMode == NodeMode.NONE) {
            return true;
        }
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
        DuctItemTransportSpec spec = itemTransportSpec();
        for (Direction d : Direction.values()) {
            getFaceNode(d).clampFilterSizes(spec);
        }
    }

    public void applyServerFilterConfig(
            ServerPlayer player,
            Direction face,
            DuctFaceNode.FilterBank bank,
            List<String> allowIn,
            List<String> denyIn,
            boolean denyOverridesAllow) {
        if (level == null || level.isClientSide) {
            return;
        }
        if ((getSettingsFaceMask() & (1 << face.ordinal())) == 0) {
            return;
        }
        DuctItemTransportSpec spec = itemTransportSpec();
        DuctFaceNode node = getFaceNode(face);
        if (!node.nodeMode.usesItemFilterConfig()) {
            return;
        }
        List<String> a = node.bankAllowFilters(bank);
        List<String> d = node.bankDenyFilters(bank);
        a.clear();
        d.clear();
        int maxA =
                Math.max(
                        0,
                        node.nodeMode.isHybrid() ? spec.filterAllowHybridSlots() : spec.filterAllowSlots());
        int maxD =
                Math.max(
                        0,
                        node.nodeMode.isHybrid() ? spec.filterDenyHybridSlots() : spec.filterDenySlots());
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasUpgrade = faceHasUpgradeSlots(face);
        for (int i = 0; i < maxA; i++) {
            String s = i < allowIn.size() ? allowIn.get(i) : "";
            s = s != null ? s : "";
            a.add(clampFilterLine(def, hasUpgrade, s));
        }
        for (int i = 0; i < maxD; i++) {
            String s = i < denyIn.size() ? denyIn.get(i) : "";
            s = s != null ? s : "";
            d.add(clampFilterLine(def, hasUpgrade, s));
        }
        if (DuctFeaturePolicy.isUsable(def.orElse(null), DuctFeatureKeys.listPrecedenceKey(node.nodeMode, bank), hasUpgrade)) {
            node.setBankDenyOverridesAllow(bank, denyOverridesAllow);
        }
        node.clampFilterSizes(spec);
        setChanged();
        refreshMenuData(face);
        ModNetwork.sendFilterSyncToPlayer(player, this, face);
        syncVisualGeometryToClients();
    }

    public void toggleListLogicFromClient(ServerPlayer player, Direction face, DuctFaceNode.FilterBank bank) {
        if (level == null || level.isClientSide) {
            return;
        }
        if ((getSettingsFaceMask() & (1 << face.ordinal())) == 0) {
            return;
        }
        DuctFaceNode node = getFaceNode(face);
        if (!node.nodeMode.usesItemFilterConfig()) {
            return;
        }
        if (!DuctFeaturePolicy.isUsable(
                ductDefinition().orElse(null),
                DuctFeatureKeys.listPrecedenceKey(node.nodeMode, bank),
                faceHasUpgradeSlots(face))) {
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
        if ((getSettingsFaceMask() & (1 << face.ordinal())) == 0) {
            return;
        }
        DuctFaceNode node = getFaceNode(face);
        if (node.nodeMode != NodeMode.EXTRACTION_FILTERING && node.nodeMode != NodeMode.RETRIEVING_EXTRACTION) {
            return;
        }
        node.selfFeed = enabled;
        setChanged();
        refreshMenuData(face);
    }

    public boolean handleMenuButtonClick(Player player, int buttonId, Direction accessFace) {
        if (level == null || level.isClientSide) {
            return false;
        }
        if ((getSettingsFaceMask() & (1 << accessFace.ordinal())) == 0) {
            return false;
        }
        DuctFaceNode node = getFaceNode(accessFace);
        boolean changed =
                switch (buttonId) {
                    case 0 -> cycleNodeMode(node, accessFace);
                    case 10 -> cycleNodeModeBackward(node, accessFace);
                    case 1 -> {
                        if (!node.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield cycleRoutingMode(node, accessFace);
                    }
                    case 2 -> {
                        node.redstoneMode = (node.redstoneMode + 1) % 4;
                        yield true;
                    }
                    case 11 -> {
                        if (!node.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield cycleRoutingModeBackward(node, accessFace);
                    }
                    case 12 -> {
                        node.redstoneMode = Math.floorMod(node.redstoneMode - 1, 4);
                        yield true;
                    }
                    case 4 -> {
                        if (!DuctFeaturePolicy.isUsable(
                                ductDefinition().orElse(null),
                                DuctFeatureKeys.SPECIAL_CHANNEL,
                                faceHasUpgradeSlots(accessFace))) {
                            yield false;
                        }
                        node.channelLetter = node.channelLetter >= 26 ? 1 : node.channelLetter + 1;
                        yield true;
                    }
                    case 5 -> {
                        if (!DuctFeaturePolicy.isUsable(
                                ductDefinition().orElse(null),
                                DuctFeatureKeys.SPECIAL_CHANNEL,
                                faceHasUpgradeSlots(accessFace))) {
                            yield false;
                        }
                        node.channelLetter = node.channelLetter <= 1 ? 26 : node.channelLetter - 1;
                        yield true;
                    }
                    default -> false;
                };
        if (changed) {
            setChanged();
            refreshMenuData(accessFace);
            syncVisualGeometryToClients();
        }
        return changed;
    }

    private boolean cycleNodeMode(DuctFaceNode node, Direction accessFace) {
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
        boolean hasUpgrade = faceHasUpgradeSlots(accessFace);
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == node.nodeMode) {
                idx = i;
                break;
            }
        }
        for (int off = 1; off <= order.length; off++) {
            NodeMode cand = order[(idx + off) % order.length];
            if (DuctFeaturePolicy.isModeUsable(def.orElse(null), cand, hasUpgrade)) {
                if (cand != node.nodeMode) {
                    node.nodeMode = cand;
                    onModeChanged(node, accessFace);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean cycleNodeModeBackward(DuctFaceNode node, Direction accessFace) {
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
        boolean hasUpgrade = faceHasUpgradeSlots(accessFace);
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == node.nodeMode) {
                idx = i;
                break;
            }
        }
        for (int off = 1; off <= order.length; off++) {
            NodeMode cand = order[Math.floorMod(idx - off, order.length)];
            if (DuctFeaturePolicy.isModeUsable(def.orElse(null), cand, hasUpgrade)) {
                if (cand != node.nodeMode) {
                    node.nodeMode = cand;
                    onModeChanged(node, accessFace);
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
            boolean hasUpgrade) {
        int idx = current.ordinal();
        int dir = delta > 0 ? 1 : -1;
        for (int step = 1; step <= v.length; step++) {
            int ni = Math.floorMod(idx + dir * step, v.length);
            if (DuctFeaturePolicy.isRoutingUsable(def.orElse(null), v[ni], hasUpgrade)) {
                return v[ni];
            }
        }
        return current;
    }

    /**
     * Cycles the routing field that applies to the current node mode (including hybrid extract vs retrieve).
     */
    private boolean stepRouting(DuctFaceNode node, int delta, Direction accessFace) {
        if (!node.nodeMode.usesRouting()) {
            return false;
        }
        Optional<DuctDefinition> def = ductDefinition();
        boolean hasUpgrade = faceHasUpgradeSlots(accessFace);
        RoutingMode[] v = RoutingMode.values();
        return switch (node.nodeMode) {
            case EXTRACTION_FILTERING -> {
                RoutingMode cur = node.routingModeExtractor;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasUpgrade);
                if (nxt != cur) {
                    node.routingModeExtractor = nxt;
                    node.routingMode = nxt;
                    yield true;
                }
                yield false;
            }
            case RETRIEVING_EXTRACTION -> {
                RoutingMode cur = node.routingModeRetriever;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasUpgrade);
                if (nxt != cur) {
                    node.routingModeRetriever = nxt;
                    node.routingModeExtractor = nxt;
                    yield true;
                }
                yield false;
            }
            default -> {
                RoutingMode cur = node.routingMode;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def, hasUpgrade);
                if (nxt != cur) {
                    node.routingMode = nxt;
                    yield true;
                }
                yield false;
            }
        };
    }

    private void onModeChanged(DuctFaceNode node, Direction face) {
        if (node.nodeMode.usesExtractBatchField()) {
            DuctItemTransportSpec spec = itemTransportSpec();
            if (node.extractBatch <= 0) {
                node.extractBatch = spec.batchDefault();
            }
            clampExtractAmount(node, face);
        }
    }

    public void applyClientFieldUpdate(Direction accessFace, int insertionPriority, int extractBatch) {
        if (level == null || level.isClientSide) {
            return;
        }
        if ((getSettingsFaceMask() & (1 << accessFace.ordinal())) == 0) {
            return;
        }
        DuctFaceNode node = getFaceNode(accessFace);
        node.insertionPriority = insertionPriority;
        node.extractBatch = Math.max(0, extractBatch);
        clampExtractAmount(node, accessFace);
        setChanged();
        refreshMenuData(accessFace);
    }

    private void clampExtractAmount(DuctFaceNode node, Direction face) {
        int cap = computeExtractBatchSettingCap(face);
        node.extractBatch = Math.min(Math.max(0, node.extractBatch), cap);
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
            DuctFaceNode n = getFaceNode(dir);
            if (n.nodeMode == NodeMode.NONE
                    && n.insertionPriority == 0
                    && n.extractBatch == 0
                    && n.roundRobinCursor == 0) {
                continue;
            }
            n.resetPipeSegmentDefaults();
            any = true;
        }
        if (any) {
            setChanged();
            syncVisualGeometryToClients();
        }
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
            if (faceSuggestsPersistedStorageNode(getFaceNode(d))) {
                m |= 1 << d.ordinal();
            }
        }
        return m;
    }

    private static boolean faceSuggestsPersistedStorageNode(DuctFaceNode n) {
        if (n.nodeMode != NodeMode.NONE) {
            return true;
        }
        if (n.insertionPriority != 0
                || n.extractBatch != 0
                || n.roundRobinCursor != 0
                || n.ticksUntilAction != 0) {
            return true;
        }
        // Low/high are always intentional; 0 (ignore) and 3 (disabled) are defaults and must not alone infer latch bits.
        if (n.redstoneMode == 1 || n.redstoneMode == 2) {
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
            getFaceNode(d).save(registries, ft);
            faces.add(ft);
        }
        tag.put("FaceNodes", faces);
        tag.putString("DuctLogicalId", logicalDuctId);
        ListTag out = new ListTag();
        for (OutboundShipment s : outboundShipments) {
            out.add(s.save(registries));
        }
        tag.put("DuctOutbound", out);
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
        setConnectionMasksForLoad(tag.getByte("PipeMask") & 0xFF, tag.getByte("StorageMask") & 0xFF);
        if (tag.contains("FaceNodes", Tag.TAG_LIST)) {
            ListTag list = tag.getList("FaceNodes", Tag.TAG_COMPOUND);
            for (int i = 0; i < FACE_COUNT && i < list.size(); i++) {
                getFaceNode(Direction.values()[i]).load(registries, list.getCompound(i));
            }
        } else {
            for (Direction d : Direction.values()) {
                getFaceNode(d).loadFromLegacyRootTag(registries, tag);
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
        if (tag.contains("DuctLogicalId", Tag.TAG_STRING)) {
            logicalDuctId = DuctIds.normalize(tag.getString("DuctLogicalId"));
        }
        setUserDisconnectedFaceMaskForLoad(tag.contains("UserDisc", Tag.TAG_BYTE) ? tag.getByte("UserDisc") & 0xFF : 0);
        requestModelDataUpdate();
        clampFaceFiltersToSpec();
        for (Direction d : Direction.values()) {
            DuctFaceNode n = getFaceNode(d);
            if (n.nodeMode.usesExtractBatchField()) {
                clampExtractAmount(n, d);
            }
        }
        if (level != null && level.isClientSide) {
            // Chunk NBT has DuctOutbound but not TransitV1; BER reads DuctTransitClientState only.
            DuctTransitClientState.syncFromOutboundShipments(worldPosition, outboundShipments, level);
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
