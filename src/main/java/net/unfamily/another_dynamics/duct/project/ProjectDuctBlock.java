package net.unfamily.another_dynamics.duct.project;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.unfamily.another_dynamics.duct.DuctReplaceHelper;
import net.unfamily.another_dynamics.duct.DuctShapes;
import net.unfamily.another_dynamics.duct.DuctWrenchTags;
import net.unfamily.another_dynamics.registry.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Placement scaffold duct: plain block id (Construction Sticks friendly), no BlockEntity, no GUI.
 * Shift+click with a definitive duct item converts the connected project network.
 */
public final class ProjectDuctBlock extends Block implements SimpleWaterloggedBlock {
    public static final IntegerProperty CONNECTIONS = IntegerProperty.create("connections", 0, 63);
    /** Wrench-disconnected pipe faces (same bit layout as {@link #CONNECTIONS}). */
    public static final IntegerProperty DISCONNECTED = IntegerProperty.create("disconnected", 0, 63);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    public ProjectDuctBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                defaultBlockState().setValue(CONNECTIONS, 0).setValue(DISCONNECTED, 0).setValue(WATERLOGGED, false));
    }

    public static int connectionMask(BlockState state) {
        return state.hasProperty(CONNECTIONS) ? state.getValue(CONNECTIONS) : 0;
    }

    public static int disconnectedMask(BlockState state) {
        return state.hasProperty(DISCONNECTED) ? state.getValue(DISCONNECTED) : 0;
    }

    /** Pipe arms shown / targeted by wrench (connections minus manual disconnects). */
    public static int effectivePipeMask(BlockState state) {
        return connectionMask(state) & ~disconnectedMask(state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return DuctShapes.forMasks(effectivePipeMask(state), 0);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return getShape(state, level, pos, ctx);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    @Override
    protected SoundType getSoundType(BlockState state) {
        return SoundType.COPPER;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(new ItemStack(ModItems.PROJECT_DUCT.get()));
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
        if (player.isShiftKeyDown() && DuctReplaceHelper.isDuctReplacementCandidate(stack)) {
            return ProjectDuctConverter.tryConvertNetwork(player, level, pos, stack, hand);
        }
        if (DuctWrenchTags.isWrench(stack)) {
            double[] loc = localHit(pos, hitResult);
            int pipeMask = effectivePipeMask(state);
            Optional<Direction> wrenchFace =
                    DuctShapes.resolveWrenchDisconnectFace(pipeMask, 0, loc[0], loc[1], loc[2]);
            if (wrenchFace.isPresent()) {
                if (level.isClientSide()) {
                    return ItemInteractionResult.SUCCESS;
                }
                applyWrenchDisconnect(level, pos, wrenchFace.get());
                return ItemInteractionResult.CONSUME;
            }
            if (!player.isShiftKeyDown()
                    && DuctShapes.canReconnectFromCoreHit(
                            pipeMask,
                            0,
                            disconnectedMask(state),
                            loc[0],
                            loc[1],
                            loc[2],
                            hitResult.getDirection())) {
                if (level.isClientSide()) {
                    return ItemInteractionResult.SUCCESS;
                }
                tryReconnectFace(level, pos, hitResult.getDirection());
                return ItemInteractionResult.CONSUME;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    private static double[] localHit(BlockPos pos, BlockHitResult hit) {
        Vec3 local = hit.getLocation();
        return new double[] {local.x - pos.getX(), local.y - pos.getY(), local.z - pos.getZ()};
    }

    public static void applyWrenchDisconnect(LevelAccessor level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ProjectDuctBlock)) {
            return;
        }
        int bit = 1 << face.ordinal();
        level.setBlock(pos, state.setValue(DISCONNECTED, disconnectedMask(state) | bit), Block.UPDATE_ALL);
        BlockPos neighborPos = pos.relative(face);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.getBlock() instanceof ProjectDuctBlock) {
            Direction opposite = face.getOpposite();
            int oppositeBit = 1 << opposite.ordinal();
            level.setBlock(
                    neighborPos,
                    neighborState.setValue(DISCONNECTED, disconnectedMask(neighborState) | oppositeBit),
                    Block.UPDATE_ALL);
        }
    }

    public static void tryReconnectFace(LevelAccessor level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ProjectDuctBlock)) {
            return;
        }
        int bit = 1 << face.ordinal();
        if ((disconnectedMask(state) & bit) == 0) {
            return;
        }
        level.setBlock(pos, state.setValue(DISCONNECTED, disconnectedMask(state) & ~bit), Block.UPDATE_ALL);
        BlockPos neighborPos = pos.relative(face);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.getBlock() instanceof ProjectDuctBlock) {
            Direction opposite = face.getOpposite();
            int oppositeBit = 1 << opposite.ordinal();
            level.setBlock(
                    neighborPos,
                    neighborState.setValue(DISCONNECTED, disconnectedMask(neighborState) & ~oppositeBit),
                    Block.UPDATE_ALL);
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        updateConnectionsAround(level, pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            updateConnectionsAround(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
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
        return state.setValue(CONNECTIONS, computeConnectionMask(level, pos));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        FluidState fluid = ctx.getLevel().getFluidState(ctx.getClickedPos());
        BlockState placed = defaultBlockState().setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
        return placed.setValue(CONNECTIONS, computeConnectionMask(ctx.getLevel(), ctx.getClickedPos()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTIONS, DISCONNECTED, WATERLOGGED);
    }

    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean canPlaceLiquid(@Nullable Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return fluid == Fluids.WATER && !state.getValue(WATERLOGGED);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (fluidState.getType() != Fluids.WATER || state.getValue(WATERLOGGED)) {
            return false;
        }
        level.setBlock(pos, state.setValue(WATERLOGGED, true), Block.UPDATE_ALL);
        level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return true;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    public static int computeConnectionMask(BlockGetter level, BlockPos pos) {
        int mask = 0;
        if (!(level instanceof Level world)) {
            for (Direction direction : Direction.values()) {
                if (ProjectDuctNetwork.isProjectDuct(level.getBlockState(pos.relative(direction)).getBlock())) {
                    mask |= 1 << direction.ordinal();
                }
            }
            return mask;
        }
        for (Direction direction : Direction.values()) {
            if (ProjectDuctVisualAdjacency.connectsVisually(world, pos, direction)) {
                mask |= 1 << direction.ordinal();
            }
        }
        return mask;
    }

    /** Refreshes connection masks on this position and touching project ducts. */
    public static void updateConnectionsAround(LevelAccessor level, BlockPos origin) {
        java.util.HashSet<BlockPos> positions = new java.util.HashSet<>();
        positions.add(origin);
        for (Direction direction : Direction.values()) {
            positions.add(origin.relative(direction));
        }
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ProjectDuctBlock)) {
                continue;
            }
            int mask = computeConnectionMask(level, pos);
            if (state.getValue(CONNECTIONS) != mask) {
                level.setBlock(pos, state.setValue(CONNECTIONS, mask), Block.UPDATE_CLIENTS);
            }
        }
    }
}
