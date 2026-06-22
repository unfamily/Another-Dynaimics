package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctChannelPolicy;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.FilterRemoteNodeRole;
import net.unfamily.another_dynamics.duct.NodeMode;

import org.jetbrains.annotations.Nullable;

/** Maps a bound inventory endpoint to reachable duct faces on the connected network. */
public final class DuctFilterDestinationResolver {
    private DuctFilterDestinationResolver() {}

    public record ResolvedFace(BlockPos ductPos, Direction face, int priority, long travelTicks) {}

    public static List<ResolvedFace> findFacesForInventoryEndpoint(
            ServerLevel level,
            BlockPos sourcePos,
            Direction sourceFace,
            DuctNetworkType networkType,
            DuctTransportKind transportKind,
            long edgeTicksPerBlock,
            int sourceChannel,
            boolean ignoreChannel,
            @Nullable DuctDirectionalEndpoint inventoryEndpoint,
            boolean targetAnyFace,
            FilterRemoteNodeRole role,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        if (inventoryEndpoint == null) {
            return List.of();
        }
        DuctDirectionalEndpoint target =
                DuctDirectionalEndpoint.migrateLegacyStoredEndpoint(level, inventoryEndpoint);
        if (target == null) {
            return List.of();
        }
        Set<BlockPos> members = DuctNetworkCache.connectedDucts(level, sourcePos, networkType);
        if (members.isEmpty()) {
            return List.of();
        }
        List<ResolvedFace> out = new ArrayList<>();
        for (BlockPos pos : members) {
            if (!(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                continue;
            }
            int storageMask = be.getStorageMask();
            for (Direction face : Direction.values()) {
                if ((storageMask & (1 << face.ordinal())) == 0) {
                    continue;
                }
                if ((be.getUserDisconnectedFaceMask() & (1 << face.ordinal())) != 0) {
                    continue;
                }
                if (pos.equals(sourcePos) && forbidSelfDestFace != null && face == forbidSelfDestFace) {
                    continue;
                }
                if (DuctSameBlockRouting.skipSameBlockDestFace(sourcePos, sourceFace, pos, face, allowSelfFeed)) {
                    continue;
                }
                if (!be.isTransportKindEnabled(face, transportKind)) {
                    continue;
                }
                DuctFaceNode node = faceNodeForKind(be, face, transportKind);
                DuctDirectionalEndpoint connected =
                        DuctDirectionalEndpoint.connectionAtDuctFace(level, pos, face);
                if (connected == null || !endpointMatches(connected, target, targetAnyFace)) {
                    continue;
                }
                if (!ignoreChannel && !DuctChannelPolicy.sameChannel(sourceChannel, node.channelLetter)) {
                    continue;
                }
                if (!passesRoleGate(be, face, node, role)) {
                    continue;
                }
                if (!DuctRedstoneLogic.isFaceTransportActive(level, pos, be.getFaceLanes(face).redstoneMode)) {
                    continue;
                }
                long dist =
                        pos.equals(sourcePos)
                                ? 0L
                                : DuctNetworkCache.routingTravelTicks(
                                                level,
                                                sourcePos,
                                                pos,
                                                edgeTicksPerBlock,
                                                networkType)
                                        .orElse(-1L);
                if (dist < 0L) {
                    continue;
                }
                out.add(new ResolvedFace(pos, face, node.insertionPriority, dist));
            }
        }
        if (out.isEmpty()) {
            return List.of();
        }
        out.sort(
                Comparator.comparingInt(ResolvedFace::priority)
                        .reversed()
                        .thenComparingLong(ResolvedFace::travelTicks));
        return out;
    }

    private static boolean endpointMatches(
            DuctDirectionalEndpoint connected, DuctDirectionalEndpoint target, boolean anyFace) {
        if (anyFace) {
            return connected.pos().equals(target.pos());
        }
        return connected.matches(target.pos(), target.face());
    }

    private static boolean passesRoleGate(
            DuctBlockEntity be, Direction face, DuctFaceNode node, FilterRemoteNodeRole role) {
        NodeMode mode = be.getFaceLanes(face).nodeMode;
        return switch (role) {
            case EXTRACT_ROUTE, FILTER_INBOUND ->
                    node.eligibilityMode.isInsertable()
                            && (mode == NodeMode.NONE
                                    || mode == NodeMode.FILTERING_INSERTION
                                    || mode == NodeMode.EXTRACTION_FILTERING
                                    || mode == NodeMode.RETRIEVING
                                    || mode == NodeMode.RETRIEVING_EXTRACTION);
            case RETRIEVE_PULL, FILTER_DONOR ->
                    node.eligibilityMode.isRetrievable()
                            && (mode == NodeMode.EXTRACTION
                                    || mode == NodeMode.EXTRACTION_FILTERING
                                    || mode == NodeMode.RETRIEVING_EXTRACTION);
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
}
