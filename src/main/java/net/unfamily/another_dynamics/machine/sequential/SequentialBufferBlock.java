package net.unfamily.another_dynamics.machine.sequential;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

/**
 * Directional sequential buffer: front is output, other faces accept input.
 *
 * <p>Placement: if the clicked block exposes an item/fluid/chemical handler on the contact face,
 * the front points at that block; otherwise the front follows the player's look direction.
 */
public final class SequentialBufferBlock extends Block implements EntityBlock {
    public static final MapCodec<SequentialBufferBlock> CODEC = simpleCodec(SequentialBufferBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public SequentialBufferBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        Direction facing;
        if (hasInteractableHandler(context.getLevel(), context.getClickedPos(), clickedFace)) {
            // Front toward the clicked inventory / tank / chemical handler.
            facing = clickedFace.getOpposite();
        } else {
            // No usable destination on the click target: front looks where the player looks.
            facing = context.getNearestLookingDirection();
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    /**
     * True when {@code pos} exposes an item, fluid, or chemical handler on {@code side} (the face
     * the Sequential Buffer would attach to as its front destination).
     */
    private static boolean hasInteractableHandler(Level level, BlockPos pos, Direction side) {
        var items = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        if (items != null && items.size() > 0) {
            return true;
        }
        if (level.getCapability(Capabilities.Fluid.BLOCK, pos, side) != null) {
            return true;
        }
        return MekanismChemicalCompat.isLoaded()
                && MekanismChemicalCompat.getChemicalHandlerAt(level, pos, side) != null;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SequentialBufferBlockEntity be) {
            serverPlayer.openMenu(be, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SequentialBufferBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return type == ModBlockEntities.SEQUENTIAL_BUFFER.get()
                ? (lvl, p, s, be) -> SequentialBufferBlockEntity.serverTick(lvl, p, s, (SequentialBufferBlockEntity) be)
                : null;
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        // Contents are dropped from SequentialBufferBlockEntity#preRemoveSideEffects.
        if (state.getValue(POWERED)) {
            level.updateNeighborsAt(pos, this);
        }
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
}
