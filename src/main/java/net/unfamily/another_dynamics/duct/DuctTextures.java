package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Resolves block/item atlas textures for ducts from {@link DuctDefinition} (populated from datapack JSON
 * {@code rendering.default_texture}). Single entry point for rendering code; add multi-texture rules here later.
 */
public final class DuctTextures {
    private static final ResourceLocation FALLBACK_ITEM_DUCT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/duct/item/item_duct_0_light");

    private DuctTextures() {}

    /**
     * Block texture for composite duct models (center, arms, nodes, line). Comes from the declaration JSON
     * {@code rendering.default_texture} for the given logical duct id.
     */
    public static ResourceLocation compositeBlockTexture(String logicalDuctId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::defaultTexture)
                .orElseGet(() -> {
                    AnotherDynamicsMod.LOGGER.debug(
                            "No duct definition for logical id '{}', using fallback texture",
                            logicalDuctId);
                    return FALLBACK_ITEM_DUCT_TEXTURE;
                });
    }
}
