package net.unfamily.another_dynamics.duct;

import java.util.Locale;

/**
 * Canonical datapack strings for {@link DuctDefinition#disabledFeatures} and {@link DuctDefinition#forbiddenFeatures}.
 * Keys are lowercase; loader normalizes input.
 */
public final class DuctFeatureKeys {
    public static final String SPECIAL_CHANNEL = "special:channel";
    public static final String SPECIAL_MODULES = "special:modules";

    public static final String FILTER_ITEM = "filter:item";
    public static final String FILTER_TAG = "filter:tag";
    public static final String FILTER_MODID = "filter:modid";
    public static final String FILTER_NBT = "filter:nbt";
    public static final String FILTER_PREDEFINED = "filter:predefined";

    private DuctFeatureKeys() {}

    public static String modeKey(NodeMode mode) {
        return "mode:" + mode.name().toLowerCase(Locale.ROOT);
    }

    public static String routingKey(RoutingMode mode) {
        return "routing:"
                + switch (mode) {
                    case NEAREST_FIRST -> "nearest";
                    case FARTHEST_FIRST -> "farthest";
                    case MIDDLEST_FIRST -> "middlest";
                    case ROUND_ROBIN -> "round_robin";
                    case RANDOM -> "random";
                };
    }

    /**
     * List editing for allow entries: {@code list:<context>:allow} where context encodes mode and optional bank
     * (extractor / filter / retriever on hybrid faces).
     */
    public static String listAllowKey(NodeMode nodeMode, DuctFaceNode.FilterBank bank) {
        return listKey(nodeMode, bank, "allow");
    }

    public static String listDenyKey(NodeMode nodeMode, DuctFaceNode.FilterBank bank) {
        return listKey(nodeMode, bank, "deny");
    }

    public static String listPrecedenceKey(NodeMode nodeMode, DuctFaceNode.FilterBank bank) {
        return listKey(nodeMode, bank, "precedence");
    }

    private static String listKey(NodeMode nodeMode, DuctFaceNode.FilterBank bank, String suffix) {
        return switch (nodeMode) {
            case NONE -> "list:none:" + bankTag(bank) + ":" + suffix;
            case EXTRACTION -> "list:extraction:" + suffix;
            case FILTERING_INSERTION -> "list:filtering_insertion:" + suffix;
            case RETRIEVING -> "list:retrieving:" + suffix;
            case EXTRACTION_FILTERING -> "list:extraction_filtering:" + hybridBankTag(bank) + ":" + suffix;
            case RETRIEVING_EXTRACTION -> "list:retrieving_extraction:" + hybridBankTag(bank) + ":" + suffix;
        };
    }

    private static String bankTag(DuctFaceNode.FilterBank bank) {
        return bank.name().toLowerCase(Locale.ROOT);
    }

    /** Hybrid modes only use EXTRACTOR, FILTER, RETRIEVER banks; fall back to bank name for safety. */
    private static String hybridBankTag(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> "extractor";
            case FILTER -> "filter";
            case RETRIEVER -> "retriever";
        };
    }

    /**
     * Classify a single filter line for {@link #FILTER_ITEM} etc. Empty lines return {@code null} (no check).
     */
    public static @org.jetbrains.annotations.Nullable String filterSyntaxKey(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("#")) {
            return FILTER_TAG;
        }
        if (t.startsWith("@")) {
            return FILTER_MODID;
        }
        if (t.startsWith("?")) {
            return FILTER_NBT;
        }
        if (t.startsWith("&")) {
            return FILTER_PREDEFINED;
        }
        return FILTER_ITEM;
    }

    /** Heuristic for loader warnings: known prefixes or exact special keys. */
    public static boolean looksLikeKnownKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return key.startsWith("mode:")
                || key.startsWith("routing:")
                || key.startsWith("list:")
                || key.startsWith("filter:")
                || key.startsWith("special:");
    }
}
