package net.unfamily.another_dynamics.duct;

import net.minecraft.world.level.block.Block;

/**
 * Blocks that participate in a {@link DuctNetworkType} graph (pipe-to-pipe links and pathfinding).
 */
public interface DuctConnectable {
    DuctNetworkType ductNetworkType();

    static boolean isSameNetwork(Block block, DuctNetworkType type) {
        return block instanceof DuctConnectable dc && dc.ductNetworkType() == type;
    }
}
