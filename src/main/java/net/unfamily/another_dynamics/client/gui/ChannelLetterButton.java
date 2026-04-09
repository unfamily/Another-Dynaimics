package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

import org.jetbrains.annotations.Nullable;

/**
 * Channel selector: cycles A–Z with colored background (Pattern Crafter letter style).
 * No empty/neutral state: value is always {@code 1..26} (A red by default).
 * Left click: previous letter; right click: next; Shift+click: jump to A (red).
 */
public final class ChannelLetterButton extends AbstractWidget {
    private static final int MIN = 1;
    private static final int MAX = 26;

    private int value = MIN;
    private final @Nullable IntConsumer onClickNotifyServer;

    public ChannelLetterButton(int x, int y, int width, int height) {
        this(x, y, width, height, null);
    }

    public ChannelLetterButton(int x, int y, int width, int height, @Nullable IntConsumer onClickNotifyServer) {
        super(x, y, width, height, Component.empty());
        this.onClickNotifyServer = onClickNotifyServer;
    }

    public int getLetterValue() {
        return value;
    }

    public void setLetterValue(int v) {
        if (v < MIN || v > MAX) {
            return;
        }
        this.value = v;
    }

    private void cycleForward() {
        value = value >= MAX ? MIN : value + 1;
    }

    private void cycleBackward() {
        value = value <= MIN ? MAX : value - 1;
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && isValidClickButton(button) && clicked(mouseX, mouseY)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            if (Screen.hasShiftDown()) {
                value = MIN;
                if (onClickNotifyServer != null) {
                    onClickNotifyServer.accept(0);
                }
                return true;
            }
            if (onClickNotifyServer != null) {
                // Left = backward (previous letter), right = forward (next letter)
                onClickNotifyServer.accept(button == 0 ? -1 : 1);
            } else if (button == 0) {
                cycleBackward();
            } else {
                cycleForward();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bg = LetterPalette.backgroundArgb(value);
        graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, bg);

        int border = isHovered ? 0xFFFFFFFF : 0xFF303030;
        graphics.fill(getX(), getY(), getX() + width, getY() + 1, border);
        graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
        graphics.fill(getX(), getY(), getX() + 1, getY() + height, border);
        graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);

        String label = String.valueOf((char) ('A' + value - 1));
        int textColor = LetterPalette.textArgb(value);
        int lw = Minecraft.getInstance().font.width(label);
        graphics.drawString(
                Minecraft.getInstance().font,
                label,
                getX() + (width - lw) / 2,
                getY() + (height - 8) / 2,
                textColor,
                false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
