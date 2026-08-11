package net.unfamily.another_dynamics.duct;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Server-authoritative network opaque flags on {@link DuctBlockEntity} (visible to all players).
 */
public final class DuctNetworkOpaquePropagation {
    private DuctNetworkOpaquePropagation() {}

    /** Physical duct component: BFS over pipe adjacency for any shared {@link DuctNetworkType}. */
    public static Set<BlockPos> connectedPhysicalDucts(Level level, BlockPos start) {
        Set<BlockPos> out = new HashSet<>();
        if (level == null || !(level.getBlockState(start).getBlock() instanceof DuctConnectable)) {
            return out;
        }
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        out.add(start);
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            for (BlockPos n : neighbors6(p)) {
                if (out.contains(n)) {
                    continue;
                }
                if (isPhysicalPipeNeighbor(level, p, n)) {
                    out.add(n);
                    q.add(n);
                }
            }
        }
        return out;
    }

    public static boolean componentHasNetworkOpaque(Level level, BlockPos start) {
        for (BlockPos p : connectedPhysicalDucts(level, start)) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof DuctBlockEntity duct && duct.isNetworkOpaqueRendering()) {
                return true;
            }
        }
        return false;
    }

    public static void setNetworkOpaqueOnComponent(ServerLevel level, BlockPos anchor, boolean opaque) {
        for (BlockPos p : connectedPhysicalDucts(level, anchor)) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof DuctBlockEntity duct) {
                duct.setNetworkOpaqueRendering(opaque);
            }
        }
    }

    /**
     * After place or topology change: if the true pipe-connected component already has any network-opaque
     * flag, paint the whole component. Does <strong>not</strong> infect mere grid neighbors (wrench-disconnected
     * faces or ducts that do not share a pipe hop — e.g. different transport kinds).
     */
    public static void onStructuralChange(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof DuctConnectable)) {
            return;
        }
        if (componentHasNetworkOpaque(level, pos)) {
            setNetworkOpaqueOnComponent(level, pos, true);
        }
    }

    /**
     * GUI opaque button cycle from duct anchor (effective display state → next state).
     */
    public static void advanceOpaqueCycle(ServerPlayer player, BlockPos anchor) {
        Level level = player.level();
        BlockEntity be = level.getBlockEntity(anchor);
        if (!(be instanceof DuctBlockEntity)) {
            advancePlayerOnlyCycle(player);
            return;
        }
        var att = ModAttachments.DUCT_PLAYER_OPAQUE.get();
        DuctPlayerOpaqueState state = player.getData(att);
        boolean allActive = state.allOpaqueActive();
        boolean networkActive = componentHasNetworkOpaque(level, anchor);

        if (allActive) {
            player.setData(att, state.withAllOpaqueActive(false));
            if (level instanceof ServerLevel serverLevel) {
                setNetworkOpaqueOnComponent(serverLevel, anchor, false);
            }
        } else if (networkActive) {
            player.setData(att, state.withAllOpaqueActive(true));
        } else if (level instanceof ServerLevel serverLevel) {
            setNetworkOpaqueOnComponent(serverLevel, anchor, true);
        }
    }

    /**
     * GUI opaque button backwards cycle:
     * <ul>
     *   <li>All → Network (disable All only; keep network flag)</li>
     *   <li>Network → Off (clear network flag)</li>
     *   <li>Off → Off</li>
     * </ul>
     */
    public static void retreatOpaqueCycle(ServerPlayer player, BlockPos anchor) {
        Level level = player.level();
        var att = ModAttachments.DUCT_PLAYER_OPAQUE.get();
        DuctPlayerOpaqueState state = player.getData(att);
        if (state.allOpaqueActive()) {
            player.setData(att, state.withAllOpaqueActive(false));
            return;
        }
        if (level instanceof ServerLevel serverLevel && componentHasNetworkOpaque(level, anchor)) {
            setNetworkOpaqueOnComponent(serverLevel, anchor, false);
        }
    }

    private static void advancePlayerOnlyCycle(ServerPlayer player) {
        var att = ModAttachments.DUCT_PLAYER_OPAQUE.get();
        DuctPlayerOpaqueState state = player.getData(att);
        player.setData(att, state.withAllOpaqueActive(!state.allOpaqueActive()));
    }

    private static boolean isPhysicalPipeNeighbor(Level level, BlockPos a, BlockPos b) {
        for (DuctNetworkType net : DuctNetworkType.values()) {
            if (DuctConnectable.isSameNetwork(level, a, net)
                    && DuctConnectable.isSameNetwork(level, b, net)
                    && DuctPathfinder.isPipeNeighbor(level, a, b, net)) {
                return true;
            }
        }
        return false;
    }

    private static Iterable<BlockPos> neighbors6(BlockPos p) {
        return () ->
                new java.util.Iterator<>() {
                    private int i = 0;

                    @Override
                    public boolean hasNext() {
                        return i < 6;
                    }

                    @Override
                    public BlockPos next() {
                        return switch (i++) {
                            case 0 -> p.north();
                            case 1 -> p.south();
                            case 2 -> p.east();
                            case 3 -> p.west();
                            case 4 -> p.above();
                            default -> p.below();
                        };
                    }
                };
    }
}
