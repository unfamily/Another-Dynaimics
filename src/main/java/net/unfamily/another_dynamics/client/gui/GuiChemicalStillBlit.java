package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Draws a 16×16 Mekanism chemical preview in GUI slots (block atlas + tint; same idea as {@link GuiFluidStillBlit}).
 */
public final class GuiChemicalStillBlit {

    private GuiChemicalStillBlit() {}

    public static void blit16(GuiGraphicsExtractor graphics, Object chemicalStack, int x, int y) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return;
        }
        if (blitChemicalIconSprite(graphics, chemicalStack, x, y)) {
            return;
        }
        blitTintQuadFallback(graphics, chemicalStack, x, y);
    }

    /** Uses {@link mekanism.api.chemical.Chemical#getIcon()} on the block atlas (reliable in full GUI passes). */
    private static boolean blitChemicalIconSprite(GuiGraphicsExtractor graphics, Object chemicalStack, int x, int y) {
        try {
            Object chemical = chemicalStack.getClass().getMethod("getChemical").invoke(chemicalStack);
            if (chemical == null) {
                return false;
            }
            Identifier icon = (Identifier) chemical.getClass().getMethod("getIcon").invoke(chemical);
            if (icon == null) {
                return false;
            }
            int tint = (int) chemicalStack.getClass().getMethod("getChemicalTint").invoke(chemicalStack);
            TextureAtlasSprite sprite =
                    Minecraft.getInstance().getAtlasManager().get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, icon));
            int alpha = (tint >> 24) & 0xFF;
            if (alpha == 0) {
                alpha = 0xFF;
            }
            int color = ARGB.color(alpha, (tint >> 16) & 0xFF, (tint >> 8) & 0xFF, tint & 0xFF);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 16, 16, color);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void blitTintQuadFallback(GuiGraphicsExtractor graphics, Object chemicalStack, int x, int y) {
        int tint = MekanismChemicalCompat.getTint(chemicalStack);
        if (tint == 0) {
            tint = 0xFFB8B8B8;
        }
        int r = (tint >> 16) & 0xFF;
        int g = (tint >> 8) & 0xFF;
        int b = tint & 0xFF;
        graphics.fill(x, y, x + 16, y + 16, 0xFF000000 | (r << 16) | (g << 8) | b);
    }
}
