package net.unfamily.another_dynamics.client.transit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Client-only smooth transit timing (Mekanism-style): advance once per world tick between sync packets, never move
 * backward when a late update arrives.
 */
public final class DuctTransitMotion {
    private static final Map<Long, MotionState> BY_LEG = new ConcurrentHashMap<>();

    private DuctTransitMotion() {}

    public static long legKey(long journeyStartGameTime, int totalTravelTicks, List<BlockPos> ductPath) {
        return legKey(journeyStartGameTime, totalTravelTicks, ductPath, 0L);
    }

    public static long legKey(
            long journeyStartGameTime, int totalTravelTicks, List<BlockPos> ductPath, long incomingReservationId) {
        long h = 0x9E3779B97F4A7C15L;
        h ^= journeyStartGameTime;
        h ^= (long) totalTravelTicks << 32;
        h ^= incomingReservationId;
        if (ductPath != null && !ductPath.isEmpty()) {
            h ^= ductPath.getFirst().asLong();
            h ^= Long.rotateLeft(ductPath.getLast().asLong(), 16);
            h ^= ductPath.size();
        }
        return h;
    }

    /**
     * Elapsed ticks on the current leg, including {@code partialTick} within the current frame. Clamped to
     * {@code [0, totalTravelTicks]}.
     */
    public static float smoothElapsedTicks(
            long legKey,
            int totalTravelTicks,
            int travelTicks,
            @Nullable Level level,
            float partialTick) {
        if (totalTravelTicks <= 0) {
            return 0f;
        }
        float serverElapsed = Math.max(0f, totalTravelTicks - travelTicks);
        if (level == null) {
            return Math.min(totalTravelTicks, serverElapsed + partialTick);
        }
        long gameTime = level.getGameTime();
        MotionState state =
                BY_LEG.compute(
                        legKey,
                        (k, prev) -> {
                            if (prev == null) {
                                return new MotionState(serverElapsed, gameTime);
                            }
                            prev.elapsed = Math.max(prev.elapsed, serverElapsed);
                            if (gameTime > prev.lastGameTime) {
                                prev.elapsed += gameTime - prev.lastGameTime;
                                prev.lastGameTime = gameTime;
                            }
                            return prev;
                        });
        float out = state.elapsed + partialTick;
        return Mth.clamp(out, 0f, totalTravelTicks);
    }

    public static void removeLeg(long legKey) {
        BY_LEG.remove(legKey);
    }

    private static final class MotionState {
        float elapsed;
        long lastGameTime;

        MotionState(float elapsed, long lastGameTime) {
            this.elapsed = elapsed;
            this.lastGameTime = lastGameTime;
        }
    }
}
