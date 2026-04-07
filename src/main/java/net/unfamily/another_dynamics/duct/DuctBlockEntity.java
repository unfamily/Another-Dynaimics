package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.items.IItemHandler;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.logistics.DuctTargetSelector;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;
import net.unfamily.another_dynamics.duct.logistics.TransitPhase;
import net.unfamily.another_dynamics.client.transit.DuctTransitClientState;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

/**
 * Block entity for {@link DuctBlock}. Up to six independent <strong>item</strong> nodes (one per {@link Direction})
 * when that face touches item storage; additional transport kinds can be composed here for hybrid ducts.
 */
public final class DuctBlockEntity extends AbstractDuctBlockEntity {
    private static final int MAX_BLOCKED_ITEM_KINDS = 5;
    private static final int FACE_COUNT = 6;

    private final DuctFaceNode[] faceNodes = new DuctFaceNode[FACE_COUNT];

    private final List<OutboundShipment> outboundShipments = new ArrayList<>();
    private final List<ItemStack> migratedStorageBacklog = new ArrayList<>();

    private final SimpleContainerData menuData = new SimpleContainerData(DuctMenuSync.COUNT);

    /**
     * Faces that have ever had a live item-storage neighbor while loaded; preserves per-face settings in NBT when the
     * inventory is removed. Does not add node voxels (those follow {@link #getStorageMask()} only).
     */
    private int latchedStorageFaceMask;

