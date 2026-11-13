package net.unfamily.another_dynamics.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.client.transit.DuctTransitPathGeometry;
import net.unfamily.another_dynamics.network.UnknownFluxRayPathPayload;

/**
 * Client handler for Unknown Flux path packets: {@code iska_lib:unknown_flux_hit} along the duct polyline.
 * Looks up the particle by id so this class does not hard-link {@code iska_lib} types.
 */
public final class UnknownFluxRayClient {
    private static final Identifier UNKNOWN_FLUX_HIT_ID =
            Identifier.fromNamespaceAndPath("iska_lib", "unknown_flux_hit");

    private UnknownFluxRayClient() {}

    public static void handlePath(UnknownFluxRayPathPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (net.unfamily.another_dynamics.duct.DuctOpaqueRendering.playerAllOpaque(mc.player)) {
            return;
        }
        List<BlockPos> path = payload.ductPath();
        if (path == null || path.isEmpty()) {
            return;
        }
        if (path.stream().allMatch(p -> {
            var be = level.getBlockEntity(p);
            return be instanceof net.unfamily.another_dynamics.duct.DuctBlockEntity duct
                    && duct.isNetworkOpaqueRendering();
        })) {
            return;
        }
        ParticleOptions particle =
                BuiltInRegistries.PARTICLE_TYPE
                        .get(UNKNOWN_FLUX_HIT_ID)
                        .map(h -> h.value())
                        .filter(ParticleOptions.class::isInstance)
                        .map(ParticleOptions.class::cast)
                        .orElse(null);
        if (particle == null) {
            return;
        }
        var rand = level.getRandom();
        DuctTransitPathGeometry.OrthogonalTransitPath ortho =
                DuctTransitPathGeometry.buildOrthogonalTransitPath(
                        path, path.getFirst(), payload.sourceAttachFace(), payload.destAttachFace());
        Vec3[] pts = ortho.points();
        if (pts.length == 0) {
            return;
        }
        if (pts.length == 1) {
            burstAt(level, particle, rand, pts[0], 4);
            return;
        }
        for (int i = 0; i < pts.length - 1; i++) {
            spawnAlongSegment(level, particle, rand, pts[i], pts[i + 1]);
        }
        burstAt(level, particle, rand, pts[0], 3);
        burstAt(level, particle, rand, pts[pts.length - 1], 3);
    }

    private static void spawnAlongSegment(
            Level level, ParticleOptions particle, net.minecraft.util.RandomSource rand, Vec3 va, Vec3 vb) {
        double len = va.distanceTo(vb);
        int steps = Math.max(2, Mth.ceil(len * 3.0));
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps;
            double x = Mth.lerp(t, va.x, vb.x) + (rand.nextDouble() - 0.5) * 0.06;
            double y = Mth.lerp(t, va.y, vb.y) + (rand.nextDouble() - 0.5) * 0.06;
            double z = Mth.lerp(t, va.z, vb.z) + (rand.nextDouble() - 0.5) * 0.06;
            level.addParticle(particle, x, y, z, 0.0, 0.0, 0.0);
        }
    }

    private static void burstAt(
            Level level, ParticleOptions particle, net.minecraft.util.RandomSource rand, Vec3 c, int count) {
        for (int k = 0; k < count; k++) {
            double ox = (rand.nextDouble() - 0.5) * 0.14;
            double oy = (rand.nextDouble() - 0.5) * 0.14;
            double oz = (rand.nextDouble() - 0.5) * 0.14;
            level.addParticle(particle, c.x + ox, c.y + oy, c.z + oz, 0.0, 0.0, 0.0);
        }
    }
}
