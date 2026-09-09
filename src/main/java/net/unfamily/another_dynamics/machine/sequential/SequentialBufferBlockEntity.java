package net.unfamily.another_dynamics.machine.sequential;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.DuctFilterMatcher;
import net.unfamily.another_dynamics.duct.DuctFluidFilterMatcher;
import net.unfamily.another_dynamics.duct.logistics.DuctCapHelper;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

/**
 * Sequential Buffer BE: accumulates matching resources in input with per-list reservation, stages
 * one Sequence List at a time into output, then ejects steps in order (5-tick gap) with backpressure.
 * Redstone gate + per-list output with anti-feedback while emitting.
 */
public final class SequentialBufferBlockEntity extends BlockEntity implements MenuProvider {
    /** Sequential Task count from config. */
    public static int sequentialTaskCount() {
        return Config.sequentialTaskCount();
    }

    /** Assumed max stack when sizing item slots from step amounts (unknown item until insert). */
    private static final int ITEM_STACK_ASSUMED_MAX = 64;
    /** Ticks between finishing one step eject and starting the next. */
    public static final int STEP_EJECT_DELAY = 5;
    /** Default pause after a sequence fully clears output before staging another. */
    public static final int DEFAULT_INTER_SEQUENCE_DELAY = 20;
    /** Max inter-sequence delay (15 minutes). */
    public static final int MAX_INTER_SEQUENCE_DELAY = 18_000;
    private static final int PULSE_EMIT_TICKS = 4;

    private SequentialTaskData[] lists;
    /** Sized from enabled Sequential Tasks ({@link #recomputeBufferSizes}). */
    private final ItemStackHandler inputItems =
            new ItemStackHandler(0) {
                @Override
                protected void onContentsChanged(int slot) {
                    setChanged();
                }
            };
    private final ItemStackHandler outputItems =
            new ItemStackHandler(0) {
                @Override
                protected void onContentsChanged(int slot) {
                    setChanged();
                }
            };
    private final FluidTank inputFluid =
            new FluidTank(0) {
                @Override
                protected void onContentsChanged() {
                    setChanged();
                }
            };
    private final FluidTank outputFluid =
            new FluidTank(0) {
                @Override
                protected void onContentsChanged() {
                    setChanged();
                }
            };

    /** Soft-dep Mekanism chemical tanks (mB-equivalent long amounts). Null when gas support is off. */
    @Nullable
    private Object inputGasTank;
    @Nullable
    private Object outputGasTank;

    private final IItemHandler inputItemsInsertOnly = filteredItemInsert(inputItems);
    private final IItemHandler outputItemsExtractOnly = extractOnly(outputItems);
    private final IFluidHandler inputFluidInsertOnly = filteredFluidInsert(inputFluid);
    private final IFluidHandler outputFluidExtractOnly = extractOnly(outputFluid);

    private SequentialGateMode gateMode = SequentialGateMode.AUTO;
    /**
     * When true (default), inserts only satisfy the first incomplete step per enabled list.
     * When false, all incomplete matching steps may accept in parallel (can jam buffered ducts).
     */
    private boolean strictSequentialIntake = false;
    private boolean emitting;
    private int emitTicksLeft;
    private boolean prevNeighborPowered;
    private int editingListIndex = -1;

    /** Index of the Sequence List currently staged in output; {@code -1} when idle. */
    private int activeListIndex = -1;
    /** Step index within {@link #activeListIndex} currently being ejected. */
    private int ejectStepIndex;
    /** Amount still to push for the current eject step. */
    private int ejectStepRemaining;
    /** Countdown before the next step may eject ({@link #STEP_EJECT_DELAY}). */
    private int ejectCooldown;
    /** Configured pause between sequences (ticks). */
    private int interSequenceDelayTicks = DEFAULT_INTER_SEQUENCE_DELAY;
    /** Remaining ticks before another list may be staged. */
    private int interSequenceCooldown;

