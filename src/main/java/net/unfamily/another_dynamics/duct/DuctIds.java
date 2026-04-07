package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Normalized logical ids used with {@link DuctDefinitionRegistry#getByLogicalId} and block model {@code duct_id}.
 * JSON may use a full {@link ResourceLocation} string; see {@link #normalizeLogicalId}.
 */
public final class DuctIds {
    /**
     * Primary built-in logical id for {@code "id": "another_dynamics:duct"} in
     * {@code data/another_dynamics/load/duct.json}. Matches the block/item registry name {@code duct}.
     */
    public static final String DEFAULT_LOGICAL_ID = "duct";

    /**
     * Legacy primary id before the registry rename; normalized saves and stacks map to {@link #DEFAULT_LOGICAL_ID}.
     */
    public static final String LEGACY_DEFAULT_LOGICAL_ID = "item_duct";

    private DuctIds() {}

    /**
     * Normalizes JSON {@code id} for {@link DuctDefinitionRegistry} (always loaded through our {@code declare_duct} pipeline).
     * <ul>
     *   <li>{@code another_dynamics:duct} → {@code duct} (same as registry name for our block/item).</li>
     *   <li>Other namespaces → {@code namespace_} + path with {@code /} as {@code _}, e.g. {@code othermod:item_duct} →
     *       {@code othermod_item_duct}. Use that string as {@code duct_id} on block models and for lookups.</li>
     *   <li>No {@code :} → returned unchanged (legacy bare ids).</li>
     * </ul>
     */
    public static String normalizeLogicalId(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return rawId;
        }
        if (!rawId.contains(":")) {
            return rawId;
        }
        ResourceLocation rl = ResourceLocation.parse(rawId);
        if (AnotherDynamicsMod.MOD_ID.equals(rl.getNamespace())) {
            return rl.getPath();
        }
        return rl.getNamespace() + "_" + rl.getPath().replace('/', '_');
    }

    /**
     * Maps legacy primary logical id {@value #LEGACY_DEFAULT_LOGICAL_ID} to {@value #DEFAULT_LOGICAL_ID} for
     * block entities, items, and loader output.
     */
    public static String canonicalLogicalId(String normalizedId) {
        if (normalizedId == null || normalizedId.isEmpty()) {
            return normalizedId;
        }
        if (LEGACY_DEFAULT_LOGICAL_ID.equals(normalizedId)) {
            return DEFAULT_LOGICAL_ID;
        }
        return normalizedId;
    }
}
