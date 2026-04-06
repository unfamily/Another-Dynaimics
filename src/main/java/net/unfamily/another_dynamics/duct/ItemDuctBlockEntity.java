package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctIncomingIndex;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.logistics.DuctTargetSelector;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

public final class ItemDuctBlockEntity extends BlockEntity implements MenuProvider {
    private static final int MAX_BLOCKED_ITEM_KINDS = 5;

    private int pipeMask;
    private int storageMask;

    private NodeMode nodeMode = NodeMode.NONE;
    private RoutingMode routingMode = RoutingMode.NEAREST_FIRST;
    /**
     * Extract modes: items per pull (0 = duct default batch). Insert modes ({@link NodeMode#NONE},
     * {@link NodeMode#FILTERING_INSERTION}): insertion priority (may be negative).
     */
    private int amountField;
    private int channelLetter = 1;
    private int redstoneMode;
    private int roundRobinCursor;

    private int ticksUntilAction;

    /**
     * On storage-attached nodes only: items removed from a chest and moving toward another duct, or stuck here
     * ({@link OutboundShipment#STUCK_TICKS}). Not stored on plain pipe blocks.
     */
    private final List<OutboundShipment> outboundShipments = new ArrayList<>();
    /**
     * Excess that could not enter the adjacent inventory; blocks duct inserts of the same item kind until cleared.
     */
    private final List<ItemStack> storageBacklog = new ArrayList<>();

