package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Item-duct pipe adjacency: same {@link DuctNetworkType#ITEM} plus {@link DuctDefinition#connectWithCompatible()}.
 */
public final class DuctPipeAdjacency {
    private DuctPipeAdjacency() {}

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
        BlockState sa = level.getBlockState(a);
        BlockState sb = level.getBlockState(b);
        Block ba = sa.getBlock();
        Block bb = sb.getBlock();
        if (!DuctConnectable.isSameNetwork(ba, DuctNetworkType.ITEM)
                || !DuctConnectable.isSameNetwork(bb, DuctNetworkType.ITEM)) {
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
