package net.unfamily.another_dynamics.client.transit;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Small axis-aligned cuboid textured with the fluid still sprite; uses entity translucent (no back-face cull) so all
 * sides stay visible while the BER applies Y spin.
 */
public final class FluidTransitCuboidRenderer {

    /** Half-extent tuned to match in-duct item ghost scale (~{@link DuctTransitBlockEntityRenderer} GHOST_SCALE on items). */
    private static final float HALF_EXTENT = 0.145f;

    private FluidTransitCuboidRenderer() {}

    public static void renderCuboid(
            FluidStack fluid, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (fluid.isEmpty()) {
            return;
        }
        var fluidType = fluid.getFluid().getFluidType();
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluidType);
        Identifier still = ext.getStillTexture(fluid);
        TextureAtlasSprite sprite =
                Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
        int tint = ext.getTintColor(fluid);
        float a = ((tint >> 24) & 0xFF) / 255f;
        if (a <= 1e-3f) {
            a = 1f;
        }
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        VertexConsumer vc = buffer.getBuffer(Sheets.translucentCullBlockSheet());
        float h = HALF_EXTENT;
        renderCuboidInternal(sprite, poseStack, vc, -h, -h, -h, h, h, h, r, g, b, a, packedLight, packedOverlay);
    }

    static void renderCuboidInternal(
            TextureAtlasSprite sp,
            PoseStack poseStack,
            VertexConsumer vc,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float r,
            float g,
            float b,
            float a,
            int light,
            int overlay) {
        drawBox(poseStack.last(), vc, sp, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a, light, overlay);
    }

    private static void drawBox(
            PoseStack.Pose pose,
            VertexConsumer vc,
            TextureAtlasSprite sp,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float r,
            float g,
            float b,
            float a,
            int light,
            int overlay) {
        Matrix4f mat = pose.pose();
        float u0 = sp.getU0();
        float u1 = sp.getU1();
        float v0 = sp.getV0();
        float v1 = sp.getV1();
        // Draw each face twice (both windings) so it remains visible even on cull-enabled translucent sheets.
        // Down -Y
        quadBoth(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, 0, -1, 0, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
        // Up +Y
        quadBoth(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, 0, 1, 0, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        // North -Z
        quadBoth(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, 0, 0, -1, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ);
        // South +Z
        quadBoth(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, 0, 0, 1, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ);
        // West -X
        quadBoth(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, -1, 0, 0, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ);
        // East +X
        quadBoth(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, 1, 0, 0, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
    }

    private static void quadBoth(
            VertexConsumer vc,
            PoseStack.Pose pose,
            Matrix4f mat,
            float r,
            float g,
            float b,
            float a,
            int light,
            int overlay,
            float u0,
            float u1,
            float v0,
            float v1,
            float nx,
            float ny,
            float nz,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4) {
        quad(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, nx, ny, nz, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4);
        quad(vc, pose, mat, r, g, b, a, light, overlay, u0, u1, v0, v1, -nx, -ny, -nz, x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1);
    }

    private static void quad(
            VertexConsumer vc,
            PoseStack.Pose pose,
            Matrix4f mat,
            float r,
            float g,
            float b,
            float a,
            int light,
            int overlay,
            float u0,
            float u1,
            float v0,
            float v1,
            float nx,
            float ny,
            float nz,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4) {
        vc.addVertex(mat, x1, y1, z1)
                .setColor(r, g, b, a)
                .setUv(u0, v1)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2)
                .setColor(r, g, b, a)
                .setUv(u1, v1)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x3, y3, z3)
                .setColor(r, g, b, a)
                .setUv(u1, v0)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x4, y4, z4)
                .setColor(r, g, b, a)
                .setUv(u0, v0)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
