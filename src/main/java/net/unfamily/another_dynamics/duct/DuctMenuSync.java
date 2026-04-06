package net.unfamily.another_dynamics.duct;

/**
 * Indices for {@link net.minecraft.world.inventory.ContainerData} synced with {@link DuctNodeMenu}.
 */
public final class DuctMenuSync {
    public static final int NODE_MODE = 0;
    public static final int ROUTING_MODE = 1;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceNode#insertionPriority} (GUI when mode uses insertion priority). */
    public static final int PRIORITY = 2;
    /** {@link net.unfamily.another_dynamics.duct.DuctFaceNode#extractBatch} (GUI when mode uses extract/retrieve batch). */
    public static final int AMOUNT_FIELD = 3;
    public static final int CHANNEL = 4;
    public static final int REDSTONE_MODE = 5;
    public static final int FLAGS = 6;
    public static final int POS_X = 7;
    public static final int POS_Y = 8;
    public static final int POS_Z = 9;
    /** {@link net.minecraft.core.Direction#ordinal()} for the GUI face (which side's node is being edited). */
    public static final int ACCESS_FACE = 10;
    public static final int FILTER_HASH_ALLOW = 11;
    public static final int FILTER_HASH_DENY = 12;
    /** 1 if {@link net.unfamily.another_dynamics.duct.DuctFaceNode#denyOverridesAllow} ({@code >>>>>}), 0 for {@code <<<<<}. */
    public static final int DENY_OVERRIDES_ALLOW = 13;
    /**
     * Max extract/retrieve batch the player may configure for this face: {@code batch.default + upgrades}, then clamped
     * by datapack {@code batch.max} when that value is non-negative.
     */
    public static final int EXTRACT_BATCH_CAP = 14;
    public static final int COUNT = 15;

    /** FLAGS bit: routing row active ({@link net.unfamily.another_dynamics.duct.NodeMode#usesRouting()}). */
    public static final int FLAG_ROUTING_ACTIVE = 1;
    /** FLAGS bit: deny/allow list row active ({@link net.unfamily.another_dynamics.duct.NodeMode#usesItemFilterConfig()}). */
    public static final int FLAG_FILTERS_ACTIVE = 2;

    private DuctMenuSync() {}
}
