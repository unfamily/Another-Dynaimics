package net.unfamily.another_dynamics.duct.project;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;

/** Last computed node-preview mask per position (client + server) to avoid redundant block updates. */
final class ProjectDuctNodePreviewTracker {
    private static final int UNSET = Integer.MIN_VALUE;
    private static final Long2IntOpenHashMap LAST = new Long2IntOpenHashMap();

    static {
        LAST.defaultReturnValue(UNSET);
    }

    private ProjectDuctNodePreviewTracker() {}

    static boolean noteChanged(BlockPos pos, int mask) {
        long key = pos.asLong();
        int previous = LAST.get(key);
        if (previous == mask) {
            return false;
        }
        if (mask == 0) {
            LAST.remove(key);
        } else {
            LAST.put(key, mask);
        }
        return true;
    }

    static void remove(BlockPos pos) {
        LAST.remove(pos.asLong());
    }
}
