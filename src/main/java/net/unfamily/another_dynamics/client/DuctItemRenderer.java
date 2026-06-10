package net.unfamily.another_dynamics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctOpaqueRendering;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom item renderer for ducts.
 *
 * <p>Bypasses the BakedModel pipeline entirely (same mechanism used by GeckoLib's
 * {@code GeoItemRenderer}) by routing through {@code IClientItemExtensions.getCustomRenderer()}.
 * Reads {@link ModDataComponents#DUCT_LOGICAL_ID} from the stack and renders the correct
 * line-shape quads from {@link DuctCompositeGeometry}, including the opaque V-shift when
 * {@code always_opaque} in the definition JSON or the player's global opaque (All) preference.
 *
 * <p>{@code ItemRenderer} already applies {@code translate(-0.5, -0.5, -0.5)} and the display
 * context transforms before calling {@link #renderByItem}, so quads in block-space [0,1]
 * are positioned correctly with no additional translation here.
 */
public final class DuctItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static final float OPAQUE_V_SHIFT_RATIO = 0.5f;

    public DuctItemRenderer() {
        super(
                Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels()
        );
    }

    @Override
    public void renderByItem(
            ItemStack stack,
            ItemDisplayContext displayContext,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        String logicalId = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }

        Map<String, DuctCompositeGeometry> cache = DuctBakedModel.getGlobalGeometryCache();
        DuctCompositeGeometry geo = cache.get(logicalId);
        if (geo == null || !geo.isBuilt()) {
            geo = cache.get(DuctIds.DEFAULT_LOGICAL_ID);
        }
        if (geo == null || !geo.isBuilt()) return;

        boolean opaqueRendering =
                DuctOpaqueRendering.effectiveItemPreviewOpaque(
                        logicalId, Minecraft.getInstance().player);

        List<BakedQuad> quads;
        if (opaqueRendering) {
            // V-shift only the center (duct body) quads; node end-caps are invariant.
            List<BakedQuad> centerQuads = geo.lineCenterQuads();
            List<BakedQuad> nodeCaps = geo.lineNodeCapsQuads();
            List<BakedQuad> shiftedCenter = centerQuads;
            if (!centerQuads.isEmpty()) {
                TextureAtlasSprite sprite = centerQuads.get(0).getSprite();
                if (sprite != null) {
                    float vShift = (sprite.getV1() - sprite.getV0()) * OPAQUE_V_SHIFT_RATIO;
                    shiftedCenter = new ArrayList<>(centerQuads.size());
                    for (BakedQuad q : centerQuads) {
                        shiftedCenter.add(DuctCompositeGeometry.shiftQuadV(q, vShift));
                    }
                }
            }
            quads = new ArrayList<>(shiftedCenter.size() + nodeCaps.size());
            quads.addAll(shiftedCenter);
            quads.addAll(nodeCaps);
        } else {
            quads = geo.lineAllQuads();
        }

        var buffer = bufferSource.getBuffer(RenderType.cutout());
        PoseStack.Pose pose = poseStack.last();
        for (BakedQuad quad : quads) {
            buffer.putBulkData(pose, quad, 1f, 1f, 1f, 1f, packedLight, packedOverlay);
        }
    }
}
