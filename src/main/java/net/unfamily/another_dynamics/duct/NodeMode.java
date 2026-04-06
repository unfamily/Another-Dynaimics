package net.unfamily.another_dynamics.duct;

/**
 * Duct node operating mode (no hybrid modes in v1).
 */
public enum NodeMode {
    /** Default / not configured; accepts inserts from the network. */
    NONE,
    /** Pulls from attached inventory and pushes into the duct network. */
    EXTRACTION,
    /** Accepts inserts; filters apply when receiving. */
    FILTERING_INSERTION,
    /** Pulls items from the network toward the attached inventory. */
    RETRIEVING;

    private static final NodeMode[] VALUES = values();

    public static NodeMode fromOrdinal(int o) {
        if (o < 0 || o >= VALUES.length) {
            return NONE;
        }
        return VALUES[o];
    }

    public boolean usesInsertionPriorityField() {
        return this == NONE || this == FILTERING_INSERTION;
    }

    public boolean usesExtractBatchField() {
        return this == EXTRACTION || this == RETRIEVING;
    }

    public boolean usesRouting() {
        return this == EXTRACTION || this == RETRIEVING;
    }
}
