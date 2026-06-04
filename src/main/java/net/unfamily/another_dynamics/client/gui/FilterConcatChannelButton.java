package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;

import java.util.function.IntConsumer;

import org.jetbrains.annotations.Nullable;

/** Per filter row: cycles None (gray) → A–Z (colored) → None. */
public final class FilterConcatChannelButton extends AbstractWidget {
    private int value;
    private final @Nullable IntConsumer onChanged;

    public FilterConcatChannelButton(int x, int y, int width, int height, @Nullable IntConsumer onChanged) {
        super(x, y, width, height, Component.empty());
        this.onChanged = onChanged;
    }

    public int getChannelOrdinal() {
        return value;
    }

    public void setChannelOrdinal(int ordinal) {
        value = Math.clamp(ordinal, 0, FilterConcatChannel.MAX_LETTER);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        setChannelOrdinal(FilterConcatChannel.fromOrdinal(value).next().ordinal());
        if (onChanged != null) {
            onChanged.accept(value);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bg = value == 0 ? LetterPalette.backgroundArgb(0) : LetterPalette.backgroundArgb(value);
        graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, bg);

        int border = isHovered ? 0xFFFFFFFF : 0xFF303030;
        graphics.fill(getX(), getY(), getX() + width, getY() + 1, border);
        graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
        graphics.fill(getX(), getY(), getX() + 1, getY() + height, border);
        graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);

        if (value > 0) {
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
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
