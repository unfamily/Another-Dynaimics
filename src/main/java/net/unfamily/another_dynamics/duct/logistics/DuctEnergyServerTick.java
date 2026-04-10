package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctEnergyTransportSpec;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctRedstoneLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.network.ModNetwork;
import org.jetbrains.annotations.Nullable;

/**
 * Energy (Forge Energy / RF) logistics: instant transfer only (no in-duct transit shipments).
 *
 * <p>Per action, the amount moved is bounded by {@code extract} of the source duct type and the bottleneck
 * {@code transfer} across the chosen duct path (minimum transfer along the path).</p>
 */
public final class DuctEnergyServerTick {
    private static final int ROUTE_RETRY_CAP = 32;

    private DuctEnergyServerTick() {}

    public static void tick(DuctBlockEntity be, ServerLevel level) {
        if (!be.ductDefinition().map(d -> d.enabledTransportKinds().contains(DuctTransportKind.ENERGY)).orElse(false)) {
            return;
        }
        if (!be.isStorageAttachmentNode()) {
            return;
        }
        DuctEnergyTransportSpec spec = be.energyTransportSpec();
        // Reuse gas cadence defaults as a sane starting point: energy is instant but should still be throttled.
        int rateTicks = 10;
        int sm = be.getStorageMask();
        for (Direction dir : Direction.values()) {
            if ((sm & (1 << dir.ordinal())) == 0) {
                continue;
            }
            DuctFaceLanes lanes = be.getFaceLanes(dir);
            if (!DuctRedstoneLogic.isFaceTransportActive(level, be.getBlockPos(), lanes.redstoneMode)) {
                continue;
            }
            DuctFaceNode node = be.getFaceNode(dir);
            if (node.ticksUntilAction > 0) {
                node.ticksUntilAction--;
                be.setChanged();
                continue;
            }
            node.ticksUntilAction = rateTicks - 1;
            if (lanes.nodeMode == NodeMode.EXTRACTION || lanes.nodeMode == NodeMode.EXTRACTION_FILTERING) {
                tryExtractPush(level, be, dir, spec);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING) {
                tryRetrievePull(level, be, dir, spec);
            } else if (lanes.nodeMode == NodeMode.RETRIEVING_EXTRACTION) {
                tryRetrievePull(level, be, dir, spec);
                tryExtractPush(level, be, dir, spec);
            }
        }
    }

