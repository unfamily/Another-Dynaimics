package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.duct.DuctConnectable;
import net.unfamily.another_dynamics.duct.DuctNetworkType;

/**
 * Per-level cache for duct pipe topology ({@link DuctPathfinder#connectedDucts} and hop distances).
 * Invalidated only on structural changes (place/break duct, wrench disconnect, mask refresh), not on redstone or GUI
 * node settings.
 */
public final class DuctNetworkCache {
    private static final Map<ServerLevel, LevelCache> BY_LEVEL = new WeakHashMap<>();

    private DuctNetworkCache() {}

    /** Invalidate all network types on this level. */
    public static void invalidate(ServerLevel level) {
        LevelCache cache = BY_LEVEL.get(level);
        if (cache != null) {
            cache.invalidateAll();
        }
        DuctRoutingEndpointIndex.onTopologyInvalidated(level);
    }

    /** Invalidate one network type (and radioactive gas subgraph when {@code GAS}). */
    public static void invalidate(ServerLevel level, DuctNetworkType network) {
        LevelCache cache = BY_LEVEL.get(level);
        if (cache != null) {
            cache.invalidate(network);
        }
        DuctRoutingEndpointIndex.onTopologyInvalidated(level);
    }

    public static Set<BlockPos> connectedDucts(ServerLevel level, BlockPos start, DuctNetworkType network) {
        NetworkComponent comp = componentContaining(level, start, network, false);
        return comp != null ? comp.members : Set.of();
    }

    /** Gas ducts reachable only through radioactive-capable pipe edges. */
    public static Set<BlockPos> connectedRadioactiveGasDucts(ServerLevel level, BlockPos start) {
        NetworkComponent comp = componentContaining(level, start, DuctNetworkType.GAS, true);
        return comp != null ? comp.members : Set.of();
    }

    public static OptionalLong hopDistance(ServerLevel level, BlockPos from, BlockPos to, DuctNetworkType network) {
        return hopDistance(level, from, to, network, false);
    }

