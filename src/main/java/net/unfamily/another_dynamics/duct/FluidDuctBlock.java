package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

/**
 * Duct that participates only in the {@link DuctNetworkType#FLUID} graph. Uses the same {@link DuctBlockEntity} and ticker
 * as {@link DuctBlock}.
 */
public class FluidDuctBlock extends DuctBlock {
    public FluidDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        return EnumSet.of(DuctNetworkType.FLUID);
    }
}
