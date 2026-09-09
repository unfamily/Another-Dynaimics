package net.unfamily.another_dynamics.duct.project;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.unfamily.another_dynamics.duct.AbstractDuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
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

    /** True while {@link #updateConnectionsAround} is applying CONNECTIONS via setBlock. */
    private static final ThreadLocal<Boolean> UPDATING_CONNECTIONS = ThreadLocal.withInitial(() -> false);

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

    /** Ghost storage nodes for conversion preview (minus wrench disconnects). */
    public static int effectiveNodePreviewMask(BlockGetter level, BlockPos pos, BlockState state) {
        int preview = computeNodePreviewMask(level, pos);
        return preview & ~disconnectedMask(state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int nodes = effectiveNodePreviewMask(level, pos, state);
        return DuctShapes.forMasks(effectivePipeMask(state), nodes);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int nodes = effectiveNodePreviewMask(level, pos, state);
        return DuctShapes.collisionForMasks(effectivePipeMask(state), nodes);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        return Shapes.empty();
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
    protected InteractionResult useItemOn(
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
            int nodeMask = effectiveNodePreviewMask(level, pos, state);
            Optional<Direction> wrenchFace =
                    DuctShapes.resolveWrenchDisconnectFace(pipeMask, nodeMask, loc[0], loc[1], loc[2]);
            if (wrenchFace.isPresent()) {
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                applyWrenchDisconnect(level, pos, wrenchFace.get());
                return InteractionResult.CONSUME;
            }
            if (!player.isShiftKeyDown()
                    && DuctShapes.canReconnectFromCoreHit(
                            pipeMask,
                            nodeMask,
                            disconnectedMask(state),
                            loc[0],
                            loc[1],
                            loc[2],
                            hitResult.getDirection())) {
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                tryReconnectFace(level, pos, hitResult.getDirection());
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
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
        syncWrenchDisconnectToNeighbor(level, pos, face, true);
        if (level instanceof ServerLevel serverLevel) {
            DuctBlockEntity.onTransitEdgeBroken(serverLevel, pos, neighborPos);
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
        syncWrenchDisconnectToNeighbor(level, pos, face, false);
    }

    private static void syncWrenchDisconnectToNeighbor(
            LevelAccessor level, BlockPos pos, Direction face, boolean disconnect) {
        BlockPos neighborPos = pos.relative(face);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.getBlock() instanceof ProjectDuctBlock) {
            Direction opposite = face.getOpposite();
            int oppositeBit = 1 << opposite.ordinal();
            int nextDisc = disconnect
                    ? disconnectedMask(neighborState) | oppositeBit
                    : disconnectedMask(neighborState) & ~oppositeBit;
            level.setBlock(neighborPos, neighborState.setValue(DISCONNECTED, nextDisc), Block.UPDATE_ALL);
            return;
        }
        BlockEntity neighborBe = level.getBlockEntity(neighborPos);
        if (neighborBe instanceof DuctBlockEntity duct) {
            Direction opposite = face.getOpposite();
            if (disconnect) {
                duct.addUserDisconnectedFace(opposite);
            } else {
                duct.clearUserDisconnectedFacePublic(opposite);
            }
            duct.setChanged();
            duct.refreshFromWorld();
        }
        if (disconnect && level instanceof ServerLevel serverLevel) {
            DuctBlockEntity.onTransitEdgeBroken(serverLevel, pos, neighborPos);
        }
    }

    // onPlace intentionally does not call refreshAround: updateConnectionsAround uses
    // setBlock(CONNECTIONS), which re-enters onPlace and StackOverflows on large networks.
    // Placement uses getStateForPlacement; neighbors refresh via neighborChanged / removal hooks.

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        ProjectDuctNodePreviewTracker.remove(pos);
        ProjectDuctVisualRefresh.refreshAround(level, pos);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            @Nullable Orientation orientation,
            boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
        ProjectDuctVisualRefresh.refreshAround(level, pos);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            LevelReader level,
            ScheduledTickAccess ticks,
            BlockPos pos,
            Direction directionToNeighbour,
            BlockPos neighbourPos,
            BlockState neighbourState,
            RandomSource random) {
        if (state.getValue(WATERLOGGED)) {
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
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
    public boolean canPlaceLiquid(@Nullable LivingEntity user, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
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
                BlockState neighbor = level.getBlockState(pos.relative(direction));
                if (ProjectDuctNetwork.isProjectDuct(neighbor.getBlock())
                        || ProjectDuctVisualAdjacency.isDefinitiveDuctNeighbor(neighbor)) {
                    mask |= 1 << direction.ordinal();
                }
            }
            return mask;
        }
        for (Direction direction : Direction.values()) {
            if (ProjectDuctVisualAdjacency.connectsPipeVisually(world, pos, direction)) {
                mask |= 1 << direction.ordinal();
            }
        }
        return mask;
    }

    public static int computeNodePreviewMask(BlockGetter level, BlockPos pos) {
        int mask = 0;
        for (Direction direction : Direction.values()) {
            if (ProjectDuctVisualAdjacency.connectsNodePreview(level, pos, direction)) {
                mask |= 1 << direction.ordinal();
            }
        }
        return mask;
    }

    /** Refreshes pipe connection masks on this position and touching project ducts. */
    public static void updateConnectionsAround(LevelAccessor level, BlockPos origin) {
        if (Boolean.TRUE.equals(UPDATING_CONNECTIONS.get())) {
            return;
        }
        UPDATING_CONNECTIONS.set(true);
        try {
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
                int pipe = computeConnectionMask(level, pos);
                if (state.getValue(CONNECTIONS) != pipe) {
                    // Clients only — never UPDATE_NEIGHBORS (would cascade neighborChanged → refresh).
                    level.setBlock(pos, state.setValue(CONNECTIONS, pipe), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        } finally {
            UPDATING_CONNECTIONS.set(false);
        }
    }
}
