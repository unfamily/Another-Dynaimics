package net.unfamily.another_dynamics.duct;

/**
 * Indices for {@link net.minecraft.world.inventory.ContainerData} synced with {@link DuctNodeMenu}.
 */
public final class DuctMenuSync {
    public static final int NODE_MODE = 0;
    public static final int ROUTING_MODE = 1;
    /** Hybrid: routing mode for Extractor sub-node. */
    public static final int ROUTING_MODE_EXTRACTOR = 2;
    /** Hybrid: routing mode for Retriever sub-node. */
    public static final int ROUTING_MODE_RETRIEVER = 3;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceNode#insertionPriority} (GUI when mode uses insertion priority). */
    public static final int PRIORITY = 4;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceNode#extractBatch} (GUI when mode uses extract/retrieve batch). */
    public static final int AMOUNT_FIELD = 5;
    public static final int CHANNEL = 6;
    public static final int REDSTONE_MODE = 7;
    public static final int FLAGS = 8;
    public static final int POS_X = 9;
    public static final int POS_Y = 10;
    public static final int POS_Z = 11;
    /** {@link net.minecraft.core.Direction#ordinal()} for the GUI face (which side's node is being edited). */
    public static final int ACCESS_FACE = 12;
    public static final int FILTER_HASH_ALLOW = 13;
    public static final int FILTER_HASH_DENY = 14;
    /** 1 if {@link net.unfamily.another_dynamics.duct.DuctFaceNode#denyOverridesAllow} ({@code >>>>>}), 0 for {@code <<<<<}. */
    public static final int DENY_OVERRIDES_ALLOW = 15;
    /**
     * Max extract/retrieve batch the player may configure for this face: {@code batch.default + module bonuses}, then clamped
     * by datapack {@code batch.max} when that value is non-negative.
     */
    public static final int EXTRACT_BATCH_CAP = 16;
    /** 1 if {@link net.unfamily.another_dynamics.duct.DuctFaceNode#selfFeed} (only used by hybrid Extr/Filt). */
    public static final int SELF_FEED = 17;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceNode#eligibilityMode} ordinal. */
    public static final int ELIGIBILITY_MODE = 18;
    /** Ordinal into {@link DuctDefinition#transportKinds()} order for GUI lane (item vs fluid). */
    public static final int ACTIVE_TRANSPORT_KIND = 19;
    /** Number of enabled transport kinds (1 = hide lane switcher). */
    public static final int TRANSPORT_KIND_COUNT = 20;
    /**
     * 0 = hub (transport-kind picker only), 1 = detail (full node UI). Only used when {@link #TRANSPORT_KIND_COUNT} &gt; 1.
     */
    public static final int MENU_VIEW_LAYER = 21;
    /**
     * High 16 bits of {@link #PRIORITY}. ContainerData syncs values as signed 16-bit shorts, so a 32-bit priority
     * is split: low 16 bits go in PRIORITY, high 16 bits go here. Reconstruct with:
     * {@code (syncData.get(PRIORITY_HI) << 16) | (syncData.get(PRIORITY) & 0xFFFF)}.
     */
    public static final int PRIORITY_HI = 22;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#transportEnabledMask} for the edited face. */
    public static final int TRANSPORT_ENABLED_MASK = 23;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#energyExtractBufferLimitFe}; 0 = AUTO in GUI. */
    public static final int ENERGY_BUF_LIMIT_EXTRACT = 24;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#energyInsertBufferLimitFe}; 0 = AUTO in GUI. */
    public static final int ENERGY_BUF_LIMIT_INSERT = 25;
    /** Current {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#energyInputBufferFe} on the edited face. */
    public static final int ENERGY_BUF_INPUT_STORED = 26;
    /** Current {@link net.unfamily.another_dynamics.duct.DuctFaceLanes#energyOutputBufferFe} on the edited face. */
    public static final int ENERGY_BUF_OUTPUT_STORED = 27;
    /** Effective max input buffer capacity (RF) for fill display. */
    public static final int ENERGY_BUF_INPUT_CAP = 28;
    /** Effective max output buffer capacity (RF) for fill display. */
    public static final int ENERGY_BUF_OUTPUT_CAP = 29;
    public static final int COUNT = 30;

    /** FLAGS bit: routing row active ({@link net.unfamily.another_dynamics.duct.NodeMode#usesRouting()}). */
    public static final int FLAG_ROUTING_ACTIVE = 1;
    /** FLAGS bit: deny/allow list row active ({@link net.unfamily.another_dynamics.duct.NodeMode#usesItemFilterConfig()}). */
    public static final int FLAG_FILTERS_ACTIVE = 2;

    private DuctMenuSync() {}
}
