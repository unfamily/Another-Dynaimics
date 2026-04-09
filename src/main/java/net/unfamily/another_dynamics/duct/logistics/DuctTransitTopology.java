package net.unfamily.another_dynamics.duct.logistics;

import java.util.List;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.unfamily.another_dynamics.duct.DuctPipeAdjacency;

/**
 * Validates duct-only path edges against the live world for in-flight shipments.
 */
public final class DuctTransitTopology {
    private DuctTransitTopology() {}

    public static BlockPos currentDuctPos(OutboundShipment s) {
        if (s.ductPath.isEmpty()) {
            return s.refundDuct;
        }
        return s.ductPath.get(s.currentPathIndex());
    }

    public static boolean isValidNeighborDuctEdge(Level level, BlockPos a, BlockPos b) {
        if (!level.isLoaded(a) || !level.isLoaded(b)) {
            return false;
        }
        return neighborItemDuctEdgeWhenChunksPresent(level, a, b);
    }

    /** Adjacent item-duct edge; both positions must be in loaded chunks. */
    private static boolean neighborItemDuctEdgeWhenChunksPresent(Level level, BlockPos a, BlockPos b) {
        return DuctPipeAdjacency.areItemPipeNeighbors(level, a, b);
    }

    /**
     * Minimum {@code k} where the edge {@code path[k]}-{@code path[k+1]} is invalid; empty if path has fewer than 2
     * nodes or all checked edges valid. Unloaded chunks are skipped (not treated as broken) so reload / sim order does
     * not disperse in-flight shipments.
     */
    public static OptionalInt firstBrokenPathEdge(Level level, List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return OptionalInt.empty();
        }
        for (int k = 0; k < path.size() - 1; k++) {
            BlockPos a = path.get(k);
            BlockPos b = path.get(k + 1);
            if (!level.isLoaded(a) || !level.isLoaded(b)) {
                continue;
            }
            if (!neighborItemDuctEdgeWhenChunksPresent(level, a, b)) {
                return OptionalInt.of(k);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Same contract as {@link #firstBrokenPathEdge} but for {@link net.unfamily.another_dynamics.duct.DuctNetworkType#FLUID}
     * pipe adjacency.
     */
    public static OptionalInt firstBrokenFluidPathEdge(Level level, List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return OptionalInt.empty();
        }
        for (int k = 0; k < path.size() - 1; k++) {
            BlockPos a = path.get(k);
            BlockPos b = path.get(k + 1);
            if (!level.isLoaded(a) || !level.isLoaded(b)) {
                continue;
            }
            if (!DuctPipeAdjacency.areFluidPipeNeighbors(level, a, b)) {
                return OptionalInt.of(k);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Same contract as {@link #firstBrokenPathEdge} but for {@link net.unfamily.another_dynamics.duct.DuctNetworkType#GAS}
     * pipe adjacency.
     */
    public static OptionalInt firstBrokenGasPathEdge(Level level, List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return OptionalInt.empty();
        }
        for (int k = 0; k < path.size() - 1; k++) {
            BlockPos a = path.get(k);
            BlockPos b = path.get(k + 1);
            if (!level.isLoaded(a) || !level.isLoaded(b)) {
                continue;
            }
            if (!DuctPipeAdjacency.areGasPipeNeighbors(level, a, b)) {
                return OptionalInt.of(k);
            }
        }
        return OptionalInt.empty();
    }
}
