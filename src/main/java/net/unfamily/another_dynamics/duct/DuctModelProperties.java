package net.unfamily.another_dynamics.duct;

import net.neoforged.neoforge.client.model.data.ModelProperty;

public final class DuctModelProperties {
    public static final ModelProperty<Integer> PIPE_MASK = new ModelProperty<>();
    public static final ModelProperty<Integer> STORAGE_MASK = new ModelProperty<>();
    /** Packed nibble (0-15) per face: node icon index from nodes.png (face ordinal order). */
    public static final ModelProperty<Integer> NODE_ICONS_PACKED = new ModelProperty<>();

    private DuctModelProperties() {}
}
