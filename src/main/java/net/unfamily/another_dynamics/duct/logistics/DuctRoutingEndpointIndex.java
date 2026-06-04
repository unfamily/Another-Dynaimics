package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctChannelPolicy;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.logistics.DuctSameBlockRouting;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;

import org.jetbrains.annotations.Nullable;

/**
 * Cached inventory of duct faces that can receive routed payloads on a connected component. Built once per
 * topology/settings generation and shared by all extractors on the same network.
 */
public final class DuctRoutingEndpointIndex {
    private static final Map<ServerLevel, LevelCache> BY_LEVEL = new WeakHashMap<>();

    private DuctRoutingEndpointIndex() {}

    public record RoutingEndpoint(
            BlockPos pos, Direction face, int channelLetter, int insertionPriority, NodeMode nodeMode) {}

    public record ScoredEndpoint(RoutingEndpoint endpoint, long distTicks) {}

    public static void onTopologyInvalidated(ServerLevel level) {
        LevelCache cache = BY_LEVEL.get(level);
        if (cache != null) {
            cache.topologyGeneration++;
            cache.rawByNetworkKey.clear();
        }
    }

    public static void onSettingsChanged(ServerLevel level) {
        LevelCache cache = BY_LEVEL.computeIfAbsent(level, k -> new LevelCache());
        cache.settingsGeneration++;
        cache.rawByNetworkKey.clear();
    }

    public static List<ScoredEndpoint> listScoredExtractDestinations(
            ServerLevel level,
            BlockPos sourcePos,
            DuctNetworkType networkType,
            DuctTransportKind transportKind,
            long edgeTicksPerBlock,
            int sourceChannel,
            boolean inboundDeliveryModes,
            boolean allowSelfDestination,
            @Nullable Direction forbidSelfDestFace,
            boolean allowSelfFeed,
            Direction sourceFace,
            boolean radioactiveGasSubgraph) {
        Set<BlockPos> members = radioactiveGasSubgraph
                ? DuctNetworkCache.connectedRadioactiveGasDucts(level, sourcePos)
                : DuctNetworkCache.connectedDucts(level, sourcePos, networkType);
        if (members.isEmpty()) {
            return List.of();
        }
        List<RoutingEndpoint> raw =
                getOrBuildRaw(level, members, networkType, transportKind, radioactiveGasSubgraph);
        List<ScoredEndpoint> scored = new ArrayList<>();
        for (RoutingEndpoint ep : raw) {
            if (ep.pos.equals(sourcePos) && forbidSelfDestFace != null && ep.face == forbidSelfDestFace) {
                continue;
            }
            if (DuctSameBlockRouting.skipSameBlockDestFace(sourcePos, sourceFace, ep.pos, ep.face, allowSelfFeed)) {
                continue;
            }
            if (!DuctChannelPolicy.sameChannel(ep.channelLetter, sourceChannel)) {
                continue;
            }
            if (inboundDeliveryModes) {
                if (!DuctTargetSelector.isNetworkInboundDeliveryMode(ep.nodeMode)) {
                    continue;
                }
            } else if (ep.nodeMode != NodeMode.NONE
                    && ep.nodeMode != NodeMode.FILTERING_INSERTION
                    && ep.nodeMode != NodeMode.EXTRACTION_FILTERING) {
                continue;
            }
            if (!(level.getBlockEntity(ep.pos) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            DuctFaceNode node = faceNodeForKind(destBe, ep.face, transportKind);
            if (!node.eligibilityMode.isInsertable()) {
                continue;
            }
            if (!DuctRedstoneLogic.isFaceTransportActive(level, ep.pos, destBe.getFaceLanes(ep.face).redstoneMode)) {
                continue;
            }
            long dist =
                    ep.pos.equals(sourcePos)
                            ? 0L
                            : DuctNetworkCache.routingTravelTicks(
                                            level,
                                            sourcePos,
                                            ep.pos,
                                            edgeTicksPerBlock,
                                            networkType,
                                            radioactiveGasSubgraph)
                                    .orElse(-1L);
            if (dist < 0L) {
                continue;
            }
            scored.add(new ScoredEndpoint(ep, dist));
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort(Comparator.comparingInt((ScoredEndpoint s) -> s.endpoint().insertionPriority()).reversed());
        return scored;
    }

    private static List<RoutingEndpoint> getOrBuildRaw(
            ServerLevel level,
            Set<BlockPos> members,
            DuctNetworkType networkType,
            DuctTransportKind transportKind,
            boolean radioactiveGasSubgraph) {
        LevelCache levelCache = BY_LEVEL.computeIfAbsent(level, k -> new LevelCache());
        long networkKey = networkCacheKey(members, networkType, transportKind, radioactiveGasSubgraph);
        CachedRaw cached = levelCache.rawByNetworkKey.get(networkKey);
        if (cached != null
                && cached.topologyGeneration == levelCache.topologyGeneration
                && cached.settingsGeneration == levelCache.settingsGeneration) {
            return cached.endpoints;
        }
        List<RoutingEndpoint> built = buildRawEndpoints(level, members, transportKind);
        levelCache.rawByNetworkKey.put(
                networkKey,
                new CachedRaw(levelCache.topologyGeneration, levelCache.settingsGeneration, built));
        return built;
    }

    private static long networkCacheKey(
            Set<BlockPos> members, DuctNetworkType networkType, DuctTransportKind kind, boolean radioactiveGas) {
        long hash = members.hashCode();
        hash = 31L * hash + networkType.ordinal();
        hash = 31L * hash + kind.ordinal();
        hash = 31L * hash + (radioactiveGas ? 1 : 0);
        return hash;
    }

    private static List<RoutingEndpoint> buildRawEndpoints(
            ServerLevel level, Set<BlockPos> members, DuctTransportKind kind) {
        List<RoutingEndpoint> out = new ArrayList<>();
        for (BlockPos pos : members) {
            if (!(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                continue;
            }
            int storageMask = be.getStorageMask();
            for (Direction face : Direction.values()) {
                if ((storageMask & (1 << face.ordinal())) == 0) {
                    continue;
                }
                if (!be.isTransportKindEnabled(face, kind)) {
                    continue;
                }
                DuctFaceNode node = faceNodeForKind(be, face, kind);
                out.add(
                        new RoutingEndpoint(
                                pos, face, node.channelLetter, node.insertionPriority, be.getFaceLanes(face).nodeMode));
            }
        }
        return out;
    }

    private static DuctFaceNode faceNodeForKind(DuctBlockEntity be, Direction face, DuctTransportKind kind) {
        return switch (kind) {
            case ITEM -> be.getFaceNode(face);
            case FLUID -> be.getFluidFaceNode(face);
            case GAS -> be.getGasFaceNode(face);
            case ENERGY, HEAT -> be.getFaceNode(face);
        };
    }

    private static final class LevelCache {
        int topologyGeneration;
        int settingsGeneration;
        final Map<Long, CachedRaw> rawByNetworkKey = new java.util.HashMap<>();
    }

    private static final class CachedRaw {
        final int topologyGeneration;
        final int settingsGeneration;
        final List<RoutingEndpoint> endpoints;

        CachedRaw(int topologyGeneration, int settingsGeneration, List<RoutingEndpoint> endpoints) {
            this.topologyGeneration = topologyGeneration;
            this.settingsGeneration = settingsGeneration;
            this.endpoints = endpoints;
        }
    }
}
