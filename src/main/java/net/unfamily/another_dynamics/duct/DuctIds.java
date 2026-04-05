package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Normalized logical ids used with {@link DuctDefinitionRegistry#getByLogicalId} and block model {@code duct_id}.
 * JSON may use a full {@link ResourceLocation} string; see {@link #normalizeLogicalId}.
 */
public final class DuctIds {
    /**
     * Normalized id for {@code "id": "another_dynamics:item_duct"} in
     * {@code data/another_dynamics/load/item_duct.json}.
     */
    public static final String ITEM_DUCT = "item_duct";

    private DuctIds() {}

    /**
     * Normalizes JSON {@code id} for {@link DuctDefinitionRegistry} (always loaded through our {@code declare_duct} pipeline).
     * <ul>
     *   <li>{@code another_dynamics:item_duct} → {@code item_duct} (same as registry name for our blocks/items).</li>
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
}
