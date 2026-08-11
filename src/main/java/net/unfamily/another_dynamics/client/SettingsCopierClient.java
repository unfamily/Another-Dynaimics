package net.unfamily.another_dynamics.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** GUI slot frames and module ghost icon for settings copier / duct screens. */
public final class SettingsCopierClient {
    public static final Identifier SLOT_FRAME_TEXTURE =
            Identifier.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot_copy.png");
    public static final Identifier MODULE_SLOT_TEXTURE =
            Identifier.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot_module.png");
    public static final Identifier MODULE_GHOST_TEXTURE =
            Identifier.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/module_ghost.png");

    /** Inset for item/ghost inside module slot art (matches duct node module column). */
    public static final int MODULE_SLOT_CONTENT_DX = 1;
    public static final int MODULE_SLOT_CONTENT_DY = 1;

    private SettingsCopierClient() {}

    public static void blitSlotFrame(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, SLOT_FRAME_TEXTURE, x, y, 0.0F, 0.0F, 18, 18, 18, 18);
    }

    public static void blitModuleSlotFrame(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, MODULE_SLOT_TEXTURE, x, y, 0.0F, 0.0F, 18, 18, 18, 18);
    }

    /** Placeholder icon when a module slot has no item (duct module column). */
    public static void blitModuleGhostIcon(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, MODULE_GHOST_TEXTURE, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
    }
}
