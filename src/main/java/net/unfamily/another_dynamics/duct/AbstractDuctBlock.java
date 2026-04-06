package net.unfamily.another_dynamics.duct;

import java.util.Collections;
import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Geometry and neighbor refresh shared by duct blocks. Subclasses supply sound, voxel shape from masks, BE/ticker, and
 * which {@link DuctNetworkType}s this block joins (single-type or hybrid).
 */
public abstract class AbstractDuctBlock extends Block implements EntityBlock, DuctConnectable {

    protected AbstractDuctBlock(Properties properties) {
        super(properties);
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
        EnumSet<DuctNetworkType> mine = ductNetworkTypes();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = level.getBlockState(n);
            Block neighborBlock = ns.getBlock();
            if (!(neighborBlock instanceof DuctConnectable nb)) {
                continue;
            }
            if (Collections.disjoint(mine, nb.ductNetworkTypes())) {
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
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            notifySameNetworkNeighbors(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void neighborChanged(
            BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        refreshAt(level, pos);
    }
}
