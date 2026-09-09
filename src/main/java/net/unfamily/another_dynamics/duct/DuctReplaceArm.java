package net.unfamily.another_dynamics.duct;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Server-side Shift-replace arm: after a successful single replace (stage 1), further Shift+same duct item clicks
 * on the same block within {@link #ARM_TICKS} escalate to project convert (stage 2) then mass definitive replace
 * (stage 3). Same-type clicks are accepted while the arm is valid so the chain is not broken by place-fallback.
 */
public final class DuctReplaceArm {
    /** ~3 seconds at 20 TPS. */
    public static final int ARM_TICKS = 60;

    private static final Map<UUID, Arm> BY_PLAYER = new ConcurrentHashMap<>();

    private DuctReplaceArm() {}

    public record Arm(
            ResourceKey<Level> dimension, BlockPos pos, String targetLogicalId, int stage, long armedUntilGameTime) {}

    public static void arm(
            ServerPlayer player, ServerLevel level, BlockPos pos, String targetLogicalId, int stage) {
        armUntil(player, level, pos, targetLogicalId, stage, level.getGameTime() + ARM_TICKS);
    }

    public static void armUntil(
            ServerPlayer player,
            ServerLevel level,
            BlockPos pos,
            String targetLogicalId,
            int stage,
            long armedUntilGameTime) {
        BY_PLAYER.put(
                player.getUUID(),
                new Arm(
                        level.dimension(),
                        pos.immutable(),
                        DuctIds.normalize(targetLogicalId),
                        stage,
                        armedUntilGameTime));
    }

    public static void clear(ServerPlayer player) {
        BY_PLAYER.remove(player.getUUID());
    }

    /**
     * Valid arm for this player/pos/item logical id, or null if expired / mismatch.
     */
    @Nullable
    public static Arm matching(ServerPlayer player, ServerLevel level, BlockPos pos, String logicalId) {
        Arm arm = BY_PLAYER.get(player.getUUID());
        if (arm == null) {
            return null;
        }
        if (level.getGameTime() > arm.armedUntilGameTime()) {
            BY_PLAYER.remove(player.getUUID(), arm);
            return null;
        }
        if (!arm.dimension().equals(level.dimension())
                || !arm.pos().equals(pos)
                || !arm.targetLogicalId().equals(DuctIds.normalize(logicalId))) {
            return null;
        }
        return arm;
    }
}
