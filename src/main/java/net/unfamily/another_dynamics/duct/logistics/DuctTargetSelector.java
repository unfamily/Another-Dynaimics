package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctChannelPolicy;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;

import org.jetbrains.annotations.Nullable;

/**
 * Picks a target duct face and path for extraction (to network consumers) or retrieving (from donors to self).
 * Endpoints must share the same {@link net.unfamily.another_dynamics.duct.DuctFaceNode#channelLetter} as the acting face.
 */
public final class DuctTargetSelector {
    private DuctTargetSelector() {}

    public record ExtractionRouting(List<BlockPos> path, Direction destStorageFace) {}

    public record RetrieverRouting(List<BlockPos> path, BlockPos donorPos, Direction donorStorageFace) {}

    public static Optional<ExtractionRouting> selectExtractionDelivery(
            ServerLevel level,
            BlockPos extractorPos,
            ItemStack probe,
            RoutingMode routing,
            int[] roundRobinState,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            @Nullable Direction forbidSelfDestFace) {
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, extractorPos, DuctNetworkType.ITEM);
        List<Candidate> cands = new ArrayList<>();
        for (BlockPos p : net) {
            if (!allowSelfDestination && p.equals(extractorPos)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof DuctBlockEntity be)) {
                continue;
            }
            int sm = be.getStorageMask();
            for (Direction d : Direction.values()) {
                if ((sm & (1 << d.ordinal())) == 0) {
                    continue;
                }
                if (p.equals(extractorPos) && forbidSelfDestFace != null && d == forbidSelfDestFace) {
                    continue;
                }
                DuctFaceNode node = be.getFaceNode(d);
                if (!isNodeEnabledByRedstone(level, p, node)) {
                    continue;
                }
                NodeMode m = node.nodeMode;
                if (m != NodeMode.NONE
                        && m != NodeMode.FILTERING_INSERTION
                        && m != NodeMode.EXTRACTION_FILTERING
                        && m != NodeMode.RETRIEVING
                        && m != NodeMode.RETRIEVING_EXTRACTION) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(node.channelLetter, extractorFaceChannel)) {
                    continue;
                }
                if (!DuctCapHelper.canInsertIntoFace(level, p, d, probe)) {
                    continue;
                }
                OptionalLong dist =
                        p.equals(extractorPos) ? OptionalLong.of(0L) : DuctPathfinder.distance(level, extractorPos, p, spec, DuctNetworkType.ITEM);
                if (dist.isEmpty()) {
                    continue;
                }
                cands.add(new Candidate(p, d, node.insertionPriority, dist.getAsLong()));
            }
        }
        if (cands.isEmpty()) {
            return Optional.empty();
        }
        cands.sort(Comparator.comparingInt((Candidate c) -> c.priority).reversed());
        int maxP = cands.get(0).priority;
        List<Candidate> tier = new ArrayList<>();
        for (Candidate c : cands) {
            if (c.priority == maxP) {
                tier.add(c);
            }
        }
        // Tie-break: if any filtering face exists at this priority, prefer it over retriever-mode faces.
        boolean anyFilter = false;
        for (Candidate c : tier) {
            if (level.getBlockEntity(c.ductPos) instanceof DuctBlockEntity be) {
                NodeMode m = be.getFaceNode(c.face).nodeMode;
                if (m == NodeMode.FILTERING_INSERTION || m == NodeMode.EXTRACTION_FILTERING) {
                    anyFilter = true;
                    break;
                }
            }
        }
        if (anyFilter) {
            tier.removeIf(
                    c -> {
                        if (!(level.getBlockEntity(c.ductPos) instanceof DuctBlockEntity be)) {
                            return true;
                        }
                        NodeMode m = be.getFaceNode(c.face).nodeMode;
                        return !(m == NodeMode.FILTERING_INSERTION || m == NodeMode.EXTRACTION_FILTERING);
                    });
        }
        Candidate pick = pickWithinTier(level, extractorPos, tier, routing, roundRobinState);
        if (pick == null) {
            return Optional.empty();
        }
        if (pick.ductPos.equals(extractorPos)) {
            return Optional.of(new ExtractionRouting(List.of(extractorPos), pick.face));
        }
        Optional<List<BlockPos>> path =
                DuctPathfinder.shortestPath(level, extractorPos, pick.ductPos, spec, DuctNetworkType.ITEM);
        return path.map(positions -> new ExtractionRouting(positions, pick.face));
    }

    public static Optional<ExtractionRouting> selectExtractionDelivery(
            ServerLevel level,
            BlockPos extractorPos,
            ItemStack probe,
            RoutingMode routing,
            int[] roundRobinState,
            int extractorFaceChannel) {
        return selectExtractionDelivery(level, extractorPos, probe, routing, roundRobinState, extractorFaceChannel, false, null);
    }

    public static Optional<RetrieverRouting> selectRetrievingDonorPath(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            RoutingMode routing,
            int[] roundRobinState,
            int retrieverFaceChannel) {
        if (!(level.getBlockEntity(retrieverPos) instanceof DuctBlockEntity)) {
            return Optional.empty();
        }
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.ITEM);
        List<DonorCandidate> cands = new ArrayList<>();
        for (BlockPos p : net) {
            if (p.equals(retrieverPos)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof DuctBlockEntity be)) {
                continue;
            }
            int sm = be.getStorageMask();
            for (Direction d : Direction.values()) {
                if ((sm & (1 << d.ordinal())) == 0) {
                    continue;
                }
                DuctFaceNode node = be.getFaceNode(d);
                if (!isNodeEnabledByRedstone(level, p, node)) {
                    continue;
                }
                NodeMode donorMode = node.nodeMode;
                if (donorMode != NodeMode.NONE && donorMode != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(node.channelLetter, retrieverFaceChannel)) {
                    continue;
                }
                Optional<ItemStack> sample = DuctCapHelper.simulateExtractOneOnFace(level, p, d);
                if (sample.isEmpty()) {
                    continue;
                }
                if (!DuctCapHelper.canInsertIntoFace(level, retrieverPos, retrieverInventoryFace, sample.get())) {
                    continue;
                }
                OptionalLong dist = DuctPathfinder.distance(level, retrieverPos, p, spec, DuctNetworkType.ITEM);
                if (dist.isEmpty()) {
                    continue;
                }
                cands.add(new DonorCandidate(p, d, node.insertionPriority, dist.getAsLong()));
            }
        }
        if (cands.isEmpty()) {
            return Optional.empty();
        }
        cands.sort(Comparator.comparingInt((DonorCandidate c) -> c.priority).reversed());
        int maxP = cands.get(0).priority;
        List<DonorCandidate> tier = new ArrayList<>();
        for (DonorCandidate c : cands) {
            if (c.priority == maxP) {
                tier.add(c);
            }
        }
        DonorCandidate pick = pickDonorWithinTier(level, retrieverPos, tier, routing, roundRobinState);
        if (pick == null) {
            return Optional.empty();
        }
        Optional<List<BlockPos>> path =
                DuctPathfinder.shortestPath(level, pick.ductPos, retrieverPos, spec, DuctNetworkType.ITEM);
        return path.map(positions -> new RetrieverRouting(positions, pick.ductPos, pick.face));
    }

    private record Candidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    private record DonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    private static boolean isNodeEnabledByRedstone(ServerLevel level, BlockPos ductPos, DuctFaceNode node) {
        return switch (node.redstoneMode) {
            case 0 -> true; // ignored
            case 1 -> !level.hasNeighborSignal(ductPos); // low
            case 2 -> level.hasNeighborSignal(ductPos); // high
            default -> false; // disabled
        };
    }

    private static Candidate pickWithinTier(
            ServerLevel level,
            BlockPos origin,
            List<Candidate> tier,
            RoutingMode routing,
            int[] roundRobinState) {
        if (tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (Candidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((Candidate c) -> Math.abs(c.dist - mean))
                                .thenComparingLong(c -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((Candidate c) -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, tier.size());
                yield tier.get(i);
            }
            case RANDOM -> {
                int i = level.random.nextInt(tier.size());
                yield tier.get(i);
            }
        };
    }

    private static DonorCandidate pickDonorWithinTier(
            ServerLevel level,
            BlockPos origin,
            List<DonorCandidate> tier,
            RoutingMode routing,
            int[] roundRobinState) {
        if (tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DonorCandidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((DonorCandidate c) -> Math.abs(c.dist - mean))
                                .thenComparingLong(c -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DonorCandidate c) -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, tier.size());
                yield tier.get(i);
            }
            case RANDOM -> {
                int i = level.random.nextInt(tier.size());
                yield tier.get(i);
            }
        };
    }
}
