package net.unfamily.another_dynamics.duct;

import net.neoforged.neoforge.client.model.data.ModelProperty;

public final class DuctModelProperties {
    public static final ModelProperty<Integer> PIPE_MASK = new ModelProperty<>();
    public static final ModelProperty<Integer> STORAGE_MASK = new ModelProperty<>();
    /** Packed nibble (0-15) per face: node icon index from nodes.png (face ordinal order). */
    public static final ModelProperty<Integer> NODE_ICONS_PACKED = new ModelProperty<>();
    /** True when this duct has at least one stalled buffered item (full or soft overlay). */
    public static final ModelProperty<Boolean> HAS_STALL = new ModelProperty<>();
    /** Bitmask (1<<face.ordinal) for faces at the busy stall threshold (full overlay). */
    public static final ModelProperty<Integer> STALL_MASK = new ModelProperty<>();
    /**
     * Bitmask for faces with early-warning soft stall overlay ({@code 1 ≤ occupied <} busy threshold).
     * Mutually exclusive with {@link #STALL_MASK} bits on the same face.
     */
    public static final ModelProperty<Integer> SOFT_STALL_MASK = new ModelProperty<>();
    /**
     * Per-block {@link net.unfamily.another_dynamics.duct.DuctDefinition#logicalId()} from {@link DuctBlockEntity};
     * drives opaque skin and (when geometry matches) definition lookups in {@link net.unfamily.another_dynamics.client.DuctBakedModel}.
     */
    public static final ModelProperty<String> DUCT_LOGICAL_ID = new ModelProperty<>();
    /** Server-synced network opaque skin (all players). */
    public static final ModelProperty<Boolean> NETWORK_OPAQUE = new ModelProperty<>();

    private DuctModelProperties() {}
}
