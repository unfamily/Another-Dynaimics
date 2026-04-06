package net.unfamily.another_dynamics.duct;

public enum RoutingMode {
    NEAREST_FIRST,
    FARTHEST_FIRST,
    MIDDLEST_FIRST,
    ROUND_ROBIN,
    RANDOM;

    private static final RoutingMode[] VALUES = values();

    public static RoutingMode fromOrdinal(int o) {
        if (o < 0 || o >= VALUES.length) {
            return NEAREST_FIRST;
        }
        return VALUES[o];
    }
}
