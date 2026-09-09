package net.unfamily.another_dynamics.duct.project;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps project-duct node-preview rendering in sync when neighbors change without expanding block states.
 */
public final class ProjectDuctVisualRefresh {
    private static final ThreadLocal<Boolean> REFRESHING = ThreadLocal.withInitial(() -> false);

    private ProjectDuctVisualRefresh() {}

    /** Updates pipe masks and requests a client model refresh when node-preview geometry changes. */
    public static void refreshAround(LevelAccessor level, BlockPos origin) {
        if (Boolean.TRUE.equals(REFRESHING.get())) {
            return;
        }
        REFRESHING.set(true);
        try {
            ProjectDuctBlock.updateConnectionsAround(level, origin);
            if (!(level instanceof Level world)) {
                return;
            }
            java.util.HashSet<BlockPos> positions = new java.util.HashSet<>();
            positions.add(origin);
            for (Direction direction : Direction.values()) {
                positions.add(origin.relative(direction));
            }
            for (BlockPos pos : positions) {
                BlockState state = world.getBlockState(pos);
                if (!(state.getBlock() instanceof ProjectDuctBlock)) {
                    continue;
                }
                int nodePreview = ProjectDuctBlock.effectiveNodePreviewMask(world, pos, state);
                if (ProjectDuctNodePreviewTracker.noteChanged(pos, nodePreview)) {
                    world.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
                }
            }
        } finally {
            REFRESHING.set(false);
        }
    }
}
