package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;

/**
 * Per-face attachment state on a {@link DuctBlockEntity}. A hybrid duct may eventually host several transport lanes
 * per direction (e.g. item + fluid); today this holds the <strong>item</strong> node only (modes, GUI slots, tick cadence).
 */
public final class DuctFaceNode {
    public enum FilterBank {
        /** Filters that govern what this face will extract (inv -> network). */
        EXTRACTOR,
        /** Filters that govern what this face will retrieve (network -> inv). */
        RETRIEVER,
        /** Filters that govern what may pass when this face is in a filtering-insertion role. */
        FILTER
    }

    public NodeMode nodeMode = NodeMode.NONE;
    public RoutingMode routingMode = RoutingMode.NEAREST_FIRST;
    /** Insert-capable modes ({@link NodeMode#NONE}, {@link NodeMode#FILTERING_INSERTION}): insertion priority for this face. */
    public int insertionPriority;
    /** Extract / retrieve modes: items moved per operation (0 = use duct default). */
    public int extractBatch;
    public int channelLetter = 1;
    public int redstoneMode;
    public int roundRobinCursor;
    public int ticksUntilAction;

    /**
     * When true ({@code >>>>>}), deny matches block even if allow would match. When false ({@code <<<<<}), allow can bypass
     * deny.
     */
    public boolean denyOverridesAllow = true;
    /** Legacy single-bank filters (migrated into {@link FilterBank#FILTER} on load). */
    public final List<String> allowFilters = new ArrayList<>();
    public final List<String> denyFilters = new ArrayList<>();

    /** Hybrid: allow this face to consider itself as a destination (only used by Extr/Filt). */
    public boolean selfFeed;

    public boolean denyOverridesAllowExtractor = true;
    public final List<String> allowFiltersExtractor = new ArrayList<>();
    public final List<String> denyFiltersExtractor = new ArrayList<>();

    public boolean denyOverridesAllowRetriever = true;
    public final List<String> allowFiltersRetriever = new ArrayList<>();
    public final List<String> denyFiltersRetriever = new ArrayList<>();

    public boolean denyOverridesAllowFilter = true;
    public final List<String> allowFiltersFilter = new ArrayList<>();
    public final List<String> denyFiltersFilter = new ArrayList<>();

    public final ItemStackHandler guiSlots;

    public DuctFaceNode(Runnable onChanged) {
        this.guiSlots =
                new ItemStackHandler(DuctNodeMenu.MACHINE_SLOTS) {
                    @Override
                    protected void onContentsChanged(int slot) {
                        onChanged.run();
                    }
                };
    }

    public void resetPipeSegmentDefaults() {
        nodeMode = NodeMode.NONE;
        insertionPriority = 0;
        extractBatch = 0;
        roundRobinCursor = 0;
        selfFeed = false;
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        tag.putByte("NodeMode", (byte) nodeMode.ordinal());
        tag.putByte("RoutingMode", (byte) routingMode.ordinal());
        tag.putInt("InsertionPriority", insertionPriority);
        tag.putInt("ExtractBatch", extractBatch);
        tag.putByte("Channel", (byte) channelLetter);
        tag.putByte("RedstoneMode", (byte) redstoneMode);
        tag.putInt("RrCursor", roundRobinCursor);
        tag.putInt("TicksAct", ticksUntilAction);
        tag.putBoolean("SelfFeed", selfFeed);
        tag.put("NodeGui", guiSlots.serializeNBT(registries));
        saveFilters(tag);
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        nodeMode = NodeMode.fromOrdinal(tag.getByte("NodeMode"));
        routingMode = RoutingMode.fromOrdinal(tag.getByte("RoutingMode"));
        insertionPriority = 0;
        extractBatch = 0;
        if (tag.contains("InsertionPriority")) {
            insertionPriority = tag.getInt("InsertionPriority");
        } else if (tag.contains("InsPriority")) {
            insertionPriority = tag.getInt("InsPriority");
        }
        if (tag.contains("ExtractBatch")) {
            extractBatch = tag.getInt("ExtractBatch");
        }
        if (!tag.contains("InsertionPriority")
                && !tag.contains("InsPriority")
                && !tag.contains("ExtractBatch")
                && tag.contains("AmountField")) {
            int legacy = tag.getInt("AmountField");
            if (nodeMode.usesInsertionPriorityField()) {
                insertionPriority = legacy;
            } else if (nodeMode.usesExtractBatchField()) {
                extractBatch = legacy;
            } else {
                insertionPriority = legacy;
            }
        }
        channelLetter = tag.contains("Channel") ? tag.getByte("Channel") & 0xFF : 1;
        redstoneMode = tag.getByte("RedstoneMode") & 0xFF;
        roundRobinCursor = tag.getInt("RrCursor");
        ticksUntilAction = tag.contains("TicksAct") ? tag.getInt("TicksAct") : 0;
        selfFeed = tag.contains("SelfFeed") && tag.getBoolean("SelfFeed");
        if (tag.contains("NodeGui", Tag.TAG_COMPOUND)) {
            guiSlots.deserializeNBT(registries, tag.getCompound("NodeGui"));
        }
        loadFilters(tag);
    }

