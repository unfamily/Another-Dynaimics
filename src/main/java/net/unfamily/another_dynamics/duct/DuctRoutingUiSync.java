package net.unfamily.another_dynamics.duct;

/**
 * Maps active face {@link NodeMode} to the menu sync slot used for the central routing button label.
 */
public final class DuctRoutingUiSync {
    private DuctRoutingUiSync() {}

    public enum RoutingSlot {
        DEFAULT,
        EXTRACTOR,
        RETRIEVER
    }

    public static RoutingSlot activeRoutingSlot(NodeMode nodeMode, boolean hybridExtractorPanel, boolean hybridRetrieverPanel) {
        if (nodeMode == NodeMode.RETRIEVING) {
            return RoutingSlot.RETRIEVER;
        }
        if (nodeMode == NodeMode.RETRIEVING_EXTRACTION && hybridRetrieverPanel) {
            return RoutingSlot.RETRIEVER;
        }
        if (nodeMode == NodeMode.EXTRACTION_FILTERING && hybridExtractorPanel) {
            return RoutingSlot.EXTRACTOR;
        }
        if (nodeMode.isHybrid() && (nodeMode == NodeMode.EXTRACTION_FILTERING || nodeMode == NodeMode.RETRIEVING_EXTRACTION)) {
            if (hybridRetrieverPanel) {
                return RoutingSlot.RETRIEVER;
            }
            if (hybridExtractorPanel) {
                return RoutingSlot.EXTRACTOR;
            }
        }
        return RoutingSlot.DEFAULT;
    }

    public static int ordinalFromSlots(
            RoutingSlot slot, int routingMode, int routingExtractor, int routingRetriever) {
        return switch (slot) {
            case EXTRACTOR -> routingExtractor;
            case RETRIEVER -> routingRetriever;
            case DEFAULT -> routingMode;
        };
    }
}
