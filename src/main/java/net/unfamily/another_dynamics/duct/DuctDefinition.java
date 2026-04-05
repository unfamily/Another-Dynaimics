package net.unfamily.another_dynamics.duct;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * Datapack duct declaration. {@link #defaultTexture} is read from {@code rendering.default_texture} and is the
 * authoritative block texture for composite duct rendering until multi-layer rules exist.
 */
public record DuctDefinition(
        ResourceLocation dataId,
        String logicalId,
        List<String> transportKinds,
        ResourceLocation defaultTexture
) {}
