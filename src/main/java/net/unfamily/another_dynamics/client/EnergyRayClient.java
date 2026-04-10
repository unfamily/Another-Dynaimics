package net.unfamily.another_dynamics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.network.EnergyRayPayload;
import net.unfamily.another_dynamics.registry.ModAttachments;
import org.joml.Vector3f;

public final class EnergyRayClient {
    private EnergyRayClient() {}

    public static void handle(EnergyRayPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }
        // Player render toggle: when opaque mode is enabled, suppress the ray.
        if (mc.player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE)) {
            return;
        }

        BlockPos a = payload.fromDuct();
        BlockPos b = payload.toDuct();
        Direction fa = Direction.from3DDataValue(Mth.clamp(payload.fromFaceOrdinal(), 0, 5));
        Direction fb = Direction.from3DDataValue(Mth.clamp(payload.toFaceOrdinal(), 0, 5));

        Vec3 from = faceCenter(a, fa);
        Vec3 to = faceCenter(b, fb);

        int rgb = payload.rgb();
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float bl = (rgb & 0xFF) / 255f;

        int steps = 12;
        Vec3 delta = to.subtract(from);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 p = from.add(delta.scale(t));
            mc.level.addParticle(new DustParticleOptions(new Vector3f(r, g, bl), 0.9f), true, p.x, p.y, p.z, 0, 0, 0);
        }
    }

    private static Vec3 faceCenter(BlockPos ductPos, Direction face) {
        return new Vec3(
                ductPos.getX() + 0.5 + face.getStepX() * 0.5,
                ductPos.getY() + 0.5 + face.getStepY() * 0.5,
                ductPos.getZ() + 0.5 + face.getStepZ() * 0.5);
    }
}

