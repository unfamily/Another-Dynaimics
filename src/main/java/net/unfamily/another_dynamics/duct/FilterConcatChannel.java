package net.unfamily.another_dynamics.duct;

import net.minecraft.network.chat.Component;

/**
 * Per-line filter concatenation group (AND within group, OR across groups / standalone lines).
 * {@link #NONE} = legacy single-line OR; {@link #A}..{@link #Z} align with {@link net.unfamily.another_dynamics.client.gui.LetterPalette}.
 */
public enum FilterConcatChannel {
    NONE,
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z;

    private static final FilterConcatChannel[] VALUES = values();
    public static final int MAX_LETTER = 26;

    public static FilterConcatChannel fromOrdinal(int o) {
        if (o < 0 || o >= VALUES.length) {
            return NONE;
        }
        return VALUES[o];
    }

    public boolean isConcatGroup() {
        return this != NONE;
    }

    /** {@link net.unfamily.another_dynamics.client.gui.LetterPalette} index 1..26, or 0 if none. */
    public int paletteIndex() {
        return ordinal();
    }

    public Component displayPrefix() {
        if (this == NONE) {
            return Component.empty();
        }
        return Component.literal("[" + (char) ('A' + ordinal() - 1) + "]");
    }

    public FilterConcatChannel next() {
        return switch (this) {
            case NONE -> A;
            case Z -> NONE;
            default -> VALUES[ordinal() + 1];
        };
    }

    public FilterConcatChannel previous() {
        return switch (this) {
            case NONE -> Z;
            case A -> NONE;
            default -> VALUES[ordinal() - 1];
        };
    }

    /** Sync concat list size to filter lines; missing entries default to {@link #NONE}. */
    public static void syncToLineSize(java.util.List<Integer> concat, int lineCount) {
        while (concat.size() < lineCount) {
            concat.add(0);
        }
        while (concat.size() > lineCount) {
            concat.remove(concat.size() - 1);
        }
    }

    public static int channelAt(java.util.List<Integer> concat, int index) {
        if (concat == null || index < 0 || index >= concat.size()) {
            return 0;
        }
        int v = concat.get(index) != null ? concat.get(index) : 0;
        return Math.clamp(v, 0, MAX_LETTER);
    }
}
