package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.unfamily.another_dynamics.duct.FilterGroupIds;

/**
 * 18×18 color cell (Pattern-Crafter style): border, fill, letter A–R for group 1–18.
 * Left click: step backward in cycle; right click: forward; Shift+click: neutral (0).
 */
public final class FilterGroupColorWidget extends AbstractWidget {
    private static final int CELL = 18;
    private static final int BORDER_COLOR = 0xFF303030;
    private static final int BORDER_HOVER_COLOR = 0xFFFFFFFF;

    public interface GroupGetter {
        int get();
    }

    public interface GroupSetter {
        void set(int value);
    }

    private final GroupGetter getter;
    private final GroupSetter setter;

    public FilterGroupColorWidget(int x, int y, GroupGetter getter, GroupSetter setter) {
        super(x, y, CELL, CELL, Component.empty());
        this.getter = getter;
        this.setter = setter;
    }

    private static int cycleGroupForward(int v) {
        int n = FilterGroupIds.normalize(v);
        return n >= FilterGroupIds.MAX_PALETTE ? 0 : n + 1;
    }

    private static int cycleGroupBackward(int v) {
        int n = FilterGroupIds.normalize(v);
        if (n <= 0) {
            return FilterGroupIds.MAX_PALETTE;
        }
        return n - 1;
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || !isValidClickButton(button)) {
            return false;
        }
        if (!clicked(mouseX, mouseY)) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        if (Screen.hasShiftDown()) {
            setter.set(0);
            return true;
        }
        int v = getter.get();
        if (button == 0) {
            setter.set(cycleGroupBackward(v));
        } else {
            setter.set(cycleGroupForward(v));
        }
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int v = getter.get();
        int disp = v <= 0 ? 0 : FilterGroupIds.normalize(v);
        int fill = FilterGroupColors.getColor(disp);
        int x = getX();
        int y = getY();
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        int border = isHoveredOrFocused() ? BORDER_HOVER_COLOR : BORDER_COLOR;
        guiGraphics.fill(x, y, x + width, y + 1, border);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, border);
        guiGraphics.fill(x, y, x + 1, y + height, border);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, border);

        if (disp > 0) {
            String label = String.valueOf((char) ('A' + disp - 1));
            int textColor = FilterGroupColors.getTextColor(disp);
            int lw = Minecraft.getInstance().font.width(label);
            guiGraphics.drawString(
                    Minecraft.getInstance().font,
                    label,
                    x + (width - lw) / 2,
                    y + (height - 8) / 2,
                    textColor,
                    false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput);
    }
}
