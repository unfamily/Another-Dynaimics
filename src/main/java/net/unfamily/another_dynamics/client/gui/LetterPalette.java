package net.unfamily.another_dynamics.client.gui;

/**
 * ARGB colors for channel letters A–Z (same palette idea as Pattern Crafter).
 */
final class LetterPalette {
    private static final int[] BACKGROUNDS = {
            0xFF404040, // unused (no empty channel)
            0xFFFF4040, // A red
            0xFF40FF40,
            0xFF4080FF,
            0xFFFFFF40,
            0xFFFF40FF,
            0xFF40FFFF,
            0xFFFF8020,
            0xFFFF80C0,
            0xFF80FF20,
            0xFFA040FF,
            0xFF804020,
            0xFF80C0FF,
            0xFFFFFFFF,
            0xFF808080,
            0xFF208040,
            0xFFC02020,
            0xFF2020A0,
            0xFFFFC040,
    };

    private LetterPalette() {}

    static int backgroundArgb(int letter1to26) {
        if (letter1to26 <= 0) {
            return BACKGROUNDS[0];
        }
        if (letter1to26 < BACKGROUNDS.length) {
            return BACKGROUNDS[letter1to26];
        }
        return BACKGROUNDS[1 + (letter1to26 - 1) % (BACKGROUNDS.length - 1)];
    }

    static int textArgb(int letter1to26) {
        int color = backgroundArgb(letter1to26);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r + g + b > 384) ? 0xFF202020 : 0xFFFFFFFF;
    }
}
