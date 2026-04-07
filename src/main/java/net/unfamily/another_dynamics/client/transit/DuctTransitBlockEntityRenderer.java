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
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Visual-only stacks along the path: uses the vanilla item renderer ({@link Minecraft#getItemRenderer()}) so geometry
 * ends up in the BER buffer pipeline; a detached item entity is easy to get wrong here.
 */
public final class DuctTransitBlockEntityRenderer implements BlockEntityRenderer<DuctBlockEntity> {

    /** Vertical tweak and scale for in-duct ghost items (block-local space before item transform). */
    private static final float GHOST_Y_OFFSET = -0.14f;
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
        boolean defOpaque =
                DuctDefinitionRegistry.getByLogicalId(DuctIds.ITEM_DUCT)
                        .map(d -> d.alwaysOpaqueRendering())
                        .orElse(false);
        boolean opaqueSkip =
                defOpaque
                        || (mc.player != null && mc.player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE.get()));
        if (opaqueSkip) {
            return;
        }
        BlockPos origin = tile.getBlockPos();
        Level level = tile.getLevel();
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(origin);
        if (visuals.isEmpty()) {
            return;
        }
        int overlay = packedOverlay != 0 ? packedOverlay : OverlayTexture.NO_OVERLAY;
        for (DuctTransitVisual v : visuals) {
            ItemStack stack = v.stack;
            if (stack.isEmpty()) {
                continue;
            }
            float progress = v.progress01(level, partialTick);
            Vec3 world = v.positionAt(progress);
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
        if (!Minecraft.useShaderTransparency() && buffer instanceof MultiBufferSource.BufferSource source) {
            source.endLastBatch();
        }
    }

    @Override
    public AABB getRenderBoundingBox(DuctBlockEntity blockEntity) {
        List<DuctTransitVisual> visuals = DuctTransitClientState.visualsAt(blockEntity.getBlockPos());
        if (visuals.isEmpty()) {
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
        return box.inflate(1.0);
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