    /** Copy legacy single-node NBT into this face (world upgrade). */
    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root) {
        if (root.contains("NodeGui", Tag.TAG_COMPOUND)) {
            guiSlots.deserializeNBT(registries, root.getCompound("NodeGui"));
        }
        nodeMode = NodeMode.fromOrdinal(root.getByte("NodeMode"));
        routingMode = RoutingMode.fromOrdinal(root.getByte("RoutingMode"));
        insertionPriority = 0;
        extractBatch = 0;
        if (root.contains("InsertionPriority")) {
            insertionPriority = root.getInt("InsertionPriority");
        } else if (root.contains("InsPriority")) {
            insertionPriority = root.getInt("InsPriority");
        }
        if (root.contains("ExtractBatch")) {
            extractBatch = root.getInt("ExtractBatch");
        }
        if (!root.contains("InsertionPriority")
                && !root.contains("InsPriority")
                && !root.contains("ExtractBatch")
                && root.contains("AmountField")) {
            int legacy = root.getInt("AmountField");
            if (nodeMode.usesInsertionPriorityField()) {
                insertionPriority = legacy;
            } else if (nodeMode.usesExtractBatchField()) {
                extractBatch = legacy;
            } else {
                insertionPriority = legacy;
            }
        }
        channelLetter = root.contains("Channel") ? root.getByte("Channel") & 0xFF : 1;
        redstoneMode = root.getByte("RedstoneMode") & 0xFF;
        roundRobinCursor = root.getInt("RrCursor");
        ticksUntilAction = 0;
        loadFilters(root);
    }

    public void clampFilterSizes(DuctItemTransportSpec spec) {
        int maxA = Math.max(0, spec.filterAllowSlots());
        int maxD = Math.max(0, spec.filterDenySlots());
        clampList(allowFilters, maxA);
        clampList(denyFilters, maxD);

        int halfA = maxA / 2;
        int halfD = maxD / 2;
        clampList(allowFiltersExtractor, halfA);
        clampList(denyFiltersExtractor, halfD);
        clampList(allowFiltersRetriever, halfA);
        clampList(denyFiltersRetriever, halfD);
        clampList(allowFiltersFilter, halfA);
        clampList(denyFiltersFilter, halfD);
    }

    private void saveFilters(CompoundTag tag) {
        CompoundTag f = new CompoundTag();
        // Legacy single-bank values (kept for downgrade tolerance; load migrates into FILTER bank when dual banks absent)
        f.putBoolean("DenyOver", denyOverridesAllow);
        f.put("Allow", toStringListTag(allowFilters));
        f.put("Deny", toStringListTag(denyFilters));

        CompoundTag ex = new CompoundTag();
        ex.putBoolean("DenyOver", denyOverridesAllowExtractor);
        ex.put("Allow", toStringListTag(allowFiltersExtractor));
        ex.put("Deny", toStringListTag(denyFiltersExtractor));
        f.put("Extractor", ex);

        CompoundTag re = new CompoundTag();
        re.putBoolean("DenyOver", denyOverridesAllowRetriever);
        re.put("Allow", toStringListTag(allowFiltersRetriever));
        re.put("Deny", toStringListTag(denyFiltersRetriever));
        f.put("Retriever", re);

        CompoundTag fi = new CompoundTag();
        fi.putBoolean("DenyOver", denyOverridesAllowFilter);
        fi.put("Allow", toStringListTag(allowFiltersFilter));
        fi.put("Deny", toStringListTag(denyFiltersFilter));
        f.put("Filter", fi);
        tag.put("FaceFilters", f);
    }

    private void loadFilters(CompoundTag tag) {
        allowFilters.clear();
        denyFilters.clear();
        denyOverridesAllow = true;
        allowFiltersExtractor.clear();
        denyFiltersExtractor.clear();
        denyOverridesAllowExtractor = true;
        allowFiltersRetriever.clear();
        denyFiltersRetriever.clear();
        denyOverridesAllowRetriever = true;
        allowFiltersFilter.clear();
        denyFiltersFilter.clear();
        denyOverridesAllowFilter = true;
        if (!tag.contains("FaceFilters", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag f = tag.getCompound("FaceFilters");
        denyOverridesAllow = !f.contains("DenyOver") || f.getBoolean("DenyOver");
        readStringListInto(f, "Allow", allowFilters);
        readStringListInto(f, "Deny", denyFilters);

        boolean hasExtractor = f.contains("Extractor", Tag.TAG_COMPOUND);
        boolean hasRetriever = f.contains("Retriever", Tag.TAG_COMPOUND);
        boolean hasFilter = f.contains("Filter", Tag.TAG_COMPOUND);
        if (hasExtractor) {
            CompoundTag ex = f.getCompound("Extractor");
            denyOverridesAllowExtractor = !ex.contains("DenyOver") || ex.getBoolean("DenyOver");
            readStringListInto(ex, "Allow", allowFiltersExtractor);
            readStringListInto(ex, "Deny", denyFiltersExtractor);
        }
        if (hasRetriever) {
            CompoundTag re = f.getCompound("Retriever");
            denyOverridesAllowRetriever = !re.contains("DenyOver") || re.getBoolean("DenyOver");
            readStringListInto(re, "Allow", allowFiltersRetriever);
            readStringListInto(re, "Deny", denyFiltersRetriever);
        }
        if (hasFilter) {
            CompoundTag fi = f.getCompound("Filter");
            denyOverridesAllowFilter = !fi.contains("DenyOver") || fi.getBoolean("DenyOver");
            readStringListInto(fi, "Allow", allowFiltersFilter);
            readStringListInto(fi, "Deny", denyFiltersFilter);
        }
        if (!hasExtractor && !hasRetriever && !hasFilter) {
            // Migration: legacy single-bank -> FILTER bank by default.
            denyOverridesAllowFilter = denyOverridesAllow;
            allowFiltersFilter.addAll(allowFilters);
            denyFiltersFilter.addAll(denyFilters);
        }
    }

    public boolean bankDenyOverridesAllow(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyOverridesAllowExtractor;
            case RETRIEVER -> denyOverridesAllowRetriever;
            case FILTER -> denyOverridesAllowFilter;
        };
    }

    public void setBankDenyOverridesAllow(FilterBank bank, boolean v) {
        switch (bank) {
            case EXTRACTOR -> denyOverridesAllowExtractor = v;
            case RETRIEVER -> denyOverridesAllowRetriever = v;
            case FILTER -> denyOverridesAllowFilter = v;
        }
    }

    public List<String> bankAllowFilters(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowFiltersExtractor;
            case RETRIEVER -> allowFiltersRetriever;
            case FILTER -> allowFiltersFilter;
        };
    }

    public List<String> bankDenyFilters(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyFiltersExtractor;
            case RETRIEVER -> denyFiltersRetriever;
            case FILTER -> denyFiltersFilter;
        };
    }

    private static void clampList(List<String> list, int max) {
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
        while (list.size() < max) {
            list.add("");
        }
    }

    private static ListTag toStringListTag(List<String> list) {
        ListTag t = new ListTag();
        for (String s : list) {
            t.add(StringTag.valueOf(s != null ? s : ""));
        }
        return t;
    }

    private static void readStringListInto(CompoundTag tag, String key, List<String> out) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            return;
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
    }
}
