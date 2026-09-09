package net.unfamily.another_dynamics.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.unfamily.another_dynamics.duct.DuctConnectable;
import net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctReplaceJobs;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.duct.logistics.DuctPathfinder;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConvertJobs;
import net.unfamily.another_dynamics.duct.project.ProjectDuctNetwork;
import net.unfamily.another_dynamics.duct.project.ProjectDuctVisualAdjacency;

/**
 * Debug commands for Another Dynamics.
 *
 * <p>{@code /ad_debug delete [pos]} — mass-remove the physical duct network containing that block
 * (definitive + project ducts), without item drops, in large quiet pulses. Tab-completes the looked-at
 * block, or {@code ~ ~ ~} when not aiming at a block. Omit {@code pos} to use the looked-at block.
 */
public final class AdDebugCommands {
    /** Blocks removed per server tick (quiet flags; no neighbor cascade). */
    private static final int DELETE_BATCH = 2048;
    /** Clients-only + known-shape: avoid UPDATE_NEIGHBORS storm. */
    private static final int QUIET_REMOVE_FLAGS = 2 | 16;

    private static final Map<ResourceKey<Level>, DeleteJob> JOBS = new ConcurrentHashMap<>();

    private AdDebugCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("ad_debug")
                        .requires(src -> src.hasPermission(2))
                        .then(
                                Commands.literal("delete")
                                        .executes(AdDebugCommands::deleteNetworkLooking)
                                        .then(
                                                Commands.argument("pos", BlockPosArgument.blockPos())
                                                        .executes(AdDebugCommands::deleteNetworkAtArg))));
    }

    private static int deleteNetworkLooking(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(
                    Component.literal(
                            "[ad_debug] Look at a duct, or pass pos (tab suggests look / ~ ~ ~)"));
            return 0;
        }
        return deleteNetworkAt(source, ((BlockHitResult) hit).getBlockPos());
    }

    private static int deleteNetworkAtArg(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return deleteNetworkAt(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"));
    }

    private static int deleteNetworkAt(CommandSourceStack source, BlockPos start) {
        ServerLevel level = source.getLevel();
        int x = start.getX();
        int y = start.getY();
        int z = start.getZ();

        if (!isDuctOrProject(level, start)) {
            source.sendFailure(
                    Component.literal(
                            "[ad_debug] No duct/project duct at " + x + " " + y + " " + z));
            return 0;
        }

        ResourceKey<Level> dim = level.dimension();
        if (JOBS.containsKey(dim)) {
            source.sendFailure(Component.literal("[ad_debug] Delete already running in this dimension"));
            return 0;
        }

        DuctReplaceJobs.clearDimension(dim);
        ProjectDuctConvertJobs.clearDimension(dim);
        DuctNetworkOpaquePropagation.clearDimension(dim);

        source.sendSuccess(
                () -> Component.literal("[ad_debug] Scanning duct network from " + x + " " + y + " " + z + "…"),
                true);

        List<BlockPos> members = collectNetworkForceLoad(level, start);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("[ad_debug] Scan found no ducts"));
            return 0;
        }

        Queue<BlockPos> pending = new ArrayDeque<>(members.size());
        for (BlockPos p : members) {
            pending.add(p.immutable());
        }
        UUID requester =
                source.getEntity() != null ? source.getEntity().getUUID() : new UUID(0L, 0L);
        JOBS.put(dim, new DeleteJob(requester, dim, pending, members.size()));
        source.sendSuccess(
                () -> Component.literal("[ad_debug] Removing " + members.size() + " ducts"),
                true);
        return members.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<ResourceKey<Level>> finished = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, DeleteJob> entry : JOBS.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                finished.add(entry.getKey());
                continue;
            }
            DeleteJob job = entry.getValue();
            int removed = processDeleteBatch(level, job);
            if (job.pending.isEmpty()) {
                DuctNetworkCache.invalidate(level);
                DuctNetworkOpaquePropagation.clearDimension(job.dimension);
                server.getPlayerList()
                        .broadcastSystemMessage(
                                Component.literal(
                                        "[ad_debug] Deleted "
                                                + job.removedTotal
                                                + "/"
                                                + job.initialCount
                                                + " ducts"),
                                false);
                finished.add(entry.getKey());
            } else if (removed > 0 && job.removedTotal % (DELETE_BATCH * 4) == 0) {
                server.getPlayerList()
                        .broadcastSystemMessage(
                                Component.literal(
                                        "[ad_debug] … "
                                                + job.removedTotal
                                                + "/"
                                                + job.initialCount),
                                false);
            }
        }
        for (ResourceKey<Level> dim : finished) {
            JOBS.remove(dim);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        JOBS.clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            JOBS.remove(serverLevel.dimension());
        }
    }

    private static int processDeleteBatch(ServerLevel level, DeleteJob job) {
        int removed = 0;
        DuctNetworkCache.pushBulkMutation();
        try {
            while (removed < DELETE_BATCH && !job.pending.isEmpty()) {
                BlockPos pos = job.pending.poll();
                if (pos == null) {
                    continue;
                }
                level.getChunk(pos);
                if (!isDuctOrProject(level, pos)) {
                    continue;
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET_REMOVE_FLAGS);
                removed++;
                job.removedTotal++;
            }
        } finally {
            DuctNetworkCache.popBulkMutation();
        }
        return removed;
    }

    /**
     * Full physical component (force-loads chunks). Includes definitive {@link DuctConnectable} and project ducts
     * adjacent via shared pipe hops / project adjacency.
     */
    private static List<BlockPos> collectNetworkForceLoad(ServerLevel level, BlockPos start) {
        Set<BlockPos> out = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        level.getChunk(start);
        q.add(start.immutable());
        out.add(start.immutable());
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (out.contains(n)) {
                    continue;
                }
                level.getChunk(n);
                if (!isDuctOrProject(level, n)) {
                    continue;
                }
                if (!areConnectedForDelete(level, p, n)) {
                    continue;
                }
                BlockPos imm = n.immutable();
                out.add(imm);
                q.add(imm);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isDuctOrProject(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof DuctConnectable
                || ProjectDuctNetwork.isProjectDuct(state.getBlock());
    }

    private static boolean areConnectedForDelete(ServerLevel level, BlockPos a, BlockPos b) {
        Direction fromA = directionBetween(a, b);
        if (fromA == null) {
            return false;
        }
        boolean aProject = ProjectDuctNetwork.isProjectDuct(level.getBlockState(a).getBlock());
        boolean bProject = ProjectDuctNetwork.isProjectDuct(level.getBlockState(b).getBlock());
        if (aProject && bProject) {
            return ProjectDuctNetwork.arePipeConnected(level, a, b, fromA);
        }
        for (DuctNetworkType net : DuctNetworkType.values()) {
            if (DuctConnectable.isSameNetwork(level, a, net)
                    && DuctConnectable.isSameNetwork(level, b, net)
                    && DuctPathfinder.isPipeNeighbor(level, a, b, net)) {
                return true;
            }
        }
        if (aProject && !bProject) {
            return ProjectDuctVisualAdjacency.isVisualPipeToProjectDuct(level, b, fromA.getOpposite());
        }
        if (!aProject && bProject) {
            return ProjectDuctVisualAdjacency.isVisualPipeToProjectDuct(level, a, fromA);
        }
        return false;
    }

    private static Direction directionBetween(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return null;
        }
        if (dx == 1) {
            return Direction.EAST;
        }
        if (dx == -1) {
            return Direction.WEST;
        }
        if (dy == 1) {
            return Direction.UP;
        }
        if (dy == -1) {
            return Direction.DOWN;
        }
        if (dz == 1) {
            return Direction.SOUTH;
        }
        if (dz == -1) {
            return Direction.NORTH;
        }
        return null;
    }

    private static final class DeleteJob {
        final UUID requester;
        final ResourceKey<Level> dimension;
        final Queue<BlockPos> pending;
        final int initialCount;
        int removedTotal;

        DeleteJob(UUID requester, ResourceKey<Level> dimension, Queue<BlockPos> pending, int initialCount) {
            this.requester = requester;
            this.dimension = dimension;
            this.pending = pending;
            this.initialCount = initialCount;
        }
    }
}
