package net.unfamily.another_dynamics.duct.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.AbstractDuctBlock;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Progressive Project Duct → definitive duct conversion: one Shift+click starts a job that converts
 * {@link Config#projectDuctConvertBatchSize()} blocks per pulse (every
 * {@link Config#projectDuctConvertTickInterval()} ticks after the first) up to
 * {@link Config#projectDuctConvertMaxPerJob()}.
 */
public final class ProjectDuctConvertJobs {
    private static final Map<JobKey, Job> JOBS = new ConcurrentHashMap<>();
    /** Same length as Iska Utils scanner loading bar. */
    private static final int LOADING_BAR_LENGTH = 15;

    private ProjectDuctConvertJobs() {}

    public static boolean hasActiveJob(Player player, ServerLevel level) {
        return JOBS.containsKey(JobKey.of(player.getUUID(), level.dimension()));
    }

    /**
     * Starts a job and runs the first batch immediately. Returns converted count in that first batch
     * (0 if nothing converted / job rejected).
     */
    public static int startAndRunFirstBatch(
            ServerPlayer player,
            ServerLevel level,
            BlockPos anchor,
            String logicalId,
            List<BlockPos> targets,
            boolean moreBeyondJob) {
        JobKey key = JobKey.of(player.getUUID(), level.dimension());
        if (JOBS.containsKey(key)) {
            player.displayClientMessage(
                    Component.translatable("another_dynamics.project_duct.convert.in_progress"), true);
            return 0;
        }
        if (targets.isEmpty()) {
            return 0;
        }
        Queue<BlockPos> pending = new ArrayDeque<>(targets.size());
        for (BlockPos pos : orderByChunkLocality(targets)) {
            pending.add(pos.immutable());
        }
        Job job =
                new Job(
                        player.getUUID(),
                        level.dimension(),
                        anchor.immutable(),
                        logicalId,
                        pending,
                        Config.projectDuctConvertBatchSize(),
                        targets.size(),
                        moreBeyondJob);
        JOBS.put(key, job);
        int converted = processBatch(job, player, level);
        if (job.isFinished()) {
            finishJob(job, player, level);
            JOBS.remove(key);
        } else {
            job.cooldownTicks = Config.projectDuctConvertTickInterval();
            sendProgressBar(player, job);
        }
        return converted;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<JobKey> finished = new ArrayList<>();
        for (Map.Entry<JobKey, Job> entry : JOBS.entrySet()) {
            Job job = entry.getValue();
            ServerLevel level = server.getLevel(job.dimension);
            if (level == null) {
                finished.add(entry.getKey());
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(job.playerId);
            if (player == null) {
                if (job.needsTopologyInvalidate) {
                    DuctNetworkCache.invalidate(level);
                }
                finished.add(entry.getKey());
                continue;
            }
            if (job.cooldownTicks > 0) {
                job.cooldownTicks--;
                // Keep the action-bar bar visible between pulses (Iska scanner style).
                if (job.cooldownTicks % 2 == 0) {
                    sendProgressBar(player, job);
                }
                if (job.cooldownTicks > 0) {
                    continue;
                }
            }
            processBatch(job, player, level);
            if (job.isFinished()) {
                finishJob(job, player, level);
                finished.add(entry.getKey());
            } else {
                job.cooldownTicks = Config.projectDuctConvertTickInterval();
                sendProgressBar(player, job);
            }
        }
        for (JobKey key : finished) {
            JOBS.remove(key);
        }
    }

    private static int processBatch(Job job, ServerPlayer player, ServerLevel level) {
        ItemStack template = ModItems.createDuctStack(job.logicalId);
        int budget = job.batchSize;
        if (!player.isCreative()) {
            budget = Math.min(budget, ProjectDuctConverter.countMatchingItems(player, template));
            if (budget <= 0) {
                job.shortOnItems = true;
                job.pending.clear();
                return 0;
            }
        }

        long deadline = System.nanoTime() + Config.ductJobTickBudgetNanos();
        List<BlockPos> convertedPositions = new ArrayList<>(Math.min(budget, 16));
        int converted = 0;
        DuctNetworkCache.pushBulkMutation();
        try {
            BlockPos warm = job.pending.peek();
            if (warm != null) {
                level.getChunk(warm);
            }
            long lastChunkKey = Long.MIN_VALUE;
            while (converted < budget && !job.pending.isEmpty() && System.nanoTime() < deadline) {
                BlockPos pos = job.pending.poll();
                if (pos == null || !ProjectDuctNetwork.isProjectDuct(level.getBlockState(pos).getBlock())) {
                    continue;
                }
                long chunkKey = chunkKey(pos);
                if (chunkKey != lastChunkKey) {
                    level.getChunk(pos);
                    lastChunkKey = chunkKey;
                }
                if (!ProjectDuctConverter.convertOne(level, pos, job.logicalId)) {
                    continue;
                }
                converted++;
                job.convertedTotal++;
                convertedPositions.add(pos);
                if (!player.isCreative()) {
                    ProjectDuctConverter.returnProjectDuctToPlayer(player, level, pos);
                    if (!ProjectDuctConverter.consumeOneMatching(player, template)) {
                        job.shortOnItems = true;
                        job.pending.clear();
                        break;
                    }
                }
            }

            if (converted > 0) {
                Set<BlockPos> refreshDone = new HashSet<>();
                EnumSet<DuctNetworkType> allNetworks = EnumSet.allOf(DuctNetworkType.class);
                for (BlockPos pos : convertedPositions) {
                    BlockState state = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
                    if (refreshDone.add(pos.immutable())) {
                        AbstractDuctBlock.refreshAdjacentDuctBlockEntities(level, pos, allNetworks);
                    }
                }
                ProjectDuctVisualRefresh.refreshAround(level, job.anchor);
                level.playSound(null, job.anchor, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 0.55f, 1.05f);
                job.needsTopologyInvalidate = true;
            }
        } finally {
            DuctNetworkCache.popBulkMutation();
        }
        // Topology invalidate deferred to finishJob (one rebuild instead of per-batch spikes).
        return converted;
    }

    /**
     * Process chunk-local first (cx, cz) so consecutive converts hit the same loaded chunk.
     */
    private static List<BlockPos> orderByChunkLocality(List<BlockPos> targets) {
        List<BlockPos> ordered = new ArrayList<>(targets);
        ordered.sort(
                Comparator.comparingInt((BlockPos p) -> SectionPos.blockToSectionCoord(p.getX()))
                        .thenComparingInt(p -> SectionPos.blockToSectionCoord(p.getZ()))
                        .thenComparingLong(BlockPos::asLong));
        return ordered;
    }

    private static long chunkKey(BlockPos pos) {
        return (((long) SectionPos.blockToSectionCoord(pos.getX())) << 32)
                ^ (SectionPos.blockToSectionCoord(pos.getZ()) & 0xffffffffL);
    }

    private static void finishJob(Job job, ServerPlayer player, ServerLevel level) {
        if (job.needsTopologyInvalidate) {
            DuctNetworkCache.invalidate(level);
        }
        if (job.convertedTotal <= 0) {
            return;
        }
        if (job.shortOnItems) {
            player.displayClientMessage(
                    Component.translatable(
                            "another_dynamics.project_duct.convert.partial",
                            job.convertedTotal,
                            job.initialTargetCount),
                    true);
        } else if (job.moreBeyondJob) {
            player.displayClientMessage(
                    Component.translatable(
                            "another_dynamics.project_duct.convert.job_cap",
                            job.convertedTotal,
                            Config.projectDuctConvertMaxPerJob()),
                    true);
        } else {
            player.displayClientMessage(
                    Component.translatable(
                            "another_dynamics.project_duct.convert.job_done", job.convertedTotal),
                    true);
        }
    }

    /**
     * Action-bar progress bar modeled on Iska Utils scanner ({@code █} + percent).
     * Progress is converted / job target count.
     */
    private static void sendProgressBar(ServerPlayer player, Job job) {
        float percentage =
                job.initialTargetCount <= 0
                        ? 1.0f
                        : Math.min(1.0f, (float) job.convertedTotal / (float) job.initialTargetCount);
        int filledBlocks = Math.round(percentage * LOADING_BAR_LENGTH);
        ChatFormatting fillColor = percentage >= 1.0f ? ChatFormatting.GREEN : ChatFormatting.RED;

        MutableComponent message = Component.empty();
        String percentText = String.format(" %.0f%% ", percentage * 100.0f);
        message.append(Component.literal(percentText).withStyle(fillColor));
        for (int i = 0; i < filledBlocks; i++) {
            message.append(Component.literal("█").withStyle(fillColor));
        }
        for (int i = filledBlocks; i < LOADING_BAR_LENGTH; i++) {
            message.append(Component.literal("█").withStyle(ChatFormatting.DARK_GRAY));
        }
        message.append(Component.literal(percentText).withStyle(fillColor));
        message.append(
                Component.literal(String.format(" %d/%d", job.convertedTotal, job.initialTargetCount))
                        .withStyle(ChatFormatting.GRAY));
        player.displayClientMessage(message, true);
    }

    /** Clears jobs for a dimension (e.g. on unload). */
    public static void clearDimension(ResourceKey<Level> dimension) {
        Iterator<Map.Entry<JobKey, Job>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().dimension.equals(dimension)) {
                it.remove();
            }
        }
    }

    private record JobKey(UUID playerId, ResourceKey<Level> dimension) {
        static JobKey of(UUID playerId, ResourceKey<Level> dimension) {
            return new JobKey(playerId, dimension);
        }
    }

    private static final class Job {
        final UUID playerId;
        final ResourceKey<Level> dimension;
        final BlockPos anchor;
        final String logicalId;
        final Queue<BlockPos> pending;
        final int batchSize;
        final int initialTargetCount;
        final boolean moreBeyondJob;
        int convertedTotal;
        boolean shortOnItems;
        boolean needsTopologyInvalidate;
        /** Ticks remaining before the next convert pulse (0 = run this tick). */
        int cooldownTicks;

        Job(
                UUID playerId,
                ResourceKey<Level> dimension,
                BlockPos anchor,
                String logicalId,
                Queue<BlockPos> pending,
                int batchSize,
                int initialTargetCount,
                boolean moreBeyondJob) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.anchor = anchor;
            this.logicalId = logicalId;
            this.pending = pending;
            this.batchSize = batchSize;
            this.initialTargetCount = initialTargetCount;
            this.moreBeyondJob = moreBeyondJob;
        }

        boolean isFinished() {
            return pending.isEmpty() || shortOnItems;
        }
    }
}
