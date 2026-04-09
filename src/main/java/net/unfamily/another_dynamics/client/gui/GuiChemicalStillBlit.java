package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Mekanism chemical preview in GUI filter slots (tint-filled quad; no Mek compile-time dep).
 */
public final class GuiChemicalStillBlit {

    private GuiChemicalStillBlit() {}

    public static void blit16(GuiGraphics graphics, Object chemicalStack, int x, int y) {
        if (chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return;
        }
        int tint = MekanismChemicalCompat.getTint(chemicalStack);
        int r = (tint >> 16) & 0xFF;
        int g = (tint >> 8) & 0xFF;
        int b = tint & 0xFF;
        graphics.fill(x, y, x + 16, y + 16, 0xFF000000 | (r << 16) | (g << 8) | b);
    }
}
