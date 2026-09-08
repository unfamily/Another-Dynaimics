package net.unfamily.another_dynamics.machine.sequential;

/**
 * Global machine gate (acceptance / work). Matches duct-style ignore/low/high/disabled, plus {@link #AUTO}
 * (comparator-style: off while the front destination holds any items, fluids, or chemicals).
 * Default: {@link #AUTO}.
 */
public enum SequentialGateMode {
    IGNORED,
    LOW,
    HIGH,
    DISABLED,
    AUTO;

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
            return AUTO;
        }
        return values[o];
    }

    /**
     * Whether the machine may accept inserts / complete / eject this tick.
     *
     * @param neighborPowered whether a neighbor redstone signal is present (ignored while the machine is emitting)
     * @param destinationHasResources whether the front-face destination holds any items, fluids, or chemicals
     */
    public boolean allowsWork(boolean neighborPowered, boolean destinationHasResources) {
        return switch (this) {
            case IGNORED -> true;
            case LOW -> !neighborPowered;
            case HIGH -> neighborPowered;
            case DISABLED -> false;
            case AUTO -> !destinationHasResources;
        };
    }
}