    /** Client-only cached icon pack from server; avoids relying on full face-node sync for rendering. */
    private int clientPackedNodeIcons = defaultPackedNodeIcons();

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
        if (level == null) {
            return true;
        }
        return switch (n.redstoneMode) {
            case 0 -> true; // ignored
            case 1 -> !level.hasNeighborSignal(worldPosition); // low
            case 2 -> level.hasNeighborSignal(worldPosition); // high
            default -> false; // disabled
        };
    }

    public DuctBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ITEM_DUCT.get(), pos, state);
        for (int i = 0; i < FACE_COUNT; i++) {
            faceNodes[i] = new DuctFaceNode(this::setChanged);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && level.isClientSide && !outboundShipments.isEmpty()) {
            DuctTransitClientState.syncFromOutboundShipments(worldPosition, outboundShipments);
        }
    }

    public DuctFaceNode getFaceNode(Direction dir) {
        return faceNodes[dir.ordinal()];
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

    public static void clientTick(Level level, BlockPos pos, BlockState state, DuctBlockEntity be) {
        be.refreshFromWorld();
    }

    public void serverTickPipe(ServerLevel serverLevel) {
        if (isRemoved()) {
            return;
        }
        refreshFromWorld();
        tickOutboundShipments(serverLevel);
        tickMigratedBacklogFlush(serverLevel);
        if (!isStorageAttachmentNode()) {
            return;
        }

        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
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
            // Visual-only system should never negative-count; preserve behavior without dropping items.
            it.remove();
            setChanged();
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
                s.travelTicks--;
                setChanged();
                dirty = true;
                continue;
            }
            if (s.transitPhase == TransitPhase.RETURN) {
                // Visual-only: complete return by removing the shipment.
                DuctIncomingIndex.unregister(level, s.refundDuct, s.registeredIncoming.copy());
                it.remove();
                setChanged();
                dirty = true;
                continue;
            }
            if (!level.isLoaded(s.destDuct)) {
                continue;
            }
            if (s.destDuct.equals(worldPosition)) {
                finishRetrieverArrival(level, s, it);
            } else {
                finishExtractionDelivery(level, s, it);
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

    private boolean resizePendingShipment(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        if (s.transitPhase == TransitPhase.RETURN) {
            // Return leg does not reserve destination capacity.
            return true;
        }
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            beginVisualReturn(level, s);
            setChanged();
            return false;
        }
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            beginVisualReturn(level, s);
            setChanged();
            return false;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(destBe.getFaceNode(s.destFace).channelLetter, s)
                || !DuctChannelPolicy.faceMatchesShipment(srcBe.getFaceNode(s.sourceFace).channelLetter, s)) {
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            beginVisualReturn(level, s);
            setChanged();
            return false;
        }
        DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());

        int planned = s.stack.getCount();
        int capExt;
        if (s.legacyOmniFaces) {
            capExt =
                    s.legacyPhysicalBuffer
                            ? planned
                            : DuctCapHelper.countExtractableMatching(level, s.refundDuct, srcBe, s.stack, planned);
        } else {
            capExt =
                    s.legacyPhysicalBuffer
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
            if (s.legacyPhysicalBuffer && planned > 0) {
                refundExcessToSource(level, s, planned);
            }
            beginVisualReturn(level, s);
            setChanged();
            return false;
        }

        if (s.legacyPhysicalBuffer && newPlanned < planned) {
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

    private static List<BlockPos> reversedPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> out = new ArrayList<>(path.size());
        for (int i = path.size() - 1; i >= 0; i--) {
            out.add(path.get(i));
        }
        return OutboundShipment.copyPath(out);
    }

    private void beginVisualReturn(ServerLevel level, OutboundShipment s) {
        if (s.transitPhase == TransitPhase.RETURN) {
            return;
        }
        DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
        DuctIncomingIndex.unregister(level, s.refundDuct, s.registeredIncoming.copy());
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        List<BlockPos> path = !s.ductPath.isEmpty() ? reversedPath(s.ductPath) : List.of(s.destDuct, s.refundDuct);
        long travel = Math.max(1L, DuctPathfinder.pathTravelTicks(path, spec));
        int ticks = (int) Math.min(travel, Integer.MAX_VALUE);
        int edge = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, DuctPathfinder.edgeTravelTicks(spec)));
        s.resetLeg(TransitPhase.RETURN, path, ticks, edge, level.getGameTime());
        s.registeredIncoming = s.stack.copy();
        DuctIncomingIndex.register(level, s.refundDuct, s.registeredIncoming.copy());
    }

    private void refundExcessToSource(ServerLevel level, OutboundShipment s, int count) {
        ItemStack excess = s.stack.copy();
        excess.setCount(count);
        refundStackToSource(level, s, excess);
    }

    private void refundStackToSource(ServerLevel level, OutboundShipment s, ItemStack excess) {
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            dropAt(level, s.refundDuct, excess);
            return;
        }
        ItemStack left =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, srcBe, excess)
                        : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, excess);
        if (!left.isEmpty()) {
            dropAt(level, s.refundDuct, left);
        }
    }

    private void finishExtractionDelivery(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        if (!(level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity destBe)) {
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            beginVisualReturn(level, s);
            setChanged();
            return;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(destBe.getFaceNode(s.destFace).channelLetter, s)) {
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            beginVisualReturn(level, s);
            setChanged();
            return;
        }
        DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());

        if (s.legacyPhysicalBuffer) {
            ItemStack toInsert = s.stack.copy();
            NodeMode dm = destBe.getFaceNode(s.destFace).nodeMode;
            if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                    && !destBe.passesItemFilters(s.destFace, toInsert, level, DuctFaceNode.FilterBank.FILTER)) {
                DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
                beginVisualReturn(level, s);
                setChanged();
                return;
            }
            ItemStack remainder =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, s.stack.copy())
                            : DuctCapHelper.insertIntoFace(level, s.destDuct, s.destFace, s.stack.copy());
            it.remove();
            if (!remainder.isEmpty()) {
                refundStackToSource(level, s, remainder);
            }
            setChanged();
            return;
        }

        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe)) {
            it.remove();
            setChanged();
            return;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(srcBe.getFaceNode(s.sourceFace).channelLetter, s)) {
            DuctIncomingIndex.unregister(level, s.destDuct, s.registeredIncoming.copy());
            it.remove();
            setChanged();
            return;
        }
        int n = s.stack.getCount();
        ItemStack extracted =
                s.legacyOmniFaces
                        ? DuctCapHelper.extractMatchingUpTo(level, s.refundDuct, srcBe, s.stack, n)
                        : DuctCapHelper.extractMatchingUpToOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, n);
        it.remove();
        if (extracted.isEmpty()) {
            setChanged();
            return;
        }
        NodeMode dm2 = destBe.getFaceNode(s.destFace).nodeMode;
        if ((dm2 == NodeMode.FILTERING_INSERTION || dm2 == NodeMode.EXTRACTION_FILTERING)
                && !destBe.passesItemFilters(s.destFace, extracted, level, DuctFaceNode.FilterBank.FILTER)) {
            beginVisualReturn(level, s);
            setChanged();
            return;
        }
        ItemStack remainder =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, extracted.copy())
                        : DuctCapHelper.insertIntoFace(level, s.destDuct, s.destFace, extracted.copy());
        if (!remainder.isEmpty()) {
            ItemStack back =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, srcBe, remainder)
                            : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, remainder);
            if (!back.isEmpty()) {
                dropAt(level, s.refundDuct, back);
            }
        }
        setChanged();
    }

    private void finishRetrieverArrival(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        DuctIncomingIndex.unregister(level, worldPosition, s.registeredIncoming.copy());
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity donorBe)) {
            beginVisualReturn(level, s);
            setChanged();
            return;
        }
        if (!DuctChannelPolicy.faceMatchesShipment(donorBe.getFaceNode(s.sourceFace).channelLetter, s)
                || !DuctChannelPolicy.faceMatchesShipment(getFaceNode(s.destFace).channelLetter, s)) {
            beginVisualReturn(level, s);
            setChanged();
            return;
        }
        if (s.legacyPhysicalBuffer) {
            ItemStack toInsert = s.stack.copy();
            NodeMode sm = getFaceNode(s.destFace).nodeMode;
            if ((sm == NodeMode.FILTERING_INSERTION || sm == NodeMode.EXTRACTION_FILTERING)
                    && !passesItemFilters(s.destFace, toInsert, level, DuctFaceNode.FilterBank.FILTER)) {
                DuctIncomingIndex.unregister(level, worldPosition, s.registeredIncoming.copy());
                beginVisualReturn(level, s);
                setChanged();
                return;
            }
            ItemStack remainder =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, s.stack.copy())
                            : DuctCapHelper.insertIntoFace(level, worldPosition, s.destFace, s.stack.copy());
            it.remove();
            if (!remainder.isEmpty()) {
                ItemStack back =
                        s.legacyOmniFaces
                                ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, donorBe, remainder)
                                : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, remainder);
                if (!back.isEmpty()) {
                    dropAt(level, s.refundDuct, back);
                }
            }
            setChanged();
            return;
        }
        int n = s.stack.getCount();
        ItemStack extracted =
                s.legacyOmniFaces
                        ? DuctCapHelper.extractMatchingUpTo(level, s.refundDuct, donorBe, s.stack, n)
                        : DuctCapHelper.extractMatchingUpToOnFace(
                                level, s.refundDuct, s.sourceFace, s.stack, n);
        it.remove();
        if (extracted.isEmpty()) {
            setChanged();
            return;
        }
        NodeMode sm2 = getFaceNode(s.destFace).nodeMode;
        if ((sm2 == NodeMode.FILTERING_INSERTION || sm2 == NodeMode.EXTRACTION_FILTERING)
                && !passesItemFilters(s.destFace, extracted, level, DuctFaceNode.FilterBank.FILTER)) {
            beginVisualReturn(level, s);
            setChanged();
            return;
        }
        ItemStack remainder =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, extracted.copy())
                        : DuctCapHelper.insertIntoFace(level, worldPosition, s.destFace, extracted.copy());
        if (!remainder.isEmpty()) {
            ItemStack back =
                    s.legacyOmniFaces
                            ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, donorBe, remainder)
                            : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, remainder);
            if (!back.isEmpty()) {
                dropAt(level, s.refundDuct, back);
            }
        }
        setChanged();
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
            ItemStack left = DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, b.copy());
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
            int batch = effectiveExtractBatch(spec, node, face);
            int avail = DuctCapHelper.countExtractableMatchingOnFace(level, worldPosition, face, probe, batch);
            int plannedCount = Math.min(batch, Math.min(probe.getMaxStackSize(), avail));
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack planned = probe.copy();
            planned.setCount(plannedCount);
            if (!canScheduleTowardFace(level, dest, destBe, destFace, planned)) {
                continue;
            }
            node.roundRobinCursor = rrProbe[0];
            long travel = Math.max(1L, DuctPathfinder.pathTravelTicks(path, spec));
            int travelTicks = (int) Math.min(travel, Integer.MAX_VALUE);
            OutboundShipment sh =
                    new OutboundShipment(planned, dest, destFace, travelTicks, worldPosition, face, node.channelLetter);
            sh.ductPath = OutboundShipment.copyPath(path);
            sh.totalTravelTicks = travelTicks;
            sh.edgeTicks = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, DuctPathfinder.edgeTravelTicks(spec)));
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
            int batch = effectiveExtractBatch(spec, node, retrieverFace);
            int avail = DuctCapHelper.countExtractableMatchingOnFace(level, donor, donorFace, probe, batch);
            int plannedCount = Math.min(batch, Math.min(probe.getMaxStackSize(), avail));
            if (plannedCount <= 0) {
                continue;
            }
            ItemStack planned = probe.copy();
            planned.setCount(plannedCount);
            if (!canScheduleTowardFace(level, worldPosition, this, retrieverFace, planned)) {
                continue;
            }
            long travel = Math.max(1L, DuctPathfinder.pathTravelTicks(path, spec));
            int travelTicks = (int) Math.min(travel, Integer.MAX_VALUE);
            OutboundShipment sh =
                    new OutboundShipment(planned, worldPosition, retrieverFace, travelTicks, donor, donorFace, node.channelLetter);
            sh.ductPath = OutboundShipment.copyPath(path);
            sh.totalTravelTicks = travelTicks;
            sh.edgeTicks = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, DuctPathfinder.edgeTravelTicks(spec)));
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
            ServerLevel level, BlockPos destDuct, DuctBlockEntity destBe, Direction destFace, ItemStack addition) {
        if (addition.isEmpty()) {
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

    private static void dropAt(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        level.addFreshEntity(
                new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }

    private int effectiveExtractBatch(DuctItemTransportSpec spec, DuctFaceNode node, Direction face) {
        int req = node.extractBatch <= 0 ? spec.batchDefault() : node.extractBatch;
        int cap = computeExtractBatchSettingCap(face);
        return Math.min(Math.max(0, req), cap);
    }

    /**
     * Maximum extract/retrieve batch the player may store on this face: {@code batch.default} plus upgrade bonuses,
     * then limited by datapack {@code batch.max} when that value is {@code >= 0}. When {@code max} is negative,
     * only default + upgrades applies (not unlimited).
     */
    public int computeExtractBatchSettingCap(Direction face) {
        return DuctDefinitionRegistry.itemDuctTransportSpec().extractBatchSettingCap(getExtractBatchUpgradeBonus(face));
    }

    /** Per-face upgrade slots that raise extract batch; extend when upgrade items exist. */
    private int getExtractBatchUpgradeBonus(Direction face) {
        DuctFaceNode node = getFaceNode(face);
        int bonus = 0;
        for (int i = 0; i < DuctNodeMenu.UPGRADE_SLOT_COUNT; i++) {
            if (!node.guiSlots.getStackInSlot(i).isEmpty()) {
                // Future: parse upgrade item stats (e.g. +8 per tier).
            }
        }
        return bonus;
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
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
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
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
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
        for (int i = 0; i < maxA; i++) {
            String s = i < allowIn.size() ? allowIn.get(i) : "";
            a.add(s != null ? s : "");
        }
        for (int i = 0; i < maxD; i++) {
            String s = i < denyIn.size() ? denyIn.get(i) : "";
            d.add(s != null ? s : "");
        }
        node.setBankDenyOverridesAllow(bank, denyOverridesAllow);
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
                    case 0 -> {
                        cycleNodeMode(node, accessFace);
                        yield true;
                    }
                    case 10 -> {
                        cycleNodeModeBackward(node, accessFace);
                        yield true;
                    }
                    case 1 -> {
                        if (!node.nodeMode.usesRouting()) {
                            yield false;
                        }
                        cycleRoutingMode(node);
                        yield true;
                    }
                    case 2 -> {
                        node.redstoneMode = (node.redstoneMode + 1) % 4;
                        yield true;
                    }
                    case 11 -> {
                        if (!node.nodeMode.usesRouting()) {
                            yield false;
                        }
                        cycleRoutingModeBackward(node);
                        yield true;
                    }
                    case 12 -> {
                        node.redstoneMode = Math.floorMod(node.redstoneMode - 1, 4);
                        yield true;
                    }
                    case 4 -> {
                        node.channelLetter = node.channelLetter >= 26 ? 1 : node.channelLetter + 1;
                        yield true;
                    }
                    case 5 -> {
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

    private void cycleNodeMode(DuctFaceNode node, Direction accessFace) {
        // Keep enum ordinals stable (NBT), but cycle in a user-friendly order with hybrid modes last.
        NodeMode[] order =
                new NodeMode[] {
                    NodeMode.NONE,
                    NodeMode.EXTRACTION,
                    NodeMode.FILTERING_INSERTION,
                    NodeMode.RETRIEVING,
                    NodeMode.EXTRACTION_FILTERING,
                    NodeMode.RETRIEVING_EXTRACTION
                };
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == node.nodeMode) {
                idx = i;
                break;
            }
        }
        node.nodeMode = order[(idx + 1) % order.length];
        onModeChanged(node, accessFace);
    }

    private void cycleNodeModeBackward(DuctFaceNode node, Direction accessFace) {
        NodeMode[] order =
                new NodeMode[] {
                    NodeMode.NONE,
                    NodeMode.EXTRACTION,
                    NodeMode.FILTERING_INSERTION,
                    NodeMode.RETRIEVING,
                    NodeMode.EXTRACTION_FILTERING,
                    NodeMode.RETRIEVING_EXTRACTION
                };
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == node.nodeMode) {
                idx = i;
                break;
            }
        }
        node.nodeMode = order[Math.floorMod(idx - 1, order.length)];
        onModeChanged(node, accessFace);
    }

    private void cycleRoutingMode(DuctFaceNode node) {
        stepRouting(node, 1);
    }

    private void cycleRoutingModeBackward(DuctFaceNode node) {
        stepRouting(node, -1);
    }

    /**
     * Cycles the routing field that applies to the current node mode (including hybrid extract vs retrieve).
     */
    private void stepRouting(DuctFaceNode node, int delta) {
        if (!node.nodeMode.usesRouting()) {
            return;
        }
        RoutingMode[] v = RoutingMode.values();
        switch (node.nodeMode) {
            case EXTRACTION_FILTERING -> {
                int idx = node.routingModeExtractor.ordinal();
                RoutingMode next = v[Math.floorMod(idx + delta, v.length)];
                node.routingModeExtractor = next;
                node.routingMode = next;
            }
            case RETRIEVING_EXTRACTION -> {
                int idx = node.routingModeRetriever.ordinal();
                RoutingMode next = v[Math.floorMod(idx + delta, v.length)];
                node.routingModeRetriever = next;
                node.routingModeExtractor = next;
            }
            default -> {
                int idx = node.routingMode.ordinal();
                node.routingMode = v[Math.floorMod(idx + delta, v.length)];
            }
        }
    }

    private void onModeChanged(DuctFaceNode node, Direction face) {
        if (node.nodeMode.usesExtractBatchField()) {
            DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
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
        if (n.redstoneMode != 0) {
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

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("PipeMask", (byte) getPipeMask());
        tag.putByte("StorageMask", (byte) getStorageMask());
        tag.putByte("LatchedFaces", (byte) latchedStorageFaceMask);
        ListTag faces = new ListTag();
        for (Direction d : Direction.values()) {
            CompoundTag ft = new CompoundTag();
            getFaceNode(d).save(registries, ft);
            faces.add(ft);
        }
        tag.put("FaceNodes", faces);
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
        requestModelDataUpdate();
        clampFaceFiltersToSpec();
        if (level != null && level.isClientSide) {
            // Chunk NBT has DuctOutbound but not TransitV1; BER reads DuctTransitClientState only.
            DuctTransitClientState.syncFromOutboundShipments(worldPosition, outboundShipments);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.putByte("PipeMask", (byte) getPipeMask());
        t.putByte("StorageMask", (byte) getStorageMask());
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
            item.putByte("Ph", (byte) s.transitPhase.ordinal());
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
        return t;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        if (tag.contains("LatchedFaces", Tag.TAG_BYTE)) {
            latchedStorageFaceMask = tag.getByte("LatchedFaces") & 0xFF;
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
            DuctTransitClientState.onDuctUpdateTag(worldPosition, tag, level.registryAccess());
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
                .build();
    }
}
