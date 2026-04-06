package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

import net.minecraft.world.level.block.Block;

/**
 * Blocks that participate in one or more {@link DuctNetworkType} routing graphs (pipe adjacency, pathfinding).
 * Hybrid ducts return several types from {@link #ductNetworkTypes()} so they connect on each network simultaneously.
 */
public interface DuctConnectable {
    EnumSet<DuctNetworkType> ductNetworkTypes();

    static boolean isSameNetwork(Block block, DuctNetworkType type) {
        return block instanceof DuctConnectable dc && dc.ductNetworkTypes().contains(type);
    }
}
