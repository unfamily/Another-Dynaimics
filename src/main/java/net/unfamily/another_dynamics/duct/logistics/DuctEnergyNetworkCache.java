package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.duct.DuctNetworkType;

/**
 * Per-level, per-tick cache for energy duct graph queries ({@link DuctPathfinder#connectedDucts} and hop distances).
 * Multiple extractors on the same network share one BFS per game tick instead of repeating full scans.
 */
public final class DuctEnergyNetworkCache {
    private static final Map<ServerLevel, LevelState> BY_LEVEL = new WeakHashMap<>();

    private DuctEnergyNetworkCache() {}

    /** Call when duct pipe/storage connectivity changes on this level. */
    public static void invalidate(ServerLevel level) {
        LevelState st = BY_LEVEL.get(level);
        if (st != null) {
            st.topologyGeneration++;
            st.memberToComponent.clear();
            st.snapshotsByComponent.clear();
        }
    }

    public static Set<BlockPos> connectedDucts(ServerLevel level, BlockPos start) {
        NetworkComponent comp = componentContaining(level, start);
        return comp != null ? comp.members : Set.of();
    }

    public static OptionalLong hopDistance(ServerLevel level, BlockPos from, BlockPos to) {
        if (from.equals(to)) {
            return OptionalLong.of(0L);
        }
        NetworkComponent comp = componentContaining(level, from);
        if (comp == null || !comp.members.contains(to)) {
            return OptionalLong.empty();
        }
        BfsSnapshot snap = snapshotFrom(level, comp, from);
        Long d = snap.distances.get(to);
        if (d == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(d);
    }

    public static Optional<List<BlockPos>> shortestPath(ServerLevel level, BlockPos from, BlockPos to) {
        if (from.equals(to)) {
            return Optional.of(List.of(from));
        }
        NetworkComponent comp = componentContaining(level, from);
        if (comp == null || !comp.members.contains(to)) {
            return Optional.empty();
        }
        BfsSnapshot snap = snapshotFrom(level, comp, from);
        if (!snap.distances.containsKey(to)) {
            return Optional.empty();
        }
        List<BlockPos> path = reconstructPath(from, to, snap.prev);
        return path.isEmpty() ? Optional.empty() : Optional.of(path);
    }

    private static NetworkComponent componentContaining(ServerLevel level, BlockPos start) {
        if (!DuctPathfinder.isEnergyNetworkMember(level, start)) {
            return null;
        }
        LevelState st = stateFor(level);
        NetworkComponent existing = st.memberToComponent.get(start);
        if (existing != null) {
            return existing;
        }
        Set<BlockPos> members = DuctPathfinder.connectedDucts(level, start, DuctNetworkType.ENERGY);
        if (members.isEmpty()) {
            return null;
        }
        NetworkComponent comp = new NetworkComponent(members, st.topologyGeneration);
        for (BlockPos p : members) {
            st.memberToComponent.put(p, comp);
        }
        return comp;
    }

    private static BfsSnapshot snapshotFrom(ServerLevel level, NetworkComponent comp, BlockPos from) {
        LevelState st = stateFor(level);
        Map<BlockPos, BfsSnapshot> perSource =
                st.snapshotsByComponent.computeIfAbsent(comp, c -> new HashMap<>());
        BfsSnapshot snap = perSource.get(from);
        if (snap != null && snap.topologyGeneration == st.topologyGeneration) {
            return snap;
        }
        snap = bfsSnapshot(level, from, comp.members, st.topologyGeneration);
        perSource.put(from, snap);
        return snap;
    }

    private static BfsSnapshot bfsSnapshot(ServerLevel level, BlockPos from, Set<BlockPos> members, int topologyGeneration) {
        Map<BlockPos, Long> distances = new HashMap<>();
        Map<BlockPos, BlockPos> prev = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(from);
        distances.put(from, 0L);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            long curDist = distances.get(cur);
            for (BlockPos n : neighbors6(cur)) {
                if (!members.contains(n) || distances.containsKey(n)) {
                    continue;
                }
                if (!DuctPathfinder.isEnergyPipeEdge(level, cur, n)) {
                    continue;
                }
                long nodeDist = curDist + 1L;
                distances.put(n, nodeDist);
                prev.put(n, cur);
                queue.add(n);
            }
        }
        // Match legacy shortestPath hop metric: path node count, not edge count.
        Map<BlockPos, Long> pathSizes = new HashMap<>();
        for (Map.Entry<BlockPos, Long> e : distances.entrySet()) {
            pathSizes.put(e.getKey(), e.getValue() + 1L);
        }
        return new BfsSnapshot(topologyGeneration, pathSizes, prev);
    }

    private static List<BlockPos> reconstructPath(BlockPos from, BlockPos to, Map<BlockPos, BlockPos> prev) {
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
            return List.of();
        }
        ArrayList<BlockPos> path = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) {
            path.add(rev.get(i));
        }
        return path;
    }

    private static List<BlockPos> neighbors6(BlockPos p) {
        return List.of(p.north(), p.south(), p.east(), p.west(), p.above(), p.below());
    }

    private static LevelState stateFor(ServerLevel level) {
        LevelState st = BY_LEVEL.computeIfAbsent(level, k -> new LevelState());
        long gt = level.getGameTime();
        if (st.tick != gt) {
            st.tick = gt;
            st.memberToComponent.clear();
            st.snapshotsByComponent.clear();
        }
        return st;
    }

    private static final class LevelState {
        long tick = -1L;
        int topologyGeneration;
        final Map<BlockPos, NetworkComponent> memberToComponent = new HashMap<>();
        final Map<NetworkComponent, Map<BlockPos, BfsSnapshot>> snapshotsByComponent = new HashMap<>();
    }

    private static final class NetworkComponent {
        final Set<BlockPos> members;
        final int topologyGeneration;

        NetworkComponent(Set<BlockPos> members, int topologyGeneration) {
            this.members = members;
            this.topologyGeneration = topologyGeneration;
        }
    }

    private static final class BfsSnapshot {
        final int topologyGeneration;
        final Map<BlockPos, Long> distances;
        final Map<BlockPos, BlockPos> prev;

        BfsSnapshot(int topologyGeneration, Map<BlockPos, Long> distances, Map<BlockPos, BlockPos> prev) {
            this.topologyGeneration = topologyGeneration;
            this.distances = distances;
            this.prev = prev;
        }
    }
}
