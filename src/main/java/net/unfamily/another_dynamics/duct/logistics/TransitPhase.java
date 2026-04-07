package net.unfamily.another_dynamics.duct.logistics;

public enum TransitPhase {
    FORWARD,
    RETURN;

    private static final TransitPhase[] VALUES = values();

    public static TransitPhase fromOrdinal(int o) {
        if (o < 0 || o >= VALUES.length) {
            return FORWARD;
        }
        return VALUES[o];
    }
}

