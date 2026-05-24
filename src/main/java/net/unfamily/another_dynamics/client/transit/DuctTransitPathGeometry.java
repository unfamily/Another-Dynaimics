package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Transit polyline: ghost leaves storage toward the <strong>first duct center</strong>, follows duct block centers
 * (90° along the network), then reaches the <strong>last duct center</strong> before moving into destination storage.
 * Consecutive keys are connected with axis-aligned segments only (Manhattan expansion when needed).
 */
public final class DuctTransitPathGeometry {
    private static final double NODE_ATTACH_PULL = 0.34;
    private static final double INFER_ATTACH_PULL = 0.28;
    private static final double EPS = 1.0e-6;
    private static final double EPS_SQ = EPS * EPS;

    private DuctTransitPathGeometry() {}

    public static OrthogonalTransitPath buildOrthogonalTransitPath(
            List<BlockPos> pathList,
            BlockPos ownerFallback,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        Vec3[] keys = buildPathKeyPoints(pathList, ownerFallback, sourceAttachFace, destAttachFace);
        Vec3[] expanded = expandKeypointsToOrthogonalPolyline(keys);
        double[] arcAtVertex = cumulativeArcLengthAtVertices(expanded);
        float[] ductHopArc01 = buildDuctHopArcProgress(pathList, expanded, arcAtVertex);
        return new OrthogonalTransitPath(expanded, arcAtVertex, ductHopArc01);
    }

    /**
     * Arc-length progress (0–1) at each duct block center so motion can advance one hop per {@code edgeTicks} without
     * skipping corners on long orthogonal segments.
     */
    private static float[] buildDuctHopArcProgress(List<BlockPos> pathList, Vec3[] polyline, double[] arc) {
        if (pathList == null || pathList.isEmpty()) {
            return new float[0];
        }
        float[] out = new float[pathList.size()];
        double total = arc.length > 0 ? arc[arc.length - 1] : 0;
        if (total <= 1e-9) {
            return out;
        }
        for (int i = 0; i < pathList.size(); i++) {
            out[i] = (float) (arcProgressAtPoint(polyline, arc, Vec3.atCenterOf(pathList.get(i))) / total);
        }
        return out;
    }

