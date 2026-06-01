package net.unfamily.another_dynamics.duct.project;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.unfamily.another_dynamics.duct.AbstractDuctBlock;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import net.unfamily.another_dynamics.duct.DuctReplaceHelper;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModItems;

import java.util.EnumSet;

/**
 * Converts a connected project-duct network into definitive ducts (one item per converted block).
 */
public final class ProjectDuctConverter {
    private ProjectDuctConverter() {}

    public static ItemInteractionResult tryConvertNetwork(
            Player player, Level level, BlockPos anchor, ItemStack heldStack, InteractionHand hand) {
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemInteractionResult.FAIL;
        }
        if (!ProjectDuctNetwork.isProjectDuct(level.getBlockState(anchor).getBlock())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        String logicalId = DuctReplaceHelper.logicalIdFromReplacementItem(heldStack);
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        logicalId = DuctIds.normalize(logicalId);
        if (DuctDefinitionRegistry.getByLogicalId(logicalId).isEmpty()) {
            player.displayClientMessage(Component.translatable("another_dynamics.project_duct.convert.unknown_type"), true);
            return ItemInteractionResult.FAIL;
        }

        ItemStack template = ModItems.createDuctStack(logicalId);
        List<BlockPos> targets = ProjectDuctNetwork.connectedOrdered(level, anchor);
        int budget = player.isCreative() ? targets.size() : countMatchingItems(player, template);
        int converted = 0;
        java.util.List<BlockPos> convertedPositions = new java.util.ArrayList<>();
        for (BlockPos pos : targets) {
            if (budget <= 0) {
                break;
            }
            if (convertOne(serverLevel, pos, logicalId)) {
                converted++;
                convertedPositions.add(pos);
                returnProjectDuctToPlayer(player, serverLevel, pos);
                if (!player.isCreative()) {
                    if (!consumeOneMatching(player, template)) {
                        break;
                    }
                    budget--;
                }
            }
        }

        if (converted == 0) {
            return ItemInteractionResult.FAIL;
        }

        DuctNetworkCache.invalidate(serverLevel);
        for (BlockPos pos : convertedPositions) {
            AbstractDuctBlock.refreshAdjacentDuctBlockEntities(
                    serverLevel, pos, EnumSet.allOf(DuctNetworkType.class));
        }
        ProjectDuctBlock.updateConnectionsAround(serverLevel, anchor);

        if (player instanceof ServerPlayer sp) {
            if (converted < targets.size()) {
                sp.displayClientMessage(
                        Component.translatable("another_dynamics.project_duct.convert.partial", converted, targets.size()),
                        true);
            } else {
                sp.displayClientMessage(Component.translatable("another_dynamics.project_duct.convert.success", converted), true);
            }
        }
        serverLevel.playSound(null, anchor, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
        return ItemInteractionResult.CONSUME;
    }

    private static boolean convertOne(ServerLevel level, BlockPos pos, String logicalId) {
        BlockState oldState = level.getBlockState(pos);
        if (!ProjectDuctNetwork.isProjectDuct(oldState.getBlock())) {
            return false;
        }
        boolean waterlogged =
                oldState.hasProperty(BlockStateProperties.WATERLOGGED) && oldState.getValue(BlockStateProperties.WATERLOGGED);

        BlockState newState = ModBlocks.DUCT.get().defaultBlockState();
        if (waterlogged) {
            newState = newState.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        if (!level.setBlock(pos, newState, Block.UPDATE_ALL)) {
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
            level.sendBlockUpdated(pos, newState, newState, Block.UPDATE_ALL);
        }
        return true;
    }

    /** Mirrors opposite disconnect bits onto definitive duct neighbors (project neighbors keep BlockState until converted). */
    private static void syncDisconnectToDefinitiveNeighbors(ServerLevel level, BlockPos pos, int disconnected) {
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

    private static void returnProjectDuctToPlayer(Player player, ServerLevel level, BlockPos pos) {
        ItemStack projectDuct = new ItemStack(ModItems.PROJECT_DUCT.get());
        if (!player.getInventory().add(projectDuct)) {
            Block.popResource(level, pos, projectDuct);
        }
    }

    private static int countMatchingItems(Player player, ItemStack template) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean consumeOneMatching(Player player, ItemStack template) {
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
