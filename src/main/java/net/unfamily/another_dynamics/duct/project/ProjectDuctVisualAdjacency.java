package net.unfamily.another_dynamics.duct.project;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.unfamily.another_dynamics.duct.AbstractDuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctConnectable;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.integration.mekanism.MekanismHeatCompat;

/**
 * Read-only adjacency rules for project duct pipe visuals (no logistics through project ducts).
 */
public final class ProjectDuctVisualAdjacency {
    private ProjectDuctVisualAdjacency() {}

    public static boolean isDefinitiveDuctNeighbor(BlockState neighborState) {
        return neighborState.getBlock() instanceof DuctConnectable;
    }

    public static boolean isProjectFaceDisconnected(BlockState projectState, Direction faceOnProject) {
        if (!(projectState.getBlock() instanceof ProjectDuctBlock)) {
            return false;
        }
        int bit = 1 << faceOnProject.ordinal();
        return (ProjectDuctBlock.disconnectedMask(projectState) & bit) != 0;
    }

    public static boolean isDefinitiveFaceDisconnected(Level level, BlockPos ductPos, Direction faceOnDuct) {
        BlockEntity be = level.getBlockEntity(ductPos);
        if (!(be instanceof AbstractDuctBlockEntity duct)) {
            return false;
        }
        return (duct.getUserDisconnectedFaceMask() & (1 << faceOnDuct.ordinal())) != 0;
    }

    /**
     * Visual pipe link between a project duct and a definitive duct (or two project ducts). Respects wrench disconnect on
     * both sides; does not participate in logistics graphs.
     */
    public static boolean isVisualPipeLink(Level level, BlockPos from, Direction fromToNeighbor) {
        BlockState fromState = level.getBlockState(from);
        BlockPos to = from.relative(fromToNeighbor);
        BlockState toState = level.getBlockState(to);
        Direction toToFrom = fromToNeighbor.getOpposite();

        if (fromState.getBlock() instanceof ProjectDuctBlock) {
            if (isProjectFaceDisconnected(fromState, fromToNeighbor)) {
                return false;
            }
        } else if (isDefinitiveFaceDisconnected(level, from, fromToNeighbor)) {
            return false;
        }

        if (ProjectDuctNetwork.isProjectDuct(toState.getBlock())) {
            if (isProjectFaceDisconnected(toState, toToFrom)) {
                return false;
            }
            return true;
        }
        if (isDefinitiveDuctNeighbor(toState)) {
            if (isDefinitiveFaceDisconnected(level, to, toToFrom)) {
                return false;
            }
            return true;
        }
        return false;
    }

    /** Definitive duct should show a pipe arm toward an adjacent project duct (geometry only). */
    public static boolean isVisualPipeToProjectDuct(Level level, BlockPos ductPos, Direction dir) {
        BlockPos neighbor = ductPos.relative(dir);
        if (!ProjectDuctNetwork.isProjectDuct(level.getBlockState(neighbor).getBlock())) {
            return false;
        }
        return isVisualPipeLink(level, ductPos, dir);
    }

    /**
     * Mirror of {@link net.unfamily.another_dynamics.duct.DuctBlockEntity} attachment probes: capability neighbors
     * a universal duct could attach to (pipe arm only; no storage voxel).
     */
    public static boolean hasSupportedCapabilityNeighbor(BlockGetter level, BlockPos pos, Direction dir) {
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.isAir()) {
            return false;
        }
        Direction attachSide = dir.getOpposite();
        if (level instanceof Level world) {
            return hasSupportedCapabilityOnLevel(world, neighborPos, attachSide);
        }
        BlockEntity be = level.getBlockEntity(neighborPos);
        if (be != null) {
            return hasSupportedCapabilityOnBlockEntity(be, attachSide);
        }
        return false;
    }

    private static boolean hasSupportedCapabilityOnLevel(Level level, BlockPos neighborPos, Direction attachSide) {
        var item = level.getCapability(Capabilities.ItemHandler.BLOCK, neighborPos, attachSide);
        if (item != null && item.getSlots() > 0) {
            return true;
        }
        if (level.getCapability(Capabilities.FluidHandler.BLOCK, neighborPos, attachSide) != null) {
            return true;
        }
        if (MekanismChemicalCompat.isLoaded()
                && MekanismChemicalCompat.getChemicalHandlerAt(level, neighborPos, attachSide) != null) {
            return true;
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, neighborPos, attachSide) != null) {
            return true;
        }
        return MekanismHeatCompat.isHeatCapabilityAvailable()
                && MekanismHeatCompat.getHeatHandler(level, neighborPos, attachSide) != null;
    }

    private static boolean hasSupportedCapabilityOnBlockEntity(BlockEntity be, Direction attachSide) {
        Level level = be.getLevel();
        if (level == null) {
            return false;
        }
        return hasSupportedCapabilityOnLevel(level, be.getBlockPos(), attachSide);
    }

    public static boolean connectsPipeVisually(Level level, BlockPos pos, Direction dir) {
        return isVisualPipeLink(level, pos, dir);
    }

    /** Faces that would become storage nodes after conversion (external capability neighbors only). */
    public static boolean connectsNodePreview(BlockGetter level, BlockPos pos, Direction dir) {
        BlockState self = level.getBlockState(pos);
        if (self.getBlock() instanceof ProjectDuctBlock && isProjectFaceDisconnected(self, dir)) {
            return false;
        }
        BlockState neighbor = level.getBlockState(pos.relative(dir));
        if (isDefinitiveDuctNeighbor(neighbor) || ProjectDuctNetwork.isProjectDuct(neighbor.getBlock())) {
            return false;
        }
        return hasSupportedCapabilityNeighbor(level, pos, dir);
    }
}
