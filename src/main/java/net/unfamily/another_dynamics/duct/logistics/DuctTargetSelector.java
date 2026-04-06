package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.ItemDuctBlockEntity;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;

/**
 * Picks a target duct and path for extraction (to network consumers) or retrieving (from donors to self).
 */
public final class DuctTargetSelector {
    private DuctTargetSelector() {}

    public static Optional<List<BlockPos>> selectExtractionDelivery(
            ServerLevel level,
            BlockPos extractorPos,
            ItemStack probe,
            RoutingMode routing,
            int[] roundRobinState) {
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, extractorPos);
        List<Candidate> cands = new ArrayList<>();
        for (BlockPos p : net) {
            if (p.equals(extractorPos)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof ItemDuctBlockEntity be)) {
                continue;
            }
            if (be.getStorageMask() == 0) {
                continue;
            }
            NodeMode m = be.getNodeMode();
            if (m != NodeMode.NONE && m != NodeMode.FILTERING_INSERTION) {
                continue;
            }
            if (!DuctCapHelper.canInsertIntoStorageFaces(level, p, be, probe)) {
                continue;
            }
            OptionalLong dist = DuctPathfinder.distance(level, extractorPos, p, spec);
            if (dist.isEmpty()) {
                continue;
            }
            cands.add(new Candidate(p, be.getAmountField(), dist.getAsLong()));
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
        BlockPos pick = pickWithinTier(level, extractorPos, tier, routing, roundRobinState);
        if (pick == null) {
            return Optional.empty();
        }
        return DuctPathfinder.shortestPath(level, extractorPos, pick, spec);
    }

    public static Optional<List<BlockPos>> selectRetrievingDonorPath(
            ServerLevel level,
            BlockPos retrieverPos,
            RoutingMode routing,
            int[] roundRobinState) {
        if (!(level.getBlockEntity(retrieverPos) instanceof ItemDuctBlockEntity retriever)) {
            return Optional.empty();
        }
        DuctItemTransportSpec spec = DuctDefinitionRegistry.itemDuctTransportSpec();
        Set<BlockPos> net = DuctPathfinder.connectedDucts(level, retrieverPos);
        List<Candidate> cands = new ArrayList<>();
        for (BlockPos p : net) {
            if (p.equals(retrieverPos)) {
                continue;
            }
            if (!(level.getBlockEntity(p) instanceof ItemDuctBlockEntity be)) {
                continue;
            }
            if (be.getStorageMask() == 0) {
                continue;
            }
            Optional<ItemStack> sample = DuctCapHelper.simulateExtractOne(level, p, be);
            if (sample.isEmpty()) {
                continue;
            }
            if (!DuctCapHelper.canInsertIntoStorageFaces(level, retrieverPos, retriever, sample.get())) {
                continue;
            }
            OptionalLong dist = DuctPathfinder.distance(level, retrieverPos, p, spec);
            if (dist.isEmpty()) {
                continue;
            }
            cands.add(new Candidate(p, be.getAmountField(), dist.getAsLong()));
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
        BlockPos donor = pickWithinTier(level, retrieverPos, tier, routing, roundRobinState);
        if (donor == null) {
            return Optional.empty();
        }
        // path donor -> retriever (items flow along path toward retriever)
        return DuctPathfinder.shortestPath(level, donor, retrieverPos, spec);
    }

    private record Candidate(BlockPos pos, int priority, long dist) {}

    private static BlockPos pickWithinTier(
            ServerLevel level,
            BlockPos origin,
            List<Candidate> tier,
            RoutingMode routing,
            int[] roundRobinState) {
        if (tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(c -> c.dist)).map(c -> c.pos).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(c -> c.dist)).map(c -> c.pos).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (Candidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((Candidate c) -> Math.abs(c.dist - mean))
                                .thenComparingLong(c -> c.pos.asLong()))
                        .map(Candidate::pos)
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(Comparator.comparingLong(c -> c.pos.asLong()));
                int i = Math.floorMod(roundRobinState[0]++, tier.size());
                yield tier.get(i).pos;
            }
            case RANDOM -> {
                int i = level.random.nextInt(tier.size());
                yield tier.get(i).pos;
            }
        };
    }
}
