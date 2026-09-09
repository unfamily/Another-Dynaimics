package net.unfamily.another_dynamics.duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Server-authoritative network opaque flags on {@link DuctBlockEntity} (visible to all players).
 *
 * <p>Structural place/break schedules a time-sliced paint (see {@link #scheduleOpaqueRefresh}) so giant
 * networks do not freeze the edit tick. GUI cycles still paint synchronously.
 */
public final class DuctNetworkOpaquePropagation {
    private static final Map<ResourceKey<Level>, DeferredOpaqueJob> DEFERRED = new ConcurrentHashMap<>();

    private DuctNetworkOpaquePropagation() {}

    /** Physical duct component: BFS over pipe adjacency for any shared {@link DuctNetworkType}. */
    public static Set<BlockPos> connectedPhysicalDucts(Level level, BlockPos start) {
        Set<BlockPos> out = new HashSet<>();
        if (level == null || !(level.getBlockState(start).getBlock() instanceof DuctConnectable)) {
            return out;
        }
        if (level instanceof ServerLevel serverLevel && !serverLevel.isLoaded(start)) {
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
                if (level instanceof ServerLevel serverLevel && !serverLevel.isLoaded(n)) {
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
     * After place or topology change: schedule a deferred component opaque refresh (coalesced per dimension).
     * Does <strong>not</strong> infect mere grid neighbors (wrench-disconnected faces or ducts that do not share
     * a pipe hop).
     */
    public static void onStructuralChange(ServerLevel level, BlockPos pos) {
        scheduleOpaqueRefresh(level, pos);
    }

    /**
     * Coalesce structural opaque paint onto the next server ticks (budgeted). Safe to call many times per edit.
     */
    public static void scheduleOpaqueRefresh(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof DuctConnectable)) {
            return;
        }
        DeferredOpaqueJob job =
                DEFERRED.computeIfAbsent(level.dimension(), k -> new DeferredOpaqueJob());
        BlockPos immutable = pos.immutable();
        if (job.seeds.contains(immutable) || job.visited.contains(immutable)) {
            return;
        }
        job.seeds.add(immutable);
    }

    public static void clearDimension(ResourceKey<Level> dimension) {
        DEFERRED.remove(dimension);
    }

    public static void cancelAll() {
        DEFERRED.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DEFERRED.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long deadline = System.nanoTime() + Config.ductJobTickBudgetNanos();
        List<ResourceKey<Level>> finished = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, DeferredOpaqueJob> entry : DEFERRED.entrySet()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                finished.add(entry.getKey());
                continue;
            }
            if (processDeferredSlice(level, entry.getValue(), deadline)) {
                finished.add(entry.getKey());
            }
        }
        for (ResourceKey<Level> dim : finished) {
            DEFERRED.remove(dim);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        cancelAll();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            clearDimension(serverLevel.dimension());
        }
    }

    /**
     * @return {@code true} if the job for this dimension is fully done
     */
    private static boolean processDeferredSlice(ServerLevel level, DeferredOpaqueJob job, long deadline) {
        while (System.nanoTime() < deadline) {
            if (job.phase == Phase.IDLE) {
                if (job.seeds.isEmpty()) {
                    return true;
                }
                BlockPos seed = job.seeds.poll();
                if (seed == null || !level.isLoaded(seed)) {
                    continue;
                }
                if (!(level.getBlockState(seed).getBlock() instanceof DuctConnectable)) {
                    continue;
                }
                job.visited.clear();
                job.members.clear();
                job.bfsQueue.clear();
                job.sawOpaque = false;
                job.bfsQueue.add(seed);
                job.visited.add(seed);
                job.phase = Phase.SCAN;
            }
            if (job.phase == Phase.SCAN) {
                while (!job.bfsQueue.isEmpty() && System.nanoTime() < deadline) {
                    BlockPos p = job.bfsQueue.poll();
                    if (p == null) {
                        continue;
                    }
                    job.members.add(p);
                    BlockEntity be = level.getBlockEntity(p);
                    if (be instanceof DuctBlockEntity duct && duct.isNetworkOpaqueRendering()) {
                        job.sawOpaque = true;
                    }
                    for (BlockPos n : neighbors6(p)) {
                        if (job.visited.contains(n) || !level.isLoaded(n)) {
                            continue;
                        }
                        if (!isPhysicalPipeNeighbor(level, p, n)) {
                            continue;
                        }
                        job.visited.add(n);
                        job.bfsQueue.add(n);
                    }
                }
                if (!job.bfsQueue.isEmpty()) {
                    return false;
                }
                if (!job.sawOpaque) {
                    job.phase = Phase.IDLE;
                    job.members.clear();
                    job.visited.clear();
                    continue;
                }
                job.paintIndex = 0;
                job.phase = Phase.PAINT;
            }
            if (job.phase == Phase.PAINT) {
                while (job.paintIndex < job.members.size() && System.nanoTime() < deadline) {
                    BlockPos p = job.members.get(job.paintIndex++);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(p);
                    if (be instanceof DuctBlockEntity duct) {
                        duct.setNetworkOpaqueRendering(true);
                    }
                }
                if (job.paintIndex < job.members.size()) {
                    return false;
                }
                job.phase = Phase.IDLE;
                job.members.clear();
                job.visited.clear();
            }
        }
        return job.phase == Phase.IDLE && job.seeds.isEmpty();
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
                new Iterator<>() {
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

    private enum Phase {
        IDLE,
        SCAN,
        PAINT
    }

    private static final class DeferredOpaqueJob {
        final ArrayDeque<BlockPos> seeds = new ArrayDeque<>();
        final ArrayDeque<BlockPos> bfsQueue = new ArrayDeque<>();
        final HashSet<BlockPos> visited = new HashSet<>();
        final List<BlockPos> members = new ArrayList<>();
        Phase phase = Phase.IDLE;
        boolean sawOpaque;
        int paintIndex;
    }
}
