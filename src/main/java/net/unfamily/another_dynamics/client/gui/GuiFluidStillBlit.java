package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Draws a 16×16 still-fluid icon in GUI slots (block atlas + fluid tint via pose color).
 */
public final class GuiFluidStillBlit {

    private GuiFluidStillBlit() {}

    public static void blit16(GuiGraphics graphics, FluidStack fluid, int x, int y) {
        if (fluid.isEmpty()) {
            return;
        }
        var fluidType = fluid.getFluid().getFluidType();
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluidType);
        TextureAtlasSprite sprite =
                Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ext.getStillTexture(fluid));
        int tint = ext.getTintColor(fluid);
        float a = ((tint >> 24) & 0xFF) / 255f;
        if (a <= 1e-3f) {
            a = 1f;
        }
        float r = ((tint >> 16) & 0xFF) / 255f;
        float gCol = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, gCol, b, a);
        graphics.blit(0, 0, 0, 16, 16, sprite);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        graphics.pose().popPose();
    }
}
