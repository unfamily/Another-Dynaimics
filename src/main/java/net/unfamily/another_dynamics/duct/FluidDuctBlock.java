package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

/**
 * Fluid-only duct ({@link DuctNetworkType#FLUID}). Same {@link DuctBlockEntity} as {@link DuctBlock}.
 * <p><strong>Material-lane family:</strong> keep filter/routing/GUI behaviour aligned with {@link DuctBlock} (item),
 * {@link GasDuctBlock}, and fluid lanes on universal ducts — see {@link DuctFaceNode#saveSettings} and
 * {@link DuctFluidFilterLogic}.
 */
public class FluidDuctBlock extends DuctBlock {
    public FluidDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        return EnumSet.of(DuctNetworkType.FLUID);
    }

    @Override
    protected String defaultSoundLogicalId() {
        return "another_dynamics:fluid_duct";
    }
}
