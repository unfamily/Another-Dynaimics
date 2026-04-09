package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

/**
 * Item + fluid hybrid: joins both network graphs simultaneously.
 */
public class HybridItemFluidDuctBlock extends DuctBlock {
    public HybridItemFluidDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        return EnumSet.of(DuctNetworkType.ITEM, DuctNetworkType.FLUID);
    }
}
