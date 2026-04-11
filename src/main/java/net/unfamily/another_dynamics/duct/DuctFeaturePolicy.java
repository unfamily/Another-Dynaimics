package net.unfamily.another_dynamics.duct;

import org.jetbrains.annotations.Nullable;

/**
 * Evaluates {@link DuctDefinition} feature sets for server and client UI.
 */
public final class DuctFeaturePolicy {
    private DuctFeaturePolicy() {}

    public static boolean isDisabled(@Nullable DuctDefinition def, String key) {
        if (def == null || key == null) {
            return false;
        }
        return def.disabledFeatures().contains(key);
    }

    public static boolean isForbidden(@Nullable DuctDefinition def, String key) {
        if (def == null || key == null) {
            return false;
        }
        return def.forbiddenFeatures().contains(key);
    }

    /**
     * @param hasModule coarse unlock: any module slot non-empty on the edited face (see duct BE helper).
     */
    public static boolean isUsable(@Nullable DuctDefinition def, String key, boolean hasModule) {
        if (def == null) {
            return true;
        }
        if (isDisabled(def, key)) {
            return false;
        }
        if (isForbidden(def, key)) {
            return hasModule;
        }
        return true;
    }

    public static boolean isModeUsable(@Nullable DuctDefinition def, NodeMode mode, boolean hasModule) {
        return isUsable(def, DuctFeatureKeys.modeKey(mode), hasModule);
    }

    public static boolean isRoutingUsable(@Nullable DuctDefinition def, RoutingMode mode, boolean hasModule) {
        return isUsable(def, DuctFeatureKeys.routingKey(mode), hasModule);
    }
}