    private final ItemStackHandler nodeGuiSlots = new ItemStackHandler(DuctNodeMenu.MACHINE_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            ItemDuctBlockEntity.this.setChanged();
        }
    };

    private final SimpleContainerData menuData = new SimpleContainerData(DuctMenuSync.COUNT);

    public ItemDuctBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ITEM_DUCT.get(), pos, state);
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
                DuctIncomingIndex.register(sl, s.destDuct, s.stack.copy());
            }
        }
    }

    private void unregisterOutboundFromIncomingIndex(ServerLevel sl) {
        for (OutboundShipment s : outboundShipments) {
            if (s.travelTicks >= 0 && !s.stack.isEmpty()) {
                DuctIncomingIndex.unregister(sl, s.destDuct, s.stack.copy());
            }
        }
    }

    public ItemStackHandler getNodeGuiSlots() {
        return nodeGuiSlots;
    }

    public SimpleContainerData getMenuData() {
        return menuData;
    }

    public NodeMode getNodeMode() {
        return nodeMode;
    }

    public RoutingMode getRoutingMode() {
        return routingMode;
    }

    public int getAmountField() {
        return amountField;
    }

    public int getChannelLetter() {
        return channelLetter;
    }

    /** Client-only: keeps visuals in sync when neighbors appear before {@code neighborChanged} runs (e.g. new duct next to existing). */
    public static void clientTick(Level level, BlockPos pos, BlockState state, ItemDuctBlockEntity be) {
        be.refreshFromWorld();
    }

    public void serverTickPipe(ServerLevel serverLevel) {
        if (isRemoved()) {
            return;
        }
        refreshFromWorld();
        refreshMenuData();
        if (!isStorageAttachmentNode()) {
            return;
        }
        tickOutboundShipments(serverLevel);
        tickStorageBacklogFlush(serverLevel);

        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        int rate = spec.clampedRateTicks(spec.rateDefaultTicks());
        if (ticksUntilAction > 0) {
            ticksUntilAction--;
            return;
        }
        ticksUntilAction = rate - 1;

        if (nodeMode == NodeMode.EXTRACTION) {
            tickExtractionPull(serverLevel, spec);
        } else if (nodeMode == NodeMode.RETRIEVING) {
            tickRetrieverPull(serverLevel, spec);
        }
    }

    private void tickOutboundShipments(ServerLevel level) {
        Iterator<OutboundShipment> it = outboundShipments.iterator();
        while (it.hasNext()) {
            OutboundShipment s = it.next();
            if (s.stack.isEmpty()) {
                it.remove();
                setChanged();
                continue;
            }
            if (s.travelTicks < 0) {
                continue;
            }
            if (s.travelTicks > 0) {
                s.travelTicks--;
                setChanged();
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
        }
    }

    private void finishExtractionDelivery(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        ItemStack registered = s.stack.copy();
        if (!(level.getBlockEntity(s.destDuct) instanceof ItemDuctBlockEntity destBe)) {
            refundOrStuck(level, s, it, registered);
            return;
        }
        if (destBe.blocksAllDuctInserts() || destBe.isItemKindBlockedInBacklog(s.stack)) {
            refundOrStuck(level, s, it, registered);
            return;
        }
        DuctIncomingIndex.unregister(level, s.destDuct, registered);
        ItemStack remainder = DuctCapHelper.insertIntoStorageFaces(level, s.destDuct, destBe, s.stack.copy());
        it.remove();
        if (!remainder.isEmpty()) {
            destBe.addToStorageBacklog(remainder);
            destBe.setChanged();
        }
        setChanged();
    }

    private void finishRetrieverArrival(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it) {
        ItemStack registered = s.stack.copy();
        DuctIncomingIndex.unregister(level, worldPosition, registered);
        ItemStack remainder = acceptFromDuctNetwork(level, s.stack.copy());
        it.remove();
        if (!remainder.isEmpty()) {
            addToStorageBacklog(remainder);
        }
        setChanged();
    }

    private void refundOrStuck(ServerLevel level, OutboundShipment s, Iterator<OutboundShipment> it, ItemStack registered) {
        DuctIncomingIndex.unregister(level, s.destDuct, registered);
        if (!(level.getBlockEntity(s.refundDuct) instanceof ItemDuctBlockEntity refundBe)) {
            dropAt(level, s.refundDuct, s.stack.copy());
            it.remove();
            setChanged();
            return;
        }
        ItemStack left = DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, refundBe, s.stack.copy());
        if (left.isEmpty()) {
            it.remove();
        } else {
            s.stack = left;
            s.travelTicks = OutboundShipment.STUCK_TICKS;
        }
        setChanged();
    }

    private void tickStorageBacklogFlush(ServerLevel level) {
        if (storageBacklog.isEmpty()) {
            return;
        }
        Iterator<ItemStack> it = storageBacklog.iterator();
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

    private void tickExtractionPull(ServerLevel level, DuctItemTransportSpec spec) {
        if (distinctStuckOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS) {
            return;
        }
        Optional<ItemStack> probe = DuctCapHelper.simulateExtractOne(level, worldPosition, this);
        if (probe.isEmpty()) {
            return;
        }
        int[] rr = new int[] {roundRobinCursor};
        Optional<List<BlockPos>> pathOpt =
                DuctTargetSelector.selectExtractionDelivery(level, worldPosition, probe.get(), routingMode, rr);
        roundRobinCursor = rr[0];
        if (pathOpt.isEmpty()) {
            return;
        }
        List<BlockPos> path = pathOpt.get();
        BlockPos dest = path.get(path.size() - 1);
        if (!(level.getBlockEntity(dest) instanceof ItemDuctBlockEntity destBe)) {
            return;
        }
        int batch = effectiveExtractBatch(spec);
        ItemStack planned = probe.get().copy();
        planned.setCount(Math.min(batch, planned.getMaxStackSize()));
        if (!canScheduleToward(level, dest, destBe, planned)) {
            return;
        }
        ItemStack extracted = DuctCapHelper.extractFromStorageFaces(level, worldPosition, this, batch);
        if (extracted.isEmpty()) {
            return;
        }
        if (!canScheduleToward(level, dest, destBe, extracted)) {
            ItemStack back = DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, extracted);
            if (!back.isEmpty()) {
                dropAt(level, worldPosition, back);
            }
            return;
        }
        long travel = Math.max(1L, DuctPathfinder.pathTravelTicks(path, spec));
        int travelTicks = (int) Math.min(travel, Integer.MAX_VALUE);
        OutboundShipment sh = new OutboundShipment(extracted, dest, travelTicks, worldPosition);
        outboundShipments.add(sh);
        DuctIncomingIndex.register(level, dest, extracted.copy());
        setChanged();
    }

    private void tickRetrieverPull(ServerLevel level, DuctItemTransportSpec spec) {
        if (distinctStuckOutboundKinds() >= MAX_BLOCKED_ITEM_KINDS
                || distinctBacklogKinds() >= MAX_BLOCKED_ITEM_KINDS) {
            return;
        }
        int[] rr = new int[] {roundRobinCursor};
        Optional<List<BlockPos>> pathOpt = DuctTargetSelector.selectRetrievingDonorPath(level, worldPosition, routingMode, rr);
        roundRobinCursor = rr[0];
        if (pathOpt.isEmpty()) {
            return;
        }
        List<BlockPos> path = pathOpt.get();
        BlockPos donor = path.get(0);
        if (!(level.getBlockEntity(donor) instanceof ItemDuctBlockEntity donorBe)) {
            return;
        }
        Optional<ItemStack> donorProbe = DuctCapHelper.simulateExtractOne(level, donor, donorBe);
        if (donorProbe.isEmpty()) {
            return;
        }
        int batch = effectiveExtractBatch(spec);
        ItemStack planned = donorProbe.get().copy();
        planned.setCount(Math.min(batch, planned.getMaxStackSize()));
        if (!canScheduleToward(level, worldPosition, this, planned)) {
            return;
        }
        ItemStack pulled = DuctCapHelper.extractFromStorageFaces(level, donor, donorBe, batch);
        if (pulled.isEmpty()) {
            return;
        }
        if (!canScheduleToward(level, worldPosition, this, pulled)) {
            ItemStack back = DuctCapHelper.insertIntoStorageFaces(level, donor, donorBe, pulled);
            if (!back.isEmpty()) {
                dropAt(level, donor, back);
            }
            return;
        }
        long travel = Math.max(1L, DuctPathfinder.pathTravelTicks(path, spec));
        int travelTicks = (int) Math.min(travel, Integer.MAX_VALUE);
        OutboundShipment sh = new OutboundShipment(pulled, worldPosition, travelTicks, donor);
        outboundShipments.add(sh);
        DuctIncomingIndex.register(level, worldPosition, pulled.copy());
        setChanged();
    }

    private boolean canScheduleToward(ServerLevel level, BlockPos destDuct, ItemDuctBlockEntity destBe, ItemStack addition) {
        List<ItemStack> order = new ArrayList<>();
        order.addAll(destBe.copiesOfStorageBacklog());
        order.addAll(DuctIncomingIndex.snapshot(level, destDuct));
        order.add(addition);
        return DuctCapHelper.canInsertStacksSequentially(level, destDuct, destBe, order);
    }

    /** Try to insert items arriving from the duct network into adjacent storage (respects backlog lock rules). */
    public ItemStack acceptFromDuctNetwork(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (blocksAllDuctInserts() || isItemKindBlockedInBacklog(stack)) {
            return stack;
        }
        return DuctCapHelper.insertIntoStorageFaces(level, worldPosition, this, stack);
    }

    public boolean blocksAllDuctInserts() {
        return distinctBacklogKinds() >= MAX_BLOCKED_ITEM_KINDS;
    }

    public boolean isItemKindBlockedInBacklog(ItemStack probe) {
        if (probe.isEmpty()) {
            return false;
        }
        for (ItemStack b : storageBacklog) {
            if (ItemStack.isSameItemSameComponents(b, probe)) {
                return true;
            }
        }
        return false;
    }

    private void addToStorageBacklog(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack rest = stack.copy();
        for (ItemStack b : storageBacklog) {
            if (ItemStack.isSameItemSameComponents(b, rest) && b.getCount() < b.getMaxStackSize()) {
                int n = Math.min(rest.getCount(), b.getMaxStackSize() - b.getCount());
                b.grow(n);
                rest.shrink(n);
                if (rest.isEmpty()) {
                    setChanged();
                    return;
                }
            }
        }
        if (!rest.isEmpty()) {
            storageBacklog.add(rest);
        }
        setChanged();
    }

    private List<ItemStack> copiesOfStorageBacklog() {
        List<ItemStack> out = new ArrayList<>(storageBacklog.size());
        for (ItemStack b : storageBacklog) {
            out.add(b.copy());
        }
        return out;
    }

    private int distinctStuckOutboundKinds() {
        List<ItemStack> kinds = new ArrayList<>();
        for (OutboundShipment s : outboundShipments) {
            if (s.travelTicks >= 0 || s.stack.isEmpty()) {
                continue;
            }
            if (!containsItemKind(kinds, s.stack)) {
                kinds.add(s.stack);
            }
        }
        return kinds.size();
    }

    private int distinctBacklogKinds() {
        List<ItemStack> kinds = new ArrayList<>();
        for (ItemStack b : storageBacklog) {
            if (b.isEmpty()) {
                continue;
            }
            if (!containsItemKind(kinds, b)) {
                kinds.add(b);
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

    private int effectiveExtractBatch(DuctItemTransportSpec spec) {
        int req = amountField <= 0 ? spec.batchDefault() : amountField;
        return spec.clampedBatch(req);
    }

    public void refreshMenuData() {
        menuData.set(DuctMenuSync.NODE_MODE, nodeMode.ordinal());
        menuData.set(DuctMenuSync.ROUTING_MODE, routingMode.ordinal());
        menuData.set(DuctMenuSync.PRIORITY, amountField);
        menuData.set(DuctMenuSync.AMOUNT_FIELD, amountField);
        menuData.set(DuctMenuSync.CHANNEL, channelLetter);
        menuData.set(DuctMenuSync.REDSTONE_MODE, redstoneMode);
        int flags = nodeMode == NodeMode.EXTRACTION ? DuctMenuSync.FLAG_ROUTING_ACTIVE : 0;
        menuData.set(DuctMenuSync.FLAGS, flags);
        menuData.set(DuctMenuSync.POS_X, worldPosition.getX());
        menuData.set(DuctMenuSync.POS_Y, worldPosition.getY());
        menuData.set(DuctMenuSync.POS_Z, worldPosition.getZ());
    }

    public boolean handleMenuButtonClick(Player player, int buttonId) {
        if (level == null || level.isClientSide) {
            return false;
        }
        boolean changed = switch (buttonId) {
            case 0 -> {
                if (!isStorageAttachmentNode()) {
                    yield false;
                }
                cycleNodeMode();
                yield true;
            }
            case 1 -> {
                if (isStorageAttachmentNode() && nodeMode == NodeMode.EXTRACTION) {
                    cycleRoutingMode();
                    yield true;
                }
                yield false;
            }
            case 2 -> {
                redstoneMode = (redstoneMode + 1) % 5;
                yield true;
            }
            case 4 -> {
                channelLetter = channelLetter >= 26 ? 1 : channelLetter + 1;
                yield true;
            }
            case 5 -> {
                channelLetter = channelLetter <= 1 ? 26 : channelLetter - 1;
                yield true;
            }
            case 6 -> {
                if (!(player instanceof ServerPlayer sp) || !isStorageAttachmentNode()) {
                    yield false;
                }
                yield dumpStuckBufferToPlayer(sp);
            }
            case 7 -> {
                if (!(player instanceof ServerPlayer sp) || !isStorageAttachmentNode()) {
                    yield false;
                }
                yield dumpEntireBufferToPlayer(sp);
            }
            default -> false;
        };
        if (changed) {
            setChanged();
            refreshMenuData();
        }
        return changed;
    }

    /**
     * Ejects items blocked on this node: outbound with {@link OutboundShipment#STUCK_TICKS} (could not refund to
     * attached storage) and {@link #storageBacklog} (could not insert into attached storage, e.g. dest overflow).
     */
    private boolean dumpStuckBufferToPlayer(ServerPlayer player) {
        boolean any = false;
        Iterator<OutboundShipment> it = outboundShipments.iterator();
        while (it.hasNext()) {
            OutboundShipment s = it.next();
            if (s.travelTicks >= 0 || s.stack.isEmpty()) {
                continue;
            }
            giveStackToPlayer(player, s.stack.copy());
            it.remove();
            any = true;
        }
        List<ItemStack> blCopy = new ArrayList<>(storageBacklog);
        for (ItemStack b : blCopy) {
            if (b.isEmpty()) {
                continue;
            }
            giveStackToPlayer(player, b.copy());
            any = true;
        }
        storageBacklog.clear();
        return any;
    }

    /**
     * Ejects all outbound (including in-transit) and storage backlog into the player inventory.
     */
    private boolean dumpEntireBufferToPlayer(ServerPlayer player) {
        if (!(level instanceof ServerLevel sl)) {
            return false;
        }
        boolean any = false;
        unregisterOutboundFromIncomingIndex(sl);
        List<OutboundShipment> outCopy = new ArrayList<>(outboundShipments);
        for (OutboundShipment s : outCopy) {
            if (!s.stack.isEmpty()) {
                giveStackToPlayer(player, s.stack.copy());
                any = true;
            }
        }
        outboundShipments.clear();
        List<ItemStack> blCopy = new ArrayList<>(storageBacklog);
        for (ItemStack b : blCopy) {
            if (!b.isEmpty()) {
                giveStackToPlayer(player, b.copy());
                any = true;
            }
        }
        storageBacklog.clear();
        return any;
    }

    private static void giveStackToPlayer(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        InvWrapper inv = new InvWrapper(player.getInventory());
        ItemStack left = ItemHandlerHelper.insertItemStacked(inv, stack, false);
        if (!left.isEmpty()) {
            player.drop(left, false);
        }
    }

    private void cycleNodeMode() {
        NodeMode[] v = NodeMode.values();
        nodeMode = v[(nodeMode.ordinal() + 1) % v.length];
        onModeChanged();
    }

    private void cycleRoutingMode() {
        RoutingMode[] v = RoutingMode.values();
        routingMode = v[(routingMode.ordinal() + 1) % v.length];
    }

    private void onModeChanged() {
        if (nodeMode.usesExtractBatchField()) {
            DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
            if (amountField <= 0) {
                amountField = spec.batchDefault();
            }
            clampExtractAmount();
        }
    }

    public void applyClientFieldUpdate(int value) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (!isStorageAttachmentNode()) {
            return;
        }
        if (nodeMode.usesInsertionPriorityField()) {
            amountField = value;
        } else if (nodeMode.usesExtractBatchField()) {
            amountField = Math.max(0, value);
            clampExtractAmount();
        }
        setChanged();
        refreshMenuData();
    }

    private void clampExtractAmount() {
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        int maxAllowed = spec.batchMax() < 0 ? Integer.MAX_VALUE : Math.max(1, spec.batchMax());
        amountField = Math.min(amountField, maxAllowed);
    }

    public void refreshFromWorld() {
        if (level == null) {
            return;
        }
        int pipe = 0;
        int storage = 0;
        for (Direction dir : Direction.values()) {
            BlockPos n = worldPosition.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (ns.getBlock() instanceof ItemDuctBlock) {
                pipe |= 1 << dir.ordinal();
            } else if (!ns.isAir()) {
                IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, n, dir.getOpposite());
                if (cap != null && cap.getSlots() > 0) {
                    storage |= 1 << dir.ordinal();
                }
            }
        }
        storage &= ~pipe;
        boolean masksChanged = pipeMask != pipe || storageMask != storage;
        pipeMask = pipe;
        storageMask = storage;
        if (masksChanged) {
            requestModelDataUpdate();
            if (!level.isClientSide) {
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        if (!level.isClientSide) {
            enforcePipeSegmentBehavior();
        }
    }

    /**
     * A duct without a storage face is only conduit geometry; reset GUI-facing node fields.
     * <p>
     * We do <strong>not</strong> clear {@link #outboundShipments} or {@link #storageBacklog} here: {@code storageMask}
     * can be 0 for a tick (capabilities not ready, load order) and would wipe in-flight items from disk/memory with
     * no trace on either node. Buffers stay until delivered, dump, or block removal.
     */
    private void enforcePipeSegmentBehavior() {
        if (storageMask != 0) {
            return;
        }
        if (nodeMode == NodeMode.NONE && amountField == 0 && roundRobinCursor == 0) {
            return;
        }
        nodeMode = NodeMode.NONE;
        amountField = 0;
        roundRobinCursor = 0;
        setChanged();
        refreshMenuData();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("PipeMask", (byte) pipeMask);
        tag.putByte("StorageMask", (byte) storageMask);
        tag.put("NodeGui", nodeGuiSlots.serializeNBT(registries));
        tag.putByte("NodeMode", (byte) nodeMode.ordinal());
        tag.putByte("RoutingMode", (byte) routingMode.ordinal());
        tag.putInt("AmountField", amountField);
        tag.putByte("Channel", (byte) channelLetter);
        tag.putByte("RedstoneMode", (byte) redstoneMode);
        tag.putInt("RrCursor", roundRobinCursor);
        ListTag out = new ListTag();
        for (OutboundShipment s : outboundShipments) {
            out.add(s.save(registries));
        }
        tag.put("DuctOutbound", out);
        ListTag bl = new ListTag();
        for (ItemStack b : storageBacklog) {
            CompoundTag bt = new CompoundTag();
            b.save(registries, bt);
            bl.add(bt);
        }
        tag.put("DuctBacklog", bl);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pipeMask = tag.getByte("PipeMask") & 0xFF;
        storageMask = tag.getByte("StorageMask") & 0xFF;
        if (tag.contains("NodeGui")) {
            nodeGuiSlots.deserializeNBT(registries, tag.getCompound("NodeGui"));
        }
        nodeMode = NodeMode.fromOrdinal(tag.getByte("NodeMode"));
        routingMode = RoutingMode.fromOrdinal(tag.getByte("RoutingMode"));
        if (tag.contains("AmountField")) {
            amountField = tag.getInt("AmountField");
        } else if (tag.contains("InsPriority")) {
            amountField = tag.getInt("InsPriority");
        }
        channelLetter = tag.contains("Channel") ? tag.getByte("Channel") & 0xFF : 1;
        redstoneMode = tag.getByte("RedstoneMode") & 0xFF;
        roundRobinCursor = tag.getInt("RrCursor");
        outboundShipments.clear();
        if (tag.contains("DuctOutbound", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DuctOutbound", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                outboundShipments.add(OutboundShipment.load(registries, list.getCompound(i)));
            }
        }
        storageBacklog.clear();
        if (tag.contains("DuctBacklog", Tag.TAG_LIST)) {
            ListTag list = tag.getList("DuctBacklog", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                ItemStack.parse(registries, list.getCompound(i)).ifPresent(storageBacklog::add);
            }
        }
        refreshMenuData();
        requestModelDataUpdate();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putByte("PipeMask", (byte) pipeMask);
        tag.putByte("StorageMask", (byte) storageMask);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder()
                .with(DuctModelProperties.PIPE_MASK, pipeMask)
                .with(DuctModelProperties.STORAGE_MASK, storageMask)
                .build();
    }

    public int getPipeMask() {
        return pipeMask;
    }

    public int getStorageMask() {
        return storageMask;
    }

    /**
     * True only for the attachment piece: at least one face touches an external inventory (not another duct).
     * Plain pipe segments have no storage face and must not run node logistics or keep extractor/retriever state.
     */
    public boolean isStorageAttachmentNode() {
        return storageMask != 0;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.another_dynamics.duct_node");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        refreshMenuData();
        return new DuctNodeMenu(containerId, playerInventory, this);
    }
}
