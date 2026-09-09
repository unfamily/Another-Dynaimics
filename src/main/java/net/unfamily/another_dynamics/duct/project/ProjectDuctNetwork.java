package net.unfamily.another_dynamics.duct.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.another_dynamics.registry.ModBlocks;

/**
 * Connected component of {@link ProjectDuctBlock} positions (BFS, deterministic order from anchor).
 */
public final class ProjectDuctNetwork {
    private ProjectDuctNetwork() {}

    public static boolean isProjectDuct(Block block) {
        return block == ModBlocks.PROJECT_DUCT.get();
    }

    public static List<BlockPos> connectedOrdered(Level level, BlockPos start) {
        return connectedOrdered(level, start, Integer.MAX_VALUE);
    }

    /**
     * BFS from {@code start}. Stops after collecting {@code maxCollect} positions (inclusive) so huge
     * networks do not freeze the server when only a batch will be converted.
     */
    public static List<BlockPos> connectedOrdered(Level level, BlockPos start, int maxCollect) {
        List<BlockPos> out = new ArrayList<>();
        if (level == null || start == null || maxCollect <= 0
                || !isProjectDuct(level.getBlockState(start).getBlock())) {
            return out;
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && out.size() < maxCollect) {
            BlockPos current = queue.removeFirst();
            out.add(current);
            if (out.size() >= maxCollect) {
                break;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (seen.contains(neighbor)) {
                    continue;
                }
                if (!arePipeConnected(level, current, neighbor, direction)) {
                    continue;
                }
                seen.add(neighbor);
                queue.addLast(neighbor);
            }
        }
        return out;
    }

    /** {@code true} when {@code a} and {@code b} are project ducts in the same pipe-connected component. */
    public static boolean sameConnectedComponent(Level level, BlockPos a, BlockPos b) {
        if (level == null || a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return isProjectDuct(level.getBlockState(a).getBlock());
        }
        if (!isProjectDuct(level.getBlockState(a).getBlock()) || !isProjectDuct(level.getBlockState(b).getBlock())) {
            return false;
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(a);
        seen.add(a);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (current.equals(b)) {
                return true;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (seen.contains(neighbor)) {
                    continue;
                }
                if (!arePipeConnected(level, current, neighbor, direction)) {
                    continue;
                }
                seen.add(neighbor);
                queue.addLast(neighbor);
            }
        }
        return false;
    }

    /** True when two adjacent project ducts share an connected (non-wrench-disconnected) pipe face. */
    public static boolean arePipeConnected(Level level, BlockPos from, BlockPos to, Direction fromTo) {
        if (!isProjectDuct(level.getBlockState(from).getBlock()) || !isProjectDuct(level.getBlockState(to).getBlock())) {
            return false;
        }
        BlockState fromState = level.getBlockState(from);
        BlockState toState = level.getBlockState(to);
        int bit = 1 << fromTo.ordinal();
        int oppositeBit = 1 << fromTo.getOpposite().ordinal();
        return (ProjectDuctBlock.connectionMask(fromState) & bit) != 0
                && (ProjectDuctBlock.disconnectedMask(fromState) & bit) == 0
                && (ProjectDuctBlock.disconnectedMask(toState) & oppositeBit) == 0;
    }
}
