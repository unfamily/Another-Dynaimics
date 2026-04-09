package net.unfamily.another_dynamics.client.gui;

/**
 * Colors for allow-line groups (values 0–26): letter A–Z uses the same ARGB sequence as {@link LetterPalette} /
 * {@link ChannelLetterButton}.
 */
public final class FilterGroupColors {
    private FilterGroupColors() {}

    public static int getColor(int value) {
        return LetterPalette.backgroundArgb(value);
    }

    /** Text color on top of {@link #getColor(int)}. */
    public static int getTextColor(int value) {
        if (value <= 0) {
            return 0xFFA0A0A0;
        }
        return LetterPalette.textArgb(value);
    }
}
