package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Voxel hitboxes for duct connection geometry (shared by any transport kind: item, fluid, etc.).
 * Aligned with {@code simple_duct_default.json} / line {@code center} element (16×16×16 units).
 * Logic mirrors {@link net.unfamily.another_dynamics.client.DuctCompositeGeometry#appendForWorld}.
 */
public final class DuctShapes {

    /** Hit tests tolerate boundary floats from raycasts (shared faces with pipe arms). */
    private static final double HIT_EPS = 1.0e-4;
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

    private DuctShapes() {}

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

    /**
     * Which storage-side {@link Direction} node voxel (same boxes as {@link #NODE}) contains the hit, in block-local
     * coordinates {@code [0,1)} per axis as used by {@link #forMasks}. Only faces present for the given masks are
     * considered (matches line vs multi topology). Empty when the ray hits pipe/core only.
     */
    public static Optional<Direction> resolveStorageNodeFace(int pipeMask, int storageMask, double lx, double ly, double lz) {
        List<Direction> hits = new ArrayList<>(6);
        forEachActiveStorageNode(pipeMask, storageMask, d -> {
            if (nodeShapeContainsLocal(NODE.get(d), lx, ly, lz)) {
                hits.add(d);
            }
        });
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() == 1) {
            return Optional.of(hits.getFirst());
        }
        double best = Double.POSITIVE_INFINITY;
        Direction pick = hits.getFirst();
        for (Direction d : hits) {
            AABB b = NODE.get(d).bounds();
            double cx = (b.minX + b.maxX) * 0.5;
            double cy = (b.minY + b.maxY) * 0.5;
            double cz = (b.minZ + b.maxZ) * 0.5;
            double dx = lx - cx;
            double dy = ly - cy;
            double dz = lz - cz;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < best) {
                best = dist;
                pick = d;
            }
        }
        return Optional.of(pick);
    }

    /**
     * Invokes {@code consumer} for each {@link Direction} that has a storage node shape for {@code pipeMask} /
     * {@code storageMask}, in the same cases as {@link #forMasks} adds {@link #NODE} voxels.
     */
    public static void forEachActiveStorageNode(int pipeMask, int storageMask, Consumer<Direction> consumer) {
        DuctConnectionShape shape = DuctConnectionShape.classify(pipeMask, storageMask);
        switch (shape) {
            case SINGLE -> {}
            case PARTIAL, MULTI -> {
                for (Direction d : Direction.values()) {
                    int bit = 1 << d.ordinal();
                    if ((storageMask & bit) != 0) {
                        consumer.accept(d);
                    }
                }
            }
            case LINE_X, LINE_Y, LINE_Z -> {
                Direction na = shape.lineEndNegative();
                Direction pb = shape.lineEndPositive();
                if ((storageMask & (1 << na.ordinal())) != 0) {
                    consumer.accept(na);
                }
                if ((storageMask & (1 << pb.ordinal())) != 0) {
                    consumer.accept(pb);
                }
            }
        }
    }

    private static boolean nodeShapeContainsLocal(VoxelShape shape, double x, double y, double z) {
        if (shape.isEmpty()) {
            return false;
        }
        AABB a = shape.bounds();
        return x >= a.minX - HIT_EPS
                && x <= a.maxX + HIT_EPS
                && y >= a.minY - HIT_EPS
                && y <= a.maxY + HIT_EPS
                && z >= a.minZ - HIT_EPS
                && z <= a.maxZ + HIT_EPS;
    }

    private static boolean armExclusiveContains(Direction d, double lx, double ly, double lz) {
        VoxelShape arm = CONNECTION_ARM.get(d);
        if (!nodeShapeContainsLocal(arm, lx, ly, lz)) {
            return false;
        }
        return !nodeShapeContainsLocal(CENTER, lx, ly, lz);
    }

    private static boolean directionActiveForConnectionModel(
            DuctConnectionShape shape, Direction d, int pipeMask, int storageMask) {
        int bit = 1 << d.ordinal();
        int conn = pipeMask | storageMask;
        if ((conn & bit) == 0) {
            return false;
        }
        return switch (shape) {
            case SINGLE -> false;
            case PARTIAL, MULTI -> true;
            case LINE_X, LINE_Y, LINE_Z -> d == shape.lineEndNegative() || d == shape.lineEndPositive();
        };
    }

    /**
     * Wrench disconnect target: storage node caps first, else pipe/inventory connector arm excluding the central core
     * voxel (reserved for reconnect clicks).
     */
    public static Optional<Direction> resolveWrenchDisconnectFace(
            int pipeMask, int storageMask, double lx, double ly, double lz) {
        Optional<Direction> node = resolveStorageNodeFace(pipeMask, storageMask, lx, ly, lz);
        if (node.isPresent()) {
            return node;
        }
        DuctConnectionShape shape = DuctConnectionShape.classify(pipeMask, storageMask);
        List<Direction> hits = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            if (!directionActiveForConnectionModel(shape, d, pipeMask, storageMask)) {
                continue;
            }
            if (armExclusiveContains(d, lx, ly, lz)) {
                hits.add(d);
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() == 1) {
            return Optional.of(hits.getFirst());
        }
        double best = Double.POSITIVE_INFINITY;
        Direction pick = hits.getFirst();
        for (Direction d : hits) {
            AABB b = CONNECTION_ARM.get(d).bounds();
            double cx = (b.minX + b.maxX) * 0.5;
            double cy = (b.minY + b.maxY) * 0.5;
            double cz = (b.minZ + b.maxZ) * 0.5;
            double dx = lx - cx;
            double dy = ly - cy;
            double dz = lz - cz;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < best) {
                best = dist;
                pick = d;
            }
        }
        return Optional.of(pick);
    }

    private static boolean ductCoreBodyContains(int pipeMask, int storageMask, double lx, double ly, double lz) {
        DuctConnectionShape shape = DuctConnectionShape.classify(pipeMask, storageMask);
        return switch (shape) {
            case SINGLE, PARTIAL, MULTI -> nodeShapeContainsLocal(CENTER, lx, ly, lz);
            case LINE_X -> nodeShapeContainsLocal(LINE_BAR_X, lx, ly, lz);
            case LINE_Y -> nodeShapeContainsLocal(LINE_BAR_Y, lx, ly, lz);
            case LINE_Z -> nodeShapeContainsLocal(LINE_BAR_Z, lx, ly, lz);
        };
    }

    /**
     * Empty-hand / non-wrench reconnect: hit the duct core (center or line bar) on a blocked face
     * ({@link net.minecraft.world.phys.BlockHitResult#getDirection()}).
     */
    public static boolean canReconnectFromCoreHit(
            int pipeMask, int storageMask, int userDisconnectedMask, double lx, double ly, double lz, Direction hitFace) {
        if ((userDisconnectedMask & (1 << hitFace.ordinal())) == 0) {
            return false;
        }
        return ductCoreBodyContains(pipeMask, storageMask, lx, ly, lz);
    }

    /**
     * Hit lies on the duct core body (central 6×6×6 cube for hub shapes, or the full line bar for straight-line shapes),
     * excluding connector arms and storage node caps — used for sneak-break and core reconnect targeting.
     */
    public static boolean isHitOnDuctCoreBody(int pipeMask, int storageMask, double lx, double ly, double lz) {
        return ductCoreBodyContains(pipeMask, storageMask, lx, ly, lz);
    }
}
