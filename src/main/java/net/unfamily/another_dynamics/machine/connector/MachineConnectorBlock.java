package net.unfamily.another_dynamics.machine.connector;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.integration.mekanism.MekanismHeatCompat;

import org.jetbrains.annotations.Nullable;

/**
 * Capability face extender: outer faces forward world-aligned machine caps; front (toward machine) is inert.
 */
public final class MachineConnectorBlock extends Block implements EntityBlock {
    public static final MapCodec<MachineConnectorBlock> CODEC = simpleCodec(MachineConnectorBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public MachineConnectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        Direction facing;
        if (hasInteractableHandler(context.getLevel(), context.getClickedPos(), clickedFace)) {
            facing = clickedFace.getOpposite();
        } else {
            facing = context.getNearestLookingDirection();
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    /**
     * True when {@code pos} exposes a known handler on {@code side} (contact face for placement).
     */
    private static boolean hasInteractableHandler(Level level, BlockPos pos, Direction side) {
        // Allow chaining: face toward another Machine Connector even if its contact face is inert.
        if (level.getBlockEntity(pos) instanceof MachineConnectorBlockEntity) {
            return true;
        }
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null) {
            return true;
        }
        if (MekanismChemicalCompat.isLoaded()
                && MekanismChemicalCompat.getChemicalHandlerAt(level, pos, side) != null) {
            return true;
        }
        if (MekanismHeatCompat.isHeatCapabilityAvailable()
                && MekanismHeatCompat.getHeatHandler(level, pos, side) != null) {
            return true;
        }
        return MachineConnectorSoftCaps.hasAnySoftHandler(level, pos, side);
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
    protected void neighborChanged(
            BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MachineConnectorBlockEntity be) {
            be.invalidateCapabilities();
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineConnectorBlockEntity(pos, state);
    }
}
