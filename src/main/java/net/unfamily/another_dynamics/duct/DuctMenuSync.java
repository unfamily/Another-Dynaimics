package net.unfamily.another_dynamics.duct;

/**
 * Indices for {@link net.minecraft.world.inventory.ContainerData} synced with {@link DuctNodeMenu}.
 */
public final class DuctMenuSync {
    public static final int NODE_MODE = 0;
    public static final int ROUTING_MODE = 1;
    public static final int PRIORITY = 2;
    public static final int AMOUNT_FIELD = 3;
    public static final int CHANNEL = 4;
    public static final int REDSTONE_MODE = 5;
    public static final int FLAGS = 6;
    public static final int POS_X = 7;
    public static final int POS_Y = 8;
    public static final int POS_Z = 9;
    public static final int COUNT = 10;

    /** FLAGS bit: routing row active (extractor-like modes). */
    public static final int FLAG_ROUTING_ACTIVE = 1;

    private DuctMenuSync() {}
}
