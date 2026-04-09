package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;

/**
 * Canonical logical ids stored in {@code another_dynamics:duct_logical_id}.
 *
 * <p>These are full {@link ResourceLocation} strings (namespace:path) as provided in datapack JSON {@code id}.</p>
 */
public final class DuctIds {
    /** Primary duct type id from datapack, used as default on the item/block entity. */
    public static final String DEFAULT_LOGICAL_ID = "another_dynamics:item_duct";

    private DuctIds() {}

    public static String normalize(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return DEFAULT_LOGICAL_ID;
        }
        // Validates/normalizes casing to the canonical string form.
        ResourceLocation rl = ResourceLocation.parse(rawId.trim());
        return rl.toString();
    }

    /**
     * Translation key for the duct node screen title: {@code {namespace}.{path_with_slashes_as_dots}.node}, e.g.
     * {@code custom:universal_duct} → {@code custom.universal_duct.node}.
     */
    public static String nodeScreenTranslationKey(String logicalId) {
        ResourceLocation rl = ResourceLocation.parse(normalize(logicalId));
        return rl.getNamespace() + "." + rl.getPath().replace('/', '.') + ".node";
    }
}
