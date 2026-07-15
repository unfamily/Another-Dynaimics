package net.unfamily.another_dynamics.duct.filterimport;

import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalUpgradeFilterImportSource;

public final class FilterImportBootstrap {
    private FilterImportBootstrap() {}

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(
                () -> FilterImportRegistry.register(ExternalUpgradeFilterImportSource.INSTANCE));
    }
}
