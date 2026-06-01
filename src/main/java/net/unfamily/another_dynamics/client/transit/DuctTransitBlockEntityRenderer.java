package net.unfamily.another_dynamics.client.transit;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Visual-only stacks along the path: uses the vanilla item renderer ({@link Minecraft#getItemRenderer()}) so geometry
 * ends up in the BER buffer pipeline; fluid uses a small textured cuboid with the same path motion and Y spin.
 */
public final class DuctTransitBlockEntityRenderer implements BlockEntityRenderer<DuctBlockEntity> {

    /** Vertical tweak and scale for in-duct ghost items (block-local space before item transform). */
    private static final float GHOST_Y_OFFSET = -0.14f;
    private static final float FLUID_Y_OFFSET = 0.0f;
    private static final float GHOST_SCALE = 0.78f;

    public DuctTransitBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(
            DuctBlockEntity tile,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {
        Minecraft mc = Minecraft.getInstance();

        if (tile.getLevel() == null) {
            return;
        }
        if (net.unfamily.another_dynamics.duct.DuctOpaqueRendering.effectiveOpaque(tile, mc.player)) {
            return;
        }
        BlockPos origin = tile.getBlockPos();
        Level level = tile.getLevel();
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(origin);
        List<DuctFluidTransitVisual> fluidVisuals = DuctFluidTransitClientState.visualsAt(origin);
        List<DuctGasTransitVisual> gasVisuals = DuctGasTransitClientState.visualsAt(origin);
        if (visuals.isEmpty() && fluidVisuals.isEmpty() && gasVisuals.isEmpty()) {
            return;
        }
        int overlay = packedOverlay != 0 ? packedOverlay : OverlayTexture.NO_OVERLAY;
        for (DuctTransitVisual v : visuals) {
            ItemStack stack = v.stack;
            if (stack.isEmpty()) {
                continue;
            }
            Vec3 world = v.positionAt(0f, level, partialTick);
            poseStack.pushPose();
            poseStack.translate(world.x - origin.getX(), world.y - origin.getY(), world.z - origin.getZ());
            poseStack.translate(0.0f, GHOST_Y_OFFSET, 0.0f);

            float rot = (level.getGameTime() + partialTick) * 3.0f;
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rot));
            poseStack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);

            BlockPos lightPos = BlockPos.containing(world);
            int light = LevelRenderer.getLightColor(level, lightPos);
            int seed = (int) (origin.asLong() ^ stack.getItem().hashCode() ^ stack.getCount());

            mc.getItemRenderer()
                    .renderStatic(stack, ItemDisplayContext.GROUND, light, overlay, poseStack, buffer, level, seed);
            poseStack.popPose();
        }
        for (DuctFluidTransitVisual fv : fluidVisuals) {
            if (fv.fluid.isEmpty()) {
                continue;
            }
            Vec3 world = fv.positionAt(0f, level, partialTick);
            poseStack.pushPose();
            poseStack.translate(world.x - origin.getX(), world.y - origin.getY(), world.z - origin.getZ());
            poseStack.translate(0.0f, FLUID_Y_OFFSET, 0.0f);
            float rot = (level.getGameTime() + partialTick) * 3.0f;
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rot));
            poseStack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
            BlockPos lightPos = BlockPos.containing(world);
            int light = LevelRenderer.getLightColor(level, lightPos);
            FluidTransitCuboidRenderer.renderCuboid(fv.fluid, poseStack, buffer, light, overlay);
            poseStack.popPose();
        }
        for (DuctGasTransitVisual gv : gasVisuals) {
            if (gv.amount <= 0) {
                continue;
            }
            Vec3 world = gv.positionAt(0f, level, partialTick);
            poseStack.pushPose();
            poseStack.translate(world.x - origin.getX(), world.y - origin.getY(), world.z - origin.getZ());
            poseStack.translate(0.0f, FLUID_Y_OFFSET, 0.0f);
            float rot = (level.getGameTime() + partialTick) * 3.0f;
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rot));
            poseStack.scale(GHOST_SCALE, GHOST_SCALE, GHOST_SCALE);
            BlockPos lightPos = BlockPos.containing(world);
            int light = LevelRenderer.getLightColor(level, lightPos);
            GasTransitCuboidRenderer.renderCuboid(gv.tintRgb, poseStack, buffer, light, overlay);
            poseStack.popPose();
        }
        if (!Minecraft.useShaderTransparency() && buffer instanceof MultiBufferSource.BufferSource source) {
            source.endLastBatch();
        }
    }

    @Override
    public AABB getRenderBoundingBox(DuctBlockEntity blockEntity) {
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(blockEntity.getBlockPos());
        List<DuctFluidTransitVisual> fluidVisuals = DuctFluidTransitClientState.visualsAt(blockEntity.getBlockPos());
        List<DuctGasTransitVisual> gasVisuals = DuctGasTransitClientState.visualsAt(blockEntity.getBlockPos());
        if (visuals.isEmpty() && fluidVisuals.isEmpty() && gasVisuals.isEmpty()) {
            return BlockEntityRenderer.super.getRenderBoundingBox(blockEntity);
        }
        AABB box = new AABB(blockEntity.getBlockPos());
        for (DuctTransitVisual v : visuals) {
            if (v.ductPath.isEmpty()) {
                box = box.minmax(new AABB(v.ownerDuct));
            } else {
                for (BlockPos p : v.ductPath) {
                    box = box.minmax(new AABB(p));
                }
            }
        }
        for (DuctFluidTransitVisual v : fluidVisuals) {
            if (v.ductPath.isEmpty()) {
                box = box.minmax(new AABB(v.ownerDuct));
            } else {
                for (BlockPos p : v.ductPath) {
                    box = box.minmax(new AABB(p));
                }
            }
        }
        for (DuctGasTransitVisual v : gasVisuals) {
            if (v.ductPath.isEmpty()) {
                box = box.minmax(new AABB(v.ownerDuct));
            } else {
                for (BlockPos p : v.ductPath) {
                    box = box.minmax(new AABB(p));
                }
            }
        }
        return box.inflate(1.0);
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
