package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

import org.jetbrains.annotations.Nullable;

/**
 * Channel selector: cycles A–Z with colored background (Pattern Crafter letter style).
 * No empty/neutral state: value is always {@code 1..26} (A red by default).
 * Left click: next letter; right click: previous; Shift+click: jump to A (red).
 */
public final class ChannelLetterButton extends AbstractWidget {
    private static final int MIN = 1;
    private static final int MAX = 26;

    private int value = MIN;
    /** Hub placeholder: empty cell like {@link FilterConcatChannelButton} on None (no lane letter). */
    private boolean hubPlaceholder;
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

    /** When true, draws neutral empty box (concat None style); does not show {@link #value}. */
    public void setHubPlaceholder(boolean hubPlaceholder) {
        this.hubPlaceholder = hubPlaceholder;
    }

    private void cycleForward() {
        value = value >= MAX ? MIN : value + 1;
    }

    private void cycleBackward() {
        value = value <= MIN ? MAX : value - 1;
    }

    private boolean isValidClickButton(int button) {
        return button == 0 || button == 1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int button = event.button();
        if (active && visible && isValidClickButton(button) && isMouseOver(event.x(), event.y())) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            if (Screen.hasShiftDown()) {
                value = MIN;
                if (onClickNotifyServer != null) {
                    onClickNotifyServer.accept(0);
                }
                return true;
            }
            if (onClickNotifyServer != null) {
                // Left = forward (next letter), right = backward (previous letter)
                onClickNotifyServer.accept(button == 0 ? 1 : -1);
            } else if (button == 0) {
                cycleForward();
            } else {
                cycleBackward();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (hubPlaceholder) {
            int bg = LetterPalette.backgroundArgb(0);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, bg);
            int border = isHovered ? 0xFFFFFFFF : 0xFF303030;
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, border);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
            graphics.fill(getX(), getY(), getX() + 1, getY() + height, border);
            graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);
            return;
        }
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
        graphics.text(
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
