package net.unfamily.another_dynamics.duct;

import net.neoforged.neoforge.client.model.data.ModelProperty;

public final class DuctModelProperties {
    public static final ModelProperty<Integer> PIPE_MASK = new ModelProperty<>();
    public static final ModelProperty<Integer> STORAGE_MASK = new ModelProperty<>();
    /** Packed nibble (0-15) per face: node icon index from nodes.png (face ordinal order). */
    public static final ModelProperty<Integer> NODE_ICONS_PACKED = new ModelProperty<>();
    /**
     * Per-block {@link net.unfamily.another_dynamics.duct.DuctDefinition#logicalId()} from {@link DuctBlockEntity};
     * drives opaque skin and (when geometry matches) definition lookups in {@link net.unfamily.another_dynamics.client.DuctBakedModel}.
     */
    public static final ModelProperty<String> DUCT_LOGICAL_ID = new ModelProperty<>();

    private DuctModelProperties() {}
}
