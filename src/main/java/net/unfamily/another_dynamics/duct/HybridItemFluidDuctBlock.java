package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

/**
 * Item + fluid hybrid (both network graphs). Subset of universal material lanes — same {@link DuctFaceNode} layout as
 * single-kind ducts; changes to item/fluid lanes should stay consistent with {@link DuctBlock} and {@link FluidDuctBlock}.
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
