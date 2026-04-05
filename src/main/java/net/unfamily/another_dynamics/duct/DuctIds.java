package net.unfamily.another_dynamics.duct;

/**
 * Logical {@code id} values from duct declaration JSON in each namespace's {@code data/.../load/} folder.
 * Used to resolve {@link DuctDefinition} (textures, transport, etc.) for blocks and rendering.
 */
public final class DuctIds {
    /** Matches {@code "id": "item_duct"} in {@code data/another_dynamics/load/item_duct.json}. */
    public static final String ITEM_DUCT = "item_duct";

    private DuctIds() {}
}
