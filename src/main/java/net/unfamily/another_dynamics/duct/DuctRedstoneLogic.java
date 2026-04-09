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
     * Whether transport on a duct face is allowed for the current world redstone (shared by all kinds on that face).
     *
     * <p>{@code redstoneMode}: 0 = ignore, 1 = active when unpowered, 2 = active when powered, 3 = always off.
     *
     * <p>Legacy saves without {@code RsFmt} on the face: stored value 3 used to mean a removed "pulse" mode and is migrated
     * to 0 (ignore); values {@code >= 4} map to 3 (disabled). See {@link DuctFaceLanes} shared NBT.
     */
    public static boolean isFaceTransportActive(Level level, BlockPos ductBlockPos, int redstoneMode) {
        if (level == null) {
            return true;
        }
        boolean powered = isDuctPowered(level, ductBlockPos);
        return switch (redstoneMode) {
            case 0 -> true;
            case 1 -> !powered;
            case 2 -> powered;
            default -> false;
        };
    }
}
