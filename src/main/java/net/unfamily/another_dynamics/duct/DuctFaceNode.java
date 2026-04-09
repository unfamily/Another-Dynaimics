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
 * Per-transport-kind lane on a duct face (item or fluid): filters, routing, channel, GUI slots, amounts.
 * Node mode, redstone gating, and action tick cadence live on {@link DuctFaceLanes} (shared per face).
 */
public final class DuctFaceNode {
    public enum EligibilityMode {
        BOTH,
        INSERT_ONLY,
        RETRIEVE_ONLY;

        public boolean isInsertable() {
            return this == BOTH || this == INSERT_ONLY;
        }

        public boolean isRetrievable() {
            return this == BOTH || this == RETRIEVE_ONLY;
        }

        public static EligibilityMode fromOrdinal(int ord) {
            EligibilityMode[] v = values();
            if (ord < 0 || ord >= v.length) {
                return BOTH;
            }
            return v[ord];
        }
    }

    public enum FilterBank {
        /** Filters that govern what this face will extract (inv -> network). */
        EXTRACTOR,
        /** Filters that govern what this face will retrieve (network -> inv). */
        RETRIEVER,
        /** Filters that govern what may pass when this face is in a filtering-insertion role. */
        FILTER
    }

    public RoutingMode routingMode = RoutingMode.NEAREST_FIRST;
    /** Hybrid: independent routing mode for the Extractor sub-node. */
    public RoutingMode routingModeExtractor = RoutingMode.NEAREST_FIRST;
    /** Hybrid: independent routing mode for the Retriever sub-node. */
    public RoutingMode routingModeRetriever = RoutingMode.NEAREST_FIRST;
    /** Insert-capable modes ({@link NodeMode#NONE}, {@link NodeMode#FILTERING_INSERTION}): insertion priority for this face. */
    public int insertionPriority;
    /** Extract / retrieve modes: items moved per operation (0 = use duct default). */
    public int extractBatch;
    public int channelLetter = 1;
    public int roundRobinCursor;
    /** Per transport kind; not shared with the sibling lane (item vs fluid may use different duct rates). */
    public int ticksUntilAction;

    /**
     * When true ({@code >>>>>}), deny matches block even if allow would match. When false ({@code <<<<<}), allow can bypass
     * deny.
     */
    public boolean denyOverridesAllow = true;
    /** Legacy single-bank filters (migrated into {@link FilterBank#FILTER} on load). */
    public final List<String> allowFilters = new ArrayList<>();
    public final List<String> denyFilters = new ArrayList<>();
    /** Parallel to {@link #allowFilters}: 0 = unlimited (insert cap / extract keep). */
    public final List<Integer> allowAllowCaps = new ArrayList<>();

    /** Hybrid: allow this face to consider itself as a destination (only used by Extr/Filt). */
    public boolean selfFeed;

    /** Controls whether this face may be a network insertion destination and/or a retrieving donor. */
    public EligibilityMode eligibilityMode = EligibilityMode.BOTH;

    public boolean denyOverridesAllowExtractor = true;
    public final List<String> allowFiltersExtractor = new ArrayList<>();
    public final List<String> denyFiltersExtractor = new ArrayList<>();
    public final List<Integer> allowAllowCapsExtractor = new ArrayList<>();

    public boolean denyOverridesAllowRetriever = true;
    public final List<String> allowFiltersRetriever = new ArrayList<>();
    public final List<String> denyFiltersRetriever = new ArrayList<>();
    public final List<Integer> allowAllowCapsRetriever = new ArrayList<>();

