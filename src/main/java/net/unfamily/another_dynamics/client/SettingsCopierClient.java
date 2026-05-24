package net.unfamily.another_dynamics.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** GUI copy-column slot frame only; item icon uses normal item models + predicates. */
public final class SettingsCopierClient {
    public static final ResourceLocation SLOT_FRAME_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot_copy.png");

    private SettingsCopierClient() {}

    public static void blitSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.blit(SLOT_FRAME_TEXTURE, x, y, 0, 0, 18, 18, 18, 18);
    }
}
