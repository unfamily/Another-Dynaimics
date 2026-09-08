package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.gui.components.EditBox;
import org.jetbrains.annotations.Nullable;

/** Shared GUI input helpers (EditBox clear on right-click, etc.). */
public final class GuiInput {
    private GuiInput() {}

    /** Clears EditBox content and resets the cursor. */
    public static void clearEditBox(@Nullable EditBox box) {
        if (box == null) {
            return;
        }
        box.setValue("");
        box.setCursorPosition(0);
        box.setHighlightPos(0);
    }

    /**
     * Right-click on a visible EditBox clears its content.
     *
     * @return true if an EditBox was cleared
     */
    public static boolean clearEditBoxOnRightClick(
            double mouseX, double mouseY, int button, EditBox... editBoxes) {
        if (button != 1 || editBoxes == null) {
            return false;
        }
        for (EditBox box : editBoxes) {
            if (box == null || !box.visible || !box.active) {
                continue;
            }
            if (box.isMouseOver(mouseX, mouseY)) {
                clearEditBox(box);
                return true;
            }
        }
        return false;
    }
}
