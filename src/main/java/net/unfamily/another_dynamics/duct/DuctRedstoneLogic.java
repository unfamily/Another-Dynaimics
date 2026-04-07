package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Shared redstone gating for per-face item duct nodes (aligned with IskaUtils extractors:
 * {@code getBestNeighborSignal} plus {@code hasNeighborSignal} for edge cases on non-full blocks).
 */
public final class DuctRedstoneLogic {
    private DuctRedstoneLogic() {}

    /**
     * Whether the duct block currently receives redstone power from the world (any face / indirect).
     */
    public static boolean isDuctPowered(Level level, BlockPos ductBlockPos) {
        if (level == null) {
            return false;
        }
        if (level.getBestNeighborSignal(ductBlockPos) > 0) {
            return true;
        }
        return level.hasNeighborSignal(ductBlockPos);
    }

    /**
     * Whether the item node on a duct face participates in transport for the current redstone seen by the duct block.
     *
     * <p>{@link DuctFaceNode#redstoneMode}: 0 = ignore, 1 = active when unpowered, 2 = active when powered, 3 = always off.
     */
    public static boolean isItemNodeActive(Level level, BlockPos ductBlockPos, DuctFaceNode node) {
        if (level == null) {
            return true;
        }
        boolean powered = isDuctPowered(level, ductBlockPos);
        return switch (node.redstoneMode) {
            case 0 -> true;
            case 1 -> !powered;
            case 2 -> powered;
            default -> false;
        };
    }
}
