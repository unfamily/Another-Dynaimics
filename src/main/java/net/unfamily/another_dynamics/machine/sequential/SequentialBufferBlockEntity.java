package net.unfamily.another_dynamics.machine.sequential;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.DelegatingResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.unfamily.another_dynamics.duct.DuctFilterMatcher;
import net.unfamily.another_dynamics.duct.DuctFluidFilterMatcher;
import net.unfamily.another_dynamics.duct.DuctNbtCodecs;
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
    public static final int SEQUENCE_LIST_COUNT = 10;
    public static final int INPUT_SLOTS = 27;
    public static final int OUTPUT_SLOTS = 27;
    public static final int FLUID_CAPACITY_MB = 64_000;
    /** Ticks between finishing one step eject and starting the next. */
    public static final int STEP_EJECT_DELAY = 5;
    /** Default pause after a sequence fully clears output before staging another. */
    public static final int DEFAULT_INTER_SEQUENCE_DELAY = 20;
    /** Max inter-sequence delay (15 minutes). */
    public static final int MAX_INTER_SEQUENCE_DELAY = 18_000;
    private static final int PULSE_EMIT_TICKS = 4;

    private final SequenceListData[] lists = new SequenceListData[SEQUENCE_LIST_COUNT];
    private final ItemStacksResourceHandler inputItems =
            new ItemStacksResourceHandler(INPUT_SLOTS) {
                @Override
                protected void onContentsChanged(int index, ItemStack previousContents) {
                    setChanged();
                }
            };
    private final ItemStacksResourceHandler outputItems =
            new ItemStacksResourceHandler(OUTPUT_SLOTS) {
                @Override
                protected void onContentsChanged(int index, ItemStack previousContents) {
                    setChanged();
                }
            };
    private final FluidStacksResourceHandler inputFluid =
            new FluidStacksResourceHandler(1, FLUID_CAPACITY_MB) {
                @Override
                protected void onContentsChanged(int index, FluidStack previousContents) {
                    setChanged();
                }
            };
    private final FluidStacksResourceHandler outputFluid =
            new FluidStacksResourceHandler(1, FLUID_CAPACITY_MB) {
                @Override
                protected void onContentsChanged(int index, FluidStack previousContents) {
                    setChanged();
                }
            };

    /** Soft-dep Mekanism chemical tanks (mB-equivalent long amounts). Null when gas support is off. */
    @Nullable
    private Object inputGasTank;
    @Nullable
    private Object outputGasTank;

    private final ResourceHandler<ItemResource> inputItemsInsertOnly = filteredItemInsert(inputItems);
    private final ResourceHandler<ItemResource> outputItemsExtractOnly = extractOnly(outputItems);
    private final ResourceHandler<FluidResource> inputFluidInsertOnly = filteredFluidInsert(inputFluid);
    private final ResourceHandler<FluidResource> outputFluidExtractOnly = extractOnly(outputFluid);

    private SequentialGateMode gateMode = SequentialGateMode.IGNORED;
    /**
     * When true (default), inserts only satisfy the first incomplete step per enabled list.
     * When false, all incomplete matching steps may accept in parallel (can jam buffered ducts).
     */
    private boolean strictSequentialIntake = true;
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
        for (int i = 0; i < SEQUENCE_LIST_COUNT; i++) {
            lists[i] = new SequenceListData();
        }
        initGasTanksIfNeeded();
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

    public SequenceListData list(int index) {
        return lists[Math.floorMod(index, SEQUENCE_LIST_COUNT)];
    }

    public ItemStacksResourceHandler inputItems() {
        return inputItems;
    }

    public ItemStacksResourceHandler outputItems() {
        return outputItems;
    }

    public FluidStacksResourceHandler inputFluid() {
        return inputFluid;
    }

    public FluidStacksResourceHandler outputFluid() {
        return outputFluid;
    }

    public SequentialGateMode gateMode() {
        return gateMode;
    }

    public void setGateMode(SequentialGateMode mode) {
        this.gateMode = mode == null ? SequentialGateMode.IGNORED : mode;
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
    public ResourceHandler<ItemResource> itemCapability(@Nullable Direction side) {
        if (side == null) {
            return null;
        }
        return side == front() ? outputItemsExtractOnly : inputItemsInsertOnly;
    }

    @Nullable
    public ResourceHandler<FluidResource> fluidCapability(@Nullable Direction side) {
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
        if (activeListIndex >= 0) {
            tickOrderedEject(level, pos);
        } else if (interSequenceCooldown > 0) {
            interSequenceCooldown--;
            setChanged();
        } else if (gateMode.allowsWork(neighborPowered) || emitting) {
            // Gate (or emit pulse window) may stage a ready list when output is clear.
            if (gateMode.allowsWork(neighborPowered)) {
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
        for (SequenceListData list : lists) {
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

    private boolean listSatisfiedInOutput(SequenceListData list) {
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

    private boolean outputHasStep(SequenceListData list, int stepIndex) {
        SequenceStepData step = list.steps().get(stepIndex);
        return switch (step.kind()) {
            case ITEM -> countMatchingItemsForStep(outputItems, list, stepIndex) >= step.amount();
            case FLUID -> {
                FluidStack fluid = fluidIn(outputFluid);
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
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData list = lists[li];
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
        for (int i = 0; i < outputItems.size(); i++) {
            if (!ItemUtil.getStack(outputItems, i).isEmpty()) {
                return false;
            }
        }
        if (!fluidIn(outputFluid).isEmpty()) {
            return false;
        }
        return gasAmount(outputGasTank) <= 0L;
    }

    private boolean canSatisfyFromInput(int listIndex) {
        SequenceListData list = lists[listIndex];
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

    private boolean canFitInOutput(SequenceListData list) {
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
                    FluidStack in = fluidIn(inputFluid);
                    if (in.isEmpty() || !fluidMatchesStep(in, list, i)) {
                        return false;
                    }
                    FluidStack out = fluidIn(outputFluid);
                    if (!out.isEmpty() && !FluidStack.isSameFluidSameComponents(out, in)) {
                        return false;
                    }
                    int space = FLUID_CAPACITY_MB - out.getAmount();
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
            SequenceListData list, int stepIndex, net.minecraft.core.HolderLookup.Provider registries) {
        SequenceStepData step = list.steps().get(stepIndex);
        int remaining = step.amount();
        // Simulate free capacity in output for stacks that match this step (concat AND aware).
        for (int i = 0; i < outputItems.size() && remaining > 0; i++) {
            ItemStack stack = ItemUtil.getStack(outputItems, i);
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
        // Also verify matching items exist in input to move.
        return remaining <= 0 && inputHasStep(indexOfList(list), stepIndex);
    }

    private int indexOfList(SequenceListData list) {
        for (int i = 0; i < SEQUENCE_LIST_COUNT; i++) {
            if (lists[i] == list) {
                return i;
            }
        }
        return 0;
    }

    private boolean inputHasStep(int listIndex, int stepIndex) {
        if (level == null || listIndex < 0 || listIndex >= SEQUENCE_LIST_COUNT) {
            return false;
        }
        SequenceListData list = lists[listIndex];
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
            int listIndex, int channel, net.minecraft.core.HolderLookup.Provider registries) {
        SequenceListData list = lists[listIndex];
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
            SequenceListData list,
            int stepIndex,
            net.minecraft.core.HolderLookup.Provider registries) {
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

    private boolean fluidMatchesStep(FluidStack fluid, SequenceListData list, int stepIndex) {
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
            ItemStacksResourceHandler handler, SequenceListData list, int stepIndex) {
        if (level == null) {
            return 0;
        }
        var registries = level.registryAccess();
        int count = 0;
        for (int i = 0; i < handler.size(); i++) {
            ItemStack stack = ItemUtil.getStack(handler, i);
            if (stack.isEmpty()) {
                continue;
            }
            if (itemMatchesStep(stack, list, stepIndex, registries)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countMatchingItems(ItemStacksResourceHandler handler, SequenceStepData step) {

        if (level == null) {
            return 0;
        }
        var registries = level.registryAccess();
        int count = 0;
        for (int i = 0; i < handler.size(); i++) {
            ItemStack stack = ItemUtil.getStack(handler, i);
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

    private static FluidStack fluidIn(FluidStacksResourceHandler tank) {
        if (tank.size() <= 0) {
            return FluidStack.EMPTY;
        }
        FluidResource resource = tank.getResource(0);
        int amount = tank.getAmountAsInt(0);
        return resource.isEmpty() || amount <= 0 ? FluidStack.EMPTY : resource.toStack(amount);
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

    private void consumeInputForList(SequenceListData list) {
        // Atomic: all steps of this list move under one root transaction when possible.
        try (Transaction tx = Transaction.openRoot()) {
            for (int si = 0; si < list.steps().size(); si++) {
                SequenceStepData step = list.steps().get(si);
                if (step.isEmpty()) {
                    continue;
                }
                switch (step.kind()) {
                    case ITEM -> {
                        if (!moveItemsToOutput(list, si, tx)) {
                            return;
                        }
                    }
                    case FLUID -> {
                        if (!moveFluidToOutput(list, si, tx)) {
                            return;
                        }
                    }
                    case GAS -> moveGasToOutput(step);
                }
            }
            tx.commit();
        }
        setChanged();
    }

    private boolean moveItemsToOutput(SequenceListData list, int stepIndex, Transaction tx) {
        if (level == null) {
            return false;
        }
        var registries = level.registryAccess();
        SequenceStepData step = list.steps().get(stepIndex);
        int remaining = step.amount();
        for (int i = 0; i < inputItems.size() && remaining > 0; i++) {
            ItemStack stack = ItemUtil.getStack(inputItems, i);
            if (stack.isEmpty() || !itemMatchesStep(stack, list, stepIndex, registries)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            ItemResource resource = ItemResource.of(stack);
            int extracted = inputItems.extract(i, resource, take, tx);
            if (extracted <= 0) {
                continue;
            }
            int inserted = outputItems.insert(resource, extracted, tx);
            int leftover = extracted - inserted;
            if (leftover > 0) {
                inputItems.insert(i, resource, leftover, tx);
                remaining -= inserted;
                return remaining <= 0;
            }
            remaining -= extracted;
        }
        return remaining <= 0;
    }

    private boolean moveFluidToOutput(SequenceListData list, int stepIndex, Transaction tx) {
        SequenceStepData step = list.steps().get(stepIndex);
        FluidStack in = fluidIn(inputFluid);
        if (in.isEmpty() || !fluidMatchesStep(in, list, stepIndex)) {
            return false;
        }
        int move = Math.min(step.amount(), in.getAmount());
        if (move <= 0) {
            return false;
        }
        FluidResource resource = FluidResource.of(in);
        int extracted = inputFluid.extract(0, resource, move, tx);
        if (extracted <= 0) {
            return false;
        }
        int inserted = outputFluid.insert(0, resource, extracted, tx);
        int leftover = extracted - inserted;
        if (leftover > 0) {
            inputFluid.insert(0, resource, leftover, tx);
        }
        return inserted > 0;
    }

    private void tickOrderedEject(ServerLevel level, BlockPos pos) {
        if (activeListIndex < 0 || activeListIndex >= SEQUENCE_LIST_COUNT) {
            finishEjectingSequence();
            return;
        }
        SequenceListData list = lists[activeListIndex];

        while (ejectStepIndex < list.steps().size()
                && (list.steps().get(ejectStepIndex) == null || list.steps().get(ejectStepIndex).isEmpty())) {
            ejectStepIndex++;
            ejectStepRemaining = 0;
        }

        if (ejectStepIndex >= list.steps().size()) {
            if (isOutputEmpty()) {
                finishEjectingSequence();
            } else {
                // Sequence steps done but remnants remain — keep pushing leftovers.
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

    /**
     * Pushes the current step's resources from buffered output to the front inventory.
     * Returns true when the step's full amount has left (or is no longer present).
     * On front rejection, leaves remaining in output and returns false (backpressure).
     */
    private boolean ejectCurrentStep(
            ServerLevel level, BlockPos pos, SequenceListData list, int stepIndex) {
        SequenceStepData step = list.steps().get(stepIndex);
        Direction front = front();
        return switch (step.kind()) {
            case ITEM -> ejectItemStepAmount(level, pos, front, list, stepIndex);
            case FLUID -> ejectFluidStepAmount(level, pos, front, list, stepIndex);
            case GAS -> ejectGasStepAmount(level, pos, front, step);
        };
    }

    private boolean ejectItemStepAmount(
            ServerLevel level, BlockPos pos, Direction front, SequenceListData list, int stepIndex) {
        if (level == null || ejectStepRemaining <= 0) {
            return true;
        }
        var registries = level.registryAccess();
        int want = ejectStepRemaining;
        int pushed = 0;
        for (int i = 0; i < outputItems.size() && pushed < want; i++) {
            ItemStack stack = ItemUtil.getStack(outputItems, i);
            if (stack.isEmpty() || !itemMatchesStep(stack, list, stepIndex, registries)) {
                continue;
            }
            int take = Math.min(want - pushed, stack.getCount());
            ItemResource resource = ItemResource.of(stack);
            ItemStack extractedStack;
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = outputItems.extract(i, resource, take, tx);
                if (extracted <= 0) {
                    continue;
                }
                tx.commit();
                extractedStack = resource.toStack(extracted);
            }
            ItemStack leftover = DuctCapHelper.insertIntoFace(level, pos, front, extractedStack);
            int accepted = extractedStack.getCount() - leftover.getCount();
            if (!leftover.isEmpty()) {
                ItemUtil.insertItemReturnRemaining(outputItems, leftover, false, null);
            }
            if (accepted <= 0) {
                // Front rejected everything — backpressure.
                setChanged();
                return false;
            }
            pushed += accepted;
            if (!leftover.isEmpty()) {
                // Partial accept — stop and retry remaining next tick.
                break;
            }
        }
        ejectStepRemaining -= pushed;
        setChanged();
        if (ejectStepRemaining <= 0) {
            return true;
        }
        // Nothing matching left in output for this step.
        int stillThere = countMatchingItemsForStep(outputItems, list, stepIndex);
        return stillThere <= 0;
    }

    private boolean ejectFluidStepAmount(
            ServerLevel level, BlockPos pos, Direction front, SequenceListData list, int stepIndex) {
        if (ejectStepRemaining <= 0) {
            return true;
        }
        FluidStack out = fluidIn(outputFluid);
        if (out.isEmpty() || !fluidMatchesStep(out, list, stepIndex)) {
            ejectStepRemaining = 0;
            return true;
        }
        BlockPos target = pos.relative(front);
        var dest = level.getCapability(Capabilities.Fluid.BLOCK, target, front.getOpposite());
        if (dest == null) {
            return false;
        }
        int want = Math.min(ejectStepRemaining, out.getAmount());
        FluidResource resource = FluidResource.of(out);
        try (Transaction tx = Transaction.openRoot()) {
            int extracted = outputFluid.extract(0, resource, want, tx);
            if (extracted <= 0) {
                return false;
            }
            int inserted = dest.insert(resource, extracted, tx);
            int leftover = extracted - inserted;
            if (leftover > 0) {
                outputFluid.insert(0, resource, leftover, tx);
            }
            if (inserted > 0) {
                tx.commit();
                ejectStepRemaining -= inserted;
                setChanged();
            }
            if (inserted <= 0) {
                return false;
            }
            if (leftover > 0) {
                return false;
            }
        }
        return ejectStepRemaining <= 0;
    }

    private boolean ejectGasStepAmount(
            ServerLevel level, BlockPos pos, Direction front, SequenceStepData step) {
        if (outputGasTank == null || !MekanismChemicalCompat.isLoaded()) {
            ejectStepRemaining = 0;
            return true;
        }
        // Soft-dep eject reserved for when Mekanism gas support is re-enabled.
        ejectStepRemaining = 0;
        return true;
    }

    /** After all steps marked done, clear any leftover output from the active sequence. */
    private void pushOutputRemnants(ServerLevel level, BlockPos pos) {
        Direction front = front();
        // Items
        for (int i = 0; i < outputItems.size(); i++) {
            ItemStack stack = ItemUtil.getStack(outputItems, i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemResource resource = ItemResource.of(stack);
            int amount = stack.getCount();
            ItemStack extractedStack;
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = outputItems.extract(i, resource, amount, tx);
                if (extracted <= 0) {
                    continue;
                }
                tx.commit();
                extractedStack = resource.toStack(extracted);
            }
            ItemStack leftover = DuctCapHelper.insertIntoFace(level, pos, front, extractedStack);
            if (!leftover.isEmpty()) {
                ItemUtil.insertItemReturnRemaining(outputItems, leftover, false, null);
                setChanged();
                return; // backpressure on remnants
            }
        }
        // Fluid
        FluidStack out = fluidIn(outputFluid);
        if (!out.isEmpty()) {
            BlockPos target = pos.relative(front);
            var dest = level.getCapability(Capabilities.Fluid.BLOCK, target, front.getOpposite());
            if (dest != null) {
                FluidResource resource = FluidResource.of(out);
                try (Transaction tx = Transaction.openRoot()) {
                    int extracted = outputFluid.extract(0, resource, out.getAmount(), tx);
                    if (extracted > 0) {
                        int inserted = dest.insert(resource, extracted, tx);
                        int leftover = extracted - inserted;
                        if (leftover > 0) {
                            outputFluid.insert(0, resource, leftover, tx);
                        }
                        if (inserted > 0) {
                            tx.commit();
                        }
                    }
                }
            }
        }
        setChanged();
    }

    // --- Per-list / per-step reservation accounting ---

    /**
     * Matching input count minus amounts claimed by earlier steps in the same list and by all
     * competing steps of other enabled lists.
     */
    private int availableItemsForStep(int listIndex, int stepIndex, @Nullable ItemStack probe) {
        SequenceListData list = lists[listIndex];
        int have = countMatchingItemsForStep(inputItems, list, stepIndex);
        int reserved = reservedItemAmount(listIndex, stepIndex, probe);
        return Math.max(0, have - reserved);
    }

    private int reservedItemAmount(int listIndex, int stepIndex, @Nullable ItemStack probe) {
        int reserved = 0;
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData other = lists[li];
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
        for (int i = 0; i < inputItems.size(); i++) {
            ItemStack stack = ItemUtil.getStack(inputItems, i);
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
        FluidStack in = probe != null && !probe.isEmpty() ? probe : fluidIn(inputFluid);
        // "have" is the tank amount when it matches this step; probe-only accepts use 0 have.
        FluidStack tank = fluidIn(inputFluid);
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
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData other = lists[li];
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
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData other = lists[li];
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

    private void moveGasToOutput(SequenceStepData step) {
        if (inputGasTank == null || outputGasTank == null || !MekanismChemicalCompat.isLoaded()) {
            return;
        }
        // Soft-dep path reserved for when Mekanism gas support is re-enabled.
    }

    private void setEmitting(ServerLevel level, BlockPos pos, BlockState state, boolean on) {
        emitting = on;
        if (state.getValue(SequentialBufferBlock.POWERED) != on) {
            level.setBlock(pos, state.setValue(SequentialBufferBlock.POWERED, on), 3);
            level.updateNeighborsAt(pos, state.getBlock());
        }
        if (!on) {
            // Re-sample neighbor after dropping POWERED to avoid latching on our own signal.
            prevNeighborPowered = level.getBestNeighborSignal(pos) > 0;
        }
        setChanged();
    }

    /** Hub Dump: empty input buffers into the player inventory; leftover drops in the world. */
    public void dumpInput(@Nullable Player player, Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        for (int i = 0; i < inputItems.size(); i++) {
            ItemStack stack = ItemUtil.getStack(inputItems, i);
            if (stack.isEmpty()) {
                continue;
            }
            inputItems.set(i, ItemResource.EMPTY, 0);
            giveOrDrop(player, level, pos, stack);
        }
        inputFluid.set(0, FluidResource.EMPTY, 0);
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
        for (int i = 0; i < inputItems.size(); i++) {
            ItemStack stack = ItemUtil.getStack(inputItems, i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isItemWantedByAnyEnabledList(stack, registries)) {
                continue;
            }
            inputItems.set(i, ItemResource.EMPTY, 0);
            giveOrDrop(player, level, pos, stack);
            changed = true;
        }
        FluidStack fluid = fluidIn(inputFluid);
        if (!fluid.isEmpty() && !isFluidWantedByAnyEnabledList(fluid)) {
            inputFluid.set(0, FluidResource.EMPTY, 0);
            changed = true;
        }
        if (gasAmount(inputGasTank) > 0L && !isGasWantedByAnyEnabledList()) {
            clearGasTank(inputGasTank);
            changed = true;
        }
        if (changed) {
            syncToClients();
        }
    }

    private boolean isItemWantedByAnyEnabledList(ItemStack stack, HolderLookup.Provider registries) {
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData list = lists[li];
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
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData list = lists[li];
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
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData list = lists[li];
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
    private static void giveOrDrop(@Nullable Player player, Level level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = stack.copy();
        if (player != null) {
            player.getInventory().add(remaining);
        }
        if (!remaining.isEmpty()) {
            Containers.dropItemStack(
                    level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, remaining);
        }
    }

    public void dropBufferContents(Level level, BlockPos pos) {
        for (int i = 0; i < inputItems.size(); i++) {
            ItemStack stack = ItemUtil.getStack(inputItems, i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                inputItems.set(i, ItemResource.EMPTY, 0);
            }
        }
        for (int i = 0; i < outputItems.size(); i++) {
            ItemStack stack = ItemUtil.getStack(outputItems, i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                outputItems.set(i, ItemResource.EMPTY, 0);
            }
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            dropBufferContents(level, pos);
        }
        super.preRemoveSideEffects(pos, state);
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
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        CompoundTag tag = new CompoundTag();
        writeCustom(tag, level != null ? level.registryAccess() : null);
        output.store("Sequential", CompoundTag.CODEC, tag);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read("Sequential", CompoundTag.CODEC)
                .ifPresent(tag -> readCustom(tag, level != null ? level.registryAccess() : null));
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
        for (SequenceListData list : lists) {
            listTag.add(list.save());
        }
        tag.put("Lists", listTag);
        if (registries != null) {
            tag.put("InItems", DuctNbtCodecs.serializeHandler(inputItems, registries));
            tag.put("OutItems", DuctNbtCodecs.serializeHandler(outputItems, registries));
            tag.put("InFluid", DuctNbtCodecs.serializeHandler(inputFluid, registries));
            tag.put("OutFluid", DuctNbtCodecs.serializeHandler(outputFluid, registries));
        }
    }

    private void readCustom(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        gateMode = SequentialGateMode.fromOrdinal(tag.getByteOr("Gate", (byte) 0) & 0xFF);
        strictSequentialIntake = tag.getBooleanOr("StrictIntake", true);
        emitting = tag.getBooleanOr("Emitting", false);
        emitTicksLeft = tag.getIntOr("EmitTicks", 0);
        editingListIndex = tag.getIntOr("EditList", -1);
        activeListIndex = tag.getIntOr("ActiveList", -1);
        ejectStepIndex = tag.getIntOr("EjectStep", 0);
        ejectStepRemaining = tag.getIntOr("EjectRemain", 0);
        ejectCooldown = tag.getIntOr("EjectCool", 0);
        interSequenceDelayTicks =
                Mth.clamp(tag.getIntOr("InterDelay", DEFAULT_INTER_SEQUENCE_DELAY), 0, MAX_INTER_SEQUENCE_DELAY);
        interSequenceCooldown = Math.max(0, tag.getIntOr("InterCool", 0));
        ListTag listTag = tag.getListOrEmpty("Lists");
        for (int i = 0; i < SEQUENCE_LIST_COUNT; i++) {
            lists[i] = new SequenceListData();
            if (i < listTag.size()) {
                lists[i].load(listTag.getCompoundOrEmpty(i));
            }
        }
        if (registries != null) {
            if (tag.contains("InItems")) {
                DuctNbtCodecs.deserializeHandler(inputItems, registries, tag.getCompoundOrEmpty("InItems"));
            }
            if (tag.contains("OutItems")) {
                DuctNbtCodecs.deserializeHandler(outputItems, registries, tag.getCompoundOrEmpty("OutItems"));
            }
            if (tag.contains("InFluid")) {
                CompoundTag inFluid = tag.getCompoundOrEmpty("InFluid");
                // Legacy FluidTank single-stack format used "Fluid"; new handler uses stacks list.
                if (inFluid.contains("Fluid") && !inFluid.contains("stacks")) {
                    FluidStack stack =
                            DuctNbtCodecs.parseFluidStack(registries, inFluid.getCompoundOrEmpty("Fluid"))
                                    .orElse(FluidStack.EMPTY);
                    inputFluid.set(
                            0,
                            stack.isEmpty() ? FluidResource.EMPTY : FluidResource.of(stack),
                            stack.getAmount());
                } else {
                    DuctNbtCodecs.deserializeHandler(inputFluid, registries, inFluid);
                }
            }
            if (tag.contains("OutFluid")) {
                CompoundTag outFluid = tag.getCompoundOrEmpty("OutFluid");
                if (outFluid.contains("Fluid") && !outFluid.contains("stacks")) {
                    FluidStack stack =
                            DuctNbtCodecs.parseFluidStack(registries, outFluid.getCompoundOrEmpty("Fluid"))
                                    .orElse(FluidStack.EMPTY);
                    outputFluid.set(
                            0,
                            stack.isEmpty() ? FluidResource.EMPTY : FluidResource.of(stack),
                            stack.getAmount());
                } else {
                    DuctNbtCodecs.deserializeHandler(outputFluid, registries, outFluid);
                }
            }
        }
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
    public void handleUpdateTag(ValueInput input) {
        HolderLookup.Provider registries = input.lookup();
        input.read("Sequential", CompoundTag.CODEC).ifPresent(tag -> readCustom(tag, registries));
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ValueInput input) {
        handleUpdateTag(input);
    }

    /** Snapshot of all Sequence Lists for Settings Copier SEQUENTIAL mode. */
    public CompoundTag captureSettings() {
        CompoundTag tag = new CompoundTag();
        tag.putByte(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 0); // machine-wide
        tag.putByte("Gate", (byte) gateMode.ordinal());
        tag.putBoolean("StrictIntake", strictSequentialIntake);
        ListTag listTag = new ListTag();
        for (SequenceListData list : lists) {
            listTag.add(list.save());
        }
        tag.put("Lists", listTag);
        return tag;
    }

    /** Snapshot of a single Sequence List (index preserved). */
    public CompoundTag captureListSettings(int listIndex) {
        int idx = Math.floorMod(listIndex, SEQUENCE_LIST_COUNT);
        CompoundTag tag = new CompoundTag();
        tag.putByte(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 1); // single list
        tag.putInt("ListIndex", idx);
        tag.put("List", list(idx).save());
        return tag;
    }

    public void applySettings(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        byte kind = tag.getByteOr(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 0);
        if (kind == 1 && tag.contains("List")) {
            int idx = Math.floorMod(tag.getIntOr("ListIndex", 0), SEQUENCE_LIST_COUNT);
            lists[idx] = new SequenceListData();
            lists[idx].load(tag.getCompoundOrEmpty("List"));
        } else {
            gateMode = SequentialGateMode.fromOrdinal(tag.getByteOr("Gate", (byte) 0) & 0xFF);
            strictSequentialIntake = tag.getBooleanOr("StrictIntake", true);
            ListTag listTag = tag.getListOrEmpty("Lists");
            for (int i = 0; i < SEQUENCE_LIST_COUNT; i++) {
                lists[i] = new SequenceListData();
                if (i < listTag.size()) {
                    lists[i].load(listTag.getCompoundOrEmpty(i));
                }
            }
        }
        setChanged();
        syncToClients();
    }

    private ResourceHandler<ItemResource> filteredItemInsert(ResourceHandler<ItemResource> delegate) {
        return new DelegatingResourceHandler<>(delegate) {
            @Override
            public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
                int need = remainingItemNeed(resource);
                if (need <= 0) {
                    return 0;
                }
                return super.insert(index, resource, Math.min(amount, need), transaction);
            }

            @Override
            public int insert(ItemResource resource, int amount, TransactionContext transaction) {
                int need = remainingItemNeed(resource);
                if (need <= 0) {
                    return 0;
                }
                return super.insert(resource, Math.min(amount, need), transaction);
            }

            @Override
            public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
                return 0;
            }

            @Override
            public int extract(ItemResource resource, int amount, TransactionContext transaction) {
                return 0;
            }
        };
    }

    private ResourceHandler<FluidResource> filteredFluidInsert(ResourceHandler<FluidResource> delegate) {
        return new DelegatingResourceHandler<>(delegate) {
            @Override
            public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
                int need = remainingFluidNeed(resource);
                if (need <= 0) {
                    return 0;
                }
                return super.insert(index, resource, Math.min(amount, need), transaction);
            }

            @Override
            public int insert(FluidResource resource, int amount, TransactionContext transaction) {
                int need = remainingFluidNeed(resource);
                if (need <= 0) {
                    return 0;
                }
                return super.insert(resource, Math.min(amount, need), transaction);
            }

            @Override
            public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
                return 0;
            }

            @Override
            public int extract(FluidResource resource, int amount, TransactionContext transaction) {
                return 0;
            }
        };
    }

    /**
     * How many more matching items enabled lists still need after reservations. Caps duct/hopper
     * inserts so a batch cannot overshoot the configured step amount (e.g. need 5 of 13 must not
     * accept a full AD batch of 8).
     *
     * <p>With {@link #strictSequentialIntake} only the first incomplete step per list may accept.
     */
    private int remainingItemNeed(ItemResource resource) {
        if (resource == null || resource.isEmpty() || level == null) {
            return 0;
        }
        ItemStack probe = resource.toStack(1);
        var registries = level.registryAccess();
        int need = 0;
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData list = lists[li];
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

    private int remainingFluidNeed(FluidResource resource) {
        if (resource == null || resource.isEmpty() || level == null) {
            return 0;
        }
        FluidStack probe = resource.toStack(1);
        int need = 0;
        for (int li = 0; li < SEQUENCE_LIST_COUNT; li++) {
            SequenceListData list = lists[li];
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
        SequenceListData list = lists[listIndex];
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

    /**
     * Extract-only wrapper with progressive unlock: only resources belonging to already-ejected
     * steps (strictly before {@link #ejectStepIndex}) may be pulled externally. Auto-eject bypasses
     * this by reading {@link #outputItems}/{@link #outputFluid} directly.
     */
    private <T extends net.neoforged.neoforge.transfer.resource.Resource>
            ResourceHandler<T> extractOnly(ResourceHandler<T> delegate) {
        return new DelegatingResourceHandler<>(delegate) {
            @Override
            public int insert(int index, T resource, int amount, TransactionContext transaction) {
                return 0;
            }

            @Override
            public int insert(T resource, int amount, TransactionContext transaction) {
                return 0;
            }

            @Override
            public int extract(int index, T resource, int amount, TransactionContext transaction) {
                if (!isExtractUnlocked(resource)) {
                    return 0;
                }
                return super.extract(index, resource, amount, transaction);
            }

            @Override
            public int extract(T resource, int amount, TransactionContext transaction) {
                if (!isExtractUnlocked(resource)) {
                    return 0;
                }
                return super.extract(resource, amount, transaction);
            }
        };
    }

    private boolean isExtractUnlocked(Object resource) {
        if (activeListIndex < 0 || activeListIndex >= SEQUENCE_LIST_COUNT || level == null) {
            // Idle: allow extract of any leftover in output.
            return true;
        }
        if (ejectStepIndex <= 0) {
            // No step fully unlocked yet — external extract blocked; auto-eject uses internal handlers.
            return false;
        }
        SequenceListData list = lists[activeListIndex];
        var registries = level.registryAccess();
        if (resource instanceof ItemResource itemResource) {
            if (itemResource.isEmpty()) {
                return false;
            }
            ItemStack stack = itemResource.toStack(1);
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
        if (resource instanceof FluidResource fluidResource) {
            if (fluidResource.isEmpty()) {
                return false;
            }
            FluidStack stack = fluidResource.toStack(1);
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
        return false;
    }
}
