package net.unfamily.another_dynamics.client.transit;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.Config;

/**
 * Client-only helpers to skip idle duct BER work and ghost draws outside the camera frustum / front hemisphere.
 */
public final class DuctTransitRenderCulling {
    /** Half-extent of the AABB around an interpolated ghost used for frustum tests. */
    private static final double GHOST_HALF_EXTENT = 0.375;

    private DuctTransitRenderCulling() {}

    public static boolean hasAnyVisuals(BlockPos ductPos) {
        return !DuctTransitClientState.visualsAt(ductPos).isEmpty()
                || !DuctFluidTransitClientState.visualsAt(ductPos).isEmpty()
                || !DuctGasTransitClientState.visualsAt(ductPos).isEmpty();
    }

    /**
     * Config transit distance capped by the client's Minecraft render distance (chunks × 16).
     */
    public static double effectiveRenderDistance() {
        double config = Config.ductTransitRenderDistance();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return config;
        }
        double clientBlocks = mc.options.renderDistance().get() * 16.0;
        return Math.min(config, clientBlocks);
    }

    public static boolean isWithinRenderDistance(BlockPos ductPos, Vec3 cameraPos) {
        double dist = effectiveRenderDistance();
        return ductPos.distToCenterSqr(cameraPos) < dist * dist;
    }

    /**
     * True when the ghost should be drawn: inside frustum when available, otherwise in front of the camera
     * (look · toGhost &gt; 0) and within render distance of the camera.
     */
    public static boolean isGhostVisible(Vec3 worldPos, Camera camera, @Nullable Frustum frustum) {
        Vec3 camPos = camera.getPosition();
        double dist = effectiveRenderDistance();
        if (worldPos.distanceToSqr(camPos) > dist * dist) {
            return false;
        }
        if (frustum != null) {
            AABB box =
                    new AABB(
                            worldPos.x - GHOST_HALF_EXTENT,
                            worldPos.y - GHOST_HALF_EXTENT,
                            worldPos.z - GHOST_HALF_EXTENT,
                            worldPos.x + GHOST_HALF_EXTENT,
                            worldPos.y + GHOST_HALF_EXTENT,
                            worldPos.z + GHOST_HALF_EXTENT);
            return frustum.isVisible(box);
        }
        Vec3 toGhost = worldPos.subtract(camPos);
        if (toGhost.lengthSqr() < 1.0e-6) {
            return true;
        }
        Vec3 look = new Vec3(camera.getLookVector());
        return look.dot(toGhost.normalize()) > 0.0;
    }
}
