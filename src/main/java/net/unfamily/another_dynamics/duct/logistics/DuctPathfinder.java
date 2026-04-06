package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.unfamily.another_dynamics.duct.DuctConnectable;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;

/**
 * Shortest paths on the duct-only subgraph. Pathfinding uses one edge cost per adjacent duct pair; billed travel time
 * uses {@link #pathTravelTicks}: {@code speed} ticks per duct <strong>block</strong> on the path (endpoints included),
 * so {@code N} collinear ducts yield {@code N} segments in that cost model.
 */
public final class DuctPathfinder {
    private DuctPathfinder() {}

    /** Datapack {@code speed}: ticks multiplied by path duct count in {@link #pathTravelTicks} (minimum 1). */
    public static long edgeTravelTicks(DuctItemTransportSpec spec) {
        int ticks = spec.effectiveSpeed(spec.speedDefault());
        return Math.max(1L, ticks);
    }

    public static Set<BlockPos> connectedDucts(Level level, BlockPos start, DuctNetworkType network) {
        Set<BlockPos> out = new HashSet<>();
        if (!DuctConnectable.isSameNetwork(level.getBlockState(start).getBlock(), network)) {
            return out;
        }
        ArrayList<BlockPos> q = new ArrayList<>();
        q.add(start);
        out.add(start);
        while (!q.isEmpty()) {
            BlockPos p = q.remove(q.size() - 1);
            for (BlockPos n : neighbors6(p)) {
                if (out.contains(n)) {
                    continue;
                }
                if (DuctConnectable.isSameNetwork(level.getBlockState(n).getBlock(), network)) {
                    out.add(n);
                    q.add(n);
                }
            }
        }
        return out;
    }

    private static List<BlockPos> neighbors6(BlockPos p) {
        return List.of(
                p.north(),
                p.south(),
                p.east(),
                p.west(),
                p.above(),
                p.below());
    }

    /**
     * Dijkstra from {@code from} to {@code to} (both must be ducts). Edge weight = travel ticks per hop.
     */
    public static Optional<List<BlockPos>> shortestPath(
            Level level, BlockPos from, BlockPos to, DuctItemTransportSpec spec, DuctNetworkType network) {
        if (!from.equals(to) && !DuctConnectable.isSameNetwork(level.getBlockState(from).getBlock(), network)) {
            return Optional.empty();
        }
        if (!DuctConnectable.isSameNetwork(level.getBlockState(to).getBlock(), network)) {
            return Optional.empty();
        }
        long w = edgeTravelTicks(spec);
        record Node(BlockPos p, long d) {}
        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingLong(Node::d));
        Map<BlockPos, Long> best = new HashMap<>();
        Map<BlockPos, BlockPos> prev = new HashMap<>();
        pq.add(new Node(from, 0));
        best.put(from, 0L);
        while (!pq.isEmpty()) {
            Node cur = pq.poll();
            if (cur.d != best.getOrDefault(cur.p, Long.MAX_VALUE)) {
                continue;
            }
            if (cur.p.equals(to)) {
                break;
            }
            for (BlockPos n : neighbors6(cur.p)) {
                if (!DuctConnectable.isSameNetwork(level.getBlockState(n).getBlock(), network)) {
                    continue;
                }
                long nd = cur.d + w;
                if (nd < best.getOrDefault(n, Long.MAX_VALUE)) {
                    best.put(n, nd);
                    prev.put(n, cur.p);
                    pq.add(new Node(n, nd));
                }
            }
        }
        if (!best.containsKey(to)) {
            return Optional.empty();
        }
        ArrayList<BlockPos> rev = new ArrayList<>();
        BlockPos c = to;
        while (c != null) {
            rev.add(c);
            if (c.equals(from)) {
                break;
            }
            c = prev.get(c);
        }
        if (rev.isEmpty() || !rev.get(rev.size() - 1).equals(from)) {
            return Optional.empty();
        }
        List<BlockPos> path = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) {
            path.add(rev.get(i));
        }
        return Optional.of(path);
    }

    /**
     * Total travel ticks along an already-resolved path: {@code speed} × number of duct blocks on the path (each
     * endpoint duct counts; {@code N} ducts in the chain → {@code N}×{@code speed}, not {@code N−1}).
     */
    public static long pathTravelTicks(List<BlockPos> path, DuctItemTransportSpec spec) {
        if (path == null || path.isEmpty()) {
            return 0L;
        }
        long w = edgeTravelTicks(spec);
        return w * path.size();
    }

    /** Same ticks as {@link #pathTravelTicks} on the shortest path, or empty if unreachable. */
    public static OptionalLong distance(Level level, BlockPos from, BlockPos to, DuctItemTransportSpec spec, DuctNetworkType network) {
        Optional<List<BlockPos>> path = shortestPath(level, from, to, spec, network);
        if (path.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(pathTravelTicks(path.get(), spec));
    }
}
