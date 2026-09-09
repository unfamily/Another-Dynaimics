package net.unfamily.another_dynamics.machine.sequential;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;

/** One hub Sequential Task. */
public final class SequentialTaskData {
    /** Canonical NBT list key when writing tasks. */
    public static final String NBT_TASKS_KEY = "Tasks";
    /** Accepted NBT/JSON aliases for the tasks list (plus legacy Stacks/Lists/seq_stack*). */
    private static final String[] NBT_TASKS_ALIASES = {
        NBT_TASKS_KEY,
        "sequential_tasks",
        "sequential_task",
        "seq_tasks",
        "seq_task",
        "seqs",
        "seq",
        "Stacks",
        "sequential_stacks",
        "sequential_stack",
        "seq_stacks",
        "seq_stack",
        "Lists"
    };

    /** Max steps per Sequential Task from config. */
    public static int maxSteps() {
        return Config.sequenceStepCount();
    }
    public static final int MAX_NAME_LENGTH = 32;

    /** Reads the tasks list from NBT, accepting {@link #NBT_TASKS_ALIASES}. */
    public static ListTag readTasksListTag(CompoundTag tag) {
        if (tag == null) {
            return new ListTag();
        }
        for (String key : NBT_TASKS_ALIASES) {
            if (tag.contains(key, Tag.TAG_LIST)) {
                return tag.getList(key, Tag.TAG_COMPOUND);
            }
        }
        return new ListTag();
    }

    /** Writes tasks under the canonical {@link #NBT_TASKS_KEY}. */
    public static void writeTasksListTag(CompoundTag tag, ListTag tasks) {
        tag.put(NBT_TASKS_KEY, tasks);
    }