    private static @Nullable IEnergyStorage getEnergyHandlerOnFace(ServerLevel level, BlockPos ductPos, Direction ductFace) {
        BlockPos neighbor = ductPos.relative(ductFace);
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, neighbor, ductFace.getOpposite());
    }

    private static void tryExtractPush(ServerLevel level, DuctBlockEntity sourceBe, Direction sourceFace, DuctEnergyTransportSpec spec) {
        BlockPos srcPos = sourceBe.getBlockPos();
        IEnergyStorage src = getEnergyHandlerOnFace(level, srcPos, sourceFace);
        if (src == null || !src.canExtract()) {
            return;
        }

        long want = Math.min(spec.clampedExtract(), Integer.MAX_VALUE);
        if (want <= 0) {
            return;
        }
        int availableSim = src.extractEnergy((int) want, true);
        if (availableSim <= 0) {
            return;
        }

        ArrayList<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, srcPos, DuctNetworkType.ENERGY));
        if (ducts.size() > 1) {
            ducts.remove(srcPos);
        }
        if (ducts.isEmpty()) {
            return;
        }

        ArrayList<DestCandidate> cands = new ArrayList<>();
        for (BlockPos destPos : ducts) {
            if (!(level.getBlockEntity(destPos) instanceof DuctBlockEntity destBe)) {
                continue;
            }
            int dsm = destBe.getStorageMask();
            OptionalLong dist =
                    destPos.equals(srcPos)
                            ? OptionalLong.of(0L)
                            : OptionalLong.of(1L); // energy is instant; distance is only used for tie-breaks later.
            for (Direction df : Direction.values()) {
                if ((dsm & (1 << df.ordinal())) == 0) {
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

                IEnergyStorage dest = getEnergyHandlerOnFace(level, destPos, df);
                if (dest == null || !dest.canReceive()) {
                    continue;
                }

                int recvSim = dest.receiveEnergy(availableSim, true);
                if (recvSim <= 0) {
                    continue;
                }
                cands.add(new DestCandidate(destPos, df, dist.orElse(0L), recvSim));
            }
        }
        if (cands.isEmpty()) {
            return;
        }
        cands.sort(
                Comparator.comparingInt((DestCandidate c) -> c.moved()).reversed()
                        .thenComparingLong(DestCandidate::dist)
                        .thenComparingLong(c -> c.ductPos().asLong())
                        .thenComparingInt(c -> c.face().ordinal()));

        int tried = 0;
        for (DestCandidate pick : cands) {
            if (tried++ >= ROUTE_RETRY_CAP) {
                return;
            }
            long bottleneck = bottleneckTransferAlongPath(level, srcPos, pick.ductPos());
            if (bottleneck <= 0) {
                continue;
            }
            int cap = (int) Math.min((long) pick.moved(), Math.min(bottleneck, (long) Integer.MAX_VALUE));
            if (cap <= 0) {
                continue;
            }
            IEnergyStorage dest = getEnergyHandlerOnFace(level, pick.ductPos(), pick.face());
            if (dest == null || !dest.canReceive()) {
                continue;
            }
            int recv = dest.receiveEnergy(cap, true);
            if (recv <= 0) {
                continue;
            }
            int extracted = src.extractEnergy(recv, false);
            if (extracted <= 0) {
                return;
            }
            int inserted = dest.receiveEnergy(extracted, false);
            if (inserted <= 0) {
                return;
            }
            if (inserted < extracted) {
                // Best-effort: refund remainder to source if destination lied.
                src.receiveEnergy(extracted - inserted, false);
            }
            sendRayIfVisible(level, srcPos, sourceFace, pick.ductPos(), pick.face(), sourceBe.energyTransportSpec().rayColor());
            return;
        }
    }

    private static void tryRetrievePull(ServerLevel level, DuctBlockEntity retrieverBe, Direction retrieverFace, DuctEnergyTransportSpec spec) {
        BlockPos retrieverPos = retrieverBe.getBlockPos();
        IEnergyStorage dest = getEnergyHandlerOnFace(level, retrieverPos, retrieverFace);
        if (dest == null || !dest.canReceive()) {
            return;
        }
        long wantL = Math.min(spec.clampedExtract(), Integer.MAX_VALUE);
        if (wantL <= 0) {
            return;
        }
        int want = (int) wantL;
        int needSim = dest.receiveEnergy(want, true);
        if (needSim <= 0) {
            return;
        }

        List<BlockPos> ducts = new ArrayList<>(DuctPathfinder.connectedDucts(level, retrieverPos, DuctNetworkType.ENERGY));
        if (ducts.isEmpty()) {
            return;
        }
        for (int donorIdx = 0; donorIdx < ducts.size() && donorIdx < ROUTE_RETRY_CAP; donorIdx++) {
            BlockPos donorPos = ducts.get(donorIdx);
            if (!(level.getBlockEntity(donorPos) instanceof DuctBlockEntity donorBe)) {
                continue;
            }
            int sm = donorBe.getStorageMask();
            for (Direction donorFace : Direction.values()) {
                if ((sm & (1 << donorFace.ordinal())) == 0) {
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
                IEnergyStorage src = getEnergyHandlerOnFace(level, donorPos, donorFace);
                if (src == null || !src.canExtract()) {
                    continue;
                }
                int availSim = src.extractEnergy(needSim, true);
                if (availSim <= 0) {
                    continue;
                }

                long bottleneck = bottleneckTransferAlongPath(level, donorPos, retrieverPos);
                if (bottleneck <= 0) {
                    continue;
                }
                int cap = (int) Math.min((long) availSim, Math.min(bottleneck, (long) Integer.MAX_VALUE));
                if (cap <= 0) {
                    continue;
                }
                int recv2 = dest.receiveEnergy(cap, true);
                if (recv2 <= 0) {
                    continue;
                }
                int extracted = src.extractEnergy(recv2, false);
                if (extracted <= 0) {
                    continue;
                }
                int inserted = dest.receiveEnergy(extracted, false);
                if (inserted <= 0) {
                    src.receiveEnergy(extracted, false);
                    continue;
                }
                if (inserted < extracted) {
                    src.receiveEnergy(extracted - inserted, false);
                }
                sendRayIfVisible(level, donorPos, donorFace, retrieverPos, retrieverFace, donorBe.energyTransportSpec().rayColor());
                return;
            }
        }
    }

    private static void sendRayIfVisible(
            ServerLevel level, BlockPos fromDuct, Direction fromFace, BlockPos toDuct, Direction toFace, String rayColor) {
        if (level == null) {
            return;
        }
        if (rayColor == null || rayColor.isBlank()) {
            rayColor = "#e30b28";
        }
        if (rayColor.equalsIgnoreCase("random")) {
            int rgb = level.random.nextInt(0x1000000);
            ModNetwork.sendEnergyRay(level, fromDuct, fromFace, toDuct, toFace, rgb);
            return;
        }
        String s = rayColor.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return;
        }
        try {
            int rgb = Integer.parseInt(s, 16) & 0xFFFFFF;
            ModNetwork.sendEnergyRay(level, fromDuct, fromFace, toDuct, toFace, rgb);
        } catch (NumberFormatException ignored) {
        }
    }

    private static long bottleneckTransferAlongPath(ServerLevel level, BlockPos fromDuct, BlockPos toDuct) {
        if (fromDuct.equals(toDuct)) {
            if (!(level.getBlockEntity(fromDuct) instanceof DuctBlockEntity be)) {
                return 0L;
            }
            return be.energyTransportSpec().clampedTransfer();
        }
        Optional<List<BlockPos>> pathOpt =
                DuctPathfinder.shortestPath(level, fromDuct, toDuct, 1L, DuctNetworkType.ENERGY);
        if (pathOpt.isEmpty()) {
            return 0L;
        }
        long min = Long.MAX_VALUE;
        for (BlockPos p : pathOpt.get()) {
            if (!(level.getBlockEntity(p) instanceof DuctBlockEntity be)) {
                return 0L;
            }
            long tr = be.energyTransportSpec().clampedTransfer();
            min = Math.min(min, tr);
            if (min <= 0L) {
                return 0L;
            }
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    private record DestCandidate(BlockPos ductPos, Direction face, long dist, int moved) {}
}

