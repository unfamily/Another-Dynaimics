package net.unfamily.another_dynamics.machine.sequential;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;
import net.unfamily.another_dynamics.duct.FilterLineTextUtil;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.network.SequentialBufferActionPayload;

/**
 * In-memory Sequential Buffer config for Settings Copier virtual editor (no world block).
 */
public final class SettingsCopierSequentialVirtualSession {
    private final ServerPlayer player;
    private final InteractionHand hand;
    private SequentialGateMode gate = SequentialGateMode.AUTO;
    private boolean strictSequentialIntake = false;
    private final SequenceListData[] lists = new SequenceListData[SequentialBufferBlockEntity.sequenceListCount()];
    private int editingListIndex = -1;

    public SettingsCopierSequentialVirtualSession(ServerPlayer player, InteractionHand hand, ItemStack copier) {
        this.player = player;
        this.hand = hand;
        for (int i = 0; i < lists.length; i++) {
            lists[i] = new SequenceListData();
        }
        loadFromCopier(copier);
    }

    public InteractionHand hand() {
        return hand;
    }

    public SequentialGateMode gateMode() {
        return gate;
    }

    public void setGateMode(SequentialGateMode mode) {
        this.gate = mode == null ? SequentialGateMode.AUTO : mode;
    }

    public boolean strictSequentialIntake() {
        return strictSequentialIntake;
    }

    public void setStrictSequentialIntake(boolean strict) {
        this.strictSequentialIntake = strict;
    }

    public void toggleStrictSequentialIntake() {
        this.strictSequentialIntake = !this.strictSequentialIntake;
    }

    public SequenceListData list(int index) {
        return lists[Math.floorMod(index, lists.length)];
    }

    public int editingListIndex() {
        return editingListIndex;
    }

    public void setEditingListIndex(int index) {
        this.editingListIndex = index;
    }

    public ItemStack getCopierStack() {
        return player.getItemInHand(hand);
    }

    public void loadFromCopier(ItemStack copier) {
        var data = SettingsCopierSequentialSnapshot.read(copier);
        if (data.isEmpty()) {
            gate = SequentialGateMode.AUTO;
            strictSequentialIntake = false;
            for (int i = 0; i < lists.length; i++) {
                lists[i] = new SequenceListData();
            }
            editingListIndex = -1;
            return;
        }
        applySnapshot(data.get());
    }

    public void persistToCopier(ItemStack copier) {
        if (copier.isEmpty() || !(copier.getItem() instanceof SettingsCopierItem)) {
            return;
        }
        SettingsCopierSequentialSnapshot.write(copier, captureSettings());
    }

    /** Machine-wide SEQUENTIAL snapshot (same shape as {@link SequentialBufferBlockEntity#captureSettings()}). */
    public CompoundTag captureSettings() {
        CompoundTag tag = new CompoundTag();
        tag.putByte(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 0);
        tag.putByte("Gate", (byte) gate.ordinal());
        tag.putBoolean("StrictIntake", strictSequentialIntake);
        ListTag listTag = new ListTag();
        for (SequenceListData list : lists) {
            listTag.add(list.save());
        }
        tag.put("Lists", listTag);
        return tag;
    }

    public void applySnapshot(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        byte kind = tag.getByteOr(SettingsCopierSequentialSnapshot.KIND_TAG, (byte) 0);
        if (kind == 1 && tag.contains("List")) {
            int idx = Math.floorMod(tag.getIntOr("ListIndex", 0), lists.length);
            for (int i = 0; i < lists.length; i++) {
                lists[i] = new SequenceListData();
            }
            lists[idx] = new SequenceListData();
            lists[idx].load(tag.getCompoundOrEmpty("List"));
            gate = SequentialGateMode.AUTO;
            strictSequentialIntake = false;
        } else {
            gate =
                    SequentialGateMode.fromOrdinal(
                            tag.getByteOr("Gate", (byte) SequentialGateMode.AUTO.ordinal()) & 0xFF);
            strictSequentialIntake = tag.getBooleanOr("StrictIntake", false);
            ListTag listTag = tag.getListOrEmpty("Lists");
            for (int i = 0; i < lists.length; i++) {
                lists[i] = new SequenceListData();
                if (i < listTag.size()) {
                    lists[i].load(listTag.getCompoundOrEmpty(i));
                }
            }
        }
    }

    /** Persist session into the held copier and sync to the client (leave-hub / full GUI close). */
    public void persistAndSync() {
        ItemStack copier = getCopierStack();
        if (copier.isEmpty() || !(copier.getItem() instanceof SettingsCopierItem)) {
            return;
        }
        SettingsCopierStoreKind.setMode(copier, SettingsCopierStoreKind.SEQUENTIAL);
        persistToCopier(copier);
        player.setItemInHand(hand, copier);
        ModNetwork.sendSettingsCopierStackSync(player, copier);
    }

