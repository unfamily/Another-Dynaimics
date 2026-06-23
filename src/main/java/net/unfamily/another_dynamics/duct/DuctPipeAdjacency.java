package net.unfamily.another_dynamics.duct;

import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Item-duct pipe adjacency: same {@link DuctNetworkType#ITEM} plus {@link DuctDefinition#connectWithCompatible()}.
 */
public final class DuctPipeAdjacency {
    private DuctPipeAdjacency() {}

    /**
     * After chunk load, block state can show a duct before the {@link DuctBlockEntity} is attached; treat as pending,
     * not broken.
     */
    public static boolean isDuctBlockEntityPending(Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockState(pos).getBlock() instanceof AbstractDuctBlock
                && !(level.getBlockEntity(pos) instanceof DuctBlockEntity);
    }

    public static boolean areItemPipeNeighbors(Level level, BlockPos a, BlockPos b) {
        if (level == null) {
            return false;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dx + dy + dz != 1) {
            return false;
        }
        Direction fromA = directionFromAToB(a, b);
        if (fromA == null) {
            return false;
        }
        if (faceDisconnected(level, a, fromA) || faceDisconnected(level, b, fromA.getOpposite())) {
            return false;
        }
        if (isDuctBlockEntityPending(level, a) || isDuctBlockEntityPending(level, b)) {
            return DuctConnectable.isSameNetwork(level, a, DuctNetworkType.ITEM)
                    && DuctConnectable.isSameNetwork(level, b, DuctNetworkType.ITEM);
        }
        BlockState sa = level.getBlockState(a);
        BlockState sb = level.getBlockState(b);
        Block ba = sa.getBlock();
        Block bb = sb.getBlock();
        if (!DuctConnectable.isSameNetwork(level, a, DuctNetworkType.ITEM)
                || !DuctConnectable.isSameNetwork(level, b, DuctNetworkType.ITEM)) {
            return false;
        }
        String idA = logicalIdOf(level, a, ba);
        String idB = logicalIdOf(level, b, bb);
        boolean compatA =
                DuctDefinitionRegistry.getByLogicalId(idA).map(DuctDefinition::connectWithCompatible).orElse(true);
        boolean compatB =
                DuctDefinitionRegistry.getByLogicalId(idB).map(DuctDefinition::connectWithCompatible).orElse(true);
        if (compatA && compatB) {
            return true;
        }
        return idA.equals(idB);
    }

    /** Same rules as {@link #areItemPipeNeighbors} for {@link DuctNetworkType#FLUID}. */
    public static boolean areFluidPipeNeighbors(Level level, BlockPos a, BlockPos b) {
        if (level == null) {
            return false;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dx + dy + dz != 1) {
            return false;
        }
        Direction fromA = directionFromAToB(a, b);
        if (fromA == null) {
            return false;
        }
        if (faceDisconnected(level, a, fromA) || faceDisconnected(level, b, fromA.getOpposite())) {
            return false;
        }
        if (isDuctBlockEntityPending(level, a) || isDuctBlockEntityPending(level, b)) {
            return DuctConnectable.isSameNetwork(level, a, DuctNetworkType.FLUID)
                    && DuctConnectable.isSameNetwork(level, b, DuctNetworkType.FLUID);
        }
        BlockState sa = level.getBlockState(a);
        BlockState sb = level.getBlockState(b);
        Block ba = sa.getBlock();
        Block bb = sb.getBlock();
        if (!DuctConnectable.isSameNetwork(level, a, DuctNetworkType.FLUID)
                || !DuctConnectable.isSameNetwork(level, b, DuctNetworkType.FLUID)) {
            return false;
        }
        String idA = logicalIdOf(level, a, ba);
        String idB = logicalIdOf(level, b, bb);
        boolean compatA =
                DuctDefinitionRegistry.getByLogicalId(idA).map(DuctDefinition::connectWithCompatible).orElse(true);
        boolean compatB =
                DuctDefinitionRegistry.getByLogicalId(idB).map(DuctDefinition::connectWithCompatible).orElse(true);
        if (compatA && compatB) {
            return true;
        }
        return idA.equals(idB);
    }

    /** Same rules as {@link #areItemPipeNeighbors} for {@link DuctNetworkType#GAS}. */
    public static boolean areGasPipeNeighbors(Level level, BlockPos a, BlockPos b) {
        if (level == null) {
            return false;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dx + dy + dz != 1) {
            return false;
        }
        Direction fromA = directionFromAToB(a, b);
        if (fromA == null) {
            return false;
        }
        if (faceDisconnected(level, a, fromA) || faceDisconnected(level, b, fromA.getOpposite())) {
            return false;
        }
        if (isDuctBlockEntityPending(level, a) || isDuctBlockEntityPending(level, b)) {
            return DuctConnectable.isSameNetwork(level, a, DuctNetworkType.GAS)
                    && DuctConnectable.isSameNetwork(level, b, DuctNetworkType.GAS);
        }
        BlockState sa = level.getBlockState(a);
        BlockState sb = level.getBlockState(b);
        Block ba = sa.getBlock();
        Block bb = sb.getBlock();
        if (!DuctConnectable.isSameNetwork(level, a, DuctNetworkType.GAS)
                || !DuctConnectable.isSameNetwork(level, b, DuctNetworkType.GAS)) {
            return false;
        }
        String idA = logicalIdOf(level, a, ba);
        String idB = logicalIdOf(level, b, bb);
        boolean compatA =
                DuctDefinitionRegistry.getByLogicalId(idA).map(DuctDefinition::connectWithCompatible).orElse(true);
        boolean compatB =
                DuctDefinitionRegistry.getByLogicalId(idB).map(DuctDefinition::connectWithCompatible).orElse(true);
        if (compatA && compatB) {
            return true;
        }
        return idA.equals(idB);
    }

    /** Same rules as {@link #areItemPipeNeighbors} for {@link DuctNetworkType#ENERGY}. */
    public static boolean areEnergyPipeNeighbors(Level level, BlockPos a, BlockPos b) {
        if (level == null) {
            return false;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dx + dy + dz != 1) {
            return false;
        }
        Direction fromA = directionFromAToB(a, b);
        if (fromA == null) {
            return false;
        }
        if (faceDisconnected(level, a, fromA) || faceDisconnected(level, b, fromA.getOpposite())) {
            return false;
        }
        BlockState sa = level.getBlockState(a);
        BlockState sb = level.getBlockState(b);
        Block ba = sa.getBlock();
        Block bb = sb.getBlock();
        if (!DuctConnectable.isSameNetwork(level, a, DuctNetworkType.ENERGY)
                || !DuctConnectable.isSameNetwork(level, b, DuctNetworkType.ENERGY)) {
            return false;
        }
        String idA = logicalIdOf(level, a, ba);
        String idB = logicalIdOf(level, b, bb);
        boolean compatA =
                DuctDefinitionRegistry.getByLogicalId(idA).map(DuctDefinition::connectWithCompatible).orElse(true);
        boolean compatB =
                DuctDefinitionRegistry.getByLogicalId(idB).map(DuctDefinition::connectWithCompatible).orElse(true);
        if (compatA && compatB) {
            return true;
        }
        return idA.equals(idB);
    }

    /** Same rules as {@link #areEnergyPipeNeighbors} for {@link DuctNetworkType#HEAT}. */
    public static boolean areHeatPipeNeighbors(Level level, BlockPos a, BlockPos b) {
        if (level == null) {
            return false;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        if (dx + dy + dz != 1) {
            return false;
        }
        Direction fromA = directionFromAToB(a, b);
        if (fromA == null) {
            return false;
        }
        if (faceDisconnected(level, a, fromA) || faceDisconnected(level, b, fromA.getOpposite())) {
            return false;
        }
        BlockState sa = level.getBlockState(a);
        BlockState sb = level.getBlockState(b);
        Block ba = sa.getBlock();
        Block bb = sb.getBlock();
        if (!DuctConnectable.isSameNetwork(level, a, DuctNetworkType.HEAT)
                || !DuctConnectable.isSameNetwork(level, b, DuctNetworkType.HEAT)) {
            return false;
        }
        String idA = logicalIdOf(level, a, ba);
        String idB = logicalIdOf(level, b, bb);
        boolean compatA =
                DuctDefinitionRegistry.getByLogicalId(idA).map(DuctDefinition::connectWithCompatible).orElse(true);
        boolean compatB =
                DuctDefinitionRegistry.getByLogicalId(idB).map(DuctDefinition::connectWithCompatible).orElse(true);
        if (compatA && compatB) {
            return true;
        }
        return idA.equals(idB);
    }

    private static Direction directionFromAToB(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        if (dx == 1) {
            return Direction.EAST;
        }
        if (dx == -1) {
            return Direction.WEST;
        }
        if (dy == 1) {
            return Direction.UP;
        }
        if (dy == -1) {
            return Direction.DOWN;
        }
        if (dz == 1) {
            return Direction.SOUTH;
        }
        if (dz == -1) {
            return Direction.NORTH;
        }
        return null;
    }

    private static boolean faceDisconnected(Level level, BlockPos pos, Direction faceOnBlock) {
        var be = level.getBlockEntity(pos);
        if (be instanceof AbstractDuctBlockEntity duct) {
            return (duct.getUserDisconnectedFaceMask() & (1 << faceOnBlock.ordinal())) != 0;
        }
        return false;
    }

    private static String logicalIdOf(Level level, BlockPos pos, Block block) {
        if (level != null) {
            var be = level.getBlockEntity(pos);
            if (be instanceof DuctBlockEntity ductBe) {
                return ductBe.getLogicalDuctId();
            }
        }
        if (block instanceof DuctConnectable dc) {
            return dc.logicalDuctId();
        }
        return DuctIds.DEFAULT_LOGICAL_ID;
    }
}
