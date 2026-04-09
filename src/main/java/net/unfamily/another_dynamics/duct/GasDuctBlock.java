package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

/**
 * Duct that participates only in the {@link DuctNetworkType#GAS} graph.
 *
 * <p>This block should only be registered when Mekanism is present.</p>
 */
public final class GasDuctBlock extends DuctBlock {
    public GasDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        return EnumSet.of(DuctNetworkType.GAS);
    }
}

