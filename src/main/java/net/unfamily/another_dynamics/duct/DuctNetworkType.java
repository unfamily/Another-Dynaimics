package net.unfamily.another_dynamics.duct;

/**
 * Logical duct network: only matching types connect as pipe neighbors and share pathfinding.
 * A block may support several types at once (hybrid duct); {@link DuctConnectable#ductNetworkTypes()} returns the set.
 * Item logistics use {@link #ITEM}; add further constants when implementing other duct kinds.
 */
public enum DuctNetworkType {
    ITEM,
    FLUID,
    GAS,
    ENERGY,
    /** Mekanism thermal ducts ({@link DuctTransportKind#HEAT}). */
    HEAT
}
