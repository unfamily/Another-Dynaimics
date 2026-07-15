package net.unfamily.another_dynamics.client.transit;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Small axis-aligned cuboid tinted with the chemical color; uses entity translucent (no back-face cull) so all
 * sides stay visible while the BER applies Y spin.
 */
public final class GasTransitCuboidRenderer {

    /** Half-extent tuned to match in-duct item ghost scale (~{@link DuctTransitBlockEntityRenderer} GHOST_SCALE on items). */
    private static final float HALF_EXTENT = 0.145f;

    private static final Identifier FALLBACK_SPRITE = Identifier.parse("minecraft:block/white_concrete");

    private GasTransitCuboidRenderer() {}

    public static void renderCuboid(
            int tintRgb, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay) {
        TextureAtlasSprite sprite =
                Minecraft.getInstance()
                        .getAtlasManager()
                        .get(new SpriteId(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS, FALLBACK_SPRITE));
        float r = ((tintRgb >> 16) & 0xFF) / 255f;
        float g = ((tintRgb >> 8) & 0xFF) / 255f;
        float b = (tintRgb & 0xFF) / 255f;
        float a = 0.70f;
        float h = HALF_EXTENT;
        FluidTransitCuboidRenderer.renderCuboidInternal(
                sprite, poseStack, submitNodeCollector, Sheets.translucentBlockSheet(), -h, -h, -h, h, h, h, r, g, b,
                a, packedLight, packedOverlay);
    }
}

