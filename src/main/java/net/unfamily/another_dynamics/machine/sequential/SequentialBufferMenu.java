package net.unfamily.another_dynamics.machine.sequential;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;
import net.unfamily.another_dynamics.duct.FilterLineTextUtil;
import net.unfamily.another_dynamics.duct.SettingsCopierFeedback;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.network.SequentialBufferActionPayload;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

/** Menu for Sequential Buffer hub / Sequence List editor (sub-views on the screen). */
public final class SequentialBufferMenu extends AbstractContainerMenu {
    /**
     * Right column mirrors ducts: redstone → Dump (channel slot height) → Settings Copier → C → P.
     * Copier frame uses {@link DuctNodeMenu#SLOT_COPY_BACKGROUND_Y} like world ducts.
     */
    public static final int SEQ_COPY_BG_X = DuctNodeMenu.SLOT_COPY_BACKGROUND_X;
    public static final int SEQ_COPY_BG_Y = DuctNodeMenu.SLOT_COPY_BACKGROUND_Y;
    public static final int SEQ_COPY_X = SEQ_COPY_BG_X + 1;
    public static final int SEQ_COPY_Y = SEQ_COPY_BG_Y + 1;
    /** Dump sits in the former channel-letter slot, above the Settings Copier. */
    public static final int SEQ_COPY_DUMP_Y =
            DuctNodeMenu.CHANNEL_BACKGROUND_Y
                    + (18 - DuctNodeMenu.COPIER_ACTION_BUTTON_H) / 2;
    public static final int SEQ_COPY_SAVE_Y = DuctNodeMenu.COPIER_SAVE_BUTTON_Y;
    public static final int SEQ_COPY_LOAD_Y = DuctNodeMenu.COPIER_LOAD_BUTTON_Y;

    /** clickMenuButton ids: dump / gate / per-list enable|clear|output / open-edit / close-edit / remove-step. */
    public static final int BTN_DUMP = 0;
    public static final int BTN_CYCLE_GATE = 1;
    public static final int BTN_CLOSE_EDIT = 2;
    public static final int BTN_TOGGLE_ENABLE_BASE = 100;
    public static final int BTN_CLEAR_LIST_BASE = 200;
    public static final int BTN_CYCLE_OUTPUT_BASE = 300;
    public static final int BTN_OPEN_EDIT_BASE = 400;
    public static final int BTN_REMOVE_STEP_BASE = 500;

    /** Slot index of the Settings Copier slot (always first). */
    public static final int COPY_SLOT_INDEX = 0;

    private final SequentialBufferBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final SimpleContainer copyContainer = new SimpleContainer(1);

    public SequentialBufferMenu(int id, Inventory inv, SequentialBufferBlockEntity be) {
        super(ModMenuTypes.SEQUENTIAL_BUFFER.get(), id);
        this.blockEntity = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());