    private static double arcProgressAtPoint(Vec3[] points, double[] arc, Vec3 target) {
        double bestArc = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 1; i < points.length; i++) {
            Vec3 a = points[i - 1];
            Vec3 b = points[i];
            double segLen = arc[i] - arc[i - 1];
            if (segLen <= 1e-9) {
                continue;
            }
            Vec3 ab = b.subtract(a);
            double abLenSq = ab.lengthSqr();
            if (abLenSq < 1e-12) {
                continue;
            }
            double t = Mth.clamp(target.subtract(a).dot(ab) / abLenSq, 0, 1);
            Vec3 proj = a.add(ab.scale(t));
            double distSq = target.distanceToSqr(proj);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestArc = arc[i - 1] + t * segLen;
            }
        }
        return bestArc;
    }

    /**
     * Ordered key waypoints (attachment offsets + block centers) before orthogonal expansion; useful for debugging.
     */
    public static Vec3[] buildPathPoints(
            List<BlockPos> pathList,
            BlockPos ownerFallback,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        return buildPathKeyPoints(pathList, ownerFallback, sourceAttachFace, destAttachFace);
    }

    /**
     * Order: optional start outside source → <strong>center of first path block</strong> → centers along path →
     * optional end outside destination. Ensures the visual always hits the first duct center before following the network.
     */
    private static Vec3[] buildPathKeyPoints(
            List<BlockPos> pathList,
            BlockPos ownerFallback,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        if (pathList == null || pathList.isEmpty()) {
            return new Vec3[] {Vec3.atCenterOf(ownerFallback)};
        }
        int n = pathList.size();
        ArrayList<Vec3> keys = new ArrayList<>(n + 3);
        Vec3 c0 = Vec3.atCenterOf(pathList.getFirst());
        if (sourceAttachFace != null) {
            keys.add(outwardTowardStorage(c0, sourceAttachFace));
        } else if (n >= 2) {
            keys.add(inferStartFromPipeDirection(pathList, c0, n));
        }
        for (int i = 0; i < n; i++) {
            Vec3 c = Vec3.atCenterOf(pathList.get(i));
            if (keys.isEmpty() || keys.get(keys.size() - 1).distanceToSqr(c) > EPS_SQ) {
                keys.add(c);
            }
        }
        Vec3 cLast = Vec3.atCenterOf(pathList.get(n - 1));
        if (destAttachFace != null) {
            Vec3 outward = outwardTowardStorage(cLast, destAttachFace);
            if (keys.get(keys.size() - 1).distanceToSqr(outward) > EPS_SQ) {
                keys.add(outward);
            }
        } else if (n >= 2) {
            Vec3 inferEnd = inferEndFromPipeDirection(pathList, cLast, n);
            if (keys.get(keys.size() - 1).distanceToSqr(inferEnd) > EPS_SQ) {
                keys.add(inferEnd);
            }
        }
        return keys.toArray(new Vec3[0]);
    }

    public static Vec3 positionAlongOrthogonalPolyline(Vec3[] points, double[] arcAtVertex, float progress01) {
        if (points == null || points.length == 0) {
            return Vec3.ZERO;
        }
        if (points.length == 1) {
            return points[0];
        }
        if (arcAtVertex == null || arcAtVertex.length != points.length) {
            return points[0].lerp(points[points.length - 1], Math.clamp(progress01, 0f, 1f));
        }
        double total = arcAtVertex[arcAtVertex.length - 1];
        if (total <= 1e-9) {
            return points[points.length - 1];
        }
        double dist = Math.clamp(progress01, 0f, 1f) * total;
        for (int i = 1; i < arcAtVertex.length; i++) {
            if (dist <= arcAtVertex[i] + 1e-9) {
                double segLen = arcAtVertex[i] - arcAtVertex[i - 1];
                if (segLen <= 1e-9) {
                    return points[i];
                }
                float t = (float) ((dist - arcAtVertex[i - 1]) / segLen);
                return points[i - 1].lerp(points[i], t);
            }
        }
        return points[points.length - 1];
    }

    private static Vec3[] expandKeypointsToOrthogonalPolyline(Vec3[] keys) {
        if (keys.length <= 1) {
            return keys.clone();
        }
        ArrayList<Vec3> acc = new ArrayList<>(keys.length * 2);
        acc.add(keys[0]);
        for (int i = 0; i < keys.length - 1; i++) {
            appendOrthogonalSegment(acc, acc.get(acc.size() - 1), keys[i + 1]);
        }
        return acc.toArray(new Vec3[0]);
    }

    private static void appendOrthogonalSegment(List<Vec3> list, Vec3 from, Vec3 to) {
        Vec3 cur = from;
        while (true) {
            double dx = to.x - cur.x;
            double dy = to.y - cur.y;
            double dz = to.z - cur.z;
            if (dx * dx + dy * dy + dz * dz < EPS_SQ) {
                return;
            }
            int nonZero =
                    (Math.abs(dx) > EPS ? 1 : 0) + (Math.abs(dy) > EPS ? 1 : 0) + (Math.abs(dz) > EPS ? 1 : 0);
            if (nonZero <= 1) {
                Vec3 last = list.get(list.size() - 1);
                if (last.distanceToSqr(to) > EPS_SQ) {
                    list.add(to);
                }
                return;
            }
            if (Math.abs(dx) > EPS) {
                cur = new Vec3(to.x, cur.y, cur.z);
            } else if (Math.abs(dy) > EPS) {
                cur = new Vec3(cur.x, to.y, cur.z);
            } else {
                cur = new Vec3(cur.x, cur.y, to.z);
            }
            Vec3 last = list.get(list.size() - 1);
            if (last.distanceToSqr(cur) > EPS_SQ) {
                list.add(cur);
            }
        }
    }

    private static double[] cumulativeArcLengthAtVertices(Vec3[] pts) {
        if (pts.length == 0) {
            return new double[0];
        }
        double[] arc = new double[pts.length];
        arc[0] = 0.0;
        for (int i = 1; i < pts.length; i++) {
            arc[i] = arc[i - 1] + pts[i - 1].distanceTo(pts[i]);
        }
        return arc;
    }

    private static Vec3 outwardTowardStorage(Vec3 ductCenter, Direction storageFaceOnDuct) {
        Vec3 step = new Vec3(storageFaceOnDuct.getStepX(), storageFaceOnDuct.getStepY(), storageFaceOnDuct.getStepZ());
        return ductCenter.add(step.scale(NODE_ATTACH_PULL));
    }

    private static Vec3 inferStartFromPipeDirection(List<BlockPos> pathList, Vec3 firstCenter, int n) {
        Vec3 toNext = Vec3.atCenterOf(pathList.get(1)).subtract(firstCenter);
        if (toNext.lengthSqr() < 1.0e-8) {
            return firstCenter;
        }
        return firstCenter.subtract(toNext.normalize().scale(INFER_ATTACH_PULL));
    }

    private static Vec3 inferEndFromPipeDirection(List<BlockPos> pathList, Vec3 lastCenter, int n) {
        Vec3 fromPrev = lastCenter.subtract(Vec3.atCenterOf(pathList.get(n - 2)));
        if (fromPrev.lengthSqr() < 1.0e-8) {
            return lastCenter;
        }
        return lastCenter.add(fromPrev.normalize().scale(INFER_ATTACH_PULL));
    }

    public static final class OrthogonalTransitPath {
        private final Vec3[] points;
        private final double[] arcAtVertex;
        private final float[] ductHopArc01;

        public OrthogonalTransitPath(Vec3[] points, double[] arcAtVertex, float[] ductHopArc01) {
            this.points = points;
            this.arcAtVertex = arcAtVertex;
            this.ductHopArc01 = ductHopArc01 != null ? ductHopArc01 : new float[0];
        }

        public Vec3[] points() {
            return points;
        }

        public double[] arcAtVertex() {
            return arcAtVertex;
        }

        public Vec3 positionAt(float progress01) {
            return DuctTransitPathGeometry.positionAlongOrthogonalPolyline(points, arcAtVertex, progress01);
        }

        /**
         * Position by duct-hop index: {@code 0} = outside source attach, {@code n} = outside destination attach,
         * integer hops land on duct block centers (matches {@code edgeTicks} per duct on the server).
         */
        public Vec3 positionAtDuctHops(float hops) {
            if (ductHopArc01.length == 0) {
                return positionAt(Mth.clamp(hops, 0f, 1f));
            }
            int n = ductHopArc01.length;
            hops = Mth.clamp(hops, 0f, n);
            int seg = (int) Math.floor(hops);
            float t = hops - seg;
            float arcFrom;
            float arcTo;
            if (seg <= 0) {
                arcFrom = 0f;
                arcTo = ductHopArc01[0];
            } else if (seg >= n) {
                return positionAt(1f);
            } else {
                arcFrom = ductHopArc01[seg - 1];
                arcTo = seg < n ? ductHopArc01[seg] : 1f;
            }
            return positionAt(Mth.lerp(t, arcFrom, arcTo));
        }
    }
}
