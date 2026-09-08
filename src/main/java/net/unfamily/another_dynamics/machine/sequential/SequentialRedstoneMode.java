package net.unfamily.another_dynamics.machine.sequential;

/**
 * Per–Sequence List redstone output. Default {@link #DISABLED}. Includes {@link #PULSE} (single impulse).
 */
public enum SequentialRedstoneMode {
    DISABLED,
    LOW,
    HIGH,
    PULSE;

    public SequentialRedstoneMode next() {
        SequentialRedstoneMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public SequentialRedstoneMode previous() {
        SequentialRedstoneMode[] values = values();
        return values[(ordinal() + values.length - 1) % values.length];
    }

    public static SequentialRedstoneMode fromOrdinal(int o) {
        SequentialRedstoneMode[] values = values();
        if (o < 0 || o >= values.length) {
            return DISABLED;
        }
        return values[o];
    }
}