    public static OptionalLong hopDistance(
            ServerLevel level, BlockPos from, BlockPos to, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        if (from.equals(to)) {
            return OptionalLong.of(0L);
        }
        NetworkComponent comp = componentContaining(level, from, network, radioactiveGasSubgraph);
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

    /**
     * Travel ticks for routing tie-breaks: {@code edgeTicksPerBlock} × path duct count (same metric as
     * {@link DuctPathfinder#distance} with uniform edge weights).
     */
    public static OptionalLong routingTravelTicks(
            ServerLevel level, BlockPos from, BlockPos to, long edgeTicksPerBlock, DuctNetworkType network) {
        return routingTravelTicks(level, from, to, edgeTicksPerBlock, network, false);
    }

    public static OptionalLong routingTravelTicks(
            ServerLevel level,
            BlockPos from,
            BlockPos to,
            long edgeTicksPerBlock,
            DuctNetworkType network,
            boolean radioactiveGasSubgraph) {
        OptionalLong hops = hopDistance(level, from, to, network, radioactiveGasSubgraph);
        if (hops.isEmpty()) {
            return OptionalLong.empty();
        }
        long w = Math.max(0L, edgeTicksPerBlock);
        return OptionalLong.of(hops.getAsLong() * w);
    }

    public static Optional<List<BlockPos>> shortestPath(
            ServerLevel level, BlockPos from, BlockPos to, DuctNetworkType network) {
        return shortestPath(level, from, to, network, false);
    }

    public static Optional<List<BlockPos>> shortestPath(
            ServerLevel level, BlockPos from, BlockPos to, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        if (from.equals(to)) {
            return Optional.of(List.of(from));
        }
        NetworkComponent comp = componentContaining(level, from, network, radioactiveGasSubgraph);
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

    private static NetworkComponent componentContaining(
            ServerLevel level, BlockPos start, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        if (!isNetworkMember(level, start, network, radioactiveGasSubgraph)) {
            return null;
        }
        NetworkState st = stateFor(level, network, radioactiveGasSubgraph);
        NetworkComponent existing = st.memberToComponent.get(start);
        if (existing != null && existing.topologyGeneration == st.topologyGeneration) {
            return existing;
        }
        Set<BlockPos> members = discoverComponent(level, start, network, radioactiveGasSubgraph);
        if (members.isEmpty()) {
            return null;
        }
        NetworkComponent comp =
                new NetworkComponent(members, st.topologyGeneration, network, radioactiveGasSubgraph);
        for (BlockPos p : members) {
            st.memberToComponent.put(p, comp);
        }
        return comp;
    }

    private static Set<BlockPos> discoverComponent(
            ServerLevel level, BlockPos start, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        Set<BlockPos> out = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        out.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (BlockPos n : neighbors6(p)) {
                if (out.contains(n)) {
                    continue;
                }
                if (!isPipeEdge(level, p, n, network, radioactiveGasSubgraph)) {
                    continue;
                }
                out.add(n);
                queue.add(n);
            }
        }
        return out;
    }

    private static boolean isNetworkMember(
            ServerLevel level, BlockPos pos, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        if (!DuctConnectable.isSameNetwork(level, pos, network)) {
            return false;
        }
        if (radioactiveGasSubgraph && network == DuctNetworkType.GAS) {
            return DuctPathfinder.gasDuctAllowsRadioactiveAt(level, pos);
        }
        return true;
    }

    private static boolean isPipeEdge(
            ServerLevel level, BlockPos from, BlockPos to, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        if (!DuctPathfinder.isPipeEdge(level, from, to, network)) {
            return false;
        }
        if (radioactiveGasSubgraph && network == DuctNetworkType.GAS) {
            return DuctPathfinder.isRadioactiveGasPipeEdge(level, from, to);
        }
        return true;
    }

    private static BfsSnapshot snapshotFrom(ServerLevel level, NetworkComponent comp, BlockPos from) {
        NetworkState st = stateFor(level, comp.network, comp.radioactiveGasSubgraph);
        Map<BlockPos, BfsSnapshot> perSource =
                st.snapshotsByComponent.computeIfAbsent(comp, c -> new HashMap<>());
        BfsSnapshot snap = perSource.get(from);
        if (snap != null && snap.topologyGeneration == st.topologyGeneration) {
            return snap;
        }
        snap = bfsSnapshot(level, from, comp.members, st.topologyGeneration, comp.network, comp.radioactiveGasSubgraph);
        perSource.put(from, snap);
        return snap;
    }

    private static BfsSnapshot bfsSnapshot(
            ServerLevel level,
            BlockPos from,
            Set<BlockPos> members,
            int topologyGeneration,
            DuctNetworkType network,
            boolean radioactiveGasSubgraph) {
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
                if (!isPipeEdge(level, cur, n, network, radioactiveGasSubgraph)) {
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

    private static NetworkState stateFor(ServerLevel level, DuctNetworkType network, boolean radioactiveGasSubgraph) {
        LevelCache levelCache = BY_LEVEL.computeIfAbsent(level, k -> new LevelCache());
        return levelCache.state(network, radioactiveGasSubgraph);
    }

    private static final class LevelCache {
        private final EnumMap<DuctNetworkType, NetworkState> standard = new EnumMap<>(DuctNetworkType.class);
        private NetworkState radioactiveGas;

        NetworkState state(DuctNetworkType network, boolean radioactiveGasSubgraph) {
            if (radioactiveGasSubgraph && network == DuctNetworkType.GAS) {
                if (radioactiveGas == null) {
                    radioactiveGas = new NetworkState(DuctNetworkType.GAS, true);
                }
                return radioactiveGas;
            }
            return standard.computeIfAbsent(network, n -> new NetworkState(n, false));
        }

        void invalidateAll() {
            for (NetworkState st : standard.values()) {
                st.invalidate();
            }
            if (radioactiveGas != null) {
                radioactiveGas.invalidate();
            }
        }

        void invalidate(DuctNetworkType network) {
            NetworkState st = standard.get(network);
            if (st != null) {
                st.invalidate();
            }
            if (network == DuctNetworkType.GAS && radioactiveGas != null) {
                radioactiveGas.invalidate();
            }
        }
    }

    private static final class NetworkState {
        final DuctNetworkType network;
        final boolean radioactiveGasSubgraph;
        int topologyGeneration;
        final Map<BlockPos, NetworkComponent> memberToComponent = new HashMap<>();
        final Map<NetworkComponent, Map<BlockPos, BfsSnapshot>> snapshotsByComponent = new HashMap<>();

        NetworkState(DuctNetworkType network, boolean radioactiveGasSubgraph) {
            this.network = network;
            this.radioactiveGasSubgraph = radioactiveGasSubgraph;
        }

        void invalidate() {
            topologyGeneration++;
            memberToComponent.clear();
            snapshotsByComponent.clear();
        }
    }

    private static final class NetworkComponent {
        final DuctNetworkType network;
        final boolean radioactiveGasSubgraph;
        final Set<BlockPos> members;
        final int topologyGeneration;

        NetworkComponent(Set<BlockPos> members, int topologyGeneration, DuctNetworkType network, boolean radioactiveGasSubgraph) {
            this.members = members;
            this.topologyGeneration = topologyGeneration;
            this.network = network;
            this.radioactiveGasSubgraph = radioactiveGasSubgraph;
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
