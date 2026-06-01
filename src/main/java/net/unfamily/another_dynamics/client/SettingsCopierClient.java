package net.unfamily.another_dynamics.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** GUI slot frames and module ghost icon for settings copier / duct screens. */
public final class SettingsCopierClient {
    public static final ResourceLocation SLOT_FRAME_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot_copy.png");
    public static final ResourceLocation MODULE_SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot_module.png");
    public static final ResourceLocation MODULE_GHOST_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/module_ghost.png");

    /** Inset for item/ghost inside module slot art (matches duct node module column). */
    public static final int MODULE_SLOT_CONTENT_DX = 1;
    public static final int MODULE_SLOT_CONTENT_DY = 1;

    private SettingsCopierClient() {}

    public static void blitSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.blit(SLOT_FRAME_TEXTURE, x, y, 0, 0, 18, 18, 18, 18);
    }

    public static void blitModuleSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.blit(MODULE_SLOT_TEXTURE, x, y, 0, 0, 18, 18, 18, 18);
    }

    /** Placeholder icon when a module slot has no item (duct module column). */
    public static void blitModuleGhostIcon(GuiGraphics graphics, int x, int y) {
        graphics.blit(MODULE_GHOST_TEXTURE, x, y, 0, 0, 16, 16, 16, 16);
    }
}
