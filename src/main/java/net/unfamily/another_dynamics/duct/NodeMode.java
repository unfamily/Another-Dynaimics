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
    /** Hybrid: extracts from attached inventory and also filters items inserted through this face. */
    EXTRACTION_FILTERING,
    /** Pulls items from the network toward the attached inventory. */
    RETRIEVING,
    /** Hybrid: retrieves from the network into the attached inventory and also extracts into the network. */
    RETRIEVING_EXTRACTION;

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
        return this == EXTRACTION || this == RETRIEVING || this == EXTRACTION_FILTERING || this == RETRIEVING_EXTRACTION;
    }

    public boolean usesRouting() {
        return this == EXTRACTION || this == RETRIEVING || this == EXTRACTION_FILTERING || this == RETRIEVING_EXTRACTION;
    }

    /**
     * When {@code false} ({@link #NONE}), deny/allow lists do not restrict items on this face and filter UI is disabled.
     */
    public boolean usesItemFilterConfig() {
        return this != NONE;
    }

    public boolean isHybrid() {
        return this == EXTRACTION_FILTERING || this == RETRIEVING_EXTRACTION;
    }

    /** Hybrid modes still use routing internally, but the player may not change the routing mode. */
    public boolean allowsRoutingConfig() {
        return usesRouting();
    }
}
