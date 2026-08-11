package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;

/**
 * Per-transport-kind lane on a duct face: filters, routing, channel, amounts.
 * <p><strong>Material-lane family:</strong> item, fluid, and gas lanes share the same shape ({@link #saveSettings} /
 * {@link #loadSettings}); fixes usually apply to all three and to matching lanes on <em>universal</em> ducts.
 * {@link net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot} stores {@code Item}/{@code Fluid}/
 * {@code Gas} tags from {@link #saveSettings} (not {@link #save}, which includes runtime cursors and {@link #guiSlots}).
 * <p>Node mode, redstone, and transport enable mask live on {@link DuctFaceLanes}. Energy/heat use {@link DuctFaceLanes}
 * {@code EnergyHeat} instead of a {@code DuctFaceNode}.
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

    /** Routing when this face pushes from storage into the network (extract / hybrid extractor lane). */
    public RoutingMode routingForExtraction(NodeMode faceMode) {
        return switch (faceMode) {
            case EXTRACTION -> routingMode;
            case EXTRACTION_FILTERING, RETRIEVING_EXTRACTION -> routingModeExtractor;
            default -> routingMode;
        };
    }

    /** Routing when this face pulls from the network into storage (retrieve / hybrid retriever lane). */
    public RoutingMode routingForRetrieval(NodeMode faceMode) {
        return switch (faceMode) {
            case RETRIEVING, RETRIEVING_EXTRACTION -> routingModeRetriever;
            default -> routingMode;
        };
    }

    /** Routing for stall / overflow resend on this face. */
    public RoutingMode routingForStallResend(NodeMode faceMode) {
        return switch (faceMode) {
            case RETRIEVING, RETRIEVING_EXTRACTION -> routingModeRetriever;
            case EXTRACTION_FILTERING -> routingModeExtractor;
            case EXTRACTION -> routingMode;
            default -> routingMode;
        };
    }
    /** Insert-capable modes ({@link NodeMode#NONE}, {@link NodeMode#FILTERING_INSERTION}): insertion priority for this face. */
    public int insertionPriority;
    /** Extract / retrieve modes: items moved per operation (0 = use duct default). */
    public int extractBatch;
    /**
     * Last {@code extractBatch} setting cap applied in {@link net.unfamily.another_dynamics.duct.DuctBlockEntity} clamp
     * for this lane; when the player had the batch pinned to the old cap and modules raise the cap, we bump
     * {@link #extractBatch} to the new cap.
     */
    public int lastExtractBatchSettingCapApplied;
    /**
     * When true, {@link net.unfamily.another_dynamics.duct.DuctBlockEntity} keeps {@link #extractBatch} at the current
     * setting cap when modules or mode changes raise the cap.
     */
    public boolean extractBatchPinnedToMax;
    public int channelLetter = 1;
    public int roundRobinCursor;
    /** Retriever: next donor inventory slot index to probe (round-robin across slots). */
    public int retrieverPullSlotCursor;
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
    /** Parallel to {@link #allowFilters}: {@link FilterConcatChannel} ordinal per line. */
    public final List<Integer> allowConcatChannels = new ArrayList<>();
    /** Parallel to {@link #denyFilters}. */
    public final List<Integer> denyConcatChannels = new ArrayList<>();
    /** Parallel to {@link #allowFilters}: optional destination binding per line. */
    public final List<DuctDirectionalEndpoint> allowRemoteNodes = new ArrayList<>();
    /** Parallel to {@link #denyFilters}. */
    public final List<DuctDirectionalEndpoint> denyRemoteNodes = new ArrayList<>();
    /** Parallel to {@link #allowRemoteNodes}: cross-channel routing for bound destinations. */
    public final List<Boolean> allowRemoteIgnoreChannel = new ArrayList<>();
    /** Parallel to {@link #denyRemoteNodes}. */
    public final List<Boolean> denyRemoteIgnoreChannel = new ArrayList<>();
    /** Parallel to {@link #allowRemoteNodes}: match block position only (any inventory face). */
    public final List<Boolean> allowRemoteAnyFace = new ArrayList<>();
    /** Parallel to {@link #denyRemoteNodes}. */
    public final List<Boolean> denyRemoteAnyFace = new ArrayList<>();

    /** Hybrid: allow this face to consider itself as a destination (only used by Extr/Filt). */
    public boolean selfFeed;

    /** Controls whether this face may be a network insertion destination and/or a retrieving donor. */
    public EligibilityMode eligibilityMode = EligibilityMode.BOTH;

    public boolean denyOverridesAllowExtractor = true;
    public final List<String> allowFiltersExtractor = new ArrayList<>();
    public final List<String> denyFiltersExtractor = new ArrayList<>();
    public final List<Integer> allowAllowCapsExtractor = new ArrayList<>();
    public final List<Integer> allowConcatChannelsExtractor = new ArrayList<>();
    public final List<Integer> denyConcatChannelsExtractor = new ArrayList<>();
    public final List<DuctDirectionalEndpoint> allowRemoteNodesExtractor = new ArrayList<>();
    public final List<DuctDirectionalEndpoint> denyRemoteNodesExtractor = new ArrayList<>();
    public final List<Boolean> allowRemoteIgnoreChannelExtractor = new ArrayList<>();
    public final List<Boolean> denyRemoteIgnoreChannelExtractor = new ArrayList<>();
    public final List<Boolean> allowRemoteAnyFaceExtractor = new ArrayList<>();
    public final List<Boolean> denyRemoteAnyFaceExtractor = new ArrayList<>();

    public boolean denyOverridesAllowRetriever = true;
    public final List<String> allowFiltersRetriever = new ArrayList<>();
    public final List<String> denyFiltersRetriever = new ArrayList<>();
    public final List<Integer> allowAllowCapsRetriever = new ArrayList<>();
    public final List<Integer> allowConcatChannelsRetriever = new ArrayList<>();
    public final List<Integer> denyConcatChannelsRetriever = new ArrayList<>();
    public final List<DuctDirectionalEndpoint> allowRemoteNodesRetriever = new ArrayList<>();
    public final List<DuctDirectionalEndpoint> denyRemoteNodesRetriever = new ArrayList<>();
    public final List<Boolean> allowRemoteIgnoreChannelRetriever = new ArrayList<>();
    public final List<Boolean> denyRemoteIgnoreChannelRetriever = new ArrayList<>();
    public final List<Boolean> allowRemoteAnyFaceRetriever = new ArrayList<>();
    public final List<Boolean> denyRemoteAnyFaceRetriever = new ArrayList<>();

    public boolean denyOverridesAllowFilter = true;
    public final List<String> allowFiltersFilter = new ArrayList<>();
    public final List<String> denyFiltersFilter = new ArrayList<>();
    /** FILTER bank caps split: Limit is used for insertion destinations, Keep is used when a Retrieving node pulls from this donor. */
    public final List<Integer> allowAllowCapsFilterLimit = new ArrayList<>();
    public final List<Integer> allowAllowCapsFilterKeep = new ArrayList<>();
    public final List<Integer> allowConcatChannelsFilter = new ArrayList<>();
    public final List<Integer> denyConcatChannelsFilter = new ArrayList<>();
    public final List<DuctDirectionalEndpoint> allowRemoteNodesFilter = new ArrayList<>();
    public final List<DuctDirectionalEndpoint> denyRemoteNodesFilter = new ArrayList<>();
    public final List<Boolean> allowRemoteIgnoreChannelFilter = new ArrayList<>();
    public final List<Boolean> denyRemoteIgnoreChannelFilter = new ArrayList<>();
    public final List<Boolean> allowRemoteAnyFaceFilter = new ArrayList<>();
    public final List<Boolean> denyRemoteAnyFaceFilter = new ArrayList<>();

    public final ItemStackHandler guiSlots;

    /**
     * Effective capacity for each multi-bank allow/deny list, updated by {@link #clampFilterSizes}.
     * Entries stored beyond this index are preserved for data safety but are not evaluated during
     * filter matching. Default is {@link Integer#MAX_VALUE} (unlimited) until first clamp.
     */
    public int effectiveAllowBankCap = Integer.MAX_VALUE;
    public int effectiveDenyBankCap = Integer.MAX_VALUE;

    public DuctFaceNode(Runnable onChanged) {
        this.guiSlots =
                new ItemStackHandler(1) {
                    @Override
                    protected void onContentsChanged(int slot) {
                        onChanged.run();
                    }
                };
    }

    /** Legacy combined GUI: slots 0–4 modules, slot 5 copy. */
    private static final int LEGACY_GUI_SLOT_COUNT = 6;

    private void loadGuiSlotsFromNbt(HolderLookup.Provider registries, CompoundTag nodeGuiTag) {
        // Ph10: guiSlots is 1 slot (copy). Legacy world saves used 6 slots (0–4 modules, 5 copy) in NodeGui.
        // New NBT has Size 1 and the copy stack in slot 0. Old migration used "any of 0–4 non-empty" as module signal;
        // that is true for the new layout whenever copy is non-empty, then slot 5 was read → empty → copy wiped.
        int declaredSize = LEGACY_GUI_SLOT_COUNT;
        if (nodeGuiTag.contains("Size")) {
            declaredSize = nodeGuiTag.getIntOr("Size", 0);
        } else if (nodeGuiTag.contains("Size")) {
            declaredSize = nodeGuiTag.getByteOr("Size", (byte) 0) & 0xFF;
        }
        if (declaredSize <= 1) {
            ItemStackHandler compact = new ItemStackHandler(1);
            DuctNbtCodecs.deserializeHandler(compact, registries, nodeGuiTag);
            guiSlots.setStackInSlot(0, compact.getStackInSlot(0).copy());
            return;
        }

        ItemStackHandler probe = new ItemStackHandler(LEGACY_GUI_SLOT_COUNT);
        DuctNbtCodecs.deserializeHandler(probe, registries, nodeGuiTag);
        boolean moduleColumnUsed = false;
        for (int i = 0; i < 5; i++) {
            if (!probe.getStackInSlot(i).isEmpty()) {
                moduleColumnUsed = true;
                break;
            }
        }
        if (moduleColumnUsed || !probe.getStackInSlot(5).isEmpty()) {
            guiSlots.setStackInSlot(0, probe.getStackInSlot(5).copy());
        } else {
            guiSlots.setStackInSlot(0, probe.getStackInSlot(0).copy());
        }
    }

    public void resetPipeSegmentDefaults() {
        insertionPriority = 0;
        extractBatch = 0;
        lastExtractBatchSettingCapApplied = 0;
        extractBatchPinnedToMax = false;
        roundRobinCursor = 0;
        retrieverPullSlotCursor = 0;
        selfFeed = false;
        ticksUntilAction = 0;
    }

    /**
     * Whether extract batch should track the GUI setting cap when modules raise it. Set when the player chooses max
     * ("M" / amount == cap) or when clamp finds the stored amount already at the current cap. A value set below the
     * cap must never be auto-raised. Legacy saves without the flag are migrated once in
     * {@link #loadExtractBatchPinnedFromTag}.
     */
    public boolean isExtractBatchPinnedToCap() {
        return extractBatchPinnedToMax;
    }

    private void loadExtractBatchPinnedFromTag(CompoundTag tag) {
        if (tag.contains("ExtractBatchPinned")) {
            extractBatchPinnedToMax = tag.getBooleanOr("ExtractBatchPinned", false);
        } else {
            extractBatchPinnedToMax =
                    lastExtractBatchSettingCapApplied > 0
                            && extractBatch >= lastExtractBatchSettingCapApplied;
        }
    }

    /** Settings copier: lane configuration without {@code NodeGui} or per-tick cursors. */
    public void saveSettings(HolderLookup.Provider registries, CompoundTag tag) {
        tag.putByte("RoutingMode", (byte) routingMode.ordinal());
        tag.putByte("RoutingModeEx", (byte) routingModeExtractor.ordinal());
        tag.putByte("RoutingModeRe", (byte) routingModeRetriever.ordinal());
        tag.putInt("InsertionPriority", insertionPriority);
        tag.putInt("ExtractBatch", extractBatch);
        tag.putInt("ExtractBatchCapMemo", lastExtractBatchSettingCapApplied);
        tag.putBoolean("ExtractBatchPinned", extractBatchPinnedToMax);
        tag.putByte("Channel", (byte) channelLetter);
        tag.putBoolean("SelfFeed", selfFeed);
        tag.putByte("EligMode", (byte) eligibilityMode.ordinal());
        saveFilters(tag);
    }

    /** Restores {@link #saveSettings} data; resets routing cursors and does not touch {@link #guiSlots}. */
    public void loadSettings(HolderLookup.Provider registries, CompoundTag tag) {
        routingMode = RoutingMode.fromOrdinal(tag.getByteOr("RoutingMode", (byte) 0));
        routingModeExtractor =
                tag.contains("RoutingModeEx")
                        ? RoutingMode.fromOrdinal(tag.getByteOr("RoutingModeEx", (byte) 0))
                        : routingMode;
        routingModeRetriever =
                tag.contains("RoutingModeRe")
                        ? RoutingMode.fromOrdinal(tag.getByteOr("RoutingModeRe", (byte) 0))
                        : routingMode;
        insertionPriority = 0;
        extractBatch = 0;
        if (tag.contains("InsertionPriority")) {
            insertionPriority = tag.getIntOr("InsertionPriority", 0);
        } else if (tag.contains("InsPriority")) {
            insertionPriority = tag.getIntOr("InsPriority", 0);
        }
        if (tag.contains("ExtractBatch")) {
            extractBatch = tag.getIntOr("ExtractBatch", 0);
        }
        lastExtractBatchSettingCapApplied =
                tag.contains("ExtractBatchCapMemo") ? tag.getIntOr("ExtractBatchCapMemo", 0) : 0;
        loadExtractBatchPinnedFromTag(tag);
        channelLetter = tag.contains("Channel") ? tag.getByteOr("Channel", (byte) 0) & 0xFF : 1;
        selfFeed = tag.contains("SelfFeed") && tag.getBooleanOr("SelfFeed", false);
        eligibilityMode =
                tag.contains("EligMode") ? EligibilityMode.fromOrdinal(tag.getByteOr("EligMode", (byte) 0)) : EligibilityMode.BOTH;
        roundRobinCursor = 0;
        retrieverPullSlotCursor = 0;
        ticksUntilAction = 0;
        loadFilters(tag);
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        tag.putByte("RoutingMode", (byte) routingMode.ordinal());
        tag.putByte("RoutingModeEx", (byte) routingModeExtractor.ordinal());
        tag.putByte("RoutingModeRe", (byte) routingModeRetriever.ordinal());
        tag.putInt("InsertionPriority", insertionPriority);
        tag.putInt("ExtractBatch", extractBatch);
        tag.putInt("ExtractBatchCapMemo", lastExtractBatchSettingCapApplied);
        tag.putBoolean("ExtractBatchPinned", extractBatchPinnedToMax);
        tag.putByte("Channel", (byte) channelLetter);
        tag.putInt("RrCursor", roundRobinCursor);
        tag.putInt("RtrPullSlot", retrieverPullSlotCursor);
        tag.putInt("TicksAct", ticksUntilAction);
        tag.putBoolean("SelfFeed", selfFeed);
        tag.putByte("EligMode", (byte) eligibilityMode.ordinal());
        tag.put("NodeGui", DuctNbtCodecs.serializeHandler(guiSlots, registries));
        saveFilters(tag);
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        routingMode = RoutingMode.fromOrdinal(tag.getByteOr("RoutingMode", (byte) 0));
        routingModeExtractor =
                tag.contains("RoutingModeEx")
                        ? RoutingMode.fromOrdinal(tag.getByteOr("RoutingModeEx", (byte) 0))
                        : routingMode;
        routingModeRetriever =
                tag.contains("RoutingModeRe")
                        ? RoutingMode.fromOrdinal(tag.getByteOr("RoutingModeRe", (byte) 0))
                        : routingMode;
        insertionPriority = 0;
        extractBatch = 0;
        if (tag.contains("InsertionPriority")) {
            insertionPriority = tag.getIntOr("InsertionPriority", 0);
        } else if (tag.contains("InsPriority")) {
            insertionPriority = tag.getIntOr("InsPriority", 0);
        }
        if (tag.contains("ExtractBatch")) {
            extractBatch = tag.getIntOr("ExtractBatch", 0);
        }
        lastExtractBatchSettingCapApplied =
                tag.contains("ExtractBatchCapMemo") ? tag.getIntOr("ExtractBatchCapMemo", 0) : 0;
        loadExtractBatchPinnedFromTag(tag);
        channelLetter = tag.contains("Channel") ? tag.getByteOr("Channel", (byte) 0) & 0xFF : 1;
        roundRobinCursor = tag.getIntOr("RrCursor", 0);
        retrieverPullSlotCursor = tag.contains("RtrPullSlot") ? tag.getIntOr("RtrPullSlot", 0) : 0;
        ticksUntilAction = tag.contains("TicksAct") ? tag.getIntOr("TicksAct", 0) : 0;
        selfFeed = tag.contains("SelfFeed") && tag.getBooleanOr("SelfFeed", false);
        eligibilityMode =
                tag.contains("EligMode") ? EligibilityMode.fromOrdinal(tag.getByteOr("EligMode", (byte) 0)) : EligibilityMode.BOTH;
        if (tag.contains("NodeGui")) {
            loadGuiSlotsFromNbt(registries, tag.getCompoundOrEmpty("NodeGui"));
        }
        loadFilters(tag);
    }

    /**
     * Copy legacy single-lane NBT (world migration). {@code sharedNodeMode} comes from {@link DuctFaceLanes} (already loaded
     * from the same root).
     */
    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root, NodeMode sharedNodeMode) {
        if (root.contains("NodeGui")) {
            loadGuiSlotsFromNbt(registries, root.getCompoundOrEmpty("NodeGui"));
        }
        routingMode = RoutingMode.fromOrdinal(root.getByteOr("RoutingMode", (byte) 0));
        insertionPriority = 0;
        extractBatch = 0;
        if (root.contains("InsertionPriority")) {
            insertionPriority = root.getIntOr("InsertionPriority", 0);
        } else if (root.contains("InsPriority")) {
            insertionPriority = root.getIntOr("InsPriority", 0);
        }
        if (root.contains("ExtractBatch")) {
            extractBatch = root.getIntOr("ExtractBatch", 0);
        }
        lastExtractBatchSettingCapApplied =
                root.contains("ExtractBatchCapMemo") ? root.getIntOr("ExtractBatchCapMemo", 0) : 0;
        loadExtractBatchPinnedFromTag(root);
        if (!root.contains("InsertionPriority")
                && !root.contains("InsPriority")
                && !root.contains("ExtractBatch")
                && root.contains("AmountField")) {
            int legacy = root.getIntOr("AmountField", 0);
            if (sharedNodeMode.usesInsertionPriorityField()) {
                insertionPriority = legacy;
            } else if (sharedNodeMode.usesExtractBatchField()) {
                extractBatch = legacy;
            } else {
                insertionPriority = legacy;
            }
        }
        channelLetter = root.contains("Channel") ? root.getByteOr("Channel", (byte) 0) & 0xFF : 1;
        roundRobinCursor = root.getIntOr("RrCursor", 0);
        ticksUntilAction = root.contains("TicksAct") ? root.getIntOr("TicksAct", 0) : 0;
        loadFilters(root);
        eligibilityMode =
                root.contains("EligMode") ? EligibilityMode.fromOrdinal(root.getByteOr("EligMode", (byte) 0)) : EligibilityMode.BOTH;
    }

    public void clampFilterSizes(
            DuctItemTransportSpec spec, NodeMode sharedNodeMode, DuctModuleEffects.FilterSlotBonuses fb) {
        int legacyA = Math.max(0, spec.filterAllowSlots() + fb.item().allowAdd());
        int legacyD = Math.max(0, spec.filterDenySlots() + fb.item().denyAdd());
        clampList(allowFilters, legacyA);
        clampList(denyFilters, legacyD);
        syncAllowCapsToAllowSize(allowAllowCaps, allowFilters.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannels, allowFilters.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannels, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodes, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodes, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteIgnoreChannel, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFace, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteIgnoreChannel, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFace, denyFilters.size());

        int bankA = DuctModuleEffects.effectiveItemAllowBank(spec, sharedNodeMode, fb);
        int bankD = DuctModuleEffects.effectiveItemDenyBank(spec, sharedNodeMode, fb);
        effectiveAllowBankCap = bankA;
        effectiveDenyBankCap = bankD;
        clampList(allowFiltersExtractor, bankA);
        clampList(denyFiltersExtractor, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsExtractor, allowFiltersExtractor.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsExtractor, allowFiltersExtractor.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceExtractor, denyFiltersExtractor.size());
        clampList(allowFiltersRetriever, bankA);
        clampList(denyFiltersRetriever, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsRetriever, allowFiltersRetriever.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsRetriever, allowFiltersRetriever.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceRetriever, denyFiltersRetriever.size());
        clampList(allowFiltersFilter, bankA);
        clampList(denyFiltersFilter, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsFilterLimit, allowFiltersFilter.size());
        syncAllowCapsToAllowSize(allowAllowCapsFilterKeep, allowFiltersFilter.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsFilter, allowFiltersFilter.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesFilter, denyFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelFilter, denyFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceFilter, denyFiltersFilter.size());
    }

    /** Same layout as {@link #clampFilterSizes(DuctItemTransportSpec, NodeMode, DuctModuleEffects.FilterSlotBonuses)} using fluid datapack caps. */
    public void clampFilterSizes(
            DuctFluidTransportSpec spec, NodeMode sharedNodeMode, DuctModuleEffects.FilterSlotBonuses fb) {
        int legacyA = Math.max(0, spec.filterAllowSlots() + fb.fluid().allowAdd());
        int legacyD = Math.max(0, spec.filterDenySlots() + fb.fluid().denyAdd());
        clampList(allowFilters, legacyA);
        clampList(denyFilters, legacyD);
        syncAllowCapsToAllowSize(allowAllowCaps, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodes, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodes, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteIgnoreChannel, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFace, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteIgnoreChannel, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFace, denyFilters.size());

        int bankA = DuctModuleEffects.effectiveFluidAllowBank(spec, sharedNodeMode, fb);
        int bankD = DuctModuleEffects.effectiveFluidDenyBank(spec, sharedNodeMode, fb);
        effectiveAllowBankCap = bankA;
        effectiveDenyBankCap = bankD;
        clampList(allowFiltersExtractor, bankA);
        clampList(denyFiltersExtractor, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsExtractor, allowFiltersExtractor.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsExtractor, allowFiltersExtractor.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceExtractor, denyFiltersExtractor.size());
        clampList(allowFiltersRetriever, bankA);
        clampList(denyFiltersRetriever, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsRetriever, allowFiltersRetriever.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsRetriever, allowFiltersRetriever.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceRetriever, denyFiltersRetriever.size());
        clampList(allowFiltersFilter, bankA);
        clampList(denyFiltersFilter, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsFilterLimit, allowFiltersFilter.size());
        syncAllowCapsToAllowSize(allowAllowCapsFilterKeep, allowFiltersFilter.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsFilter, allowFiltersFilter.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesFilter, denyFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelFilter, denyFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceFilter, denyFiltersFilter.size());
    }

    /** Same layout as item clamp using gas datapack caps. */
    public void clampFilterSizes(DuctGasTransportSpec spec, NodeMode sharedNodeMode, DuctModuleEffects.FilterSlotBonuses fb) {
        int legacyA = Math.max(0, spec.filterAllowSlots() + fb.gas().allowAdd());
        int legacyD = Math.max(0, spec.filterDenySlots() + fb.gas().denyAdd());
        clampList(allowFilters, legacyA);
        clampList(denyFilters, legacyD);
        syncAllowCapsToAllowSize(allowAllowCaps, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodes, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodes, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteIgnoreChannel, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFace, allowFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteIgnoreChannel, denyFilters.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFace, denyFilters.size());

        int bankA = DuctModuleEffects.effectiveGasAllowBank(spec, sharedNodeMode, fb);
        int bankD = DuctModuleEffects.effectiveGasDenyBank(spec, sharedNodeMode, fb);
        effectiveAllowBankCap = bankA;
        effectiveDenyBankCap = bankD;
        clampList(allowFiltersExtractor, bankA);
        clampList(denyFiltersExtractor, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsExtractor, allowFiltersExtractor.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsExtractor, allowFiltersExtractor.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceExtractor, allowFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelExtractor, denyFiltersExtractor.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceExtractor, denyFiltersExtractor.size());
        clampList(allowFiltersRetriever, bankA);
        clampList(denyFiltersRetriever, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsRetriever, allowFiltersRetriever.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsRetriever, allowFiltersRetriever.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceRetriever, allowFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelRetriever, denyFiltersRetriever.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceRetriever, denyFiltersRetriever.size());
        clampList(allowFiltersFilter, bankA);
        clampList(denyFiltersFilter, bankD);
        syncAllowCapsToAllowSize(allowAllowCapsFilterLimit, allowFiltersFilter.size());
        syncAllowCapsToAllowSize(allowAllowCapsFilterKeep, allowFiltersFilter.size());
        FilterConcatChannel.syncToLineSize(allowConcatChannelsFilter, allowFiltersFilter.size());
        FilterConcatChannel.syncToLineSize(denyConcatChannelsFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteNodesFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteNodesFilter, denyFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                allowRemoteIgnoreChannelFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceFilter, allowFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(
                denyRemoteIgnoreChannelFilter, denyFiltersFilter.size());
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceFilter, denyFiltersFilter.size());
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
        putConcatChannelArray(f, "AllowConcat", allowConcatChannels);
        putConcatChannelArray(f, "DenyConcat", denyConcatChannels);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(f, "AllowRemoteNode", allowRemoteNodes);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(f, "DenyRemoteNode", denyRemoteNodes);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(f, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannel);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(f, "AllowRemoteAnyFace", allowRemoteAnyFace);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(f, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannel);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(f, "DenyRemoteAnyFace", denyRemoteAnyFace);

        CompoundTag ex = new CompoundTag();
        ex.putBoolean("DenyOver", denyOverridesAllowExtractor);
        ex.put("Allow", toStringListTag(allowFiltersExtractor));
        ex.put("Deny", toStringListTag(denyFiltersExtractor));
        putAllowCapArray(ex, allowAllowCapsExtractor);
        putConcatChannelArray(ex, "AllowConcat", allowConcatChannelsExtractor);
        putConcatChannelArray(ex, "DenyConcat", denyConcatChannelsExtractor);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(ex, "AllowRemoteNode", allowRemoteNodesExtractor);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(ex, "DenyRemoteNode", denyRemoteNodesExtractor);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(ex, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannelExtractor);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(ex, "AllowRemoteAnyFace", allowRemoteAnyFaceExtractor);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(ex, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannelExtractor);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(ex, "DenyRemoteAnyFace", denyRemoteAnyFaceExtractor);
        f.put("Extractor", ex);

        CompoundTag re = new CompoundTag();
        re.putBoolean("DenyOver", denyOverridesAllowRetriever);
        re.put("Allow", toStringListTag(allowFiltersRetriever));
        re.put("Deny", toStringListTag(denyFiltersRetriever));
        putAllowCapArray(re, allowAllowCapsRetriever);
        putConcatChannelArray(re, "AllowConcat", allowConcatChannelsRetriever);
        putConcatChannelArray(re, "DenyConcat", denyConcatChannelsRetriever);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(re, "AllowRemoteNode", allowRemoteNodesRetriever);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(re, "DenyRemoteNode", denyRemoteNodesRetriever);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(re, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannelRetriever);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(re, "AllowRemoteAnyFace", allowRemoteAnyFaceRetriever);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(re, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannelRetriever);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(re, "DenyRemoteAnyFace", denyRemoteAnyFaceRetriever);
        f.put("Retriever", re);

        CompoundTag fi = new CompoundTag();
        fi.putBoolean("DenyOver", denyOverridesAllowFilter);
        fi.put("Allow", toStringListTag(allowFiltersFilter));
        fi.put("Deny", toStringListTag(denyFiltersFilter));
        // Backward-compatible: keep writing AllowCap as Limit.
        putAllowCapArray(fi, allowAllowCapsFilterLimit);
        putAllowCapArray(fi, "AllowCapLim", allowAllowCapsFilterLimit);
        putAllowCapArray(fi, "AllowCapKeep", allowAllowCapsFilterKeep);
        putConcatChannelArray(fi, "AllowConcat", allowConcatChannelsFilter);
        putConcatChannelArray(fi, "DenyConcat", denyConcatChannelsFilter);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(fi, "AllowRemoteNode", allowRemoteNodesFilter);
        DuctFilterRemoteNodeLogic.putRemoteNodeList(fi, "DenyRemoteNode", denyRemoteNodesFilter);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(fi, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannelFilter);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(fi, "AllowRemoteAnyFace", allowRemoteAnyFaceFilter);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(fi, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannelFilter);
        DuctFilterRemoteNodeLogic.putIgnoreChannelArray(fi, "DenyRemoteAnyFace", denyRemoteAnyFaceFilter);
        f.put("Filter", fi);
        tag.put("FaceFilters", f);
    }

    private void loadFilters(CompoundTag tag) {
        allowFilters.clear();
        denyFilters.clear();
        allowAllowCaps.clear();
        allowConcatChannels.clear();
        denyConcatChannels.clear();
        allowRemoteNodes.clear();
        denyRemoteNodes.clear();
        allowRemoteIgnoreChannel.clear();
        allowRemoteAnyFace.clear();
        denyRemoteIgnoreChannel.clear();
        denyRemoteAnyFace.clear();
        denyOverridesAllow = true;
        allowFiltersExtractor.clear();
        denyFiltersExtractor.clear();
        allowAllowCapsExtractor.clear();
        allowConcatChannelsExtractor.clear();
        denyConcatChannelsExtractor.clear();
        allowRemoteNodesExtractor.clear();
        denyRemoteNodesExtractor.clear();
        allowRemoteIgnoreChannelExtractor.clear();
        allowRemoteAnyFaceExtractor.clear();
        denyRemoteIgnoreChannelExtractor.clear();
        denyRemoteAnyFaceExtractor.clear();
        denyOverridesAllowExtractor = true;
        allowFiltersRetriever.clear();
        denyFiltersRetriever.clear();
        allowAllowCapsRetriever.clear();
        allowConcatChannelsRetriever.clear();
        denyConcatChannelsRetriever.clear();
        allowRemoteNodesRetriever.clear();
        denyRemoteNodesRetriever.clear();
        allowRemoteIgnoreChannelRetriever.clear();
        allowRemoteAnyFaceRetriever.clear();
        denyRemoteIgnoreChannelRetriever.clear();
        denyRemoteAnyFaceRetriever.clear();
        denyOverridesAllowRetriever = true;
        allowFiltersFilter.clear();
        denyFiltersFilter.clear();
        allowAllowCapsFilterLimit.clear();
        allowAllowCapsFilterKeep.clear();
        allowConcatChannelsFilter.clear();
        denyConcatChannelsFilter.clear();
        allowRemoteNodesFilter.clear();
        denyRemoteNodesFilter.clear();
        allowRemoteIgnoreChannelFilter.clear();
        allowRemoteAnyFaceFilter.clear();
        denyRemoteIgnoreChannelFilter.clear();
        denyRemoteAnyFaceFilter.clear();
        denyOverridesAllowFilter = true;
        if (!tag.contains("FaceFilters")) {
            return;
        }
        CompoundTag f = tag.getCompoundOrEmpty("FaceFilters");
        denyOverridesAllow = !f.contains("DenyOver") || f.getBooleanOr("DenyOver", false);
        readStringListInto(f, "Allow", allowFilters);
        readStringListInto(f, "Deny", denyFilters);
        readAllowCapsInto(f, allowAllowCaps, allowFilters.size());
        readConcatChannelsInto(f, "AllowConcat", allowConcatChannels, allowFilters.size());
        readConcatChannelsInto(f, "DenyConcat", denyConcatChannels, denyFilters.size());
        DuctFilterRemoteNodeLogic.readRemoteNodeListInto(f, "AllowRemoteNode", allowRemoteNodes, allowFilters.size());
        DuctFilterRemoteNodeLogic.readRemoteNodeListInto(f, "DenyRemoteNode", denyRemoteNodes, denyFilters.size());
        DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                f, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannel, allowFilters.size());
        DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                f, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannel, denyFilters.size());
        DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                f, "AllowRemoteAnyFace", allowRemoteAnyFace, allowFilters.size());
        DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                f, "DenyRemoteAnyFace", denyRemoteAnyFace, denyFilters.size());

        boolean hasExtractor = f.contains("Extractor");
        boolean hasRetriever = f.contains("Retriever");
        boolean hasFilter = f.contains("Filter");
        if (hasExtractor) {
            CompoundTag ex = f.getCompoundOrEmpty("Extractor");
            denyOverridesAllowExtractor = !ex.contains("DenyOver") || ex.getBooleanOr("DenyOver", false);
            readStringListInto(ex, "Allow", allowFiltersExtractor);
            readStringListInto(ex, "Deny", denyFiltersExtractor);
            readAllowCapsInto(ex, allowAllowCapsExtractor, allowFiltersExtractor.size());
            readConcatChannelsInto(ex, "AllowConcat", allowConcatChannelsExtractor, allowFiltersExtractor.size());
            readConcatChannelsInto(ex, "DenyConcat", denyConcatChannelsExtractor, denyFiltersExtractor.size());
            DuctFilterRemoteNodeLogic.readRemoteNodeListInto(
                    ex, "AllowRemoteNode", allowRemoteNodesExtractor, allowFiltersExtractor.size());
            DuctFilterRemoteNodeLogic.readRemoteNodeListInto(
                    ex, "DenyRemoteNode", denyRemoteNodesExtractor, denyFiltersExtractor.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    ex, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannelExtractor, allowFiltersExtractor.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    ex, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannelExtractor, denyFiltersExtractor.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    ex, "AllowRemoteAnyFace", allowRemoteAnyFaceExtractor, allowFiltersExtractor.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    ex, "DenyRemoteAnyFace", denyRemoteAnyFaceExtractor, denyFiltersExtractor.size());
        }
        if (hasRetriever) {
            CompoundTag re = f.getCompoundOrEmpty("Retriever");
            denyOverridesAllowRetriever = !re.contains("DenyOver") || re.getBooleanOr("DenyOver", false);
            readStringListInto(re, "Allow", allowFiltersRetriever);
            readStringListInto(re, "Deny", denyFiltersRetriever);
            readAllowCapsInto(re, allowAllowCapsRetriever, allowFiltersRetriever.size());
            readConcatChannelsInto(re, "AllowConcat", allowConcatChannelsRetriever, allowFiltersRetriever.size());
            readConcatChannelsInto(re, "DenyConcat", denyConcatChannelsRetriever, denyFiltersRetriever.size());
            DuctFilterRemoteNodeLogic.readRemoteNodeListInto(
                    re, "AllowRemoteNode", allowRemoteNodesRetriever, allowFiltersRetriever.size());
            DuctFilterRemoteNodeLogic.readRemoteNodeListInto(
                    re, "DenyRemoteNode", denyRemoteNodesRetriever, denyFiltersRetriever.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    re, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannelRetriever, allowFiltersRetriever.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    re, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannelRetriever, denyFiltersRetriever.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    re, "AllowRemoteAnyFace", allowRemoteAnyFaceRetriever, allowFiltersRetriever.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    re, "DenyRemoteAnyFace", denyRemoteAnyFaceRetriever, denyFiltersRetriever.size());
        }
        if (hasFilter) {
            CompoundTag fi = f.getCompoundOrEmpty("Filter");
            denyOverridesAllowFilter = !fi.contains("DenyOver") || fi.getBooleanOr("DenyOver", false);
            readStringListInto(fi, "Allow", allowFiltersFilter);
            readStringListInto(fi, "Deny", denyFiltersFilter);
            // Migration: old single AllowCap is treated as Limit; Keep defaults to 0.
            readAllowCapsInto(fi, "AllowCapLim", allowAllowCapsFilterLimit, allowFiltersFilter.size());
            if (allowAllowCapsFilterLimit.stream().allMatch(v -> v == 0) && fi.contains("AllowCap")) {
                allowAllowCapsFilterLimit.clear();
                readAllowCapsInto(fi, allowAllowCapsFilterLimit, allowFiltersFilter.size());
            }
            readAllowCapsInto(fi, "AllowCapKeep", allowAllowCapsFilterKeep, allowFiltersFilter.size());
            readConcatChannelsInto(fi, "AllowConcat", allowConcatChannelsFilter, allowFiltersFilter.size());
            readConcatChannelsInto(fi, "DenyConcat", denyConcatChannelsFilter, denyFiltersFilter.size());
            DuctFilterRemoteNodeLogic.readRemoteNodeListInto(
                    fi, "AllowRemoteNode", allowRemoteNodesFilter, allowFiltersFilter.size());
            DuctFilterRemoteNodeLogic.readRemoteNodeListInto(
                    fi, "DenyRemoteNode", denyRemoteNodesFilter, denyFiltersFilter.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    fi, "AllowRemoteIgnoreChannel", allowRemoteIgnoreChannelFilter, allowFiltersFilter.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    fi, "DenyRemoteIgnoreChannel", denyRemoteIgnoreChannelFilter, denyFiltersFilter.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    fi, "AllowRemoteAnyFace", allowRemoteAnyFaceFilter, allowFiltersFilter.size());
            DuctFilterRemoteNodeLogic.readIgnoreChannelInto(
                    fi, "DenyRemoteAnyFace", denyRemoteAnyFaceFilter, denyFiltersFilter.size());
        }
        if (!hasExtractor && !hasRetriever && !hasFilter) {
            // Migration: legacy single-bank -> FILTER bank by default.
            denyOverridesAllowFilter = denyOverridesAllow;
            allowFiltersFilter.addAll(allowFilters);
            denyFiltersFilter.addAll(denyFilters);
            allowAllowCapsFilterLimit.addAll(allowAllowCaps);
            allowConcatChannelsFilter.addAll(allowConcatChannels);
            denyConcatChannelsFilter.addAll(denyConcatChannels);
            allowRemoteNodesFilter.addAll(allowRemoteNodes);
            denyRemoteNodesFilter.addAll(denyRemoteNodes);
            allowRemoteIgnoreChannelFilter.addAll(allowRemoteIgnoreChannel);
            denyRemoteIgnoreChannelFilter.addAll(denyRemoteIgnoreChannel);
            allowRemoteAnyFaceFilter.addAll(allowRemoteAnyFace);
            denyRemoteAnyFaceFilter.addAll(denyRemoteAnyFace);
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

    public List<Integer> bankAllowConcatChannels(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowConcatChannelsExtractor;
            case RETRIEVER -> allowConcatChannelsRetriever;
            case FILTER -> allowConcatChannelsFilter;
        };
    }

    public List<Integer> bankDenyConcatChannels(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyConcatChannelsExtractor;
            case RETRIEVER -> denyConcatChannelsRetriever;
            case FILTER -> denyConcatChannelsFilter;
        };
    }

    public List<DuctDirectionalEndpoint> bankAllowRemoteNodes(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowRemoteNodesExtractor;
            case RETRIEVER -> allowRemoteNodesRetriever;
            case FILTER -> allowRemoteNodesFilter;
        };
    }

    public List<DuctDirectionalEndpoint> bankDenyRemoteNodes(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyRemoteNodesExtractor;
            case RETRIEVER -> denyRemoteNodesRetriever;
            case FILTER -> denyRemoteNodesFilter;
        };
    }

    public List<Boolean> bankAllowRemoteIgnoreChannel(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowRemoteIgnoreChannelExtractor;
            case RETRIEVER -> allowRemoteIgnoreChannelRetriever;
            case FILTER -> allowRemoteIgnoreChannelFilter;
        };
    }

    public List<Boolean> bankDenyRemoteIgnoreChannel(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyRemoteIgnoreChannelExtractor;
            case RETRIEVER -> denyRemoteIgnoreChannelRetriever;
            case FILTER -> denyRemoteIgnoreChannelFilter;
        };
    }

    public List<Boolean> bankAllowRemoteAnyFace(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowRemoteAnyFaceExtractor;
            case RETRIEVER -> allowRemoteAnyFaceRetriever;
            case FILTER -> allowRemoteAnyFaceFilter;
        };
    }

    public List<Boolean> bankDenyRemoteAnyFace(FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyRemoteAnyFaceExtractor;
            case RETRIEVER -> denyRemoteAnyFaceRetriever;
            case FILTER -> denyRemoteAnyFaceFilter;
        };
    }

    /** Migrates duct-position remote bindings saved before physical attachment endpoints. */
    public boolean migrateLegacyRemoteNodeEndpoints(net.minecraft.world.level.Level level) {
        boolean changed = false;
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, allowRemoteNodes);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, denyRemoteNodes);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, allowRemoteNodesExtractor);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, denyRemoteNodesExtractor);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, allowRemoteNodesRetriever);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, denyRemoteNodesRetriever);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, allowRemoteNodesFilter);
        changed |= DuctFilterRemoteNodeLogic.migrateLegacyEndpointsInPlace(level, denyRemoteNodesFilter);
        return changed;
    }

    private static void putConcatChannelArray(CompoundTag tag, String key, List<Integer> concat) {
        if (concat == null || concat.isEmpty()) {
            return;
        }
        byte[] arr = new byte[concat.size()];
        for (int i = 0; i < concat.size(); i++) {
            int v = concat.get(i) != null ? concat.get(i) : 0;
            arr[i] = (byte) Math.clamp(v, 0, FilterConcatChannel.MAX_LETTER);
        }
        tag.putByteArray(key, arr);
    }

    private static void readConcatChannelsInto(
            CompoundTag tag, String key, List<Integer> target, int lineCount) {
        target.clear();
        if (tag.contains(key)) {
            for (byte b : tag.getByteArray(key).orElse(new byte[0])) {
                target.add((int) b & 0xFF);
            }
        }
        FilterConcatChannel.syncToLineSize(target, lineCount);
    }

    private static void clampList(List<String> list, int max) {
        // Only remove trailing blank slots beyond capacity.
        // Non-blank entries beyond capacity are preserved so that temporarily removing a filter
        // module does not destroy configured filter patterns; those extra entries are inactive
        // (not evaluated during filtering) until capacity is restored.
        while (list.size() > max) {
            String last = list.get(list.size() - 1);
            if (last == null || last.isBlank()) {
                list.remove(list.size() - 1);
            } else {
                break;
            }
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
        if (!tag.contains(key)) {
            return;
        }
        ListTag list = tag.getListOrEmpty(key);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getStringOr(i, ""));
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
        if (tag.contains(key)) {
            int[] arr = tag.getIntArray(key).orElse(new int[0]);
            for (int v : arr) {
                out.add(Math.max(0, v));
            }
        }
        syncAllowCapsToAllowSize(out, allowSize);
    }

}
