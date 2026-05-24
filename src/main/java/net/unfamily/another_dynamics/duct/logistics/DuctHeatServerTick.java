package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctHeatTransportSpec;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.MekHeatRayColor;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.integration.mekanism.MekanismHeatCompat;
import net.unfamily.another_dynamics.network.ModNetwork;
import org.jetbrains.annotations.Nullable;

/**
 * Mekanism heat logistics: instant transport between actions. Per-face action interval is set in common config
 * ({@link DuctModuleEffects#HEAT_ACTION_RATE_TICKS} ticks). Throughput is
 * capped at extracting/retrieving faces; increment {@code rate}
 * modules do not apply.
 */
public final class DuctHeatServerTick {
    private static final double HEAT_EPS = 1e-4;

    private DuctHeatServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        if (!MekanismHeatCompat.isHeatCapabilityAvailable()) {
            return;
        }
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.HEAT)).orElse(false)) {
            return;
        }
        if (!be.isStorageAttachmentNode()) {
            return;
        }
        DuctHeatTransportSpec spec = be.heatTransportSpec();
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            if (!be.isTransportKindEnabled(dir, DuctTransportKind.HEAT)) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            DuctFaceNode node = be.getFaceNode(dir);
            if (lanes.heatTicksUntilAction > 0) {
                lanes.heatTicksUntilAction--;
                be.setChanged();
                continue;
            }
            int rate = DuctModuleEffects.effectiveHeatActionRateTicks(be, dir, spec);
            lanes.heatTicksUntilAction = rate - 1;
            if (lanes.nodeMode == NodeMode.EXTRACTION || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING) {
                RoutingMode rm = be.heatRoutingForFace(dir, true);
                tryExtractPush(level, be, dir, spec, rm, node);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING) {
                RoutingMode rm = be.heatRoutingForFace(dir, false);
                tryRetrievePull(level, be, dir, spec, rm, node);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                RoutingMode rmR = be.heatRoutingForFace(dir, false);
                RoutingMode rmE = be.heatRoutingForFace(dir, true);
                tryRetrievePull(level, be, dir, spec, rmR, node);
                tryExtractPush(level, be, dir, spec, rmE, node);
            } else if (lanes.nodeMode == NodeMode.NONE || lanes.nodeMode == NodeMode.FILTERING_INSERTION) {
                if (node.eligibilityMode.isRetrievable()) {
                    tryExtractPush(level, be, dir, spec, be.heatRoutingForFace(dir, true), node);
                }
            }
        }
    }

    private static @Nullable Object getHeatOnFace(ServerLevel level, BlockPos ductPos, Direction ductFace) {
        BlockPos neighbor = ductPos.relative(ductFace);
        return MekanismHeatCompat.getHeatHandler(level, neighbor, ductFace.getOpposite());
    }

    private static void tryExtractPush(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctHeatTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes sourceLanes = sourceBe.getFaceLanes(sourceFace);
        BlockPos srcPos = sourceBe.getBlockPos();
        if (!node.eligibilityMode.isRetrievable()) {
            return;
        }
        Object src = getHeatOnFace(level, srcPos, sourceFace);
        if (src == null) {
            return;
        }
        double tSrc0 = MekanismHeatCompat.getTotalTemperature(src);
        if (MekanismHeatCompat.getTotalHeatCapacity(src) < 1.0) {
            return;
        }

        java.util.Set<BlockPos> ducts = DuctNetworkCache.connectedDucts(level, srcPos, DuctNetworkType.HEAT);
        if (ducts.isEmpty()) {
            return;
        }

        ArrayList<HeatDestCandidate> cands = new ArrayList<>();
        for (BlockPos destPos : ducts) {
            if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            int dsm = destBe.getStorageMask();
            OptionalLong dist =
                    destPos.equals(srcPos)
                            ? OptionalLong.of(0L)
                            : hopDistance(level, srcPos, destPos);
            if (dist.isEmpty()) {
                continue;
            }
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
                    continue;
                }
                if (DuctSameBlockRouting.skipSameBlockDestFace(srcPos, sourceFace, destPos, df, false)) {
                    continue;
                }
                DuctFaceLanes destLanes = destBe.getFaceLanes(df);
                if (!DuctRedstoneLogic.isFaceTransportActive(level, destPos, destLanes.redstoneMode)) {
                    continue;
                }
                NodeMode dm = destLanes.nodeMode;
                if (dm != NodeMode.NONE
                        && dm != NodeMode.FILTERING_INSERTION
                        && dm != NodeMode.EXTRACTION_FILTERING
                        && dm != NodeMode.RETRIEVING
                        && dm != NodeMode.RETRIEVING_EXTRACTION) {
                    continue;
                }
                if (!destBe.getFaceNode(df).eligibilityMode.isInsertable()) {
                    continue;
                }
                Object dest = getHeatOnFace(level, destPos, df);
                if (dest == null) {
                    continue;
                }
                double tDst = MekanismHeatCompat.getTotalTemperature(dest);
                if (tSrc0 <= tDst + HEAT_EPS) {
                    continue;
                }
                double cap = DuctModuleEffects.effectiveHeatExtractPerAction(sourceBe, sourceFace, spec);
                double qEq = equilibriumTransfer(src, dest);
                if (Math.min(cap, qEq) <= HEAT_EPS) {
                    continue;
                }
                int priority = destBe.getFaceNode(df).insertionPriority;
                cands.add(new HeatDestCandidate(destPos, df, priority, dist.orElse(Long.MAX_VALUE)));
            }
        }
        if (cands.isEmpty()) {
            return;
        }
        cands.sort(Comparator.comparingInt((HeatDestCandidate c) -> c.priority()).reversed());
        int maxP = cands.getFirst().priority();
        ArrayList<HeatDestCandidate> tier = new ArrayList<>();
        for (HeatDestCandidate c : cands) {
            if (c.priority() == maxP) {
                tier.add(c);
            }
        }
        int[] rr = new int[] {sourceLanes.heatRoundRobinCursor};
        if (!dispatchHeatExtractToTier(level, sourceBe, sourceFace, spec, src, srcPos, tier, routing, rr)) {
            return;
        }
        sourceLanes.heatRoundRobinCursor = rr[0];
        sourceBe.setChanged();
    }

    private static boolean dispatchHeatExtractToTier(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctHeatTransportSpec spec,
            Object src,
            BlockPos srcPos,
            ArrayList<HeatDestCandidate> tier,
            RoutingMode routing,
            int[] roundRobinState) {
        HeatDestCandidate pick = pickWithinTier(level, tier, routing, roundRobinState);
        if (pick == null) {
            return false;
        }
        return tryHeatExtractToCandidate(level, sourceBe, sourceFace, spec, src, srcPos, pick);
    }

    private static boolean tryHeatExtractToCandidate(
            ServerLevel level,
            DuctBlockEntity sourceBe,
            Direction sourceFace,
            DuctHeatTransportSpec spec,
            Object src,
            BlockPos srcPos,
            HeatDestCandidate pick) {
        Object dest = getHeatOnFace(level, pick.ductPos(), pick.face());
        if (dest == null) {
            return false;
        }
        double cap = DuctModuleEffects.effectiveHeatExtractPerAction(sourceBe, sourceFace, spec);
        double qEq = equilibriumTransfer(src, dest);
        double q = Math.min(cap, qEq);
        if (q <= HEAT_EPS) {
            return false;
        }
        double tHot = Math.max(MekanismHeatCompat.getTotalTemperature(src), MekanismHeatCompat.getTotalTemperature(dest));
        MekanismHeatCompat.handleHeat(src, -q);
        MekanismHeatCompat.handleHeat(dest, q);
        sendHeatRayIfVisible(level, srcPos, pick.ductPos(), tHot, sourceFace, pick.face());
        return true;
    }

    private static void tryRetrievePull(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctHeatTransportSpec spec,
            RoutingMode routing,
            DuctFaceNode node) {
        DuctFaceLanes retrieverLanes = retrieverBe.getFaceLanes(retrieverFace);
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        Object dest = getHeatOnFace(level, retrieverPos, retrieverFace);
        if (dest == null) {
            return;
        }
        double tDest0 = MekanismHeatCompat.getTotalTemperature(dest);

        List<BlockPos> ducts = new ArrayList<>(DuctNetworkCache.connectedDucts(level, retrieverPos, DuctNetworkType.HEAT));
        if (ducts.isEmpty()) {
            return;
        }
        ArrayList<HeatDonorCandidate> donors = new ArrayList<>();
        for (BlockPos donorPos : ducts) {
            if (!(level.getBlockEntity(donorPos) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            int sm = donorBe.getStorageMask();
            for (Direction donorFace : Direction.values()) {
                if ((sm & (1 << donorFace.ordinal())) == 0) {
                    continue;
                }
                if (DuctSameBlockRouting.skipSameBlockDonorFace(retrieverPos, retrieverFace, donorPos, donorFace)) {
                    continue;
                }
                DuctFaceLanes donorLanes = donorBe.getFaceLanes(donorFace);
                if (!DuctRedstoneLogic.isFaceTransportActive(level, donorPos, donorLanes.redstoneMode)) {
                    continue;
                }
                NodeMode dm = donorLanes.nodeMode;
                if (dm != NodeMode.NONE && dm != NodeMode.FILTERING_INSERTION) {
                    continue;
                }
                if (!donorBe.getFaceNode(donorFace).eligibilityMode.isRetrievable()) {
                    continue;
                }
                Object src = getHeatOnFace(level, donorPos, donorFace);
                if (src == null) {
                    continue;
                }
                double tSrc = MekanismHeatCompat.getTotalTemperature(src);
                if (tSrc <= tDest0 + HEAT_EPS) {
                    continue;
                }
                OptionalLong dist =
                        donorPos.equals(retrieverPos)
                                ? OptionalLong.of(0L)
                                : hopDistance(level, retrieverPos, donorPos);
                if (dist.isEmpty()) {
                    continue;
                }
                int priority = donorBe.getFaceNode(donorFace).insertionPriority;
                donors.add(new HeatDonorCandidate(donorPos, donorFace, priority, dist.getAsLong()));
            }
        }
        if (donors.isEmpty()) {
            return;
        }
        donors.sort(Comparator.comparingInt((HeatDonorCandidate c) -> c.priority()).reversed());
        int maxP = donors.getFirst().priority();
        ArrayList<HeatDonorCandidate> tier = new ArrayList<>();
        for (HeatDonorCandidate c : donors) {
            if (c.priority() == maxP) {
                tier.add(c);
            }
        }
        int[] rr = new int[] {retrieverLanes.heatRoundRobinCursor};
        if (!dispatchHeatRetrieveFromTier(level, retrieverBe, retrieverFace, spec, dest, retrieverPos, tier, routing, rr)) {
            return;
        }
        retrieverLanes.heatRoundRobinCursor = rr[0];
        retrieverBe.setChanged();
    }

    private static boolean dispatchHeatRetrieveFromTier(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctHeatTransportSpec spec,
            Object dest,
            BlockPos retrieverPos,
            ArrayList<HeatDonorCandidate> tier,
            RoutingMode routing,
            int[] roundRobinState) {
        HeatDonorCandidate pick = pickDonorWithinTier(level, tier, routing, roundRobinState);
        if (pick == null) {
            return false;
        }
        return tryHeatRetrieveFromDonor(level, retrieverBe, retrieverFace, spec, dest, retrieverPos, pick);
    }

    private static boolean tryHeatRetrieveFromDonor(
            ServerLevel level,
            DuctBlockEntity retrieverBe,
            Direction retrieverFace,
            DuctHeatTransportSpec spec,
            Object dest,
            BlockPos retrieverPos,
            HeatDonorCandidate pick) {
        Object src = getHeatOnFace(level, pick.ductPos(), pick.face());
        if (src == null) {
            return false;
        }
        double cap = DuctModuleEffects.effectiveHeatExtractPerAction(retrieverBe, retrieverFace, spec);
        double qEq = equilibriumTransfer(src, dest);
        double q = Math.min(cap, qEq);
        if (q <= HEAT_EPS) {
            return false;
        }
        double tHot = Math.max(MekanismHeatCompat.getTotalTemperature(src), MekanismHeatCompat.getTotalTemperature(dest));
        MekanismHeatCompat.handleHeat(src, -q);
        MekanismHeatCompat.handleHeat(dest, q);
        sendHeatRayIfVisible(level, pick.ductPos(), retrieverPos, tHot, pick.face(), retrieverFace);
        return true;
    }

    private static double equilibriumTransfer(Object src, Object dst) {
        double tSrc = MekanismHeatCompat.getTotalTemperature(src);
        double tDst = MekanismHeatCompat.getTotalTemperature(dst);
        if (tSrc <= tDst + HEAT_EPS) {
            return 0.0;
        }
        double cSrc = MekanismHeatCompat.getTotalHeatCapacity(src);
        double cDst = MekanismHeatCompat.getTotalHeatCapacity(dst);
        if (cSrc < 1.0 || cDst < 1.0) {
            return 0.0;
        }
        return (cSrc * cDst / (cSrc + cDst)) * (tSrc - tDst);
    }

    private static void sendHeatRayIfVisible(
            ServerLevel level,
            BlockPos fromDuct,
            BlockPos toDuct,
            double visualTempKelvin,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        Optional<List<BlockPos>> pathOpt =
                fromDuct.equals(toDuct)
                        ? Optional.of(List.of(fromDuct))
                        : DuctNetworkCache.shortestPath(level, fromDuct, toDuct, DuctNetworkType.HEAT);
        if (pathOpt.isEmpty()) {
            return;
        }
        List<BlockPos> path = pathOpt.get();
        for (BlockPos p : path) {
            if (level.getBlockEntity(p) instanceof DuctBlockEntity be && be.ductAlwaysOpaqueRendering()) {
                return;
            }
        }
        BlockPos mid = path.get(path.size() / 2);
        int argb = MekHeatRayColor.argbForDust(level, visualTempKelvin, mid.getX(), mid.getY(), mid.getZ());
        if (((argb >>> 24) & 0xFF) < 8) {
            return;
        }
        ModNetwork.sendEnergyRayPath(level, path, argb, midOf(path), sourceAttachFace, destAttachFace);
    }

    private static Vec3 midOf(List<BlockPos> path) {
        BlockPos a = path.getFirst();
        BlockPos b = path.getLast();
        return new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, (a.getY() + b.getY()) / 2.0 + 0.5, (a.getZ() + b.getZ()) / 2.0 + 0.5);
    }

    private static OptionalLong hopDistance(ServerLevel level, BlockPos from, BlockPos to) {
        return DuctNetworkCache.hopDistance(level, from, to, DuctNetworkType.HEAT);
    }

    private static @Nullable HeatDestCandidate pickWithinTier(
            ServerLevel level, List<HeatDestCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(HeatDestCandidate::dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(HeatDestCandidate::dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (HeatDestCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(
                                Comparator.comparingDouble((HeatDestCandidate c) -> Math.abs(c.dist() - mean))
                                        .thenComparingLong(c -> c.ductPos().asLong())
                                        .thenComparingInt(c -> c.face().ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                ArrayList<HeatDestCandidate> ordered = new ArrayList<>(tier);
                ordered.sort(
                        Comparator.comparingLong((HeatDestCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, ordered.size());
                yield ordered.get(i);
            }
            case RANDOM -> tier.get(level.random.nextInt(tier.size()));
        };
    }

    private static void orderDestTier(List<HeatDestCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(
                    Comparator.comparingLong(HeatDestCandidate::dist)
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case FARTHEST_FIRST -> tier.sort(
                    Comparator.comparingLong(HeatDestCandidate::dist).reversed()
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (HeatDestCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                tier.sort(
                        Comparator.comparingDouble((HeatDestCandidate c) -> Math.abs(c.dist() - mean))
                                .thenComparingLong(c -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((HeatDestCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<HeatDestCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                for (int i2 = tier.size() - 1; i2 > 0; i2--) {
                    int j = level.random.nextInt(i2 + 1);
                    HeatDestCandidate a = tier.get(i2);
                    tier.set(i2, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private static @Nullable HeatDonorCandidate pickDonorWithinTier(
            ServerLevel level, List<HeatDonorCandidate> tier, RoutingMode routing, int[] roundRobinState) {
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        return switch (routing) {
            case NEAREST_FIRST -> tier.stream().min(Comparator.comparingLong(HeatDonorCandidate::dist)).orElse(null);
            case FARTHEST_FIRST -> tier.stream().max(Comparator.comparingLong(HeatDonorCandidate::dist)).orElse(null);
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (HeatDonorCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                yield tier.stream()
                        .min(
                                Comparator.comparingDouble((HeatDonorCandidate c) -> Math.abs(c.dist() - mean))
                                        .thenComparingLong(c -> c.ductPos().asLong())
                                        .thenComparingInt(c -> c.face().ordinal()))
                        .orElse(null);
            }
            case ROUND_ROBIN -> {
                ArrayList<HeatDonorCandidate> ordered = new ArrayList<>(tier);
                ordered.sort(
                        Comparator.comparingLong((HeatDonorCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int i = Math.floorMod(roundRobinState[0]++, ordered.size());
                yield ordered.get(i);
            }
            case RANDOM -> tier.get(level.random.nextInt(tier.size()));
        };
    }

    private static void orderDonorTier(List<HeatDonorCandidate> tier, RoutingMode routing, ServerLevel level, int roundRobinCursor) {
        if (tier.isEmpty()) {
            return;
        }
        switch (routing) {
            case NEAREST_FIRST -> tier.sort(
                    Comparator.comparingLong(HeatDonorCandidate::dist)
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case FARTHEST_FIRST -> tier.sort(
                    Comparator.comparingLong(HeatDonorCandidate::dist).reversed()
                            .thenComparingLong(c -> c.ductPos().asLong())
                            .thenComparingInt(c -> c.face().ordinal()));
            case MIDDLEST_FIRST -> {
                double sum = 0;
                for (HeatDonorCandidate c : tier) {
                    sum += c.dist();
                }
                double mean = sum / tier.size();
                tier.sort(
                        Comparator.comparingDouble((HeatDonorCandidate c) -> Math.abs(c.dist() - mean))
                                .thenComparingLong(c -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
            }
            case ROUND_ROBIN -> {
                tier.sort(
                        Comparator.comparingLong((HeatDonorCandidate c) -> c.ductPos().asLong())
                                .thenComparingInt(c -> c.face().ordinal()));
                int start = Math.floorMod(roundRobinCursor, tier.size());
                if (start != 0) {
                    ArrayList<HeatDonorCandidate> rotated = new ArrayList<>(tier.size());
                    rotated.addAll(tier.subList(start, tier.size()));
                    rotated.addAll(tier.subList(0, start));
                    tier.clear();
                    tier.addAll(rotated);
                }
            }
            case RANDOM -> {
                for (int i2 = tier.size() - 1; i2 > 0; i2--) {
                    int j = level.random.nextInt(i2 + 1);
                    HeatDonorCandidate a = tier.get(i2);
                    tier.set(i2, tier.get(j));
                    tier.set(j, a);
                }
            }
        }
    }

    private record HeatDestCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}

    private record HeatDonorCandidate(BlockPos ductPos, Direction face, int priority, long dist) {}
}