    public SequentialBufferBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEQUENTIAL_BUFFER.get(), pos, state);
        lists = new SequentialTaskData[sequentialTaskCount()];
        for (int i = 0; i < lists.length; i++) {
            lists[i] = new SequentialTaskData();
        }
        initGasTanksIfNeeded();
        recomputeBufferSizes(null);
    }

    private void initGasTanksIfNeeded() {
        if (!MekanismChemicalCompat.isLoaded()) {
            inputGasTank = null;
            outputGasTank = null;
            return;
        }
        // Soft-dep: chemical tank objects stay null until Mekanism gas support is re-enabled.
        // Matching / move / eject paths no-op when tanks are absent.
        inputGasTank = null;
        outputGasTank = null;
    }

    public SequentialTaskData list(int index) {
        ensureTasksSize();
        return lists[Math.floorMod(index, lists.length)];
    }

    /** Grow/shrink the lists array to match current config (preserves existing entries). */
    private void ensureTasksSize() {
        int n = sequentialTaskCount();
        if (lists != null && lists.length == n) {
            return;
        }
        SequentialTaskData[] next = new SequentialTaskData[n];
        for (int i = 0; i < n; i++) {
            if (lists != null && i < lists.length && lists[i] != null) {
                next[i] = lists[i];
            } else {
                next[i] = new SequentialTaskData();
            }
        }
        lists = next;
    }

    public ItemStackHandler inputItems() {
        return inputItems;
    }

    public ItemStackHandler outputItems() {
        return outputItems;
    }

    public FluidTank inputFluid() {
        return inputFluid;
    }

    public FluidTank outputFluid() {
        return outputFluid;
    }

    public SequentialGateMode gateMode() {
        return gateMode;
    }

    public void setGateMode(SequentialGateMode mode) {
        this.gateMode = mode == null ? SequentialGateMode.AUTO : mode;
        setChanged();
    }

    public boolean strictSequentialIntake() {
        return strictSequentialIntake;
    }

    public void setStrictSequentialIntake(boolean strict) {
        this.strictSequentialIntake = strict;
        setChanged();
    }

    public void toggleStrictSequentialIntake() {
        this.strictSequentialIntake = !this.strictSequentialIntake;
        setChanged();
    }

    /** Notify tracking clients after GUI mutations. */
    public void syncToClients() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public int editingListIndex() {
        return editingListIndex;
    }

    public void setEditingListIndex(int index) {
        this.editingListIndex = index;
        setChanged();
    }

    public int activeListIndex() {
        return activeListIndex;
    }

    public int interSequenceDelayTicks() {
        return interSequenceDelayTicks;
    }

    public void setInterSequenceDelayTicks(int ticks) {
        this.interSequenceDelayTicks = Mth.clamp(ticks, 0, MAX_INTER_SEQUENCE_DELAY);
        setChanged();
    }

    public boolean isEmitting() {
        return emitting;
    }

    public Direction front() {
        BlockState state = getBlockState();
        return state.hasProperty(SequentialBufferBlock.FACING)
                ? state.getValue(SequentialBufferBlock.FACING)
                : Direction.NORTH;
    }

    /** Front face exposes output (extract); all other faces expose input (insert). */
    @Nullable
    public IItemHandler itemCapability(@Nullable Direction side) {
        if (side == null) {
            return null;
        }
        return side == front() ? outputItemsExtractOnly : inputItemsInsertOnly;
    }

    @Nullable
    public IFluidHandler fluidCapability(@Nullable Direction side) {
        if (side == null) {
            return null;
        }
        return side == front() ? outputFluidExtractOnly : inputFluidInsertOnly;
    }

    public static void serverTick(
            Level level, BlockPos pos, BlockState state, SequentialBufferBlockEntity be) {
        be.tickServer((ServerLevel) level, pos, state);
    }

    private void tickServer(ServerLevel level, BlockPos pos, BlockState state) {
        // Anti-feedback: while POWERED, ignore neighbor redstone for the input gate.
        boolean neighborPowered = emitting ? false : level.getBestNeighborSignal(pos) > 0;
        if (!emitting) {
            prevNeighborPowered = neighborPowered;
        }

        // Pipeline: finish active eject / inter-sequence cooldown before staging another list.
        boolean destHasResources = destinationHasResources();
        if (activeListIndex >= 0) {
            tickOrderedEject(level, pos);
        } else if (interSequenceCooldown > 0) {
            interSequenceCooldown--;
            setChanged();
        } else if (gateMode.allowsWork(neighborPowered, destHasResources) || emitting) {
            if (gateMode.allowsWork(neighborPowered, destHasResources)) {
                tryCompleteLists();
            }
        }

        if (emitting) {
            if (emitTicksLeft > 0) {
                emitTicksLeft--;
            }
            if (emitTicksLeft <= 0 && !hasReadyOutputSignal()) {
                setEmitting(level, pos, state, false);
            }
        } else if (hasReadyOutputSignal()) {
            setEmitting(level, pos, state, true);
            emitTicksLeft = PULSE_EMIT_TICKS;
        }
    }

    private boolean hasReadyOutputSignal() {
        for (SequentialTaskData list : lists) {
            if (!list.isEnabled() || list.outputMode() == SequentialRedstoneMode.DISABLED) {
                continue;
            }
            boolean satisfied = listSatisfiedInOutput(list);
            switch (list.outputMode()) {
                case HIGH, PULSE -> {
                    if (satisfied) {
                        return true;
                    }
                }
                case LOW -> {
                    if (!satisfied) {
                        return true;
                    }
                }
                default -> {}
            }
        }
        return false;
    }

    private boolean listSatisfiedInOutput(SequentialTaskData list) {
        if (!list.hasContent()) {
            return false;
        }
        list.syncConcatSize();
        java.util.HashSet<Integer> consumedConcat = new java.util.HashSet<>();
        for (int i = 0; i < list.steps().size(); i++) {
            SequenceStepData step = list.steps().get(i);
            if (step.isEmpty()) {
                continue;
            }
            int ch = list.concatAt(i);
            if (ch == 0) {
                if (!outputHasStep(list, i)) {
                    return false;
                }
            } else if (!consumedConcat.contains(ch)) {
                consumedConcat.add(ch);
                for (int j = 0; j < list.steps().size(); j++) {
                    if (list.concatAt(j) != ch) {
                        continue;
                    }
                    if (list.steps().get(j).isEmpty()) {
                        continue;
                    }
                    if (!outputHasStep(list, j)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean outputHasStep(SequentialTaskData list, int stepIndex) {
        SequenceStepData step = list.steps().get(stepIndex);
        return switch (step.kind()) {
            case ITEM -> countMatchingItemsForStep(outputItems, list, stepIndex) >= step.amount();
            case FLUID -> {
                FluidStack fluid = outputFluid.getFluid();
                yield fluidMatchesStep(fluid, list, stepIndex) && fluid.getAmount() >= step.amount();
            }
            case GAS -> gasAmountMatching(outputGasTank, step) >= step.amount();
        };
    }

    private void tryCompleteLists() {
        if (activeListIndex >= 0 || interSequenceCooldown > 0) {
            return;
        }
        // Never mix sequences: stage only when output is fully clear.
        if (!isOutputEmpty()) {
            return;
        }
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            if (canSatisfyFromInput(li) && canFitInOutput(list)) {
                consumeInputForList(list);
                beginEjecting(li);
                return;
            }
        }
    }

    private void beginEjecting(int listIndex) {
        activeListIndex = listIndex;
        ejectStepIndex = 0;
        ejectStepRemaining = 0;
        ejectCooldown = 0;
        setChanged();
    }

    private void finishEjectingSequence() {
        activeListIndex = -1;
        ejectStepIndex = 0;
        ejectStepRemaining = 0;
        ejectCooldown = 0;
        interSequenceCooldown = interSequenceDelayTicks;
        setChanged();
    }

    private boolean isOutputEmpty() {
        for (int i = 0; i < outputItems.getSlots(); i++) {
            if (!outputItems.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        if (!outputFluid.getFluid().isEmpty()) {
            return false;
        }
        return gasTankAmount(outputGasTank) <= 0L;
    }

    private long gasTankAmount(@Nullable Object tank) {
        if (tank == null || !MekanismChemicalCompat.isLoaded()) {
            return 0L;
        }
        try {
            Object stack = tank.getClass().getMethod("getStack").invoke(tank);
            return MekanismChemicalCompat.getAmount(stack);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean canSatisfyFromInput(int listIndex) {
        SequentialTaskData list = lists[listIndex];
        if (!list.hasContent() || level == null) {
            return false;
        }
        list.syncConcatSize();
        var registries = level.registryAccess();
        java.util.HashSet<Integer> consumedConcat = new java.util.HashSet<>();
        for (int i = 0; i < list.steps().size(); i++) {
            SequenceStepData step = list.steps().get(i);
            if (step.isEmpty()) {
                continue;
            }
            int ch = list.concatAt(i);
            if (ch == 0) {
                if (!inputHasStep(listIndex, i)) {
                    return false;
                }
            } else if (!consumedConcat.contains(ch)) {
                consumedConcat.add(ch);
                if (!inputHasConcatGroup(listIndex, ch, registries)) {
                    return false;
                }
            }
        }
        return list.hasContent();
    }

    private boolean canFitInOutput(SequentialTaskData list) {
        if (level == null) {
            return false;
        }
        var registries = level.registryAccess();
        for (int i = 0; i < list.steps().size(); i++) {
            SequenceStepData step = list.steps().get(i);
            if (step.isEmpty()) {
                continue;
            }
            switch (step.kind()) {
                case ITEM -> {
                    if (!canFitItemStepInOutput(list, i, registries)) {
                        return false;
                    }
                }
                case FLUID -> {
                    FluidStack in = inputFluid.getFluid();
                    if (in.isEmpty() || !fluidMatchesStep(in, list, i)) {
                        return false;
                    }
                    FluidStack out = outputFluid.getFluid();
                    if (!out.isEmpty() && !FluidStack.isSameFluidSameComponents(out, in)) {
                        return false;
                    }
                    int space = outputFluid.getCapacity() - out.getAmount();
                    if (space < Math.min(step.amount(), in.getAmount())) {
                        return false;
                    }
                }
                case GAS -> {
                    // Soft-dep: accept when tanks absent (no-op move).
                }
            }
        }
        return true;
    }

    private boolean canFitItemStepInOutput(
            SequentialTaskData list, int stepIndex, HolderLookup.Provider registries) {
        SequenceStepData step = list.steps().get(stepIndex);
        int remaining = step.amount();
        for (int i = 0; i < outputItems.getSlots() && remaining > 0; i++) {
            ItemStack stack = outputItems.getStackInSlot(i);
            if (stack.isEmpty()) {
                remaining -= Math.min(remaining, 64);
                continue;
            }
            if (!itemMatchesStep(stack, list, stepIndex, registries)) {
                continue;
            }
            int space = stack.getMaxStackSize() - stack.getCount();
            if (space > 0) {
                remaining -= Math.min(remaining, space);
            }
        }
        return remaining <= 0 && inputHasStep(indexOfList(list), stepIndex);
    }

    private int indexOfList(SequentialTaskData list) {
        for (int i = 0; i < sequentialTaskCount(); i++) {
            if (lists[i] == list) {
                return i;
            }
        }
        return 0;
    }

    private boolean inputHasStep(int listIndex, int stepIndex) {
        if (level == null || listIndex < 0 || listIndex >= sequentialTaskCount()) {
            return false;
        }
        SequentialTaskData list = lists[listIndex];
        if (stepIndex < 0 || stepIndex >= list.steps().size()) {
            return false;
        }
        SequenceStepData step = list.steps().get(stepIndex);
        if (step.isEmpty()) {
            return false;
        }
        return switch (step.kind()) {
            case ITEM -> availableItemsForStep(listIndex, stepIndex, null) >= step.amount();
            case FLUID -> availableFluidForStep(listIndex, stepIndex, null) >= step.amount();
            case GAS -> availableGasForStep(listIndex, stepIndex, null) >= step.amount();
        };
    }

    private boolean inputHasConcatGroup(
            int listIndex, int channel, HolderLookup.Provider registries) {
        SequentialTaskData list = lists[listIndex];
        boolean any = false;
        for (int i = 0; i < list.steps().size(); i++) {
            if (list.concatAt(i) != channel) {
                continue;
            }
            SequenceStepData step = list.steps().get(i);
            if (step.isEmpty()) {
                continue;
            }
            any = true;
            if (!inputHasStep(listIndex, i)) {
                return false;
            }
        }
        return any;
    }

    private boolean itemMatchesStep(
            ItemStack stack,
            SequentialTaskData list,
            int stepIndex,
            HolderLookup.Provider registries) {
        if (stack.isEmpty() || stepIndex < 0 || stepIndex >= list.steps().size()) {
            return false;
        }
        int ch = list.concatAt(stepIndex);
        if (ch == 0) {
            SequenceStepData step = list.steps().get(stepIndex);
            return step.kind() == SequenceStepData.Kind.ITEM
                    && DuctFilterMatcher.matchesAnyNonEmptyEntry(stack, step.filter(), registries);
        }
        boolean anyInGroup = false;
        for (int i = 0; i < list.steps().size(); i++) {
            if (list.concatAt(i) != ch) {
                continue;
            }
            SequenceStepData step = list.steps().get(i);
            if (step.isEmpty() || step.kind() != SequenceStepData.Kind.ITEM) {
                continue;
            }
            anyInGroup = true;
            if (!DuctFilterMatcher.matchesAnyNonEmptyEntry(stack, step.filter(), registries)) {
                return false;
            }
        }
        return anyInGroup;
    }

    private boolean fluidMatchesStep(FluidStack fluid, SequentialTaskData list, int stepIndex) {
        if (fluid.isEmpty() || level == null || stepIndex < 0 || stepIndex >= list.steps().size()) {
            return false;
        }
        var registries = level.registryAccess();
        int ch = list.concatAt(stepIndex);
        if (ch == 0) {
            SequenceStepData step = list.steps().get(stepIndex);
            return step.kind() == SequenceStepData.Kind.FLUID
                    && DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(fluid, step.filter(), registries);
        }
        boolean anyInGroup = false;
        for (int i = 0; i < list.steps().size(); i++) {
            if (list.concatAt(i) != ch) {
                continue;
            }
            SequenceStepData step = list.steps().get(i);
            if (step.isEmpty() || step.kind() != SequenceStepData.Kind.FLUID) {
                continue;
            }
            anyInGroup = true;
            if (!DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(fluid, step.filter(), registries)) {
                return false;
            }
        }
        return anyInGroup;
    }

    private int countMatchingItemsForStep(
            ItemStackHandler handler, SequentialTaskData list, int stepIndex) {
        if (level == null) {
            return 0;
        }
        var registries = level.registryAccess();
        int count = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (itemMatchesStep(stack, list, stepIndex, registries)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countMatchingItems(ItemStackHandler handler, SequenceStepData step) {
        if (level == null) {
            return 0;
        }
        var registries = level.registryAccess();
        int count = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (DuctFilterMatcher.matchesAnyNonEmptyEntry(stack, step.filter(), registries)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean fluidMatches(FluidStack fluid, SequenceStepData step) {
        if (fluid.isEmpty() || level == null) {
            return false;
        }
        return DuctFluidFilterMatcher.matchesAnyNonEmptyEntry(fluid, step.filter(), level.registryAccess());
    }

    private long gasAmountMatching(@Nullable Object tank, SequenceStepData step) {
        if (tank == null || !MekanismChemicalCompat.isLoaded() || step.isEmpty()) {
            return 0L;
        }
        try {
            Object stack = tank.getClass().getMethod("getStack").invoke(tank);
            if (MekanismChemicalCompat.isEmptyStack(stack)) {
                return 0L;
            }
            String id = MekanismChemicalCompat.getTypeRegistryName(stack);
            if (id == null || id.isBlank()) {
                return 0L;
            }
            String filter = step.filter().trim();
            String bare = filter.startsWith("-") ? filter.substring(1).trim() : filter;
            if (!bare.equals(id) && !filter.contains(id)) {
                return 0L;
            }
            return MekanismChemicalCompat.getAmount(stack);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private long gasAmount(@Nullable Object tank) {
        if (tank == null || !MekanismChemicalCompat.isLoaded()) {
            return 0L;
        }
        try {
            Object stack = tank.getClass().getMethod("getStack").invoke(tank);
            return MekanismChemicalCompat.getAmount(stack);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private void consumeInputForList(SequentialTaskData list) {
        // Atomic on 1.21: snapshot handlers; restore if any step fails mid-move.
        ItemStack[] inSnap = snapshotItems(inputItems);
        ItemStack[] outSnap = snapshotItems(outputItems);
        FluidStack inFluidSnap = inputFluid.getFluid().copy();
        FluidStack outFluidSnap = outputFluid.getFluid().copy();
        for (int si = 0; si < list.steps().size(); si++) {
            SequenceStepData step = list.steps().get(si);
            if (step.isEmpty()) {
                continue;
            }
            boolean ok =
                    switch (step.kind()) {
                        case ITEM -> moveItemsToOutput(list, si);
                        case FLUID -> moveFluidToOutput(list, si);
                        case GAS -> {
                            moveGasToOutput(step);
                            yield true;
                        }
                    };
            if (!ok) {
                restoreItems(inputItems, inSnap);
                restoreItems(outputItems, outSnap);
                inputFluid.setFluid(inFluidSnap);
                outputFluid.setFluid(outFluidSnap);
                return;
            }
        }
        setChanged();
    }

    private static ItemStack[] snapshotItems(ItemStackHandler handler) {
        ItemStack[] snap = new ItemStack[handler.getSlots()];
        for (int i = 0; i < snap.length; i++) {
            snap[i] = handler.getStackInSlot(i).copy();
        }
        return snap;
    }

    private static void restoreItems(ItemStackHandler handler, ItemStack[] snap) {
        for (int i = 0; i < snap.length; i++) {
            handler.setStackInSlot(i, snap[i].copy());
        }
    }

    private boolean moveItemsToOutput(SequentialTaskData list, int stepIndex) {
        if (level == null) {
            return false;
        }
        var registries = level.registryAccess();
        SequenceStepData step = list.steps().get(stepIndex);
        int remaining = step.amount();
        for (int i = 0; i < inputItems.getSlots() && remaining > 0; i++) {
            ItemStack stack = inputItems.getStackInSlot(i);
            if (stack.isEmpty() || !itemMatchesStep(stack, list, stepIndex, registries)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            ItemStack extracted = inputItems.extractItem(i, take, false);
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(outputItems, extracted, false);
            if (!leftover.isEmpty()) {
                inputItems.insertItem(i, leftover, false);
                remaining -= extracted.getCount() - leftover.getCount();
                return remaining <= 0;
            }
            remaining -= extracted.getCount();
        }
        return remaining <= 0;
    }

    private boolean moveFluidToOutput(SequentialTaskData list, int stepIndex) {
        SequenceStepData step = list.steps().get(stepIndex);
        FluidStack in = inputFluid.getFluid();
        if (in.isEmpty() || !fluidMatchesStep(in, list, stepIndex)) {
            return false;
        }
        int move = Math.min(step.amount(), in.getAmount());
        if (move <= 0) {
            return false;
        }
        FluidStack drained = inputFluid.drain(move, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            return false;
        }
        int filled = outputFluid.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        int leftover = drained.getAmount() - filled;
        if (leftover > 0) {
            inputFluid.fill(drained.copyWithAmount(leftover), IFluidHandler.FluidAction.EXECUTE);
        }
        return filled > 0;
    }

    private void moveGasToOutput(SequenceStepData step) {
        if (inputGasTank == null || outputGasTank == null || !MekanismChemicalCompat.isLoaded()) {
            return;
        }
        // Soft-dep path reserved for when Mekanism gas support is re-enabled.
    }

    private void tickOrderedEject(ServerLevel level, BlockPos pos) {
        if (activeListIndex < 0 || activeListIndex >= sequentialTaskCount()) {
            finishEjectingSequence();
            return;
        }
        SequentialTaskData list = lists[activeListIndex];

        while (ejectStepIndex < list.steps().size()
                && (list.steps().get(ejectStepIndex) == null || list.steps().get(ejectStepIndex).isEmpty())) {
            ejectStepIndex++;
            ejectStepRemaining = 0;
        }

        if (ejectStepIndex >= list.steps().size()) {
            if (isOutputEmpty()) {
                finishEjectingSequence();
            } else {
                pushOutputRemnants(level, pos);
            }
            return;
        }

        if (ejectCooldown > 0) {
            ejectCooldown--;
            setChanged();
            return;
        }

        SequenceStepData step = list.steps().get(ejectStepIndex);
        if (ejectStepRemaining <= 0) {
            ejectStepRemaining = step.amount();
        }

        boolean stepDone = ejectCurrentStep(level, pos, list, ejectStepIndex);
        if (stepDone) {
            ejectStepIndex++;
            ejectStepRemaining = 0;
            ejectCooldown = STEP_EJECT_DELAY;
            setChanged();
        }
    }

    private boolean ejectCurrentStep(
            ServerLevel level, BlockPos pos, SequentialTaskData list, int stepIndex) {
        SequenceStepData step = list.steps().get(stepIndex);
        Direction front = front();
        return switch (step.kind()) {
            case ITEM -> ejectItemStepAmount(level, pos, front, list, stepIndex);
            case FLUID -> ejectFluidStepAmount(level, pos, front, list, stepIndex);
            case GAS -> {
                ejectStepRemaining = 0;
                yield true;
            }
        };
    }

    private boolean ejectItemStepAmount(
            ServerLevel level, BlockPos pos, Direction front, SequentialTaskData list, int stepIndex) {
        if (level == null || ejectStepRemaining <= 0) {
            return true;
        }
        var registries = level.registryAccess();
        int want = ejectStepRemaining;
        int pushed = 0;
        for (int i = 0; i < outputItems.getSlots() && pushed < want; i++) {
            ItemStack stack = outputItems.getStackInSlot(i);
            if (stack.isEmpty() || !itemMatchesStep(stack, list, stepIndex, registries)) {
                continue;
            }
            int take = Math.min(want - pushed, stack.getCount());
            ItemStack extracted = outputItems.extractItem(i, take, false);
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack leftover = DuctCapHelper.insertIntoFace(level, pos, front, extracted);
            int accepted = extracted.getCount() - leftover.getCount();
            if (!leftover.isEmpty()) {
                ItemHandlerHelper.insertItemStacked(outputItems, leftover, false);
            }
            if (accepted <= 0) {
                setChanged();
                return false;
            }
            pushed += accepted;
            if (!leftover.isEmpty()) {
                break;
            }
        }
        ejectStepRemaining -= pushed;
        setChanged();
        if (ejectStepRemaining <= 0) {
            return true;
        }
        return countMatchingItemsForStep(outputItems, list, stepIndex) <= 0;
    }

    private boolean ejectFluidStepAmount(
            ServerLevel level, BlockPos pos, Direction front, SequentialTaskData list, int stepIndex) {
        if (ejectStepRemaining <= 0) {
            return true;
        }
        FluidStack out = outputFluid.getFluid();
        if (out.isEmpty() || !fluidMatchesStep(out, list, stepIndex)) {
            ejectStepRemaining = 0;
            return true;
        }
        BlockPos target = pos.relative(front);
        IFluidHandler dest =
                level.getCapability(Capabilities.FluidHandler.BLOCK, target, front.getOpposite());
        if (dest == null) {
            return false;
        }
        int want = Math.min(ejectStepRemaining, out.getAmount());
        FluidStack sim = outputFluid.drain(want, IFluidHandler.FluidAction.SIMULATE);
        if (sim.isEmpty()) {
            return false;
        }
        int filled = dest.fill(sim, IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0) {
            return false;
        }
        outputFluid.drain(filled, IFluidHandler.FluidAction.EXECUTE);
        ejectStepRemaining -= filled;
        setChanged();
        return ejectStepRemaining <= 0;
    }

    private void pushOutputRemnants(ServerLevel level, BlockPos pos) {
        Direction front = front();
        for (int i = 0; i < outputItems.getSlots(); i++) {
            ItemStack stack = outputItems.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack extracted = outputItems.extractItem(i, stack.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack leftover = DuctCapHelper.insertIntoFace(level, pos, front, extracted);
            if (!leftover.isEmpty()) {
                ItemHandlerHelper.insertItemStacked(outputItems, leftover, false);
                setChanged();
                return;
            }
        }
        FluidStack out = outputFluid.getFluid();
        if (!out.isEmpty()) {
            BlockPos target = pos.relative(front);
            IFluidHandler dest =
                    level.getCapability(Capabilities.FluidHandler.BLOCK, target, front.getOpposite());
            if (dest != null) {
                FluidStack sim = outputFluid.drain(out.getAmount(), IFluidHandler.FluidAction.SIMULATE);
                if (!sim.isEmpty()) {
                    int filled = dest.fill(sim, IFluidHandler.FluidAction.EXECUTE);
                    if (filled > 0) {
                        outputFluid.drain(filled, IFluidHandler.FluidAction.EXECUTE);
                    }
                }
            }
        }
        setChanged();
    }

    // --- Per-list / per-step reservation accounting ---

    private int availableItemsForStep(int listIndex, int stepIndex, @Nullable ItemStack probe) {
        SequentialTaskData list = lists[listIndex];
        int have = countMatchingItemsForStep(inputItems, list, stepIndex);
        int reserved = reservedItemAmount(listIndex, stepIndex, probe);
        return Math.max(0, have - reserved);
    }

    private int reservedItemAmount(int listIndex, int stepIndex, @Nullable ItemStack probe) {
        int reserved = 0;
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData other = lists[li];
            if (!other.isEnabled() || !other.hasContent()) {
                continue;
            }
            for (int si = 0; si < other.steps().size(); si++) {
                if (li == listIndex && si >= stepIndex) {
                    continue;
                }
                SequenceStepData step = other.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.ITEM) {
                    continue;
                }
                if (!itemStepsCompete(listIndex, stepIndex, li, si, probe)) {
                    continue;
                }
                reserved += step.amount();
            }
        }
        return reserved;
    }

    private boolean itemStepsCompete(
            int listA, int stepA, int listB, int stepB, @Nullable ItemStack probe) {
        if (level == null) {
            return false;
        }
        var registries = level.registryAccess();
        if (probe != null && !probe.isEmpty()) {
            return itemMatchesStep(probe, lists[listA], stepA, registries)
                    && itemMatchesStep(probe, lists[listB], stepB, registries);
        }
        for (int i = 0; i < inputItems.getSlots(); i++) {
            ItemStack stack = inputItems.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (itemMatchesStep(stack, lists[listA], stepA, registries)
                    && itemMatchesStep(stack, lists[listB], stepB, registries)) {
                return true;
            }
        }
        return false;
    }

    private int availableFluidForStep(int listIndex, int stepIndex, @Nullable FluidStack probe) {
        FluidStack tank = inputFluid.getFluid();
        int have = 0;
        if (!tank.isEmpty() && fluidMatchesStep(tank, lists[listIndex], stepIndex)) {
            have = tank.getAmount();
        }
        FluidStack competeProbe = !tank.isEmpty() ? tank : (probe != null ? probe : FluidStack.EMPTY);
        int reserved = reservedFluidAmount(listIndex, stepIndex, competeProbe);
        return Math.max(0, have - reserved);
    }

    private int reservedFluidAmount(int listIndex, int stepIndex, FluidStack competeFluid) {
        if (competeFluid.isEmpty()) {
            return 0;
        }
        int reserved = 0;
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData other = lists[li];
            if (!other.isEnabled() || !other.hasContent()) {
                continue;
            }
            for (int si = 0; si < other.steps().size(); si++) {
                if (li == listIndex && si >= stepIndex) {
                    continue;
                }
                SequenceStepData step = other.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.FLUID) {
                    continue;
                }
                if (!fluidMatchesStep(competeFluid, lists[listIndex], stepIndex)) {
                    continue;
                }
                if (!fluidMatchesStep(competeFluid, other, si)) {
                    continue;
                }
                reserved += step.amount();
            }
        }
        return reserved;
    }

    private int availableGasForStep(int listIndex, int stepIndex, @Nullable String probeId) {
        SequenceStepData step = lists[listIndex].steps().get(stepIndex);
        long have = gasAmountMatching(inputGasTank, step);
        int reserved = reservedGasAmount(listIndex, stepIndex, probeId);
        return (int) Math.max(0L, have - reserved);
    }

    private int reservedGasAmount(int listIndex, int stepIndex, @Nullable String probeId) {
        SequenceStepData self = lists[listIndex].steps().get(stepIndex);
        String selfId = probeId != null ? probeId : gasFilterId(self);
        if (selfId == null || selfId.isBlank()) {
            return 0;
        }
        int reserved = 0;
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData other = lists[li];
            if (!other.isEnabled() || !other.hasContent()) {
                continue;
            }
            for (int si = 0; si < other.steps().size(); si++) {
                if (li == listIndex && si >= stepIndex) {
                    continue;
                }
                SequenceStepData step = other.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.GAS) {
                    continue;
                }
                String otherId = gasFilterId(step);
                if (otherId == null || !otherId.equals(selfId)) {
                    continue;
                }
                reserved += step.amount();
            }
        }
        return reserved;
    }

    @Nullable
    private static String gasFilterId(SequenceStepData step) {
        if (step == null || step.isEmpty()) {
            return null;
        }
        String filter = step.filter().trim();
        if (filter.startsWith("-")) {
            filter = filter.substring(1).trim();
        }
        return filter.isBlank() ? null : filter;
    }

    private void setEmitting(ServerLevel level, BlockPos pos, BlockState state, boolean on) {
        emitting = on;
        if (state.getValue(SequentialBufferBlock.POWERED) != on) {
            level.setBlock(pos, state.setValue(SequentialBufferBlock.POWERED, on), 3);
            level.updateNeighborsAt(pos, state.getBlock());
        }
        if (!on) {
            prevNeighborPowered = level.getBestNeighborSignal(pos) > 0;
        }
        setChanged();
    }

    /** Hub Dump: empty input buffers into the player inventory; leftover drops in the world. */
    public void dumpInput(@Nullable Player player, Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        for (int i = 0; i < inputItems.getSlots(); i++) {
            ItemStack stack = inputItems.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            inputItems.setStackInSlot(i, ItemStack.EMPTY);
            giveOrDrop(player, level, pos, stack);
        }
        inputFluid.setFluid(FluidStack.EMPTY);
        clearGasTank(inputGasTank);
        syncToClients();
    }

    /**
     * After Sequence List edits: eject input that no longer matches any enabled list
     * (player inventory first, excess into the world). Fluids/gas with no remaining need are cleared.
     */
    public void ejectOrphanedInput(@Nullable Player player) {
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockPos pos = worldPosition;
        var registries = level.registryAccess();
        boolean changed = false;
        for (int i = 0; i < inputItems.getSlots(); i++) {
            ItemStack stack = inputItems.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isItemWantedByAnyEnabledList(stack, registries)) {
                continue;
            }
            inputItems.setStackInSlot(i, ItemStack.EMPTY);
            giveOrDrop(player, level, pos, stack);
            changed = true;
        }
        FluidStack fluid = inputFluid.getFluid();
        if (!fluid.isEmpty() && !isFluidWantedByAnyEnabledList(fluid)) {
            inputFluid.setFluid(FluidStack.EMPTY);
            changed = true;
        }
        if (gasAmount(inputGasTank) > 0L && !isGasWantedByAnyEnabledList()) {
            clearGasTank(inputGasTank);
            changed = true;
        }
        recomputeBufferSizes(player);
        if (changed) {
            syncToClients();
        } else {
            setChanged();
        }
    }

    private boolean isItemWantedByAnyEnabledList(ItemStack stack, HolderLookup.Provider registries) {
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            for (int si = 0; si < list.steps().size(); si++) {
                SequenceStepData step = list.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.ITEM) {
                    continue;
                }
                if (itemMatchesStep(stack, list, si, registries)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFluidWantedByAnyEnabledList(FluidStack fluid) {
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            for (int si = 0; si < list.steps().size(); si++) {
                SequenceStepData step = list.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.FLUID) {
                    continue;
                }
                if (fluidMatchesStep(fluid, list, si)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isGasWantedByAnyEnabledList() {
        if (!MekanismChemicalCompat.isLoaded() || inputGasTank == null) {
            return false;
        }
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            for (int si = 0; si < list.steps().size(); si++) {
                SequenceStepData step = list.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.GAS) {
                    continue;
                }
                if (gasAmountMatching(inputGasTank, step) > 0L) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Try player inventory first; leftover drops at {@code pos}. */
    private static void giveOrDrop(@Nullable Player player, @Nullable Level level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = stack.copy();
        if (player != null) {
            player.getInventory().add(remaining);
        }
        if (!remaining.isEmpty() && level != null && pos != null) {
            Containers.dropItemStack(
                    level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, remaining);
        }
    }

    /**
     * Resize item slots and fluid tank capacities to match enabled Sequential Tasks.
     * Input holds all enabled lists at once; output holds the largest single list.
     * Overflow from a shrink is given to {@code player} when present, otherwise dropped.
     */
    public void recomputeBufferSizes(@Nullable Player player) {
        int inputItemSlots = 0;
        int outputItemSlots = 0;
        int inputFluidMb = 0;
        int outputFluidMb = 0;
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            int listItemSlots = 0;
            int listFluidMb = 0;
            for (SequenceStepData step : list.steps()) {
                if (step == null || step.isEmpty()) {
                    continue;
                }
                switch (step.kind()) {
                    case ITEM -> listItemSlots += itemSlotsForAmount(step.amount());
                    case FLUID -> listFluidMb = addCap(listFluidMb, step.amount());
                    case GAS -> {
                        // Gas tanks soft-dep; size when tanks are wired.
                    }
                }
            }
            inputItemSlots = addCap(inputItemSlots, listItemSlots);
            inputFluidMb = addCap(inputFluidMb, listFluidMb);
            outputItemSlots = Math.max(outputItemSlots, listItemSlots);
            outputFluidMb = Math.max(outputFluidMb, listFluidMb);
        }
        resizeItemHandler(inputItems, inputItemSlots, player);
        resizeItemHandler(outputItems, outputItemSlots, player);
        resizeFluidTank(inputFluid, inputFluidMb);
        resizeFluidTank(outputFluid, outputFluidMb);
        setChanged();
    }

    private static int itemSlotsForAmount(int amount) {
        int n = Math.max(0, amount);
        if (n <= 0) {
            return 0;
        }
        return (n + ITEM_STACK_ASSUMED_MAX - 1) / ITEM_STACK_ASSUMED_MAX;
    }

    private static int addCap(int a, int b) {
        long sum = (long) a + (long) b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private void resizeItemHandler(ItemStackHandler handler, int newSize, @Nullable Player player) {
        int oldSize = handler.getSlots();
        if (oldSize == newSize) {
            return;
        }
        ItemStack[] kept = new ItemStack[Math.min(oldSize, newSize)];
        for (int i = 0; i < kept.length; i++) {
            kept[i] = handler.getStackInSlot(i).copy();
        }
        for (int i = newSize; i < oldSize; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                giveOrDrop(player, level, worldPosition, stack.copy());
            }
        }
        handler.setSize(newSize);
        for (int i = 0; i < kept.length; i++) {
            if (kept[i] != null && !kept[i].isEmpty()) {
                handler.setStackInSlot(i, kept[i]);
            }
        }
    }

    private void resizeFluidTank(FluidTank tank, int newCapacity) {
        int cap = Math.max(0, newCapacity);
        tank.setCapacity(cap);
        FluidStack fluid = tank.getFluid();
        if (!fluid.isEmpty() && fluid.getAmount() > cap) {
            if (cap <= 0) {
                tank.setFluid(FluidStack.EMPTY);
            } else {
                tank.setFluid(fluid.copyWithAmount(cap));
            }
        }
    }

    public void dropBufferContents(Level level, BlockPos pos) {
        for (int i = 0; i < inputItems.getSlots(); i++) {
            ItemStack stack = inputItems.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                inputItems.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < outputItems.getSlots(); i++) {
            ItemStack stack = outputItems.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                outputItems.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
    }

    private static void clearGasTank(@Nullable Object tank) {
        if (tank == null || !MekanismChemicalCompat.isLoaded()) {
            return;
        }
        try {
            tank.getClass().getMethod("setEmpty").invoke(tank);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.another_dynamics.sequential_buffer");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new SequentialBufferMenu(id, inv, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag sequential = new CompoundTag();
        writeCustom(sequential, registries);
        tag.put("Sequential", sequential);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Sequential", Tag.TAG_COMPOUND)) {
            readCustom(tag.getCompound("Sequential"), registries);
        }
    }

    private void writeCustom(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        tag.putByte("Gate", (byte) gateMode.ordinal());
        tag.putBoolean("StrictIntake", strictSequentialIntake);
        tag.putBoolean("Emitting", emitting);
        tag.putInt("EmitTicks", emitTicksLeft);
        tag.putInt("EditList", editingListIndex);
        tag.putInt("ActiveList", activeListIndex);
        tag.putInt("EjectStep", ejectStepIndex);
        tag.putInt("EjectRemain", ejectStepRemaining);
        tag.putInt("EjectCool", ejectCooldown);
        tag.putInt("InterDelay", interSequenceDelayTicks);
        tag.putInt("InterCool", interSequenceCooldown);
        ListTag listTag = new ListTag();
        for (SequentialTaskData list : lists) {
            listTag.add(list.save());
        }
        SequentialTaskData.writeTasksListTag(tag, listTag);
        if (registries != null) {
            tag.put("InItems", inputItems.serializeNBT(registries));
            tag.put("OutItems", outputItems.serializeNBT(registries));
            tag.put("InFluid", inputFluid.writeToNBT(registries, new CompoundTag()));
            tag.put("OutFluid", outputFluid.writeToNBT(registries, new CompoundTag()));
        }
    }

    private void readCustom(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        gateMode =
                SequentialGateMode.fromOrdinal(
                        tag.contains("Gate", Tag.TAG_BYTE)
                                ? tag.getByte("Gate") & 0xFF
                                : SequentialGateMode.AUTO.ordinal());
        strictSequentialIntake = tag.contains("StrictIntake") && tag.getBoolean("StrictIntake");
        emitting = tag.contains("Emitting") && tag.getBoolean("Emitting");
        emitTicksLeft = tag.contains("EmitTicks", Tag.TAG_INT) ? tag.getInt("EmitTicks") : 0;
        editingListIndex = tag.contains("EditList", Tag.TAG_INT) ? tag.getInt("EditList") : -1;
        activeListIndex = tag.contains("ActiveList", Tag.TAG_INT) ? tag.getInt("ActiveList") : -1;
        ejectStepIndex = tag.contains("EjectStep", Tag.TAG_INT) ? tag.getInt("EjectStep") : 0;
        ejectStepRemaining = tag.contains("EjectRemain", Tag.TAG_INT) ? tag.getInt("EjectRemain") : 0;
        ejectCooldown = tag.contains("EjectCool", Tag.TAG_INT) ? tag.getInt("EjectCool") : 0;
        interSequenceDelayTicks =
                Mth.clamp(
                        tag.contains("InterDelay", Tag.TAG_INT)
                                ? tag.getInt("InterDelay")
                                : DEFAULT_INTER_SEQUENCE_DELAY,
                        0,
                        MAX_INTER_SEQUENCE_DELAY);
        interSequenceCooldown =
                Math.max(0, tag.contains("InterCool", Tag.TAG_INT) ? tag.getInt("InterCool") : 0);
        ListTag listTag =
                SequentialTaskData.readTasksListTag(tag);
        ensureTasksSize();
        for (int i = 0; i < lists.length; i++) {
            lists[i] = new SequentialTaskData();
            if (i < listTag.size()) {
                lists[i].load(listTag.getCompound(i));
            }
        }
        if (registries != null) {
            if (tag.contains("InItems", Tag.TAG_COMPOUND)) {
                inputItems.deserializeNBT(registries, tag.getCompound("InItems"));
            }
            if (tag.contains("OutItems", Tag.TAG_COMPOUND)) {
                outputItems.deserializeNBT(registries, tag.getCompound("OutItems"));
            }
            if (tag.contains("InFluid", Tag.TAG_COMPOUND)) {
                inputFluid.readFromNBT(registries, tag.getCompound("InFluid"));
            }
            if (tag.contains("OutFluid", Tag.TAG_COMPOUND)) {
                outputFluid.readFromNBT(registries, tag.getCompound("OutFluid"));
            }
        }
        recomputeBufferSizes(null);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag outer = super.getUpdateTag(registries);
        CompoundTag tag = new CompoundTag();
        writeCustom(tag, registries);
        outer.put("Sequential", tag);
        return outer;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("Sequential", Tag.TAG_COMPOUND)) {
            readCustom(tag.getCompound("Sequential"), registries);
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(
            Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            handleUpdateTag(tag, registries);
        }
    }

    /** Snapshot of all Sequential Tasks for Settings Copier SEQUENTIAL mode. */
    public CompoundTag captureSettings() {
        CompoundTag tag = new CompoundTag();
        tag.putByte(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 0);
        tag.putByte("Gate", (byte) gateMode.ordinal());
        tag.putBoolean("StrictIntake", strictSequentialIntake);
        ListTag listTag = new ListTag();
        for (SequentialTaskData list : lists) {
            listTag.add(list.save());
        }
        SequentialTaskData.writeTasksListTag(tag, listTag);
        return tag;
    }

    /** Snapshot of a single Sequence List (index preserved). */
    public CompoundTag captureListSettings(int listIndex) {
        int idx = Math.floorMod(listIndex, sequentialTaskCount());
        CompoundTag tag = new CompoundTag();
        tag.putByte(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 1);
        tag.putInt("ListIndex", idx);
        tag.put("List", list(idx).save());
        return tag;
    }

    public void applySettings(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        ensureTasksSize();
        byte kind =
                tag.contains(SettingsCopierSequentialSnapshot.KIND_TAG, Tag.TAG_BYTE)
                        ? tag.getByte(SettingsCopierSequentialSnapshot.KIND_TAG)
                        : 0;
        if (kind == 1 && tag.contains("List", Tag.TAG_COMPOUND)) {
            int idx =
                    Math.floorMod(
                            tag.contains("ListIndex", Tag.TAG_INT) ? tag.getInt("ListIndex") : 0,
                            lists.length);
            lists[idx] = new SequentialTaskData();
            lists[idx].load(tag.getCompound("List"));
        } else {
            gateMode =
                    SequentialGateMode.fromOrdinal(
                            tag.contains("Gate", Tag.TAG_BYTE)
                                    ? tag.getByte("Gate") & 0xFF
                                    : SequentialGateMode.AUTO.ordinal());
            strictSequentialIntake = tag.contains("StrictIntake") && tag.getBoolean("StrictIntake");
            ListTag listTag = SequentialTaskData.readTasksListTag(tag);
            for (int i = 0; i < lists.length; i++) {
                lists[i] = new SequentialTaskData();
                if (i < listTag.size()) {
                    lists[i].load(listTag.getCompound(i));
                }
            }
        }
        recomputeBufferSizes(null);
        setChanged();
        syncToClients();
    }

    private IItemHandler filteredItemInsert(ItemStackHandler delegate) {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return delegate.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return delegate.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                int need = remainingItemNeed(stack);
                if (need <= 0) {
                    return stack;
                }
                int toInsert = Math.min(stack.getCount(), need);
                ItemStack limited = stack.getCount() == toInsert ? stack : stack.copyWithCount(toInsert);
                ItemStack leftover = delegate.insertItem(slot, limited, simulate);
                int accepted = toInsert - leftover.getCount();
                int rejected = stack.getCount() - accepted;
                if (rejected <= 0) {
                    return ItemStack.EMPTY;
                }
                return stack.copyWithCount(rejected);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(int slot) {
                return delegate.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return acceptsItemInsert(stack) && delegate.isItemValid(slot, stack);
            }
        };
    }

    private IFluidHandler filteredFluidInsert(FluidTank delegate) {
        return new IFluidHandler() {
            @Override
            public int getTanks() {
                return delegate.getTanks();
            }

            @Override
            public FluidStack getFluidInTank(int tank) {
                return delegate.getFluidInTank(tank);
            }

            @Override
            public int getTankCapacity(int tank) {
                return delegate.getTankCapacity(tank);
            }

            @Override
            public boolean isFluidValid(int tank, FluidStack stack) {
                return acceptsFluidInsert(stack) && delegate.isFluidValid(tank, stack);
            }

            @Override
            public int fill(FluidStack resource, FluidAction action) {
                int need = remainingFluidNeed(resource);
                if (need <= 0) {
                    return 0;
                }
                if (resource.getAmount() <= need) {
                    return delegate.fill(resource, action);
                }
                FluidStack limited = resource.copyWithAmount(need);
                return delegate.fill(limited, action);
            }

            @Override
            public FluidStack drain(FluidStack resource, FluidAction action) {
                return FluidStack.EMPTY;
            }

            @Override
            public FluidStack drain(int maxDrain, FluidAction action) {
                return FluidStack.EMPTY;
            }
        };
    }

    /**
     * True when the front-face destination holds any items, fluids, or chemicals (comparator-style
     * occupancy used by {@link SequentialGateMode#AUTO}).
     */
    private boolean destinationHasResources() {
        if (level == null) {
            return false;
        }
        Direction front = front();

        var items = DuctCapHelper.getHandlerOnFace(level, worldPosition, front);
        if (items != null) {
            for (int i = 0; i < items.getSlots(); i++) {
                if (!items.getStackInSlot(i).isEmpty()) {
                    return true;
                }
            }
        }

        BlockPos target = worldPosition.relative(front);
        IFluidHandler fluids =
                level.getCapability(Capabilities.FluidHandler.BLOCK, target, front.getOpposite());
        if (fluids != null) {
            for (int i = 0; i < fluids.getTanks(); i++) {
                if (!fluids.getFluidInTank(i).isEmpty()) {
                    return true;
                }
            }
        }

        Object chemicals = MekanismChemicalCompat.getChemicalHandlerOnFace(level, worldPosition, front);
        return MekanismChemicalCompat.handlerHasStoredChemical(chemicals);
    }

    /** Debug/probe: remaining insert need for {@code stack} (buffer only; ignores duct IncomingIndex). */
    public int debugRemainingItemNeed(ItemStack stack) {
        return remainingItemNeed(stack);
    }

    /**
     * How many more matching items enabled lists still need after reservations. Caps duct/hopper
     * inserts so a batch cannot overshoot the configured step amount (e.g. need 5 of 13 must not
     * accept a full AD batch of 8).
     *
     * <p>With {@link #strictSequentialIntake} only the first incomplete step per list may accept.
     */
    private int remainingItemNeed(ItemStack stack) {
        if (stack == null || stack.isEmpty() || level == null) {
            return 0;
        }
        if (gateMode == SequentialGateMode.AUTO && destinationHasResources()) {
            return 0;
        }
        ItemStack probe = stack.copyWithCount(1);
        var registries = level.registryAccess();
        int need = 0;
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            if (strictSequentialIntake) {
                int si = firstIncompleteStepIndex(li);
                if (si < 0) {
                    continue;
                }
                SequenceStepData step = list.steps().get(si);
                if (step.kind() != SequenceStepData.Kind.ITEM) {
                    continue;
                }
                if (!itemMatchesStep(probe, list, si, registries)) {
                    continue;
                }
                int avail = availableItemsForStep(li, si, probe);
                if (avail < step.amount()) {
                    need += step.amount() - avail;
                }
                continue;
            }
            for (int si = 0; si < list.steps().size(); si++) {
                SequenceStepData step = list.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.ITEM) {
                    continue;
                }
                if (!itemMatchesStep(probe, list, si, registries)) {
                    continue;
                }
                int avail = availableItemsForStep(li, si, probe);
                if (avail < step.amount()) {
                    need += step.amount() - avail;
                }
            }
        }
        return need;
    }

    private int remainingFluidNeed(FluidStack resource) {
        if (resource == null || resource.isEmpty() || level == null) {
            return 0;
        }
        if (gateMode == SequentialGateMode.AUTO && destinationHasResources()) {
            return 0;
        }
        FluidStack probe = resource.copyWithAmount(1);
        int need = 0;
        for (int li = 0; li < sequentialTaskCount(); li++) {
            SequentialTaskData list = lists[li];
            if (!list.isEnabled() || !list.hasContent()) {
                continue;
            }
            if (strictSequentialIntake) {
                int si = firstIncompleteStepIndex(li);
                if (si < 0) {
                    continue;
                }
                SequenceStepData step = list.steps().get(si);
                if (step.kind() != SequenceStepData.Kind.FLUID) {
                    continue;
                }
                if (!fluidMatchesStep(probe, list, si)) {
                    continue;
                }
                int avail = availableFluidForStep(li, si, probe);
                if (avail < step.amount()) {
                    need += step.amount() - avail;
                }
                continue;
            }
            for (int si = 0; si < list.steps().size(); si++) {
                SequenceStepData step = list.steps().get(si);
                if (step.isEmpty() || step.kind() != SequenceStepData.Kind.FLUID) {
                    continue;
                }
                if (!fluidMatchesStep(probe, list, si)) {
                    continue;
                }
                int avail = availableFluidForStep(li, si, probe);
                if (avail < step.amount()) {
                    need += step.amount() - avail;
                }
            }
        }
        return need;
    }

    /** Index of the first non-empty incomplete step in an enabled list, or {@code -1}. */
    private int firstIncompleteStepIndex(int listIndex) {
        SequentialTaskData list = lists[listIndex];
        for (int si = 0; si < list.steps().size(); si++) {
            SequenceStepData step = list.steps().get(si);
            if (step == null || step.isEmpty()) {
                continue;
            }
            if (!inputHasStep(listIndex, si)) {
                return si;
            }
        }
        return -1;
    }

    /** Accepts when any enabled list still has remaining need for the probe after reservations. */
    private boolean acceptsItemInsert(ItemStack stack) {
        return remainingItemNeed(stack) > 0;
    }

    private boolean acceptsFluidInsert(FluidStack resource) {
        return remainingFluidNeed(resource) > 0;
    }

    private IItemHandler extractOnly(ItemStackHandler delegate) {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return delegate.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return delegate.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return stack;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack stack = delegate.getStackInSlot(slot);
                if (stack.isEmpty() || !isItemExtractUnlocked(stack)) {
                    return ItemStack.EMPTY;
                }
                return delegate.extractItem(slot, amount, simulate);
            }

            @Override
            public int getSlotLimit(int slot) {
                return delegate.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        };
    }

    private IFluidHandler extractOnly(FluidTank delegate) {
        return new IFluidHandler() {
            @Override
            public int getTanks() {
                return delegate.getTanks();
            }

            @Override
            public FluidStack getFluidInTank(int tank) {
                return delegate.getFluidInTank(tank);
            }

            @Override
            public int getTankCapacity(int tank) {
                return delegate.getTankCapacity(tank);
            }

            @Override
            public boolean isFluidValid(int tank, FluidStack stack) {
                return false;
            }

            @Override
            public int fill(FluidStack resource, FluidAction action) {
                return 0;
            }

            @Override
            public FluidStack drain(FluidStack resource, FluidAction action) {
                if (!isFluidExtractUnlocked(resource)) {
                    return FluidStack.EMPTY;
                }
                return delegate.drain(resource, action);
            }

            @Override
            public FluidStack drain(int maxDrain, FluidAction action) {
                FluidStack inTank = delegate.getFluid();
                if (inTank.isEmpty() || !isFluidExtractUnlocked(inTank)) {
                    return FluidStack.EMPTY;
                }
                return delegate.drain(maxDrain, action);
            }
        };
    }

    private boolean isItemExtractUnlocked(ItemStack stack) {
        if (activeListIndex < 0 || activeListIndex >= sequentialTaskCount() || level == null) {
            return true;
        }
        if (ejectStepIndex <= 0) {
            return false;
        }
        SequentialTaskData list = lists[activeListIndex];
        var registries = level.registryAccess();
        for (int si = 0; si < ejectStepIndex && si < list.steps().size(); si++) {
            SequenceStepData step = list.steps().get(si);
            if (step.isEmpty() || step.kind() != SequenceStepData.Kind.ITEM) {
                continue;
            }
            if (itemMatchesStep(stack, list, si, registries)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFluidExtractUnlocked(FluidStack stack) {
        if (activeListIndex < 0 || activeListIndex >= sequentialTaskCount() || level == null) {
            return true;
        }
        if (ejectStepIndex <= 0 || stack.isEmpty()) {
            return false;
        }
        SequentialTaskData list = lists[activeListIndex];
        for (int si = 0; si < ejectStepIndex && si < list.steps().size(); si++) {
            SequenceStepData step = list.steps().get(si);
            if (step.isEmpty() || step.kind() != SequenceStepData.Kind.FLUID) {
                continue;
            }
            if (fluidMatchesStep(stack, list, si)) {
                return true;
            }
        }
        return false;
    }
}
