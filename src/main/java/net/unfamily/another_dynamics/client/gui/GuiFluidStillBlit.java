package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Draws a 16×16 still-fluid icon in GUI slots (block atlas + fluid tint via pose color).
 */
public final class GuiFluidStillBlit {

    private GuiFluidStillBlit() {}

    public static void blit16(GuiGraphicsExtractor graphics, FluidStack fluid, int x, int y) {
        if (fluid.isEmpty()) {
            return;
        }
        FluidState state = fluid.getFluid().defaultFluidState();
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(state);
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        int tint = model.fluidTintSource() != null ? model.fluidTintSource().colorAsStack(fluid) : 0xFFFFFFFF;
        int alpha = (tint >> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }
        int color = ARGB.color(alpha, (tint >> 16) & 0xFF, (tint >> 8) & 0xFF, tint & 0xFF);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 16, 16, color);
    }
}
