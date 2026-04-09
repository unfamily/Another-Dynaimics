package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.*;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * Gas ("chemical") logistics: plan extract+insert with simulation only, enqueue {@link GasTransitShipment};
 * {@link DuctBlockEntity} executes transfer when travel completes and re-validates each tick in transit.
 *
 * <p>Mekanism is optional: this does nothing when Mekanism isn't loaded.</p>
 */
public final class DuctGasServerTick {
    private DuctGasServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        try {
        if (!MekanismChemicalCompat.isLoaded()) {
            return;
        }
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.GAS)).orElse(false)) {
            return;
        }
        DuctGasTransportSpec spec = be.gasTransportSpec();
        int rate = spec.clampedRateTicks(spec.rateDefaultTicks());
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            DuctFaceNode node = lanes.gas;
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            if (node.ticksUntilAction > 0) {
                node.ticksUntilAction--;
                be.setChanged();
                continue;
            }
            node.ticksUntilAction = rate - 1;
            if (lanes.nodeMode == NodeMode.EXTRACTION
                    || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING
                    || lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                tryExtractPush(level, be, dir, lanes.nodeMode, node, spec);
            }
        }
        } catch (Throwable t) {
            // Never let a bad compat call disable ticking for this block entity.
            AnotherDynamicsMod.LOGGER.error("Gas tick failed at {} (ductId={})", be.getBlockPos(), be.getLogicalDuctId(), t);
        }
    }

    private static void tryExtractPush(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            NodeMode sourceMode,
            DuctFaceNode node,
            DuctGasTransportSpec spec) {
        BlockPos srcPos = sourceBe.getBlockPos();
        Object srcHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, srcPos, sourceFace);
        if (srcHandler == null) {
            return;
        }

        long want = node.extractBatch > 0 ? node.extractBatch : spec.batchDefault();
        want = spec.clampedBatch(want);
        Object available = MekanismChemicalCompat.drainProbe(srcHandler, want);
        if (MekanismChemicalCompat.isEmptyStack(available)) {
            return;
        }
        if (!DuctGasFilterLogic.passesGasFiltersForBank(node, DuctFaceNode.FilterBank.EXTRACTOR, available, level)) {
            return;
        }

        // Build candidate insertion faces across the gas network: priority first, then routing tie-break.
        List<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, srcPos, DuctNetworkType.GAS));
        boolean allowSelf = sourceMode == NodeMode.EXTRACTION_FILTERING && node.selfFeed;
        if (ducts.size() > 1 && !allowSelf) {
            ducts.remove(srcPos);
        }
        if (ducts.isEmpty()) {
            return;
        }

        String chemicalId = MekanismChemicalCompat.getTypeRegistryName(available);
        if (chemicalId == null || chemicalId.isEmpty()) {
            return;
        }

        ArrayList<DestCandidate> cands = new ArrayList<>();
        for (BlockPos destPos : ducts) {
            if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            OptionalLong dist =
                    destPos.equals(srcPos)
                            ? OptionalLong.of(0L)
                            : DuctPathfinder.distance(level, srcPos, destPos, spec, DuctNetworkType.GAS);
            if (dist.isEmpty()) {
                continue;
            }
            int dsm = destBe.getStorageMask();
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
                    continue;
                }
                DuctFaceLanes destLanes = destBe.getFaceLanes(df);
                DuctFaceNode destNode = destLanes.gas;
                if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
                    continue;
                }
                NodeMode dm = destLanes.nodeMode;
                if (dm != NodeMode.NONE && dm != NodeMode.FILTERING_INSERTION && dm != NodeMode.EXTRACTION_FILTERING) {
                    continue;
                }
                if (!destNode.eligibilityMode.isInsertable()) {
                    continue;
                }
                if (!allowSelf && destPos.equals(srcPos) && df == sourceFace) {
                    continue;
                }
                if (!DuctChannelPolicy.sameChannel(destNode.channelLetter, node.channelLetter)) {
                    continue;
                }

                Object destHandler = MekanismChemicalCompat.getChemicalHandlerOnFace(level, destPos, df);
                if (destHandler == null) {
                    continue;
                }

                long availAmt = MekanismChemicalCompat.getAmount(available);
                if (availAmt <= 0) {
                    continue;
                }
                Object tryStack = MekanismChemicalCompat.copyWithAmount(available, availAmt);
                long simulated = MekanismChemicalCompat.simulateInsert(destHandler, tryStack);
                if (simulated <= 0) {
                    continue;
                }

                if ((dm == NodeMode.FILTERING_INSERTION || dm == NodeMode.EXTRACTION_FILTERING)
                        && !DuctGasFilterLogic.passesGasFiltersForBank(destNode, DuctFaceNode.FilterBank.FILTER, available, level)) {
                    continue;
                }

                // TODO gas limit/keep semantics for FILTER bank (pending-aware) - after basic transport works.
                cands.add(new DestCandidate(destPos, df, destNode.insertionPriority, dist.getAsLong(), simulated));
            }
        }
        if (cands.isEmpty()) {
            return;
        }

        cands.sort(Comparator.comparingInt((DestCandidate c) -> c.priority).reversed());
        int maxP = cands.getFirst().priority;
        ArrayList<DestCandidate> tier = new ArrayList<>();
        for (DestCandidate c : cands) {
            if (c.priority == maxP) {
                tier.add(c);
            }
        }
        int[] rr = new int[] {node.roundRobinCursor};
        DestCandidate pick = pickWithinTier(level, tier, node.routingMode, rr);
        if (pick == null) {
            return;
        }
        node.roundRobinCursor = rr[0];
        sourceBe.setChanged();

        long moved = Math.min(MekanismChemicalCompat.getAmount(available), pick.moved);
        if (moved <= 0) {
            return;
        }
        Object planned = MekanismChemicalCompat.copyWithAmount(available, moved);

        List<BlockPos> rawPath;
        if (pick.ductPos.equals(srcPos)) {
            rawPath = List.of(srcPos);
        } else {
            rawPath = DuctPathfinder.shortestPath(level, srcPos, pick.ductPos, spec, DuctNetworkType.GAS)
                    .orElseGet(() -> List.of(srcPos, pick.ductPos));
        }
        List<BlockPos> pathWire = OutboundShipment.copyPath(rawPath);
        sourceBe.scheduleGasTransitPending(level, planned, pathWire, sourceFace, pick.face, pick.ductPos, spec);
    }

    private record DestCandidate(BlockPos ductPos, Direction face, int priority, long dist, long moved) {}

    private static DestCandidate pickWithinTier(
            ServerLevel level, List<DestCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(c -> c.dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (DestCandidate c : tier) {
                    sum += c.dist;
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(Comparator.comparingDouble((DestCandidate c) -> Math.abs(c.dist - mean))
                                .thenComparingLong(c -> c.ductPos.asLong())
                                .thenComparingInt(c -> c.face.ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                tier.sort(Comparator.comparingLong((DestCandidate c) -> c.ductPos.asLong())
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

