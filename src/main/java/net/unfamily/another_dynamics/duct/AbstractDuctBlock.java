package net.unfamily.another_dynamics.duct;

import java.util.Collections;
import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Geometry and neighbor refresh shared by duct blocks. Subclasses supply sound, voxel shape from masks, BE/ticker, and
 * which {@link DuctNetworkType}s this block joins (single-type or hybrid).
 */
public abstract class AbstractDuctBlock extends Block implements EntityBlock, DuctConnectable, SimpleWaterloggedBlock {

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    protected AbstractDuctBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WATERLOGGED, false));
    }

    @Override
    public abstract EnumSet<DuctNetworkType> ductNetworkTypes();

    protected abstract SoundType soundTypeForDuct();

    @Override
    protected SoundType getSoundType(BlockState state) {
        return soundTypeForDuct();
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    protected abstract VoxelShape shapeForMasks(int pipeMask, int storageMask);

    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        BlockEntity be = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof DuctBlockEntity ductBe) {
            java.util.ArrayList<net.minecraft.world.item.ItemStack> drops = new java.util.ArrayList<>();
            var drop = new net.minecraft.world.item.ItemStack(this.asItem());
            drop.set(ModDataComponents.DUCT_LOGICAL_ID.get(), ductBe.getLogicalDuctId());
            drops.add(drop);
            ductBe.appendUpgradeAndGuiDrops(drops, builder.getLevel().registryAccess());
            return drops;
        }
        return super.getDrops(state, builder);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AbstractDuctBlockEntity duct) {
            return shapeForMasks(duct.getPipeMask(), duct.getVisualStorageMask());
        }
        return DuctShapes.coreOnly();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return getShape(state, level, pos, ctx);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    protected static void refreshAt(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AbstractDuctBlockEntity duct) {
            duct.refreshFromWorld();
        }
    }

    protected void notifySameNetworkNeighbors(LevelAccessor level, BlockPos pos) {
        refreshAdjacentDuctBlockEntities(level, pos, ductNetworkTypes());
    }

    /**
     * Refreshes connection masks on all directly touching duct block entities that share a {@link DuctNetworkType} with
     * {@code networks} (e.g. after wrench disconnect / reconnect).
     */
    public static void refreshAdjacentDuctBlockEntities(
            LevelAccessor level, BlockPos pos, EnumSet<DuctNetworkType> networks) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = level.getBlockState(n);
            Block neighborBlock = ns.getBlock();
            if (!(neighborBlock instanceof DuctConnectable nb)) {
                continue;
            }
            if (Collections.disjoint(networks, nb.ductNetworkTypes())) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(n);
            if (be instanceof AbstractDuctBlockEntity duct) {
                duct.refreshFromWorld();
            }
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        refreshAt(level, pos);
        notifySameNetworkNeighbors(level, pos);
        if (!level.isClientSide() && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation.onStructuralChange(serverLevel, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            notifySameNetworkNeighbors(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        FluidState fluid = ctx.getLevel().getFluidState(ctx.getClickedPos());
        return defaultBlockState().setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        // Never allow a duct block to be replaced by fluids (prevents flowing fluids from deleting the block).
        // Water can still be inserted via waterlogging (placeLiquid), not by replacement.
        return false;
    }

    @Override
    public boolean canPlaceLiquid(@Nullable Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        // Only water is allowed to be placed into ducts (via bucket or fluid placement logic).
        return fluid == Fluids.WATER && !state.getValue(WATERLOGGED);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        // Only accept water; all other fluids must not be placeable where ducts are.
        if (fluidState.getType() != Fluids.WATER || state.getValue(WATERLOGGED)) {
            return false;
        }
        level.setBlock(pos, state.setValue(WATERLOGGED, true), 3);
        level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return true;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WATERLOGGED);
    }

    @Override
    protected void neighborChanged(
            BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        refreshAt(level, pos);
    }
}
