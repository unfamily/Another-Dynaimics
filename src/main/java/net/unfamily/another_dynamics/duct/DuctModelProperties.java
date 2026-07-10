package net.unfamily.another_dynamics.duct;

import net.neoforged.neoforge.model.data.ModelProperty;

public final class DuctModelProperties {
    public static final ModelProperty<Integer> PIPE_MASK = new ModelProperty<>();
    public static final ModelProperty<Integer> STORAGE_MASK = new ModelProperty<>();
    /** Packed nibble (0-15) per face: node icon index from nodes.png (face ordinal order). */
    public static final ModelProperty<Integer> NODE_ICONS_PACKED = new ModelProperty<>();
    /** True when this duct has at least one stalled buffered item. */
    public static final ModelProperty<Boolean> HAS_STALL = new ModelProperty<>();
    /** Bitmask (1<<face.ordinal) for faces that have any stalled buffer (any transport kind). */
    public static final ModelProperty<Integer> STALL_MASK = new ModelProperty<>();
    /**
     * Per-block {@link net.unfamily.another_dynamics.duct.DuctDefinition#logicalId()} from {@link DuctBlockEntity};
     * drives opaque skin and (when geometry matches) definition lookups in {@link net.unfamily.another_dynamics.client.DuctBlockStateModel}.
     */
    public static final ModelProperty<String> DUCT_LOGICAL_ID = new ModelProperty<>();
    /** Server-synced network opaque skin (all players). */
    public static final ModelProperty<Boolean> NETWORK_OPAQUE = new ModelProperty<>();

    private DuctModelProperties() {}
}
