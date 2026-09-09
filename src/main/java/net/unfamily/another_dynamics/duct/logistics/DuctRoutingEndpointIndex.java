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
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;

import org.jetbrains.annotations.Nullable;

/**
 * Cached inventory of duct faces that can receive or donate routed payloads on a connected component.
 * Built from {@link DuctNetworkCache#storageAttachmentMembers} (not the full pipe member set) and split by role
 * so query-time filtering stays thin. {@link NodeMode#NONE} is an active simple inventory node and remains in
 * extract/inbound/donor lists.
 */
public final class DuctRoutingEndpointIndex {
    private static final Map<ServerLevel, LevelCache> BY_LEVEL = new WeakHashMap<>();

    private DuctRoutingEndpointIndex() {}

    /** Which pre-filtered raw list to build / fetch. */
    public enum EndpointRole {
        /** Destinations for extract push: NONE, FILTERING_INSERTION, EXTRACTION_FILTERING. */
        EXTRACT_DEST,
        /** Stall / inbound delivery: extract set + RETRIEVING, RETRIEVING_EXTRACTION. */
        INBOUND_DEST,
        /** Retriever donors: NONE, FILTERING_INSERTION. */
        DONOR
    }

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
        EndpointRole role = inboundDeliveryModes ? EndpointRole.INBOUND_DEST : EndpointRole.EXTRACT_DEST;
        return listScored(
                level,
                sourcePos,
                networkType,
                transportKind,
                edgeTicksPerBlock,
                sourceChannel,
                role,
                forbidSelfDestFace,
                allowSelfFeed,
                sourceFace,
                radioactiveGasSubgraph,
                true);
    }

    /**
     * Scored donor faces for retriever pull (item/fluid/gas). Channel and redstone filtered here; capability
     * probes remain caller-side.
     */
    public static List<ScoredEndpoint> listScoredDonors(
            ServerLevel level,
            BlockPos retrieverPos,
            DuctNetworkType networkType,
            DuctTransportKind transportKind,
            long edgeTicksPerBlock,
            int retrieverChannel,
            @Nullable Direction forbidSelfDonorFace,
            boolean allowSelfDonor,
            Direction retrieverInventoryFace,
            boolean radioactiveGasSubgraph) {
        return listScored(
                level,
                retrieverPos,
                networkType,
                transportKind,
                edgeTicksPerBlock,
                retrieverChannel,
                EndpointRole.DONOR,
                forbidSelfDonorFace,
                allowSelfDonor,
                retrieverInventoryFace,
                radioactiveGasSubgraph,
                false);
    }

    private static List<ScoredEndpoint> listScored(
            ServerLevel level,
            BlockPos sourcePos,
            DuctNetworkType networkType,
            DuctTransportKind transportKind,
            long edgeTicksPerBlock,
            int sourceChannel,
            EndpointRole role,
            @Nullable Direction forbidSelfFace,
            boolean allowSelf,
            Direction sourceFace,
            boolean radioactiveGasSubgraph,
            boolean destStyleSelfSkip) {
        Set<BlockPos> storageMembers =
                radioactiveGasSubgraph
                        ? DuctNetworkCache.storageAttachmentMembers(level, sourcePos, networkType, true)
                        : DuctNetworkCache.storageAttachmentMembers(level, sourcePos, networkType);
        if (storageMembers.isEmpty()) {
            long componentId =
                    radioactiveGasSubgraph
                            ? DuctNetworkCache.radioactiveGasComponentId(level, sourcePos)
                            : DuctNetworkCache.componentId(level, sourcePos, networkType);
            if (componentId == 0L) {
                return List.of();
            }
        }
        List<RoutingEndpoint> raw =
                getOrBuildRaw(level, sourcePos, storageMembers, networkType, transportKind, radioactiveGasSubgraph, role);
        List<ScoredEndpoint> scored = new ArrayList<>();
        for (RoutingEndpoint ep : raw) {
            if (ep.pos.equals(sourcePos) && forbidSelfFace != null && ep.face == forbidSelfFace) {
                continue;
            }
            if (destStyleSelfSkip) {
                if (DuctSameBlockRouting.skipSameBlockDestFace(sourcePos, sourceFace, ep.pos, ep.face, allowSelf)) {
                    continue;
                }
            } else if (!allowSelf
                    && DuctSameBlockRouting.skipSameBlockDonorFace(sourcePos, sourceFace, ep.pos, ep.face)) {
                continue;
            }
            if (!DuctChannelPolicy.sameChannel(ep.channelLetter, sourceChannel)) {
                continue;
            }
            if (!(level.getBlockEntity(ep.pos) instanceof DuctBlockEntity destBe)) {
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
            BlockPos sourcePos,
            Set<BlockPos> storageMembers,
            DuctNetworkType networkType,
            DuctTransportKind transportKind,
            boolean radioactiveGasSubgraph,
            EndpointRole role) {
        LevelCache levelCache = BY_LEVEL.computeIfAbsent(level, k -> new LevelCache());
        long componentId =
                radioactiveGasSubgraph
                        ? DuctNetworkCache.radioactiveGasComponentId(level, sourcePos)
                        : DuctNetworkCache.componentId(level, sourcePos, networkType);
        long networkKey = networkCacheKey(componentId, transportKind, radioactiveGasSubgraph, role);
        CachedRaw cached = levelCache.rawByNetworkKey.get(networkKey);
        if (cached != null
                && cached.topologyGeneration == levelCache.topologyGeneration
                && cached.settingsGeneration == levelCache.settingsGeneration) {
            return cached.endpoints;
        }
        List<RoutingEndpoint> built = buildRawEndpoints(level, storageMembers, transportKind, role);
        DuctTransitDebugLog.endpointCacheBuild(
                level, componentId, networkType, built.size(), storageMembers.size());
        levelCache.rawByNetworkKey.put(
                networkKey,
                new CachedRaw(levelCache.topologyGeneration, levelCache.settingsGeneration, built));
        return built;
    }

    private static long networkCacheKey(
            long componentId, DuctTransportKind kind, boolean radioactiveGas, EndpointRole role) {
        long hash = componentId;
        hash = 31L * hash + kind.ordinal();
        hash = 31L * hash + (radioactiveGas ? 1 : 0);
        hash = 31L * hash + role.ordinal();
        return hash;
    }

    private static List<RoutingEndpoint> buildRawEndpoints(
            ServerLevel level, Set<BlockPos> storageMembers, DuctTransportKind kind, EndpointRole role) {
        List<RoutingEndpoint> out = new ArrayList<>();
        for (BlockPos pos : storageMembers) {
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
                NodeMode mode = be.getFaceLanes(face).nodeMode;
                if (!roleAcceptsMode(role, mode)) {
                    continue;
                }
                DuctFaceNode node = faceNodeForKind(be, face, kind);
                if (role == EndpointRole.DONOR) {
                    if (!node.eligibilityMode.isRetrievable()) {
                        continue;
                    }
                } else if (!node.eligibilityMode.isInsertable()) {
                    continue;
                }
                out.add(
                        new RoutingEndpoint(
                                pos, face, node.channelLetter, node.insertionPriority, mode));
            }
        }
        return out;
    }

    /** {@link NodeMode#NONE} is an active simple inventory node — always allowed where listed today. */
    private static boolean roleAcceptsMode(EndpointRole role, NodeMode mode) {
        return switch (role) {
            case EXTRACT_DEST ->
                    mode == NodeMode.NONE
                            || mode == NodeMode.FILTERING_INSERTION
                            || mode == NodeMode.EXTRACTION_FILTERING;
            case INBOUND_DEST ->
                    mode == NodeMode.NONE
                            || mode == NodeMode.FILTERING_INSERTION
                            || mode == NodeMode.EXTRACTION_FILTERING
                            || mode == NodeMode.RETRIEVING
                            || mode == NodeMode.RETRIEVING_EXTRACTION;
            case DONOR -> mode == NodeMode.NONE || mode == NodeMode.FILTERING_INSERTION;
        };
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
