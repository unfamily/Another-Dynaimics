package net.unfamily.another_dynamics.duct;

/**
 * Normalizes filter line strings entered in GUI edit boxes.
 */
public final class FilterLineTextUtil {
    private FilterLineTextUtil() {}

    /**
     * Removes repeated wrapping {@code '} and {@code "} at the start and end only.
     * Inner quotes (e.g. NBT substrings) are preserved.
     */
    public static String stripWrappingQuotes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        int start = 0;
        int end = raw.length();
        while (start < end && isWrappingQuote(raw.charAt(start))) {
            start++;
        }
        while (end > start && isWrappingQuote(raw.charAt(end - 1))) {
            end--;
        }
        if (start == 0 && end == raw.length()) {
            return raw;
        }
        return raw.substring(start, end);
    }

    private static boolean isWrappingQuote(char c) {
        return c == '\'' || c == '"';
    }
}
