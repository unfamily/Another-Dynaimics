package net.unfamily.another_dynamics.client.transit;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import org.joml.Matrix3f;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Client-side world renderer for item transit visuals.
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, value = Dist.CLIENT)
public final class DuctTransitRenderer {
    private DuctTransitRenderer() {}

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        boolean opaque = mc.player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE.get());
        if (opaque) {
            return;
        }
        // NeoForge may supply an empty PoseStack for this stage; seed it with the level model-view
        // so camera-relative translations match the active render pass.
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(event.getModelViewMatrix());
        poseStack.last().normal().set(new Matrix3f(event.getModelViewMatrix()).invert().transpose());

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        var cameraPos = event.getCamera().getPosition();

        for (var entry : DuctTransitClientState.snapshot().entrySet()) {
            for (DuctTransitVisual v : entry.getValue()) {
                ItemStack stack = v.stack;
                if (stack.isEmpty()) {
                    continue;
                }
                float progress = v.progress01(partial);
                var pos = v.positionAt(progress).subtract(cameraPos);
                poseStack.pushPose();
                poseStack.translate(pos.x, pos.y, pos.z);

                // subtle rotation for readability; no bobbing
                float rot = (mc.level.getGameTime() + partial) * 3.0f;
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(rot));
                poseStack.scale(0.35f, 0.35f, 0.35f);

                int light = 0xF000F0;
                itemRenderer.renderStatic(
                        stack,
                        ItemDisplayContext.GROUND,
                        light,
                        net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        poseStack,
                        buffers,
                        mc.level,
                        0);
                poseStack.popPose();
            }
        }
        buffers.endBatch();
    }
}

