package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.RoutingMode;

import org.jetbrains.annotations.Nullable;

/**
 * Picks a target duct face and path for extraction (to network consumers) or retrieving (from donors to self).
 * Endpoints must share the same {@link net.unfamily.another_dynamics.duct.DuctFaceNode#channelLetter} as the acting face.
 */
public final class DuctTargetSelector {
    private DuctTargetSelector() {}

    /** Face modes that can receive items from the network (extraction routing / retriever delivery). */
    public static boolean isNetworkInboundDeliveryMode(NodeMode m) {
        return m == NodeMode.NONE
                || m == NodeMode.FILTERING_INSERTION
                || m == NodeMode.EXTRACTION_FILTERING
                || m == NodeMode.RETRIEVING
                || m == NodeMode.RETRIEVING_EXTRACTION;
    }

    public record ExtractionRouting(List<BlockPos> path, Direction destStorageFace) {}

    public record RetrieverRouting(List<BlockPos> path, BlockPos donorPos, Direction donorStorageFace) {}

    public record ExtractionCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    public record DonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    public static Optional<ExtractionRouting> selectExtractionDelivery(
            ServerLevel level,
            BlockPos extractorPos,
            Direction sourceFace,
            ItemStack probe,
            RoutingMode routing,
            int[] roundRobinState,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        DuctItemTransportSpec spec =
                level.getBlockEntity(extractorPos) instanceof DuctBlockEntity extractorDuct
                        ? extractorDuct.itemTransportSpec()
                        : DuctDefinitionRegistry.itemDuctTransportSpec();
        List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                DuctRoutingEndpointIndex.listScoredExtractDestinations(
                        level,
                        extractorPos,
                        DuctNetworkType.ITEM,
                        DuctTransportKind.ITEM,
                        DuctPathfinder.edgeTravelTicks(spec),
                        extractorFaceChannel,
                        false,
                        allowSelfDestination,
                        forbidSelfDestFace,
                        allowSelfFeed,
                        sourceFace,
                        false);
        List<ExtractionCandidate> cands = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            DuctRoutingEndpointIndex.RoutingEndpoint ep = s.endpoint();
            if (!DuctCapHelper.canInsertIntoFace(level, ep.pos(), ep.face(), probe)) {
                continue;
            }
            cands.add(new ExtractionCandidate(ep.pos(), ep.face(), ep.insertionPriority(), s.distTicks()));
        }
        if (cands.isEmpty()) {
            return Optional.empty();
        }
        cands.sort(Comparator.comparingInt((ExtractionCandidate c) -> c.priority).reversed());
        int maxP = cands.get(0).priority;
        List<ExtractionCandidate> tier = new ArrayList<>();
        for (ExtractionCandidate c : cands) {
            if (c.priority == maxP) {
                tier.add(c);
            }
        }
        ExtractionCandidate pick = pickWithinTier(level, extractorPos, tier, routing, roundRobinState);
        if (pick == null) {
            return Optional.empty();
        }
        if (pick.ductPos.equals(extractorPos)) {
            return Optional.of(new ExtractionRouting(List.of(extractorPos), pick.face));
        }
        Optional<List<BlockPos>> path =
                DuctPathfinder.shortestItemPathForScheduling(level, extractorPos, pick.ductPos, spec);
        return path.map(positions -> new ExtractionRouting(positions, pick.face));
    }

    public static List<ExtractionCandidate> listExtractionDeliveryCandidates(
            ServerLevel level,
            BlockPos extractorPos,
            Direction sourceFace,
            ItemStack probe,
            RoutingMode routing,
            int roundRobinCursor,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        return listExtractionDeliveryCandidatesInternal(
                level,
                extractorPos,
                sourceFace,
                probe,
                true,
                false,
                routing,
                roundRobinCursor,
                extractorFaceChannel,
                allowSelfDestination,
                allowSelfFeed,
                forbidSelfDestFace);
    }

    /**
     * Lists candidate destinations ordered by priority then routing, but does not apply any {@code probe}-dependent
     * insertability checks. Used when the scheduler wants to pick the best destination first, then find a compatible
     * source item for it (more stable behavior with mixed inventories).
     */
    public static List<ExtractionCandidate> listExtractionDeliveryCandidatesWithoutProbe(
            ServerLevel level,
            BlockPos extractorPos,
            Direction sourceFace,
            RoutingMode routing,
            int roundRobinCursor,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        return listExtractionDeliveryCandidatesInternal(
                level,
                extractorPos,
                sourceFace,
                ItemStack.EMPTY,
                false,
                false,
                routing,
                roundRobinCursor,
                extractorFaceChannel,
                allowSelfDestination,
                allowSelfFeed,
                forbidSelfDestFace);
    }

    /**
     * Stall relay / fluid-style inbound targets: includes {@link NodeMode#RETRIEVING} faces, not only filter-insertion
     * destinations.
     */
    public static List<ExtractionCandidate> listInboundDeliveryCandidatesWithoutProbe(
            ServerLevel level,
            BlockPos sourcePos,
            Direction sourceFace,
            RoutingMode routing,
            int roundRobinCursor,
            int sourceFaceChannel,
            boolean allowSelfDestination,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        return listExtractionDeliveryCandidatesInternal(
                level,
                sourcePos,
                sourceFace,
                ItemStack.EMPTY,
                false,
                true,
                routing,
                roundRobinCursor,
                sourceFaceChannel,
                allowSelfDestination,
                allowSelfFeed,
                forbidSelfDestFace);
    }

    private static List<ExtractionCandidate> listExtractionDeliveryCandidatesInternal(
            ServerLevel level,
            BlockPos extractorPos,
            Direction sourceFace,
            ItemStack probe,
            boolean requireProbeInsertable,
            boolean inboundDeliveryModes,
            RoutingMode routing,
            int roundRobinCursor,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            boolean allowSelfFeed,
            @Nullable Direction forbidSelfDestFace) {
        DuctItemTransportSpec spec =
                level.getBlockEntity(extractorPos) instanceof DuctBlockEntity extractorDuct
                        ? extractorDuct.itemTransportSpec()
                        : DuctDefinitionRegistry.itemDuctTransportSpec();
        List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                DuctRoutingEndpointIndex.listScoredExtractDestinations(
                        level,
                        extractorPos,
                        DuctNetworkType.ITEM,
                        DuctTransportKind.ITEM,
                        DuctPathfinder.edgeTravelTicks(spec),
                        extractorFaceChannel,
                        inboundDeliveryModes,
                        allowSelfDestination,
                        forbidSelfDestFace,
                        allowSelfFeed,
                        sourceFace,
                        false);
        List<ExtractionCandidate> cands = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            DuctRoutingEndpointIndex.RoutingEndpoint ep = s.endpoint();
            if (requireProbeInsertable && !DuctCapHelper.canInsertIntoFace(level, ep.pos(), ep.face(), probe)) {
                continue;
            }
            cands.add(new ExtractionCandidate(ep.pos(), ep.face(), ep.insertionPriority(), s.distTicks()));
        }
        if (cands.isEmpty()) {
            return List.of();
        }
        cands.sort(Comparator.comparingInt((ExtractionCandidate c) -> c.priority).reversed());

        ArrayList<ExtractionCandidate> out = new ArrayList<>(cands.size());
        int i = 0;
        boolean rrApplied = false;
        while (i < cands.size()) {
            int p = cands.get(i).priority;
            ArrayList<ExtractionCandidate> tier = new ArrayList<>();
            while (i < cands.size() && cands.get(i).priority == p) {
                tier.add(cands.get(i++));
            }
            orderTier(tier, routing, level, rrApplied ? 0 : roundRobinCursor);
            rrApplied = rrApplied || routing == RoutingMode.ROUND_ROBIN;
            out.addAll(tier);
        }
        return out;
    }

    public static Optional<ExtractionRouting> selectExtractionDelivery(
            ServerLevel level,
            BlockPos extractorPos,
            Direction sourceFace,
            ItemStack probe,
            RoutingMode routing,
            int[] roundRobinState,
            int extractorFaceChannel) {
        return selectExtractionDelivery(
                level,
                extractorPos,
                sourceFace,
                probe,
                routing,
                roundRobinState,
                extractorFaceChannel,
                true,
                false,
                null);
    }

    public static Optional<RetrieverRouting> selectRetrievingDonorPath(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            RoutingMode routing,
            int[] roundRobinState,
            int retrieverFaceChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace) {
        if (!(level.getBlockEntity(retrieverPos) instanceof DuctBlockEntity retrieverBe)) {
            return Optional.empty();
        }
        DuctItemTransportSpec spec = retrieverBe.itemTransportSpec();
        List<DonorCandidate> cands =
                collectItemDonorCandidates(
                        level,
                        retrieverPos,
                        retrieverInventoryFace,
                        retrieverFaceChannel,
                        allowSelfDonor,
                        forbidSelfDonorFace,
                        spec);
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
        if (pick.ductPos.equals(retrieverPos)) {
            return Optional.of(new RetrieverRouting(List.of(retrieverPos), pick.ductPos, pick.face));
        }
        Optional<List<BlockPos>> path =
                DuctPathfinder.shortestItemPathForScheduling(level, pick.ductPos, retrieverPos, spec);
        return path.map(positions -> new RetrieverRouting(positions, pick.ductPos, pick.face));
    }

    public static List<DonorCandidate> listRetrievingDonorCandidates(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            RoutingMode routing,
            int roundRobinCursor,
            int retrieverFaceChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace) {
        if (!(level.getBlockEntity(retrieverPos) instanceof DuctBlockEntity retrieverBe)) {
            return List.of();
        }
        DuctItemTransportSpec spec = retrieverBe.itemTransportSpec();
        List<DonorCandidate> cands =
                collectItemDonorCandidates(
                        level,
                        retrieverPos,
                        retrieverInventoryFace,
                        retrieverFaceChannel,
                        allowSelfDonor,
                        forbidSelfDonorFace,
                        spec);
        if (cands.isEmpty()) {
            return List.of();
        }
        cands.sort(Comparator.comparingInt((DonorCandidate c) -> c.priority).reversed());
        ArrayList<DonorCandidate> out = new ArrayList<>(cands.size());
        int i = 0;
        boolean rrApplied = false;
        while (i < cands.size()) {
            int p = cands.get(i).priority;
            ArrayList<DonorCandidate> tier = new ArrayList<>();
            while (i < cands.size() && cands.get(i).priority == p) {
                tier.add(cands.get(i++));
            }
            orderTierDonors(tier, routing, level, rrApplied ? 0 : roundRobinCursor);
            rrApplied = rrApplied || routing == RoutingMode.ROUND_ROBIN;
            out.addAll(tier);
        }
        return out;
    }

    private static List<DonorCandidate> collectItemDonorCandidates(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            int retrieverFaceChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace,
            DuctItemTransportSpec spec) {
        List<DuctRoutingEndpointIndex.ScoredEndpoint> scored =
                DuctRoutingEndpointIndex.listScoredDonors(
                        level,
                        retrieverPos,
                        DuctNetworkType.ITEM,
                        DuctTransportKind.ITEM,
                        DuctPathfinder.edgeTravelTicks(spec),
                        retrieverFaceChannel,
                        forbidSelfDonorFace,
                        allowSelfDonor,
                        retrieverInventoryFace,
                        false);
        List<DonorCandidate> cands = new ArrayList<>();
        for (DuctRoutingEndpointIndex.ScoredEndpoint s : scored) {
            DuctRoutingEndpointIndex.RoutingEndpoint ep = s.endpoint();
            if (!(level.getBlockEntity(ep.pos()) instanceof DuctBlockEntity be)) {
                continue;
            }
            if (!DuctCapHelper.donorMaySupplyRetriever(level, ep.pos(), ep.face(), be)) {
                continue;
            }
            cands.add(new DonorCandidate(ep.pos(), ep.face(), ep.insertionPriority(), s.distTicks()));
        }
        return cands;
    }

    public static Optional<RetrieverRouting> selectRetrievingDonorPath(
            ServerLevel level,
            BlockPos retrieverPos,
            Direction retrieverInventoryFace,
            RoutingMode routing,
            int[] roundRobinState,
            int retrieverFaceChannel) {
        return selectRetrievingDonorPath(
                level, retrieverPos, retrieverInventoryFace, routing, roundRobinState, retrieverFaceChannel, false, null);
    }

    private static void orderTier(
            List<ExtractionCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(Comparator.comparingLong((ExtractionCandidate c) -> c.dist)
                    .thenComparingLong(c -> c.ductPos.asLong())
                    .thenComparingInt(c -> c.face.ordinal()));
            case FARTHEST_FIRST -> tier.sort(Comparator.comparingLong((ExtractionCandidate c) -> c.dist).reversed()
                    .thenComparingLong(c -> c.ductPos.asLong())
                    .thenComparingInt(c -> c.face.ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (ExtractionCandidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                tier.sort(Comparator.comparingDouble((ExtractionCandidate c) -> Math.abs(c.dist - mean))
                        .thenComparingLong(c -> c.ductPos.asLong())
                        .thenComparingInt(c -> c.face.ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((ExtractionCandidate c) -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<ExtractionCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                // Shuffle within tier to preserve priority-grouping.
                for (int i = tier.size() - 1; i > 0; i--) {
                    int j = level.random.nextInt(i + 1);
                    ExtractionCandidate a = tier.get(i);
                    tier.set(i, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private static void orderTierDonors(
            List<DonorCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(Comparator.comparingLong((DonorCandidate c) -> c.dist)
                    .thenComparingLong(c -> c.ductPos.asLong())
                    .thenComparingInt(c -> c.face.ordinal()));
            case FARTHEST_FIRST -> tier.sort(Comparator.comparingLong((DonorCandidate c) -> c.dist).reversed()
                    .thenComparingLong(c -> c.ductPos.asLong())
                    .thenComparingInt(c -> c.face.ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DonorCandidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                tier.sort(Comparator.comparingDouble((DonorCandidate c) -> Math.abs(c.dist - mean))
                        .thenComparingLong(c -> c.ductPos.asLong())
                        .thenComparingInt(c -> c.face.ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((DonorCandidate c) -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<DonorCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                for (int i = tier.size() - 1; i > 0; i--) {
                    int j = level.random.nextInt(i + 1);
                    DonorCandidate a = tier.get(i);
                    tier.set(i, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private static ExtractionCandidate pickWithinTier(
            ServerLevel level,
            BlockPos origin,
            List<ExtractionCandidate> tier,
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
                for (ExtractionCandidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((ExtractionCandidate c) -> Math.abs(c.dist - mean))
                                .thenComparingLong(c -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((ExtractionCandidate c) -> c.ductPos.asLong())
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
