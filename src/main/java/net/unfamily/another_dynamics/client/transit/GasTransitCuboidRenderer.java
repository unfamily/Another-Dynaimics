package net.unfamily.another_dynamics.client.transit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Small axis-aligned cuboid tinted with the chemical color; uses entity translucent (no back-face cull) so all
 * sides stay visible while the BER applies Y spin.
 */
public final class GasTransitCuboidRenderer {

    /** Half-extent tuned to match in-duct item ghost scale (~{@link DuctTransitBlockEntityRenderer} GHOST_SCALE on items). */
    private static final float HALF_EXTENT = 0.145f;

    private static final Identifier FALLBACK_SPRITE = Identifier.parse("minecraft:block/white_concrete");

    private GasTransitCuboidRenderer() {}

    public static void renderCuboid(int tintRgb, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        TextureAtlasSprite sprite =
                Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(FALLBACK_SPRITE);
        float r = ((tintRgb >> 16) & 0xFF) / 255f;
        float g = ((tintRgb >> 8) & 0xFF) / 255f;
        float b = (tintRgb & 0xFF) / 255f;
        float a = 0.70f;
        VertexConsumer vc = buffer.getBuffer(Sheets.translucentCullBlockSheet());
        float h = HALF_EXTENT;
        FluidTransitCuboidRenderer.renderCuboidInternal(
                sprite, poseStack, vc, -h, -h, -h, h, h, h, r, g, b, a, packedLight, packedOverlay);
    }
}

