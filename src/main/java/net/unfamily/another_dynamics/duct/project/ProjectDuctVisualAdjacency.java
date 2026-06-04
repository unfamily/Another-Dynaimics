package net.unfamily.another_dynamics.duct.project;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
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

    /**
     * Mirror of {@link net.unfamily.another_dynamics.duct.DuctBlockEntity} attachment probes: capability neighbors
     * a universal duct could attach to (pipe arm only; no storage voxel).
     */
    public static boolean hasSupportedCapabilityNeighbor(Level level, BlockPos pos, Direction dir) {
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (neighborState.isAir()) {
            return false;
        }
        Direction attachSide = dir.getOpposite();
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

    public static boolean connectsVisually(Level level, BlockPos pos, Direction dir) {
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighborState = level.getBlockState(neighborPos);
        if (ProjectDuctNetwork.isProjectDuct(neighborState.getBlock())) {
            return true;
        }
        if (isDefinitiveDuctNeighbor(neighborState)) {
            return true;
        }
        return hasSupportedCapabilityNeighbor(level, pos, dir);
    }
}
