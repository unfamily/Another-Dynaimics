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

    /** {@code true} when every loaded edge on {@code path} is still a valid item-duct link. */
    public static boolean isItemPathIntact(Level level, List<BlockPos> path) {
        return firstBrokenPathEdge(level, path).isEmpty();
    }

    public static boolean isItemShipmentPathIntact(Level level, OutboundShipment s) {
        if (s == null || s.ductPath == null || s.ductPath.size() < 2) {
            return true;
        }
        return isItemPathIntact(level, s.ductPath);
    }

    /** Where the first broken edge lies relative to source/destination ducts on a scheduled path. */
    public enum PathBreakSite {
        INTACT,
        /** First path edge at {@code sourceDuct} (source node isolated from the network). */
        SOURCE_ENDPOINT,
        /** Last path edge at {@code destDuct} (destination node isolated from the network). */
        DEST_ENDPOINT,
        /** Any other broken edge (mid-route pipe, etc.). */
        MID_PATH
    }

    public static PathBreakSite classifyPathBreakSite(
            List<BlockPos> path, int brokenEdgeIdx, BlockPos sourceDuct, BlockPos destDuct) {
        if (path == null || path.size() < 2 || brokenEdgeIdx < 0 || brokenEdgeIdx >= path.size() - 1) {
            return PathBreakSite.MID_PATH;
        }
        int lastEdge = path.size() - 2;
        if (brokenEdgeIdx == 0
                && sourceDuct != null
                && path.get(0).equals(sourceDuct)) {
            return PathBreakSite.SOURCE_ENDPOINT;
        }
        if (brokenEdgeIdx == lastEdge
                && destDuct != null
                && path.get(path.size() - 1).equals(destDuct)) {
            return PathBreakSite.DEST_ENDPOINT;
        }
        return PathBreakSite.MID_PATH;
    }

    public static PathBreakSite classifyItemShipmentPathBreak(Level level, OutboundShipment s) {
        if (s == null || s.ductPath == null || s.ductPath.size() < 2) {
            return PathBreakSite.INTACT;
        }
        OptionalInt broken = firstBrokenPathEdge(level, s.ductPath);
        if (broken.isEmpty()) {
            return PathBreakSite.INTACT;
        }
        return classifyPathBreakSite(s.ductPath, broken.getAsInt(), s.refundDuct, s.destDuct);
    }

    public static PathBreakSite classifyPathBreakForWrenchEdge(
            List<BlockPos> path, BlockPos edgeA, BlockPos edgeB, BlockPos sourceDuct, BlockPos destDuct) {
        if (path == null || path.size() < 2 || edgeA == null || edgeB == null) {
            return PathBreakSite.MID_PATH;
        }
        for (int k = 0; k < path.size() - 1; k++) {
            BlockPos p0 = path.get(k);
            BlockPos p1 = path.get(k + 1);
            if ((p0.equals(edgeA) && p1.equals(edgeB)) || (p0.equals(edgeB) && p1.equals(edgeA))) {
                return classifyPathBreakSite(path, k, sourceDuct, destDuct);
            }
        }
        return PathBreakSite.MID_PATH;
    }

    public static PathBreakSite classifyFluidShipmentPathBreak(Level level, FluidTransitShipment s, BlockPos sourceDuct) {
        if (s == null || s.ductPath == null || s.ductPath.size() < 2) {
            return PathBreakSite.INTACT;
        }
        OptionalInt broken = firstBrokenFluidPathEdge(level, s.ductPath);
        if (broken.isEmpty()) {
            return PathBreakSite.INTACT;
        }
        BlockPos source = sourceDuct != null ? sourceDuct : s.ductPath.get(0);
        return classifyPathBreakSite(s.ductPath, broken.getAsInt(), source, s.destDuct);
    }

    public static PathBreakSite classifyGasShipmentPathBreak(Level level, GasTransitShipment s, BlockPos sourceDuct) {
        if (s == null || s.ductPath == null || s.ductPath.size() < 2) {
            return PathBreakSite.INTACT;
        }
        OptionalInt broken = firstBrokenGasPathEdge(level, s.ductPath);
        if (broken.isEmpty()) {
            return PathBreakSite.INTACT;
        }
        BlockPos source = sourceDuct != null ? sourceDuct : s.ductPath.get(0);
        return classifyPathBreakSite(s.ductPath, broken.getAsInt(), source, s.destDuct);
    }

    /**
     * Committed item pulls may still complete delivery when only the source node lost its network link; stall at dest on
     * failure.
     */
    public static boolean shouldAllowItemDeliveryDespiteBrokenPath(Level level, OutboundShipment s) {
        if (s == null) {
            return false;
        }
        PathBreakSite site = classifyItemShipmentPathBreak(level, s);
        return site == PathBreakSite.SOURCE_ENDPOINT
                && (s.sourceExtractCommitted || s.legacyPhysicalBuffer);
    }

    public static boolean shouldCancelCommittedItemTransitForPathBreak(Level level, OutboundShipment s) {
        PathBreakSite site = classifyItemShipmentPathBreak(level, s);
        return shouldCancelItemTransitForPathBreakSite(site, s);
    }

    public static boolean shouldCancelItemTransitForPathBreakSite(PathBreakSite site, OutboundShipment s) {
        if (site == PathBreakSite.INTACT) {
            return false;
        }
        if (site == PathBreakSite.SOURCE_ENDPOINT) {
            return !(s.sourceExtractCommitted || s.legacyPhysicalBuffer);
        }
        return true;
    }

    public static boolean shouldCancelFluidTransitForPathBreakSite(PathBreakSite site) {
        return site != PathBreakSite.INTACT && site != PathBreakSite.SOURCE_ENDPOINT;
    }

    public static boolean shouldCancelGasTransitForPathBreakSite(PathBreakSite site) {
        return site != PathBreakSite.INTACT && site != PathBreakSite.SOURCE_ENDPOINT;
    }

    /** Whether {@code path} contains the undirected edge between adjacent ducts {@code a} and {@code b}. */
    public static boolean pathUsesAdjacentEdge(List<BlockPos> path, BlockPos a, BlockPos b) {
        if (path == null || path.size() < 2 || a == null || b == null) {
            return false;
        }
        for (int k = 0; k < path.size() - 1; k++) {
            BlockPos p0 = path.get(k);
            BlockPos p1 = path.get(k + 1);
            if ((p0.equals(a) && p1.equals(b)) || (p0.equals(b) && p1.equals(a))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFluidPathIntact(Level level, List<BlockPos> path) {
        return firstBrokenFluidPathEdge(level, path).isEmpty();
    }

    public static boolean isGasPathIntact(Level level, List<BlockPos> path) {
        return firstBrokenGasPathEdge(level, path).isEmpty();
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
