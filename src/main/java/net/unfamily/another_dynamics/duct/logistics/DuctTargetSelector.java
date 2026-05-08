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
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.NodeMode;
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
            ItemStack probe,
            RoutingMode routing,
            int[] roundRobinState,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            @Nullable Direction forbidSelfDestFace) {
        DuctItemTransportSpec spec =
                level.getBlockEntity(extractorPos) instanceof DuctBlockEntity extractorDuct
                        ? extractorDuct.itemTransportSpec()
                        : DuctDefinitionRegistry.itemDuctTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, extractorPos, DuctNetworkType.ITEM);
        List<ExtractionCandidate> cands = new ArrayList<>();
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
                if (!DuctRedstoneLogic.isFaceTransportActive(level, p, be.getFaceLanes(d).redstoneMode)) {
                    continue;
                }
                NodeMode m = be.getFaceLanes(d).nodeMode;
                if (m != NodeMode.NONE
                        && m != NodeMode.FILTERING_INSERTION
                        && m != NodeMode.EXTRACTION_FILTERING) {
                    continue;
                }
                if (!node.eligibilityMode.isInsertable()) {
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
                cands.add(new ExtractionCandidate(p, d, node.insertionPriority, dist.getAsLong()));
            }
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
                DuctPathfinder.shortestPath(level, extractorPos, pick.ductPos, spec, DuctNetworkType.ITEM);
        return path.map(positions -> new ExtractionRouting(positions, pick.face));
    }

    public static List<ExtractionCandidate> listExtractionDeliveryCandidates(
            ServerLevel level,
            BlockPos extractorPos,
            ItemStack probe,
            RoutingMode routing,
            int roundRobinCursor,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            @Nullable Direction forbidSelfDestFace) {
        return listExtractionDeliveryCandidatesInternal(
                level,
                extractorPos,
                probe,
                true,
                routing,
                roundRobinCursor,
                extractorFaceChannel,
                allowSelfDestination,
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
            RoutingMode routing,
            int roundRobinCursor,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            @Nullable Direction forbidSelfDestFace) {
        return listExtractionDeliveryCandidatesInternal(
                level,
                extractorPos,
                ItemStack.EMPTY,
                false,
                routing,
                roundRobinCursor,
                extractorFaceChannel,
                allowSelfDestination,
                forbidSelfDestFace);
    }

    private static List<ExtractionCandidate> listExtractionDeliveryCandidatesInternal(
            ServerLevel level,
            BlockPos extractorPos,
            ItemStack probe,
            boolean requireProbeInsertable,
            RoutingMode routing,
            int roundRobinCursor,
            int extractorFaceChannel,
            boolean allowSelfDestination,
            @Nullable Direction forbidSelfDestFace) {
        DuctItemTransportSpec spec =
                level.getBlockEntity(extractorPos) instanceof DuctBlockEntity extractorDuct
                        ? extractorDuct.itemTransportSpec()
                        : DuctDefinitionRegistry.itemDuctTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, extractorPos, DuctNetworkType.ITEM);
        List<ExtractionCandidate> cands = new ArrayList<>();
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
                if (!DuctRedstoneLogic.isFaceTransportActive(level, p, be.getFaceLanes(d).redstoneMode)) {
                    continue;
                }
                NodeMode m = be.getFaceLanes(d).nodeMode;
                if (m != NodeMode.NONE
                        && m != NodeMode.FILTERING_INSERTION
                        && m != NodeMode.EXTRACTION_FILTERING) {
                    continue;
                }
                if (!node.eligibilityMode.isInsertable()) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(node.channelLetter, extractorFaceChannel)) {
                    continue;
                }
                if (requireProbeInsertable && !DuctCapHelper.canInsertIntoFace(level, p, d, probe)) {
                    continue;
                }
                OptionalLong dist =
                        p.equals(extractorPos)
                                ? OptionalLong.of(0L)
                                : DuctPathfinder.distance(level, extractorPos, p, spec, DuctNetworkType.ITEM);
                if (dist.isEmpty()) {
                    continue;
                }
                cands.add(new ExtractionCandidate(p, d, node.insertionPriority, dist.getAsLong()));
            }
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
            int retrieverFaceChannel,
            boolean allowSelfDonor,
            @Nullable Direction forbidSelfDonorFace) {
        if (!(level.getBlockEntity(retrieverPos) instanceof DuctBlockEntity retrieverBe)) {
            return Optional.empty();
        }
        DuctItemTransportSpec spec = retrieverBe.itemTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.ITEM);
        List<DonorCandidate> cands = new ArrayList<>();
        for (BlockPos p : net) {
            if (!allowSelfDonor && p.equals(retrieverPos)) {
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
                if (p.equals(retrieverPos) && forbidSelfDonorFace != null && d == forbidSelfDonorFace) {
                    continue;
                }
                DuctFaceNode node = be.getFaceNode(d);
                if (!DuctRedstoneLogic.isFaceTransportActive(level, p, be.getFaceLanes(d).redstoneMode)) {
                    continue;
                }
                NodeMode donorMode = be.getFaceLanes(d).nodeMode;
                if (donorMode != NodeMode.NONE && donorMode != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!node.eligibilityMode.isRetrievable()) {
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
                OptionalLong dist =
                        p.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctPathfinder.distance(level, retrieverPos, p, spec, DuctNetworkType.ITEM);
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
        if (pick.ductPos.equals(retrieverPos)) {
            return Optional.of(new RetrieverRouting(List.of(retrieverPos), pick.ductPos, pick.face));
        }
        Optional<List<BlockPos>> path =
                DuctPathfinder.shortestPath(level, pick.ductPos, retrieverPos, spec, DuctNetworkType.ITEM);
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
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.ITEM);
        List<DonorCandidate> cands = new ArrayList<>();
        for (BlockPos p : net) {
            if (!allowSelfDonor && p.equals(retrieverPos)) {
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
                if (p.equals(retrieverPos) && forbidSelfDonorFace != null && d == forbidSelfDonorFace) {
                    continue;
                }
                DuctFaceNode node = be.getFaceNode(d);
                if (!DuctRedstoneLogic.isFaceTransportActive(level, p, be.getFaceLanes(d).redstoneMode)) {
                    continue;
                }
                NodeMode donorMode = be.getFaceLanes(d).nodeMode;
                if (donorMode != NodeMode.NONE && donorMode != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!node.eligibilityMode.isRetrievable()) {
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
                OptionalLong dist =
                        p.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : DuctPathfinder.distance(level, retrieverPos, p, spec, DuctNetworkType.ITEM);
                if (dist.isEmpty()) {
                    continue;
                }
                cands.add(new DonorCandidate(p, d, node.insertionPriority, dist.getAsLong()));
            }
        }
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