    public boolean denyOverridesAllowFilter = true;
    public final List<String> allowFiltersFilter = new ArrayList<>();
    public final List<String> denyFiltersFilter = new ArrayList<>();
    /** FILTER bank caps split: Limit is used for insertion destinations, Keep is used when a Retrieving node pulls from this donor. */
    public final List<Integer> allowAllowCapsFilterLimit = new ArrayList<>();
    public final List<Integer> allowAllowCapsFilterKeep = new ArrayList<>();

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
        insertionPriority = 0;
        extractBatch = 0;
        roundRobinCursor = 0;
        selfFeed = false;
        ticksUntilAction = 0;
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        tag.putByte("RoutingMode", (byte) routingMode.ordinal());
        tag.putByte("RoutingModeEx", (byte) routingModeExtractor.ordinal());
        tag.putByte("RoutingModeRe", (byte) routingModeRetriever.ordinal());
        tag.putInt("InsertionPriority", insertionPriority);
        tag.putInt("ExtractBatch", extractBatch);
        tag.putByte("Channel", (byte) channelLetter);
        tag.putInt("RrCursor", roundRobinCursor);
        tag.putInt("TicksAct", ticksUntilAction);
        tag.putBoolean("SelfFeed", selfFeed);
        tag.putByte("EligMode", (byte) eligibilityMode.ordinal());
        tag.put("NodeGui", guiSlots.serializeNBT(registries));
        saveFilters(tag);
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        routingMode = RoutingMode.fromOrdinal(tag.getByte("RoutingMode"));
        routingModeExtractor =
                tag.contains("RoutingModeEx")
                        ? RoutingMode.fromOrdinal(tag.getByte("RoutingModeEx"))
                        : routingMode;
        routingModeRetriever =
                tag.contains("RoutingModeRe")
                        ? RoutingMode.fromOrdinal(tag.getByte("RoutingModeRe"))
                        : routingMode;
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
        channelLetter = tag.contains("Channel") ? tag.getByte("Channel") & 0xFF : 1;
        roundRobinCursor = tag.getInt("RrCursor");
        ticksUntilAction = tag.contains("TicksAct") ? tag.getInt("TicksAct") : 0;
        selfFeed = tag.contains("SelfFeed") && tag.getBoolean("SelfFeed");
        eligibilityMode =
                tag.contains("EligMode") ? EligibilityMode.fromOrdinal(tag.getByte("EligMode")) : EligibilityMode.BOTH;
        if (tag.contains("NodeGui", Tag.TAG_COMPOUND)) {
            guiSlots.deserializeNBT(registries, tag.getCompound("NodeGui"));
        }
        loadFilters(tag);
    }

    /**
     * Copy legacy single-lane NBT (world upgrade). {@code sharedNodeMode} comes from {@link DuctFaceLanes} (already loaded
     * from the same root).
     */
    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root, NodeMode sharedNodeMode) {
        if (root.contains("NodeGui", Tag.TAG_COMPOUND)) {
            guiSlots.deserializeNBT(registries, root.getCompound("NodeGui"));
        }
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
            if (sharedNodeMode.usesInsertionPriorityField()) {
                insertionPriority = legacy;
            } else if (sharedNodeMode.usesExtractBatchField()) {
                extractBatch = legacy;
            } else {
                insertionPriority = legacy;
            }
        }
        channelLetter = root.contains("Channel") ? root.getByte("Channel") & 0xFF : 1;
        roundRobinCursor = root.getInt("RrCursor");
        ticksUntilAction = root.contains("TicksAct") ? root.getInt("TicksAct") : 0;
        loadFilters(root);
        eligibilityMode =
                root.contains("EligMode") ? EligibilityMode.fromOrdinal(root.getByte("EligMode")) : EligibilityMode.BOTH;
    }

    public void clampFilterSizes(DuctItemTransportSpec spec, NodeMode sharedNodeMode) {
        int legacyA = Math.max(0, spec.filterAllowSlots());
        int legacyD = Math.max(0, spec.filterDenySlots());
        clampList(allowFilters, legacyA);
        clampList(denyFilters, legacyD);
        syncAllowCapsToAllowSize(allowAllowCaps, allowFilters.size());

        int bankA =
                sharedNodeMode.isHybrid()
                        ? Math.max(0, spec.filterAllowHybridSlots())
                        : Math.max(0, spec.filterAllowSlots());
        int bankD =
                sharedNodeMode.isHybrid()
                        ? Math.max(0, spec.filterDenyHybridSlots())
                        : Math.max(0, spec.filterDenySlots());
        clampList(allowFiltersExtractor, bankA);
        clampList(denyFiltersExtractor, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsExtractor, allowFiltersExtractor.size());
        clampList(allowFiltersRetriever, bankA);
        clampList(denyFiltersRetriever, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsRetriever, allowFiltersRetriever.size());
        clampList(allowFiltersFilter, bankA);
        clampList(denyFiltersFilter, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsFilterLimit, allowFiltersFilter.size());
        syncAllowCapsToAllowSize(allowAllowCapsFilterKeep, allowFiltersFilter.size());
    }

    /** Same layout as {@link #clampFilterSizes(DuctItemTransportSpec, NodeMode)} using fluid datapack caps. */
    public void clampFilterSizes(DuctFluidTransportSpec spec, NodeMode sharedNodeMode) {
        int legacyA = Math.max(0, spec.filterAllowSlots());
        int legacyD = Math.max(0, spec.filterDenySlots());
        clampList(allowFilters, legacyA);
        clampList(denyFilters, legacyD);
        syncAllowCapsToAllowSize(allowAllowCaps, allowFilters.size());

        int bankA =
                sharedNodeMode.isHybrid()
                        ? Math.max(0, spec.filterAllowHybridSlots())
                        : Math.max(0, spec.filterAllowSlots());
        int bankD =
                sharedNodeMode.isHybrid()
                        ? Math.max(0, spec.filterDenyHybridSlots())
                        : Math.max(0, spec.filterDenySlots());
        clampList(allowFiltersExtractor, bankA);
        clampList(denyFiltersExtractor, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsExtractor, allowFiltersExtractor.size());
        clampList(allowFiltersRetriever, bankA);
        clampList(denyFiltersRetriever, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsRetriever, allowFiltersRetriever.size());
        clampList(allowFiltersFilter, bankA);
        clampList(denyFiltersFilter, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsFilterLimit, allowFiltersFilter.size());
        syncAllowCapsToAllowSize(allowAllowCapsFilterKeep, allowFiltersFilter.size());
    }

    private static void syncAllowCapsToAllowSize(List<Integer> caps, int allowSize) {
        while (caps.size() < allowSize) {
            caps.add(0);
        }
        while (caps.size() > allowSize) {
            caps.remove(caps.size() - 1);
        }
    }

    private void saveFilters(CompoundTag tag) {
        CompoundTag f = new CompoundTag();
        // Legacy single-bank values (kept for downgrade tolerance; load migrates into FILTER bank when dual banks absent)
        f.putBoolean("DenyOver", denyOverridesAllow);
        f.put("Allow", toStringListTag(allowFilters));
        f.put("Deny", toStringListTag(denyFilters));
        putAllowCapArray(f, allowAllowCaps);

        CompoundTag ex = new CompoundTag();
        ex.putBoolean("DenyOver", denyOverridesAllowExtractor);
        ex.put("Allow", toStringListTag(allowFiltersExtractor));
        ex.put("Deny", toStringListTag(denyFiltersExtractor));
        putAllowCapArray(ex, allowAllowCapsExtractor);
        f.put("Extractor", ex);

        CompoundTag re = new CompoundTag();
        re.putBoolean("DenyOver", denyOverridesAllowRetriever);
        re.put("Allow", toStringListTag(allowFiltersRetriever));
        re.put("Deny", toStringListTag(denyFiltersRetriever));
        putAllowCapArray(re, allowAllowCapsRetriever);
        f.put("Retriever", re);

        CompoundTag fi = new CompoundTag();
        fi.putBoolean("DenyOver", denyOverridesAllowFilter);
        fi.put("Allow", toStringListTag(allowFiltersFilter));
        fi.put("Deny", toStringListTag(denyFiltersFilter));
        // Backward-compatible: keep writing AllowCap as Limit.
        putAllowCapArray(fi, allowAllowCapsFilterLimit);
        putAllowCapArray(fi, "AllowCapLim", allowAllowCapsFilterLimit);
        putAllowCapArray(fi, "AllowCapKeep", allowAllowCapsFilterKeep);
        f.put("Filter", fi);
        tag.put("FaceFilters", f);
    }

    private void loadFilters(CompoundTag tag) {
        allowFilters.clear();
        denyFilters.clear();
        allowAllowCaps.clear();
        denyOverridesAllow = true;
        allowFiltersExtractor.clear();
        denyFiltersExtractor.clear();
        allowAllowCapsExtractor.clear();
        denyOverridesAllowExtractor = true;
        allowFiltersRetriever.clear();
        denyFiltersRetriever.clear();
        allowAllowCapsRetriever.clear();
        denyOverridesAllowRetriever = true;
        allowFiltersFilter.clear();
        denyFiltersFilter.clear();
        allowAllowCapsFilterLimit.clear();
        allowAllowCapsFilterKeep.clear();
        denyOverridesAllowFilter = true;
        if (!tag.contains("FaceFilters", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag f = tag.getCompound("FaceFilters");
        denyOverridesAllow = !f.contains("DenyOver") || f.getBoolean("DenyOver");
        readStringListInto(f, "Allow", allowFilters);
        readStringListInto(f, "Deny", denyFilters);
        readAllowCapsInto(f, allowAllowCaps, allowFilters.size());

        boolean hasExtractor = f.contains("Extractor", Tag.TAG_COMPOUND);
        boolean hasRetriever = f.contains("Retriever", Tag.TAG_COMPOUND);
        boolean hasFilter = f.contains("Filter", Tag.TAG_COMPOUND);
        if (hasExtractor) {
            CompoundTag ex = f.getCompound("Extractor");
            denyOverridesAllowExtractor = !ex.contains("DenyOver") || ex.getBoolean("DenyOver");
            readStringListInto(ex, "Allow", allowFiltersExtractor);
            readStringListInto(ex, "Deny", denyFiltersExtractor);
            readAllowCapsInto(ex, allowAllowCapsExtractor, allowFiltersExtractor.size());
        }
        if (hasRetriever) {
            CompoundTag re = f.getCompound("Retriever");
            denyOverridesAllowRetriever = !re.contains("DenyOver") || re.getBoolean("DenyOver");
            readStringListInto(re, "Allow", allowFiltersRetriever);
            readStringListInto(re, "Deny", denyFiltersRetriever);
            readAllowCapsInto(re, allowAllowCapsRetriever, allowFiltersRetriever.size());
        }
        if (hasFilter) {
            CompoundTag fi = f.getCompound("Filter");
            denyOverridesAllowFilter = !fi.contains("DenyOver") || fi.getBoolean("DenyOver");
            readStringListInto(fi, "Allow", allowFiltersFilter);
            readStringListInto(fi, "Deny", denyFiltersFilter);
            // Migration: old single AllowCap is treated as Limit; Keep defaults to 0.
            readAllowCapsInto(fi, "AllowCapLim", allowAllowCapsFilterLimit, allowFiltersFilter.size());
            if (allowAllowCapsFilterLimit.stream().allMatch(v -> v == 0) && fi.contains("AllowCap", Tag.TAG_INT_ARRAY)) {
                allowAllowCapsFilterLimit.clear();
                readAllowCapsInto(fi, allowAllowCapsFilterLimit, allowFiltersFilter.size());
            }
            readAllowCapsInto(fi, "AllowCapKeep", allowAllowCapsFilterKeep, allowFiltersFilter.size());
        }
        if (!hasExtractor && !hasRetriever && !hasFilter) {
            // Migration: legacy single-bank -> FILTER bank by default.
            denyOverridesAllowFilter = denyOverridesAllow;
            allowFiltersFilter.addAll(allowFilters);
            denyFiltersFilter.addAll(denyFilters);
            allowAllowCapsFilterLimit.addAll(allowAllowCaps);
            syncAllowCapsToAllowSize(allowAllowCapsFilterKeep, allowFiltersFilter.size());
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

    public List<Integer> bankAllowCaps(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowAllowCapsExtractor;
            case RETRIEVER -> allowAllowCapsRetriever;
            case FILTER -> allowAllowCapsFilterLimit;
        };
    }

    public List<Integer> filterBankKeepCaps() {
        return allowAllowCapsFilterKeep;
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

    private static void putAllowCapArray(CompoundTag tag, List<Integer> caps) {
        putAllowCapArray(tag, "AllowCap", caps);
    }

    private static void putAllowCapArray(CompoundTag tag, String key, List<Integer> caps) {
        int n = caps.size();
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) {
            arr[i] = Math.max(0, caps.get(i));
        }
        tag.putIntArray(key, arr);
    }

    private static void readAllowCapsInto(CompoundTag tag, List<Integer> out, int allowSize) {
        readAllowCapsInto(tag, "AllowCap", out, allowSize);
    }

    private static void readAllowCapsInto(CompoundTag tag, String key, List<Integer> out, int allowSize) {
        out.clear();
        if (tag.contains(key, Tag.TAG_INT_ARRAY)) {
            int[] arr = tag.getIntArray(key);
            for (int v : arr) {
                out.add(Math.max(0, v));
            }
        }
        syncAllowCapsToAllowSize(out, allowSize);
    }
}
