package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Spreads duct face action ticks across game time so many extractors on the same network do not all run heavy
 * routing work on the same server tick.
 */
public final class DuctActionScheduling {
    private DuctActionScheduling() {}

    public static int staggerOffset(BlockPos pos, Direction face, int rate) {
        if (rate <= 1) {
            return 0;
        }
        long mixed = pos.asLong() ^ ((long) face.ordinal() * 0x9E3779B97F4A7C15L);
        return Math.floorMod(mixed, rate);
    }

    /** True when this face may fire its rate-limited action on the current game tick. */
    public static boolean isStaggerSlot(ServerLevel level, BlockPos pos, Direction face, int rate) {
        if (rate <= 1) {
            return true;
        }
        return Math.floorMod(level.getGameTime() + staggerOffset(pos, face, rate), rate) == 0;
    }
}
