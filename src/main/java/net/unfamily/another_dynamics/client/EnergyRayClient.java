package net.unfamily.another_dynamics.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.client.transit.DuctTransitPathGeometry;
import net.unfamily.another_dynamics.network.EnergyRayPathPayload;
import net.unfamily.another_dynamics.registry.ModAttachments;
import org.joml.Vector3f;
/**
 * Client handler for energy/heat path packets: tinted dust along the duct polyline, with endpoints pulled into
 * source/destination node faces (same geometry as item/fluid transit).
 */
public final class EnergyRayClient {
    private EnergyRayClient() {}

    public static void handlePath(EnergyRayPathPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (mc.player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE.get())) {
            return;
        }
        List<BlockPos> path = payload.ductPath();
        if (path == null || path.isEmpty()) {
            return;
        }
        int argb = payload.argb();
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float scale = 0.5F;
        var dust = new DustParticleOptions(new Vector3f(r, g, b), scale);
        var rand = level.random;

        DuctTransitPathGeometry.OrthogonalTransitPath ortho =
                DuctTransitPathGeometry.buildOrthogonalTransitPath(
                        path, path.getFirst(), payload.sourceAttachFace(), payload.destAttachFace());
        Vec3[] pts = ortho.points();
        if (pts.length == 0) {
            return;
        }
        if (pts.length == 1) {
            burstAt(level, dust, rand, pts[0], 4);
            return;
        }
        for (int i = 0; i < pts.length - 1; i++) {
            spawnAlongSegment(level, dust, rand, pts[i], pts[i + 1]);
        }
        burstAt(level, dust, rand, pts[0], 3);
        burstAt(level, dust, rand, pts[pts.length - 1], 3);
    }

    private static void spawnAlongSegment(Level level, DustParticleOptions dust, net.minecraft.util.RandomSource rand, Vec3 va, Vec3 vb) {
        double len = va.distanceTo(vb);
        int steps = Math.max(2, Mth.ceil(len * 3.0));
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            double x = Mth.lerp(t, va.x, vb.x) + (rand.nextDouble() - 0.5) * 0.06;
            double y = Mth.lerp(t, va.y, vb.y) + (rand.nextDouble() - 0.5) * 0.06;
            double z = Mth.lerp(t, va.z, vb.z) + (rand.nextDouble() - 0.5) * 0.06;
            level.addParticle(dust, x, y, z, 0.0, 0.0, 0.0);
        }
    }

    private static void burstAt(Level level, DustParticleOptions dust, net.minecraft.util.RandomSource rand, Vec3 c, int count) {
        for (int k = 0; k < count; k++) {
            double ox = (rand.nextDouble() - 0.5) * 0.14;
            double oy = (rand.nextDouble() - 0.5) * 0.14;
            double oz = (rand.nextDouble() - 0.5) * 0.14;
            level.addParticle(dust, c.x + ox, c.y + oy, c.z + oz, 0.0, 0.0, 0.0);
        }
    }
}
