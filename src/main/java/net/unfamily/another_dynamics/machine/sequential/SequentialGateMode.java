package net.unfamily.another_dynamics.machine.sequential;

/**
 * Global machine gate (acceptance / work). No pulse — matches duct-style ignore/low/high/disabled.
 * Default: {@link #IGNORED}.
 */
public enum SequentialGateMode {
    IGNORED,
    LOW,
    HIGH,
    DISABLED;

    public SequentialGateMode next() {
        SequentialGateMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public SequentialGateMode previous() {
        SequentialGateMode[] values = values();
        return values[(ordinal() + values.length - 1) % values.length];
    }

    public static SequentialGateMode fromOrdinal(int o) {
        SequentialGateMode[] values = values();
        if (o < 0 || o >= values.length) {
            return IGNORED;
        }
        return values[o];
    }

    /** Whether the machine may accept / complete / eject this tick given neighbor power. */
    public boolean allowsWork(boolean neighborPowered) {
        return switch (this) {
            case IGNORED -> true;
            case LOW -> !neighborPowered;
            case HIGH -> neighborPowered;
            case DISABLED -> false;
        };
    }
}
