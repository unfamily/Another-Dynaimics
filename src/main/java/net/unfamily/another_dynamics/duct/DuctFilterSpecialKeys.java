package net.unfamily.another_dynamics.duct;

import org.jetbrains.annotations.Nullable;

/**
 * Shared special filter tokens ({@code &...} macros) used by item/fluid/gas matchers and reorder.
 */
public final class DuctFilterSpecialKeys {
    /**
     * Matches any non-empty stack that reaches this line (use after more specific filters).
     * Reorder always sinks these lines to the bottom of the list.
     */
    public static final String ANYTHING_ELSE = "&anything_else";

    private static final String ANYTHING_ELSE_BODY = "anything_else";

    private DuctFilterSpecialKeys() {}

    public static boolean isAnythingElseLine(@Nullable String filter) {
        if (filter == null) {
            return false;
        }
        String t = filter.trim();
        if (t.equalsIgnoreCase(ANYTHING_ELSE) || t.equalsIgnoreCase(ANYTHING_ELSE_BODY)) {
            return true;
        }
        return t.startsWith("&") && t.substring(1).trim().equalsIgnoreCase(ANYTHING_ELSE_BODY);
    }

    public static boolean isAnythingElseMacroBody(@Nullable String macroBody) {
        return macroBody != null && macroBody.trim().equalsIgnoreCase(ANYTHING_ELSE_BODY);
    }
}
