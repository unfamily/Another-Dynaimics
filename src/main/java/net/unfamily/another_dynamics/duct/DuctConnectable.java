package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Blocks that participate in one or more {@link DuctNetworkType} routing graphs (pipe adjacency, pathfinding).
 * Hybrid ducts return several types from {@link #ductNetworkTypes()} so they connect on each network simultaneously.
 */
public interface DuctConnectable {
    EnumSet<DuctNetworkType> ductNetworkTypes();

    /**
     * Datapack duct id for connection rules ({@link DuctDefinition#connectWithCompatible()}). Override when a block
     * maps to a non-default {@link DuctDefinition}.
     */
    default String logicalDuctId() {
        return DuctIds.DEFAULT_LOGICAL_ID;
    }

    static boolean isSameNetwork(Block block, DuctNetworkType type) {
        return block instanceof DuctConnectable dc && dc.ductNetworkTypes().contains(type);
    }

    /**
     * Network membership resolved from the placed duct's logical id (BlockEntity-driven).
     *
     * <p>This is what enables a single physical block id (another_dynamics:duct) to behave as item/fluid/gas/hybrid
     * depending on its loaded {@link DuctDefinition}.</p>
     */
    static boolean isSameNetwork(Level level, BlockPos pos, DuctNetworkType type) {
        if (level == null || pos == null || type == null) {
            return false;
        }
        Block block = level.getBlockState(pos).getBlock();
        if (!(block instanceof DuctConnectable dc)) {
            return false;
        }
        if (level.getBlockEntity(pos) instanceof DuctBlockEntity ductBe) {
            var kinds = ductBe.ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
            return switch (type) {
                case ITEM -> kinds.contains(DuctTransportKind.ITEM);
                case FLUID -> kinds.contains(DuctTransportKind.FLUID);
                case GAS -> MekanismChemicalCompat.isLoaded() && kinds.contains(DuctTransportKind.GAS);
            };
        }
        return dc.ductNetworkTypes().contains(type);
    }
}

