package net.unfamily.another_dynamics.duct.project;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctReplaceHelper;
import net.unfamily.another_dynamics.integration.cablefacades.CableFacadesCompat;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Converts a connected project-duct network into definitive ducts (one item per converted block).
 * Large networks run as progressive jobs via {@link ProjectDuctConvertJobs}.
 */
public final class ProjectDuctConverter {
    private ProjectDuctConverter() {}

    public static ItemInteractionResult tryConvertNetwork(
            Player player, Level level, BlockPos anchor, ItemStack heldStack, InteractionHand hand) {
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.FAIL;
        }
        if (!ProjectDuctNetwork.isProjectDuct(level.getBlockState(anchor).getBlock())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (ProjectDuctConvertJobs.hasActiveJob(serverPlayer, serverLevel)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("another_dynamics.project_duct.convert.in_progress"), true);
            return ItemInteractionResult.CONSUME;
        }

        String logicalId = DuctReplaceHelper.logicalIdFromReplacementItem(heldStack);
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        logicalId = DuctIds.normalize(logicalId);
        if (DuctDefinitionRegistry.getByLogicalId(logicalId).isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("another_dynamics.project_duct.convert.unknown_type"), true);
            return ItemInteractionResult.FAIL;
        }

        int maxJob = Config.projectDuctConvertMaxPerJob();
        List<BlockPos> discovered = ProjectDuctNetwork.connectedOrdered(level, anchor, maxJob + 1);
        boolean moreBeyondJob = discovered.size() > maxJob;
        List<BlockPos> targets = moreBeyondJob ? discovered.subList(0, maxJob) : discovered;
        if (targets.isEmpty()) {
            return ItemInteractionResult.FAIL;
        }

        if (!player.isCreative()) {
            ItemStack template = ModItems.createDuctStack(logicalId);
            if (countMatchingItems(player, template) <= 0) {
                serverPlayer.displayClientMessage(
                        Component.translatable(
                                "another_dynamics.project_duct.convert.partial", 0, targets.size()),
                        true);
                return ItemInteractionResult.FAIL;
            }
        }

        int converted =
                ProjectDuctConvertJobs.startAndRunFirstBatch(
                        serverPlayer, serverLevel, anchor, logicalId, targets, moreBeyondJob);
        return converted > 0 || ProjectDuctConvertJobs.hasActiveJob(serverPlayer, serverLevel)
                ? ItemInteractionResult.CONSUME
                : ItemInteractionResult.FAIL;
    }

    /** Quiet place: sync clients without neighbor shape storm (Iska-style locality). */
    private static final int QUIET_CONVERT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    static boolean convertOne(ServerLevel level, BlockPos pos, String logicalId) {
        BlockState oldState = level.getBlockState(pos);
        if (!ProjectDuctNetwork.isProjectDuct(oldState.getBlock())) {
            return false;
        }
        boolean waterlogged =
                oldState.hasProperty(BlockStateProperties.WATERLOGGED)
                        && oldState.getValue(BlockStateProperties.WATERLOGGED);

        BlockState newState = ModBlocks.DUCT.get().defaultBlockState();
        if (waterlogged) {
            newState = newState.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        final BlockState placedState = newState;
        final boolean[] placed = {false};
        CableFacadesCompat.runPreservingFacade(
                level, pos, () -> placed[0] = level.setBlock(pos, placedState, QUIET_CONVERT_FLAGS));
        if (!placed[0]) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof DuctBlockEntity duct) {
            duct.setLogicalDuctId(logicalId);
            int disconnected = ProjectDuctBlock.disconnectedMask(oldState);
            if (disconnected != 0) {
                duct.importUserDisconnectedFaceMask(disconnected);
                syncDisconnectToDefinitiveNeighbors(level, pos, disconnected);
            }
            duct.refreshFromWorld();
            // Client BE/packet notify is deferred to the convert batch (see ProjectDuctConvertJobs).
        }
        return true;
    }

    private static void syncDisconnectToDefinitiveNeighbors(
            ServerLevel level, BlockPos pos, int disconnected) {
        for (Direction dir : Direction.values()) {
            int bit = 1 << dir.ordinal();
            if ((disconnected & bit) == 0) {
                continue;
            }
            BlockPos neighborPos = pos.relative(dir);
            if (ProjectDuctNetwork.isProjectDuct(level.getBlockState(neighborPos).getBlock())) {
                continue;
            }
            BlockEntity neighborBe = level.getBlockEntity(neighborPos);
            if (neighborBe instanceof DuctBlockEntity neighbor) {
                neighbor.addUserDisconnectedFace(dir.getOpposite());
                neighbor.setChanged();
                neighbor.refreshFromWorld();
            }
        }
    }

    /** Survival only: return a Project Duct item for the converted block. */
    static void returnProjectDuctToPlayer(Player player, ServerLevel level, BlockPos pos) {
        ItemStack projectDuct = new ItemStack(ModItems.PROJECT_DUCT.get());
        if (!player.getInventory().add(projectDuct)) {
            Block.popResource(level, pos, projectDuct);
        }
    }

    public static int countMatchingItems(Player player, ItemStack template) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean consumeOneMatching(Player player, ItemStack template) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
                continue;
            }
            stack.shrink(1);
            player.getInventory().setChanged();
            return true;
        }
        return false;
    }
}