    /**
     * Push an in-memory mirror to the client UI without writing the held item.
     * Authoritative persist happens on leave-hub or full Settings Copier GUI close.
     */
    public void syncClientMirror() {
        ItemStack mirror = getCopierStack().copy();
        if (mirror.isEmpty() || !(mirror.getItem() instanceof SettingsCopierItem)) {
            return;
        }
        SettingsCopierStoreKind.setMode(mirror, SettingsCopierStoreKind.SEQUENTIAL);
        persistToCopier(mirror);
        ModNetwork.sendSettingsCopierStackSync(player, mirror);
    }

    /**
     * Apply hub / list / step actions (same ids as {@link SequentialBufferMenu#handleAction}).
     * Machine-only actions (dump, copy/paste, inter-delay) are no-ops.
     */
    public boolean handleAction(SequentialBufferActionPayload payload) {
        int listIndex = Mth.clamp(payload.listIndex(), 0, lists.length - 1);
        boolean changed =
                switch (payload.action()) {
                    case SequentialBufferActionPayload.ACTION_DUMP,
                            SequentialBufferActionPayload.ACTION_COPY_SETTINGS,
                            SequentialBufferActionPayload.ACTION_PASTE_SETTINGS,
                            SequentialBufferActionPayload.ACTION_COPY_LIST,
                            SequentialBufferActionPayload.ACTION_PASTE_LIST,
                            SequentialBufferActionPayload.ACTION_SET_INTER_DELAY -> false;
                    case SequentialBufferActionPayload.ACTION_CYCLE_GATE -> {
                        setGateMode(gate.next());
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CYCLE_GATE_PREV -> {
                        setGateMode(gate.previous());
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_TOGGLE_STRICT_INTAKE -> {
                        toggleStrictSequentialIntake();
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_TOGGLE_ENABLE -> {
                        SequenceListData list = list(listIndex);
                        if (!list.hasContent()) {
                            list.setEnabled(false);
                        } else {
                            list.setEnabled(!list.isEnabled());
                        }
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CLEAR_LIST -> {
                        list(listIndex).clear();
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT -> {
                        SequenceListData list = list(listIndex);
                        list.setOutputMode(list.outputMode().next());
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT_PREV -> {
                        SequenceListData list = list(listIndex);
                        list.setOutputMode(list.outputMode().previous());
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_OPEN_EDIT -> {
                        setEditingListIndex(listIndex);
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CLOSE_EDIT -> {
                        setEditingListIndex(-1);
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_ADD_STEP -> addOrUpdateStep(listIndex, -1, payload);
                    case SequentialBufferActionPayload.ACTION_UPDATE_STEP ->
                            addOrUpdateStep(listIndex, payload.stepIndex(), payload);
                    case SequentialBufferActionPayload.ACTION_REMOVE_STEP -> {
                        if (!list(listIndex).removeStepAt(payload.stepIndex())) {
                            yield false;
                        }
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CLEAR_STEP ->
                            clearStepContent(listIndex, payload.stepIndex());
                    case SequentialBufferActionPayload.ACTION_SET_AMOUNT -> {
                        SequenceListData list = list(listIndex);
                        int step = payload.stepIndex();
                        if (step < 0 || step >= list.steps().size()) {
                            yield false;
                        }
                        list.steps().get(step).setAmount(Math.max(1, payload.amount()));
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_SET_CONCAT -> {
                        SequenceListData list = list(listIndex);
                        int step = payload.stepIndex();
                        if (step < 0 || step >= SequenceListData.maxSteps()) {
                            yield false;
                        }
                        list.ensureStepSlot(step);
                        list.setConcatAt(step, payload.concatOrdinal());
                        list.compactTrailingEmptySteps();
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CYCLE_CONCAT -> {
                        SequenceListData list = list(listIndex);
                        int step = payload.stepIndex();
                        if (step < 0 || step >= SequenceListData.maxSteps()) {
                            yield false;
                        }
                        list.ensureStepSlot(step);
                        list.cycleConcatNext(step);
                        list.compactTrailingEmptySteps();
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_CYCLE_CONCAT_PREV -> {
                        SequenceListData list = list(listIndex);
                        int step = payload.stepIndex();
                        if (step < 0 || step >= SequenceListData.maxSteps()) {
                            yield false;
                        }
                        list.ensureStepSlot(step);
                        list.cycleConcatPrev(step);
                        list.compactTrailingEmptySteps();
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_REORDER_STEPS -> {
                        list(listIndex).reorderStepsByFilterWeight();
                        yield true;
                    }
                    case SequentialBufferActionPayload.ACTION_SET_LIST_NAME -> {
                        list(listIndex).setCustomName(payload.filter());
                        yield true;
                    }
                    default -> false;
                };
        if (changed) {
            syncClientMirror();
        }
        return changed;
    }

    private boolean clearStepContent(int listIndex, int stepIndex) {
        SequenceListData list = list(listIndex);
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
        return true;
    }

    private boolean addOrUpdateStep(int listIndex, int stepIndex, SequentialBufferActionPayload payload) {
        SequenceListData list = list(listIndex);
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
            if (list.steps().size() >= SequenceListData.maxSteps()) {
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
            if (stepIndex >= SequenceListData.maxSteps()) {
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
        return true;
    }
}
