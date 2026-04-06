package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

public final class ItemDuctBlock extends Block implements EntityBlock {
    public ItemDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected SoundType getSoundType(BlockState state) {
        return DuctSoundTypes.forLogicalDuct(DuctIds.ITEM_DUCT);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ItemDuctBlockEntity duct) {
            return ItemDuctShapes.forMasks(duct.getPipeMask(), duct.getStorageMask());
        }
        return ItemDuctShapes.coreOnly();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return getShape(state, level, pos, ctx);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ItemDuctBlockEntity duct) {
            return ItemDuctShapes.forMasks(duct.getPipeMask(), duct.getStorageMask());
        }
        return ItemDuctShapes.coreOnly();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ItemDuctBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTicker(type);
    }

    @Nullable
    private static <T extends BlockEntity> BlockEntityTicker<T> createTicker(BlockEntityType<T> type) {
        return type == ModBlockEntities.ITEM_DUCT.get()
                ? (level, pos, blockState, be) -> {
                    ItemDuctBlockEntity duct = (ItemDuctBlockEntity) be;
                    if (level.isClientSide()) {
                        ItemDuctBlockEntity.clientTick(level, pos, blockState, duct);
                    } else if (level instanceof ServerLevel sl) {
                        duct.serverTickPipe(sl);
                    }
                }
                : null;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        refreshAt(level, pos);
        notifyNeighborDucts(level, pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            notifyNeighborDucts(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        refreshAt(level, pos);
    }

    private static void refreshAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ItemDuctBlockEntity be) {
            be.refreshFromWorld();
        }
    }

    private static void notifyNeighborDucts(LevelAccessor level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockEntity(n) instanceof ItemDuctBlockEntity be) {
                be.refreshFromWorld();
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ItemDuctBlockEntity duct) {
                serverPlayer.openMenu(duct);
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ItemDuctBlockEntity duct) {
                serverPlayer.openMenu(duct);
                return ItemInteractionResult.CONSUME;
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }
}
