package net.unfamily.another_dynamics.duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.logistics.DuctRoutingEndpointIndex;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConverter;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Progressive mass replace of definitive ducts (triple-Shift stage 3). Reuses Project convert batch/interval/max
 * config keys 200–203. Discovery ({@code isCompatible}) is time-sliced; the first replace pulse is deferred so the
 * click tick does not also mutate the network.
 */
public final class DuctReplaceJobs {
    private static final Map<JobKey, Job> JOBS = new ConcurrentHashMap<>();
    private static final int LOADING_BAR_LENGTH = 15;
    private static final int QUIET_SYNC_FLAGS = Block.UPDATE_CLIENTS;

    private DuctReplaceJobs() {}

    public static boolean hasActiveJob(Player player, ServerLevel level) {
        return JOBS.containsKey(JobKey.of(player.getUUID(), level.dimension()));
    }

    /**
     * Starts a discovering job from pre-collected candidate positions (no {@code isCompatible} yet). Does not run a
     * replace batch on this tick.
     *
     * @return {@code true} if the job was started
     */
    public static boolean startDiscovering(
            ServerPlayer player,
            ServerLevel level,
            BlockPos anchor,
            String logicalId,
            List<BlockPos> candidates,
            int maxTargets,
            InteractionHand hand) {
        JobKey key = JobKey.of(player.getUUID(), level.dimension());
        if (JOBS.containsKey(key)) {
            player.sendSystemMessage(
                    Component.translatable("another_dynamics.duct_replace.in_progress"), true);
            return false;
        }
        if (candidates == null || candidates.isEmpty() || maxTargets <= 0) {
            return false;
        }
        boolean moreBeyondJob = candidates.size() > maxTargets;
        List<BlockPos> capped =
                moreBeyondJob ? new ArrayList<>(candidates.subList(0, maxTargets)) : new ArrayList<>(candidates);
        Queue<BlockPos> discoverQueue = new ArrayDeque<>(capped.size());
        for (BlockPos pos : orderByChunkLocality(capped)) {
            discoverQueue.add(pos.immutable());
        }
        Job job =
                new Job(
                        player.getUUID(),
                        level.dimension(),
                        anchor.immutable(),
                        logicalId,
                        discoverQueue,
                        new ArrayDeque<>(),
                        Config.projectDuctConvertBatchSize(),
                        capped.size(),
                        moreBeyondJob,
                        hand,
                        true);
        JOBS.put(key, job);
        // Defer first work to the next pulse so the click tick stays light.
        job.cooldownTicks = Config.projectDuctConvertTickInterval();
        sendProgressBar(player, job);
        return true;
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
            if (job.discovering) {
                processDiscoverSlice(job, player, level);
                if (job.discovering) {
                    job.cooldownTicks = Config.projectDuctConvertTickInterval();
                    sendProgressBar(player, job);
                    continue;
                }
                // Discovery finished this tick: do not also replace; schedule first replace pulse.
                if (job.pending.isEmpty()) {
                    finishJob(job, player);
                    finished.add(entry.getKey());
                    continue;
                }
                job.initialTargetCount = job.pending.size();
                job.cooldownTicks = Config.projectDuctConvertTickInterval();
                sendProgressBar(player, job);
                continue;
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

    private static void processDiscoverSlice(Job job, ServerPlayer player, ServerLevel level) {
        long deadline = System.nanoTime() + Config.ductJobTickBudgetNanos();
        String target = DuctIds.normalize(job.logicalId);
        BlockPos warm = job.discoverQueue.peek();
        if (warm != null) {
            level.getChunk(warm);
        }
        long lastChunkKey = Long.MIN_VALUE;
        while (!job.discoverQueue.isEmpty() && System.nanoTime() < deadline) {
            BlockPos pos = job.discoverQueue.poll();
            if (pos == null) {
                continue;
            }
            long chunkKey = chunkKey(pos);
            if (chunkKey != lastChunkKey) {
                level.getChunk(pos);
                lastChunkKey = chunkKey;
            }
            if (!(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                continue;
            }
            if (be.getLogicalDuctId().equals(target)) {
                continue;
            }
            if (DuctReplaceHelper.isCompatible(be, target).isPresent()) {
                continue;
            }
            job.pending.add(pos.immutable());
            job.discoveredAccepted++;
        }
        if (job.discoverQueue.isEmpty()) {
            job.discovering = false;
            if (job.pending.isEmpty() && job.replacedTotal <= 0) {
                player.sendSystemMessage(
                        Component.translatable("another_dynamics.duct_replace.no_compatible_ducts"), true);
            }
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
        List<BlockPos> replacedPositions = new ArrayList<>(Math.min(budget, 16));
        int replaced = 0;
        BlockPos warm = job.pending.peek();
        if (warm != null) {
            level.getChunk(warm);
        }
        long lastChunkKey = Long.MIN_VALUE;
        while (replaced < budget && !job.pending.isEmpty() && System.nanoTime() < deadline) {
            BlockPos pos = job.pending.poll();
            if (pos == null || !(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                continue;
            }
            long chunkKey = chunkKey(pos);
            if (chunkKey != lastChunkKey) {
                level.getChunk(pos);
                lastChunkKey = chunkKey;
            }
            String current = be.getLogicalDuctId();
            if (current.equals(job.logicalId)) {
                continue;
            }
            if (DuctReplaceHelper.isCompatible(be, job.logicalId).isPresent()) {
                continue;
            }
            if (!player.isCreative() && ProjectDuctConverter.countMatchingItems(player, template) <= 0) {
                job.shortOnItems = true;
                job.pending.clear();
                break;
            }
            if (!DuctReplaceHelper.performReplace(
                    player, level, pos, be, current, job.logicalId, job.hand, false, true)) {
                continue;
            }
            replaced++;
            job.replacedTotal++;
            replacedPositions.add(pos);
        }
        if (replaced > 0) {
            for (BlockPos pos : replacedPositions) {
                BlockState state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, QUIET_SYNC_FLAGS);
            }
            // Logical-id swap does not change pipe topology.
            DuctRoutingEndpointIndex.onSettingsChanged(level);
            level.playSound(
                    null, job.anchor, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.35f, 1.2f);
        }
        return replaced;
    }

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

    private static void finishJob(Job job, ServerPlayer player) {
        if (job.replacedTotal <= 0) {
            return;
        }
        if (job.shortOnItems) {
            player.sendSystemMessage(
                    Component.translatable(
                            "another_dynamics.duct_replace.job_partial",
                            job.replacedTotal,
                            job.initialTargetCount),
                    true);
        } else if (job.moreBeyondJob) {
            player.sendSystemMessage(
                    Component.translatable(
                            "another_dynamics.duct_replace.job_cap",
                            job.replacedTotal,
                            Config.projectDuctConvertMaxPerJob()),
                    true);
        } else {
            player.sendSystemMessage(
                    Component.translatable("another_dynamics.duct_replace.job_done", job.replacedTotal),
                    true);
        }
    }

    private static void sendProgressBar(ServerPlayer player, Job job) {
        int denom =
                job.discovering
                        ? Math.max(1, job.candidateCap)
                        : Math.max(1, job.initialTargetCount);
        int numer = job.discovering ? job.discoveredAccepted : job.replacedTotal;
        float percentage = Math.min(1.0f, (float) numer / (float) denom);
        int filledBlocks = Math.round(percentage * LOADING_BAR_LENGTH);
        ChatFormatting fillColor =
                !job.discovering && percentage >= 1.0f ? ChatFormatting.GREEN : ChatFormatting.RED;

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
                Component.literal(String.format(" %d/%d", numer, denom)).withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(message, true);
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
        final Queue<BlockPos> discoverQueue;
        final Queue<BlockPos> pending;
        final int batchSize;
        final int candidateCap;
        final boolean moreBeyondJob;
        final InteractionHand hand;
        int initialTargetCount;
        int discoveredAccepted;
        int replacedTotal;
        boolean shortOnItems;
        boolean discovering;
        int cooldownTicks;

        Job(
                UUID playerId,
                ResourceKey<Level> dimension,
                BlockPos anchor,
                String logicalId,
                Queue<BlockPos> discoverQueue,
                Queue<BlockPos> pending,
                int batchSize,
                int candidateCap,
                boolean moreBeyondJob,
                InteractionHand hand,
                boolean discovering) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.anchor = anchor;
            this.logicalId = logicalId;
            this.discoverQueue = discoverQueue;
            this.pending = pending;
            this.batchSize = batchSize;
            this.candidateCap = candidateCap;
            this.initialTargetCount = candidateCap;
            this.moreBeyondJob = moreBeyondJob;
            this.hand = hand;
            this.discovering = discovering;
        }

        boolean isFinished() {
            return !discovering && (pending.isEmpty() || shortOnItems);
        }
    }
}
