package net.unfamily.another_dynamics.duct;

/**
 * Allow-line group ids: {@code 0} = none; {@code 1}–{@link #MAX_PALETTE} map to letters A–Z in order (same palette as
 * duct channel letters). Values {@code > MAX_PALETTE} wrap through the non-zero range.
 */
public final class FilterGroupIds {
    public static final int MAX_PALETTE = 26;

    private FilterGroupIds() {}

    public static int normalize(int v) {
        if (v <= 0) {
            return 0;
        }
        if (v <= MAX_PALETTE) {
            return v;
        }
        return 1 + (v - 1) % MAX_PALETTE;
    }
}
