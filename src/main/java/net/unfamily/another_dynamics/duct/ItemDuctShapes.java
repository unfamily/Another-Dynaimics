package net.unfamily.another_dynamics.duct;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Hitboxes aligned with {@code simple_duct_default.json} / line {@code center} element (16×16×16 units).
 * Logic mirrors {@link net.unfamily.another_dynamics.client.DuctCompositeGeometry#appendForWorld}.
 */
public final class ItemDuctShapes {
    private static final VoxelShape CENTER = box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape LINE_BAR_Z = box(5, 5, 0, 11, 11, 16);
    private static final VoxelShape LINE_BAR_X = box(0, 5, 5, 16, 11, 11);
    private static final VoxelShape LINE_BAR_Y = box(5, 0, 5, 11, 16, 11);

    private static final Map<Direction, VoxelShape> CONNECTION_ARM = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> NODE = new EnumMap<>(Direction.class);

    static {
        CONNECTION_ARM.put(Direction.UP, box(5, 11, 5, 11, 16, 11));
        CONNECTION_ARM.put(Direction.DOWN, box(5, 0, 5, 11, 5, 11));
        CONNECTION_ARM.put(Direction.NORTH, box(5, 5, 0, 11, 11, 5));
        CONNECTION_ARM.put(Direction.SOUTH, box(5, 5, 11, 11, 11, 16));
        CONNECTION_ARM.put(Direction.EAST, box(11, 5, 5, 16, 11, 11));
        CONNECTION_ARM.put(Direction.WEST, box(0, 5, 5, 5, 11, 11));

        NODE.put(Direction.UP, box(4, 12, 4, 12, 16, 12));
        NODE.put(Direction.DOWN, box(4, 0, 4, 12, 4, 12));
        NODE.put(Direction.NORTH, box(4, 4, 0, 12, 12, 4));
        NODE.put(Direction.SOUTH, box(4, 4, 12, 12, 12, 16));
        NODE.put(Direction.EAST, box(12, 4, 4, 16, 12, 12));
        NODE.put(Direction.WEST, box(0, 4, 4, 4, 12, 12));
    }

    private ItemDuctShapes() {}

    private static VoxelShape box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Shapes.box(x0 / 16.0, y0 / 16.0, z0 / 16.0, x1 / 16.0, y1 / 16.0, z1 / 16.0);
    }

    private static VoxelShape or(VoxelShape a, VoxelShape b) {
        return Shapes.or(a, b);
    }

    public static VoxelShape forMasks(int pipeMask, int storageMask) {
        DuctConnectionShape shape = DuctConnectionShape.classify(pipeMask, storageMask);
        return switch (shape) {
            case SINGLE -> CENTER;
            case PARTIAL, MULTI -> centerPlusArmsAndNodes(pipeMask, storageMask);
            case LINE_X -> lineShape(LINE_BAR_X, storageMask, shape);
            case LINE_Y -> lineShape(LINE_BAR_Y, storageMask, shape);
            case LINE_Z -> lineShape(LINE_BAR_Z, storageMask, shape);
        };
    }

    private static VoxelShape centerPlusArmsAndNodes(int pipeMask, int storageMask) {
        VoxelShape s = CENTER;
        for (Direction d : Direction.values()) {
            int bit = 1 << d.ordinal();
            if ((pipeMask & bit) != 0) {
                s = or(s, CONNECTION_ARM.get(d));
            }
            if ((storageMask & bit) != 0) {
                s = or(s, CONNECTION_ARM.get(d));
                s = or(s, NODE.get(d));
            }
        }
        return s;
    }

    private static VoxelShape lineShape(VoxelShape bar, int storageMask, DuctConnectionShape lineShape) {
        VoxelShape s = bar;
        Direction na = lineShape.lineEndNegative();
        Direction pb = lineShape.lineEndPositive();
        if ((storageMask & (1 << na.ordinal())) != 0) {
            s = or(s, NODE.get(na));
        }
        if ((storageMask & (1 << pb.ordinal())) != 0) {
            s = or(s, NODE.get(pb));
        }
        return s;
    }

    public static VoxelShape coreOnly() {
        return CENTER;
    }
}