    private boolean enabled;
    private SequentialRedstoneMode outputMode = SequentialRedstoneMode.DISABLED;
    /** Empty means use the default localized "Sequence List N" label. */
    private String customName = "";
    private final List<SequenceStepData> steps = new ArrayList<>();
    /** Parallel to {@link #steps}: {@link FilterConcatChannel} ordinal per step. */
    private final List<Integer> concatChannels = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled && hasContent();
    }

    public SequentialRedstoneMode outputMode() {
        return outputMode;
    }

    public void setOutputMode(SequentialRedstoneMode mode) {
        this.outputMode = mode == null ? SequentialRedstoneMode.DISABLED : mode;
    }

    /** Raw custom name; empty when using the default label. */
    public String customName() {
        return customName == null ? "" : customName;
    }

    public void setCustomName(String name) {
        if (name == null || name.isBlank()) {
            this.customName = "";
            return;
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
        }
        this.customName = trimmed;
    }

    /** Player-facing label for hub rows / titles ({@code oneBasedIndex} is 1..10). */
    public Component displayName(int oneBasedIndex) {
        if (customName != null && !customName.isBlank()) {
            return Component.literal(customName);
        }
        return Component.translatable(
                "gui.another_dynamics.sequential_buffer.sequence_list", oneBasedIndex);
    }

    public List<SequenceStepData> steps() {
        return steps;
    }

    public List<Integer> concatChannels() {
        syncConcatSize();
        return concatChannels;
    }

    public int concatAt(int index) {
        syncConcatSize();
        return FilterConcatChannel.channelAt(concatChannels, index);
    }

    public void setConcatAt(int index, int ordinal) {
        syncConcatSize();
        if (index < 0 || index >= concatChannels.size()) {
            return;
        }
        concatChannels.set(index, Math.clamp(ordinal, 0, FilterConcatChannel.MAX_LETTER));
    }

    public void cycleConcatNext(int index) {
        setConcatAt(index, FilterConcatChannel.fromOrdinal(concatAt(index)).next().ordinal());
    }

    public void cycleConcatPrev(int index) {
        setConcatAt(index, FilterConcatChannel.fromOrdinal(concatAt(index)).previous().ordinal());
    }

    public void syncConcatSize() {
        // Slot grid is always maxSteps(); keep concat channels aligned to capacity.
        FilterConcatChannel.syncToLineSize(concatChannels, maxSteps());
    }

    /**
     * Ensures steps list can address {@code index} (pads with empty steps) up to {@link #maxSteps()}.
     * Used when setting concat on an empty slot or editing a vacant index.
     */
    public void ensureStepSlot(int index) {
        if (index < 0 || index >= maxSteps()) {
            return;
        }
        while (steps.size() <= index) {
            steps.add(new SequenceStepData());
        }
        syncConcatSize();
    }

    /** Trim trailing empty steps (no filter) after edits; keep concat synced to maxSteps(). */
    public void compactTrailingEmptySteps() {
        while (!steps.isEmpty()) {
            SequenceStepData last = steps.get(steps.size() - 1);
            if (last != null && !last.isEmpty()) {
                break;
            }
            steps.remove(steps.size() - 1);
            // Keep concat channel at that index for the fixed 50-slot grid; do not shrink concat below maxSteps().
        }
        syncConcatSize();
        if (!hasContent()) {
            enabled = false;
        }
    }

    public boolean hasContent() {
        for (SequenceStepData step : steps) {
            if (step != null && !step.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        steps.clear();
        concatChannels.clear();
        enabled = false;
        outputMode = SequentialRedstoneMode.DISABLED;
        // Keep customName so the list label survives a content clear.
    }

    /** Removes step and parallel concat entry at {@code index}. */
    public boolean removeStepAt(int index) {
        if (index < 0 || index >= steps.size()) {
            return false;
        }
        syncConcatSize();
        steps.remove(index);
        if (index < concatChannels.size()) {
            concatChannels.remove(index);
        }
        syncConcatSize();
        if (!hasContent()) {
            enabled = false;
        }
        return true;
    }

    public CompoundTag save() {
        syncConcatSize();
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled && hasContent());
        tag.putByte("OutMode", (byte) outputMode.ordinal());
        if (customName != null && !customName.isEmpty()) {
            tag.putString("Name", customName);
        }
        ListTag list = new ListTag();
        for (SequenceStepData step : steps) {
            list.add(step.save());
        }
        tag.put("Steps", list);
        tag.putByteArray("Concat", toConcatByteArray(concatChannels));
        return tag;
    }

    public void load(CompoundTag tag) {
        steps.clear();
        concatChannels.clear();
        customName = tag.contains("Name", Tag.TAG_STRING) ? tag.getString("Name") : "";
        if (customName.length() > MAX_NAME_LENGTH) {
            customName = customName.substring(0, MAX_NAME_LENGTH);
        }
        outputMode =
                SequentialRedstoneMode.fromOrdinal(
                        tag.contains("OutMode", Tag.TAG_BYTE) ? tag.getByte("OutMode") & 0xFF : 0);
        ListTag list =
                tag.contains("Steps", Tag.TAG_LIST) ? tag.getList("Steps", Tag.TAG_COMPOUND) : new ListTag();
        for (int i = 0; i < list.size() && i < maxSteps(); i++) {
            CompoundTag stepTag = list.getCompound(i);
            SequenceStepData step = new SequenceStepData();
            step.load(stepTag);
            steps.add(step);
        }
        readConcatInto(
                concatChannels,
                tag.contains("Concat", Tag.TAG_BYTE_ARRAY) ? tag.getByteArray("Concat") : null,
                maxSteps());
        enabled = tag.contains("Enabled") && tag.getBoolean("Enabled") && hasContent();
    }

    public SequentialTaskData copy() {
        SequentialTaskData copy = new SequentialTaskData();
        copy.enabled = this.enabled;
        copy.outputMode = this.outputMode;
        copy.customName = this.customName;
        for (SequenceStepData step : steps) {
            copy.steps.add(step.copy());
        }
        syncConcatSize();
        copy.concatChannels.addAll(this.concatChannels);
        copy.syncConcatSize();
        return copy;
    }

    /**
     * Reorder steps like duct filter lines: concat letter groups first (A→Z, original order within),
     * then standalone lines by weight (-id → # → @ → ? → other).
     */
    public void reorderStepsByFilterWeight() {
        syncConcatSize();
        if (steps.isEmpty()) {
            return;
        }
        record SortRow(SequenceStepData step, int concatCh, int weight, int origIndex) {}
        ArrayList<SortRow> rows = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            SequenceStepData step = steps.get(i);
            String filter = step == null || step.filter() == null ? "" : step.filter();
            rows.add(new SortRow(step, concatAt(i), weightForLine(filter), i));
        }
        Map<Integer, List<SortRow>> concatGroups = new LinkedHashMap<>();
        ArrayList<SortRow> noneRows = new ArrayList<>();
        for (SortRow r : rows) {
            if (r.concatCh() > 0) {
                concatGroups.computeIfAbsent(r.concatCh(), k -> new ArrayList<>()).add(r);
            } else {
                noneRows.add(r);
            }
        }
        noneRows.sort(
                Comparator.comparingInt(SortRow::weight)
                        .thenComparing(r -> r.step().filter() == null ? "" : r.step().filter()));
        ArrayList<SortRow> ordered = new ArrayList<>(rows.size());
        for (int ch = 1; ch <= FilterConcatChannel.MAX_LETTER; ch++) {
            List<SortRow> group = concatGroups.get(ch);
            if (group == null || group.isEmpty()) {
                continue;
            }
            group.sort(Comparator.comparingInt(SortRow::origIndex));
            ordered.addAll(group);
        }
        ordered.addAll(noneRows);

        steps.clear();
        concatChannels.clear();
        for (SortRow r : ordered) {
            steps.add(r.step());
            concatChannels.add(r.concatCh());
        }
        syncConcatSize();
    }

    /** Sequential line weight: {@code -}/{@code #}/{@code @} share weight 0; then {@code &}, {@code ?}, unknown, empty. */
    public static int weightForLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return 10_000;
        }
        String t = line.trim();
        if (t.startsWith("-") || t.startsWith("#") || t.startsWith("@")) {
            return 0;
        }
        String bare = t;
        ResourceLocation id = ResourceLocation.tryParse(bare);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return 0;
        }
        if (t.startsWith("&")) {
            return 300;
        }
        if (t.startsWith("?")) {
            return 400;
        }
        return 500;
    }

    private static byte[] toConcatByteArray(List<Integer> concat) {
        byte[] arr = new byte[concat.size()];
        for (int i = 0; i < concat.size(); i++) {
            int v = concat.get(i) != null ? concat.get(i) : 0;
            arr[i] = (byte) Math.clamp(v, 0, FilterConcatChannel.MAX_LETTER);
        }
        return arr;
    }

    private static void readConcatInto(List<Integer> target, byte[] arr, int lineCount) {
        target.clear();
        if (arr != null) {
            for (byte b : arr) {
                target.add(Math.clamp(b & 0xFF, 0, FilterConcatChannel.MAX_LETTER));
            }
        }
        FilterConcatChannel.syncToLineSize(target, lineCount);
    }
}
