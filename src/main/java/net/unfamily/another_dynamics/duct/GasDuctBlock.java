package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

/**
 * Gas-only duct ({@link DuctNetworkType#GAS}); registered when Mekanism is present.
 * <p><strong>Material-lane family:</strong> keep filter/routing/GUI behaviour aligned with {@link DuctBlock} (item),
 * {@link FluidDuctBlock}, and gas lanes on universal ducts — see {@link DuctFaceNode#saveSettings} and
 * {@link DuctGasFilterLogic}.
 */
public final class GasDuctBlock extends DuctBlock {
    public GasDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        return EnumSet.of(DuctNetworkType.GAS);
    }

    @Override
    protected String defaultSoundLogicalId() {
        return "another_dynamics:gas_duct";
    }
}

