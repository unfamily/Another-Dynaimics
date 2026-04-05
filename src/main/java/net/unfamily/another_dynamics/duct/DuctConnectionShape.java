package net.unfamily.another_dynamics.duct;

import net.minecraft.core.Direction;

/**
 * Topology of duct connections (pipe + storage faces) for hitbox and composite rendering.
 */
public enum DuctConnectionShape {
    SINGLE,
    PARTIAL,
    LINE_X,
    LINE_Y,
    LINE_Z,
    MULTI;

    /**
     * Uses {@code pipeMask | storageMask}: LINE when exactly two opposite faces are active.
     */
    public static DuctConnectionShape classify(int pipeMask, int storageMask) {
        int conn = pipeMask | storageMask;
        int bc = Integer.bitCount(conn);
        if (bc == 0) {
            return SINGLE;
        }
        if (bc == 2) {
            if (oppositeMask(conn, Direction.NORTH, Direction.SOUTH)) {
                return LINE_Z;
            }
            if (oppositeMask(conn, Direction.EAST, Direction.WEST)) {
                return LINE_X;
            }
            if (oppositeMask(conn, Direction.UP, Direction.DOWN)) {
                return LINE_Y;
            }
        }
        if (bc == 1) {
            return PARTIAL;
        }
        return MULTI;
    }

    private static boolean oppositeMask(int mask, Direction a, Direction b) {
        return (mask & (1 << a.ordinal())) != 0 && (mask & (1 << b.ordinal())) != 0;
    }

    public Direction.Axis lineAxis() {
        return switch (this) {
            case LINE_X -> Direction.Axis.X;
            case LINE_Y -> Direction.Axis.Y;
            case LINE_Z -> Direction.Axis.Z;
            default -> Direction.Axis.Z;
        };
    }

    /** End faces of a straight line along {@link #lineAxis()} (only valid for LINE_*). */
    public Direction lineEndPositive() {
        return switch (this) {
            case LINE_X -> Direction.EAST;
            case LINE_Y -> Direction.UP;
            case LINE_Z -> Direction.SOUTH;
            default -> Direction.SOUTH;
        };
    }

    public Direction lineEndNegative() {
        return switch (this) {
            case LINE_X -> Direction.WEST;
            case LINE_Y -> Direction.DOWN;
            case LINE_Z -> Direction.NORTH;
            default -> Direction.NORTH;
        };
    }
}
