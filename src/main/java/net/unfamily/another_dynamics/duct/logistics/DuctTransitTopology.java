package net.unfamily.another_dynamics.duct.logistics;

import java.util.List;
import java.util.OptionalInt;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
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

    /**
     * {@code true} when {@code ductPos} is the path node where the in-flight item is right now (matches client ghost
     * position anchor), not merely any duct along the route.
     */
    public static boolean isTransitItemAtDuct(OutboundShipment s, BlockPos ductPos) {
        if (ductPos == null || s == null || s.ductPath == null || s.ductPath.isEmpty()) {
            return false;
        }
        return ductPos.equals(currentDuctPos(s));
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
    /**
     * Edge index where {@code removedPos} lies on {@code path} (as endpoint of edge {@code k} or {@code k+1}).
     */
    public static OptionalInt edgeIndexForBlockOnPath(List<BlockPos> path, BlockPos removedPos) {
        if (path == null || removedPos == null || path.size() < 2) {
            return OptionalInt.empty();
        }
        for (int k = 0; k < path.size() - 1; k++) {
            if (path.get(k).equals(removedPos) || path.get(k + 1).equals(removedPos)) {
                return OptionalInt.of(k);
            }
        }
        return OptionalInt.empty();
    }

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
     * When {@link #firstBrokenPathEdge} reports {@code edgeIndex}, returns the path position that is no longer a duct
     * block (the removed/broken duct on that edge).
     */
    public static Optional<BlockPos> missingDuctOnBrokenEdge(Level level, List<BlockPos> path, int edgeIndex) {
        if (path == null || edgeIndex < 0 || edgeIndex >= path.size() - 1) {
            return Optional.empty();
        }
        BlockPos a = path.get(edgeIndex);
        BlockPos b = path.get(edgeIndex + 1);
        boolean aDuct = level.getBlockEntity(a) instanceof DuctBlockEntity;
        boolean bDuct = level.getBlockEntity(b) instanceof DuctBlockEntity;
        if (aDuct && !bDuct) {
            return Optional.of(b);
        }
        if (!aDuct && bDuct) {
            return Optional.of(a);
        }
        if (!aDuct) {
            return Optional.of(a);
        }
        if (!bDuct) {
            return Optional.of(b);
        }
        return Optional.of(b);
    }

    /** World position for dropping in-flight items (duct center along current travel progress). */
    public static Vec3 transitDropPosition(OutboundShipment s) {
        if (s.ductPath == null || s.ductPath.isEmpty()) {
            return Vec3.atCenterOf(s.refundDuct);
        }
        int idx = Math.min(s.currentPathIndex(), s.ductPath.size() - 1);
        BlockPos p = s.ductPath.get(idx);
        return Vec3.atCenterOf(p);
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
