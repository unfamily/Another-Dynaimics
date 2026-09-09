package net.unfamily.another_dynamics.duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConverter;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Progressive mass replace of definitive ducts (triple-Shift stage 3). Reuses Project convert batch/interval/max
 * config keys 200–202.
 */
public final class DuctReplaceJobs {
    private static final Map<JobKey, Job> JOBS = new ConcurrentHashMap<>();
    private static final int LOADING_BAR_LENGTH = 15;

    private DuctReplaceJobs() {}

    public static boolean hasActiveJob(Player player, ServerLevel level) {
        return JOBS.containsKey(JobKey.of(player.getUUID(), level.dimension()));
    }

    public static int startAndRunFirstBatch(
            ServerPlayer player,
            ServerLevel level,
            BlockPos anchor,
            String logicalId,
            List<BlockPos> targets,
            boolean moreBeyondJob,
            InteractionHand hand) {
        JobKey key = JobKey.of(player.getUUID(), level.dimension());
        if (JOBS.containsKey(key)) {
            player.displayClientMessage(
                    Component.translatable("another_dynamics.duct_replace.in_progress"), true);
            return 0;
        }
        if (targets.isEmpty()) {
            return 0;
        }
        Queue<BlockPos> pending = new ArrayDeque<>(targets.size());
        for (BlockPos pos : targets) {
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
                        moreBeyondJob,
                        hand);
        JOBS.put(key, job);
        int replaced = processBatch(job, player, level);
        if (job.isFinished()) {
            finishJob(job, player);
            JOBS.remove(key);
        } else {
            job.cooldownTicks = Config.projectDuctConvertTickInterval();
            sendProgressBar(player, job);
        }
        return replaced;
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
                finished.add(entry.getKey());
                continue;
            }
            if (job.cooldownTicks > 0) {
                job.cooldownTicks--;
                if (job.cooldownTicks % 2 == 0) {
                    sendProgressBar(player, job);
                }
                if (job.cooldownTicks > 0) {
                    continue;
                }
            }
            processBatch(job, player, level);
            if (job.isFinished()) {
                finishJob(job, player);
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

        int replaced = 0;
        DuctNetworkCache.pushBulkMutation();
        try {
            while (replaced < budget && !job.pending.isEmpty()) {
                BlockPos pos = job.pending.poll();
                if (pos == null || !(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                    continue;
                }
                String current = be.getLogicalDuctId();
                if (current.equals(job.logicalId)) {
                    continue;
                }
                if (DuctReplaceHelper.isCompatible(be, job.logicalId).isPresent()) {
                    continue;
                }
                if (!player.isCreative()
                        && ProjectDuctConverter.countMatchingItems(player, template) <= 0) {
                    job.shortOnItems = true;
                    job.pending.clear();
                    break;
                }
                if (!DuctReplaceHelper.performReplace(
                        player, level, pos, be, current, job.logicalId, job.hand, false)) {
                    continue;
                }
                replaced++;
                job.replacedTotal++;
            }
        } finally {
            DuctNetworkCache.popBulkMutation();
        }
        if (replaced > 0) {
            DuctNetworkCache.invalidate(level);
        }
        return replaced;
    }

    private static void finishJob(Job job, ServerPlayer player) {
        if (job.replacedTotal <= 0) {
            return;
        }
        if (job.shortOnItems) {
            player.displayClientMessage(
                    Component.translatable(
                            "another_dynamics.duct_replace.job_partial",
                            job.replacedTotal,
                            job.initialTargetCount),
                    true);
        } else if (job.moreBeyondJob) {
            player.displayClientMessage(
                    Component.translatable(
                            "another_dynamics.duct_replace.job_cap",
                            job.replacedTotal,
                            Config.projectDuctConvertMaxPerJob()),
                    true);
        } else {
            player.displayClientMessage(
                    Component.translatable("another_dynamics.duct_replace.job_done", job.replacedTotal),
                    true);
        }
    }

    private static void sendProgressBar(ServerPlayer player, Job job) {
        float percentage =
                job.initialTargetCount <= 0
                        ? 1.0f
                        : Math.min(1.0f, (float) job.replacedTotal / (float) job.initialTargetCount);
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
                Component.literal(String.format(" %d/%d", job.replacedTotal, job.initialTargetCount))
                        .withStyle(ChatFormatting.GRAY));
        player.displayClientMessage(message, true);
    }

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
        final InteractionHand hand;
        int replacedTotal;
        boolean shortOnItems;
        int cooldownTicks;

        Job(
                UUID playerId,
                ResourceKey<Level> dimension,
                BlockPos anchor,
                String logicalId,
                Queue<BlockPos> pending,
                int batchSize,
                int initialTargetCount,
                boolean moreBeyondJob,
                InteractionHand hand) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.anchor = anchor;
            this.logicalId = logicalId;
            this.pending = pending;
            this.batchSize = batchSize;
            this.initialTargetCount = initialTargetCount;
            this.moreBeyondJob = moreBeyondJob;
            this.hand = hand;
        }

        boolean isFinished() {
            return pending.isEmpty() || shortOnItems;
        }
    }
}