        addSlot(
                new Slot(copyContainer, 0, SEQ_COPY_X, SEQ_COPY_Y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return !stack.isEmpty() && stack.getItem() instanceof SettingsCopierItem;
                    }

                    @Override
                    public int getMaxStackSize() {
                        return 1;
                    }
                });
        addPlayerInventory(inv);
    }

    public static SequentialBufferMenu createClient(int id, Inventory inv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        var level = inv.player.level();
        var be = level.getBlockEntity(pos);
        if (!(be instanceof SequentialBufferBlockEntity sequential)) {
            throw new IllegalStateException("Missing SequentialBufferBlockEntity at " + pos);
        }
        return new SequentialBufferMenu(id, inv, sequential);
    }

    private void addPlayerInventory(Inventory inv) {
        int startX = DuctNodeMenu.PLAYER_SLOTS_X;
        int startY = DuctNodeMenu.PLAYER_SLOTS_Y;
        int hotbarGap = 4;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, startX + col * 18, startY + row * 18));
            }
        }
        int hotbarY = startY + 3 * 18 + hotbarGap;
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, startX + col * 18, hotbarY));
        }
    }

    public SequentialBufferBlockEntity blockEntity() {
        return blockEntity;
    }

    /** Alias for screen / packet handlers. */
    public SequentialBufferBlockEntity getBe() {
        return blockEntity;
    }

    public SimpleContainer copyContainer() {
        return copyContainer;
    }

    public ItemStack copySlotStack() {
        return copyContainer.getItem(0);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.SEQUENTIAL_BUFFER.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, pos) -> clearContainer(player, copyContainer));
    }

    /**
     * Encoded button ids for simple hub actions (no string payload). Prefer
     * {@link #handleAction} via {@link SequentialBufferActionPayload} from the GUI.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide() || !(player instanceof ServerPlayer)) {
            return false;
        }
        if (id == BTN_DUMP) {
            return handleAction(player, new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_DUMP));
        }
        if (id == BTN_CYCLE_GATE) {
            return handleAction(player, new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_CYCLE_GATE));
        }
        if (id == BTN_CLOSE_EDIT) {
            return handleAction(player, new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_CLOSE_EDIT));
        }
        if (id >= BTN_TOGGLE_ENABLE_BASE && id < BTN_TOGGLE_ENABLE_BASE + SequentialBufferBlockEntity.sequentialTaskCount()) {
            return handleAction(
                    player,
                    new SequentialBufferActionPayload(
                            SequentialBufferActionPayload.ACTION_TOGGLE_ENABLE, id - BTN_TOGGLE_ENABLE_BASE));
        }
        if (id >= BTN_CLEAR_LIST_BASE && id < BTN_CLEAR_LIST_BASE + SequentialBufferBlockEntity.sequentialTaskCount()) {
            return handleAction(
                    player,
                    new SequentialBufferActionPayload(
                            SequentialBufferActionPayload.ACTION_CLEAR_LIST, id - BTN_CLEAR_LIST_BASE));
        }
        if (id >= BTN_CYCLE_OUTPUT_BASE && id < BTN_CYCLE_OUTPUT_BASE + SequentialBufferBlockEntity.sequentialTaskCount()) {
            return handleAction(
                    player,
                    new SequentialBufferActionPayload(
                            SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT, id - BTN_CYCLE_OUTPUT_BASE));
        }
        if (id >= BTN_OPEN_EDIT_BASE && id < BTN_OPEN_EDIT_BASE + SequentialBufferBlockEntity.sequentialTaskCount()) {
            return handleAction(
                    player,
                    new SequentialBufferActionPayload(
                            SequentialBufferActionPayload.ACTION_OPEN_EDIT, id - BTN_OPEN_EDIT_BASE));
        }
        if (id >= BTN_REMOVE_STEP_BASE && id < BTN_REMOVE_STEP_BASE + SequentialTaskData.maxSteps()) {
            int edit = blockEntity.editingListIndex();
            if (edit < 0) {
                return false;
            }
            return handleAction(
                    player,
                    new SequentialBufferActionPayload(
                            SequentialBufferActionPayload.ACTION_REMOVE_STEP, edit, id - BTN_REMOVE_STEP_BASE));
        }
        return false;
    }

    /** Server-side handler for {@link SequentialBufferActionPayload}. */
    public boolean handleAction(Player player, SequentialBufferActionPayload payload) {
        if (player.level().isClientSide() || blockEntity.isRemoved()) {
            return false;
        }
        int listIndex = Mth.clamp(payload.listIndex(), 0, SequentialBufferBlockEntity.sequentialTaskCount() - 1);
        return switch (payload.action()) {
            case SequentialBufferActionPayload.ACTION_DUMP -> {
                blockEntity.dumpInput(player, player.level(), blockEntity.getBlockPos());
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CYCLE_GATE -> {
                blockEntity.setGateMode(blockEntity.gateMode().next());
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CYCLE_GATE_PREV -> {
                blockEntity.setGateMode(blockEntity.gateMode().previous());
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_TOGGLE_STRICT_INTAKE -> {
                blockEntity.toggleStrictSequentialIntake();
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_TOGGLE_ENABLE -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                if (!list.hasContent()) {
                    list.setEnabled(false);
                } else {
                    list.setEnabled(!list.isEnabled());
                }
                blockEntity.ejectOrphanedInput(player);
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CLEAR_LIST -> {
                blockEntity.list(listIndex).clear();
                blockEntity.ejectOrphanedInput(player);
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                list.setOutputMode(list.outputMode().next());
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT_PREV -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                list.setOutputMode(list.outputMode().previous());
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_OPEN_EDIT -> {
                blockEntity.setEditingListIndex(listIndex);
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CLOSE_EDIT -> {
                blockEntity.setEditingListIndex(-1);
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_ADD_STEP -> {
                yield addOrUpdateStep(player, listIndex, -1, payload);
            }
            case SequentialBufferActionPayload.ACTION_UPDATE_STEP -> {
                yield addOrUpdateStep(player, listIndex, payload.stepIndex(), payload);
            }
            case SequentialBufferActionPayload.ACTION_REMOVE_STEP -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                if (!list.removeStepAt(payload.stepIndex())) {
                    yield false;
                }
                blockEntity.ejectOrphanedInput(player);
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CLEAR_STEP -> {
                yield clearStepContent(listIndex, payload.stepIndex(), player);
            }
            case SequentialBufferActionPayload.ACTION_SET_AMOUNT -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                int step = payload.stepIndex();
                if (step < 0 || step >= list.steps().size()) {
                    yield false;
                }
                list.steps().get(step).setAmount(Math.max(1, payload.amount()));
                blockEntity.ejectOrphanedInput(player);
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_SET_CONCAT -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                int step = payload.stepIndex();
                if (step < 0 || step >= SequentialTaskData.maxSteps()) {
                    yield false;
                }
                // Empty fixed slots still store concat; pad then compact trailing empties.
                list.ensureStepSlot(step);
                list.setConcatAt(step, payload.concatOrdinal());
                list.compactTrailingEmptySteps();
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CYCLE_CONCAT -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                int step = payload.stepIndex();
                if (step < 0 || step >= SequentialTaskData.maxSteps()) {
                    yield false;
                }
                list.ensureStepSlot(step);
                list.cycleConcatNext(step);
                list.compactTrailingEmptySteps();
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_CYCLE_CONCAT_PREV -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                int step = payload.stepIndex();
                if (step < 0 || step >= SequentialTaskData.maxSteps()) {
                    yield false;
                }
                list.ensureStepSlot(step);
                list.cycleConcatPrev(step);
                list.compactTrailingEmptySteps();
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_REORDER_STEPS -> {
                blockEntity.list(listIndex).reorderStepsByFilterWeight();
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_COPY_SETTINGS -> {
                yield copySettings(player, -1);
            }
            case SequentialBufferActionPayload.ACTION_PASTE_SETTINGS -> {
                yield pasteSettings(player, -1);
            }
            case SequentialBufferActionPayload.ACTION_COPY_LIST -> {
                yield copySettings(player, listIndex);
            }
            case SequentialBufferActionPayload.ACTION_PASTE_LIST -> {
                yield pasteSettings(player, listIndex);
            }
            case SequentialBufferActionPayload.ACTION_SET_INTER_DELAY -> {
                blockEntity.setInterSequenceDelayTicks(payload.amount());
                blockEntity.syncToClients();
                yield true;
            }
            case SequentialBufferActionPayload.ACTION_SET_LIST_NAME -> {
                SequentialTaskData list = blockEntity.list(listIndex);
                list.setCustomName(payload.filter());
                blockEntity.syncToClients();
                yield true;
            }
            default -> false;
        };
    }

    /** Prefer copy-slot Settings Copier, then carried, then hands. */
    private Optional<ItemStack> findCopier(Player player) {
        ItemStack slotStack = copySlotStack();
        if (!slotStack.isEmpty() && slotStack.getItem() instanceof SettingsCopierItem) {
            return Optional.of(slotStack);
        }
        return SettingsCopierSequentialSnapshot.findCopierInHands(player, getCarried());
    }

    private boolean copySettings(Player player, int listIndex) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        var copierOpt = findCopier(player);
        if (copierOpt.isEmpty()) {
            SettingsCopierFeedback.notifyPasteFailed(serverPlayer);
            return false;
        }
        ItemStack copier = copierOpt.get();
        if (!(copier.getItem() instanceof SettingsCopierItem)) {
            return false;
        }
        CompoundTag snapshot =
                listIndex < 0
                        ? blockEntity.captureSettings()
                        : blockEntity.captureListSettings(listIndex);
        SettingsCopierSequentialSnapshot.write(copier, snapshot);
        SettingsCopierFeedback.notifyCopied(serverPlayer);
        return true;
    }

    private boolean pasteSettings(Player player, int listIndex) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        var copierOpt = findCopier(player);
        if (copierOpt.isEmpty()) {
            SettingsCopierFeedback.notifyPasteFailed(serverPlayer);
            return false;
        }
        ItemStack copier = copierOpt.get();
        if (SettingsCopierStoreKind.getMode(copier) != SettingsCopierStoreKind.SEQUENTIAL) {
            SettingsCopierFeedback.notifyWrongMode(serverPlayer);
            return false;
        }
        var data = SettingsCopierSequentialSnapshot.read(copier);
        if (data.isEmpty()) {
            SettingsCopierFeedback.notifyPasteFailed(serverPlayer);
            return false;
        }
        CompoundTag tag = data.get();
        if (listIndex >= 0) {
            byte kind =
                    tag.contains(SettingsCopierSequentialSnapshot.KIND_TAG, Tag.TAG_BYTE)
                            ? tag.getByte(SettingsCopierSequentialSnapshot.KIND_TAG)
                            : 0;
            if (kind == 1) {
                tag.putInt("ListIndex", listIndex);
            } else {
                ListTag lists = SequentialTaskData.readTasksListTag(tag);
                if (listIndex < lists.size()) {
                    CompoundTag single = new CompoundTag();
                    single.putByte(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 1);
                    single.putInt("ListIndex", listIndex);
                    single.put("List", lists.getCompound(listIndex));
                    tag = single;
                }
            }
        }
        blockEntity.applySettings(tag);
        blockEntity.ejectOrphanedInput(player);
        SettingsCopierFeedback.notifyPasted(serverPlayer);
        return true;
    }

    /** Clears filter/amount/kind on an existing step slot without removing the index. */
    private boolean clearStepContent(int listIndex, int stepIndex, Player player) {
        SequentialTaskData list = blockEntity.list(listIndex);
        if (stepIndex < 0 || stepIndex >= list.steps().size()) {
            return false;
        }
        SequenceStepData step = list.steps().get(stepIndex);
        if (step == null || step.isEmpty()) {
            return false;
        }
        step.setFilter("");
        step.setKind(SequenceStepData.Kind.ITEM);
        step.setAmount(1);
        list.compactTrailingEmptySteps();
        blockEntity.ejectOrphanedInput(player);
        blockEntity.syncToClients();
        return true;
    }

    private boolean addOrUpdateStep(
            Player player, int listIndex, int stepIndex, SequentialBufferActionPayload payload) {
        SequentialTaskData list = blockEntity.list(listIndex);
        String filter = FilterLineTextUtil.normalizeForCommit(payload.filter());
        if (filter.isEmpty()) {
            return false;
        }
        if (filter.equalsIgnoreCase("&anything_else") || filter.equalsIgnoreCase("anything_else")) {
            return false;
        }
        SequenceStepData.Kind kind = SequenceStepData.Kind.fromOrdinal(payload.kindOrdinal());
        int amount = Math.max(1, payload.amount());
        int concat = Math.clamp(payload.concatOrdinal(), 0, FilterConcatChannel.MAX_LETTER);
        if (stepIndex < 0) {
            if (list.steps().size() >= SequentialTaskData.maxSteps()) {
                return false;
            }
            SequenceStepData step = new SequenceStepData();
            step.setFilter(filter);
            step.setKind(kind);
            step.setAmount(amount);
            list.steps().add(step);
            list.syncConcatSize();
            list.setConcatAt(list.steps().size() - 1, concat);
        } else {
            if (stepIndex >= SequentialTaskData.maxSteps()) {
                return false;
            }
            list.ensureStepSlot(stepIndex);
            SequenceStepData step = list.steps().get(stepIndex);
            step.setFilter(filter);
            step.setKind(kind);
            step.setAmount(amount);
            list.setConcatAt(stepIndex, concat);
        }
        list.compactTrailingEmptySteps();
        blockEntity.ejectOrphanedInput(player);
        blockEntity.syncToClients();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        int playerFirst = COPY_SLOT_INDEX + 1;
        int playerLast = this.slots.size();
        if (index == COPY_SLOT_INDEX) {
            if (!moveItemStackTo(stack, playerFirst, playerLast, true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof SettingsCopierItem) {
            if (!moveItemStackTo(stack, COPY_SLOT_INDEX, COPY_SLOT_INDEX + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else {
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
