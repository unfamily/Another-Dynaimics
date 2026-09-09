package net.unfamily.another_dynamics.duct.filterimport;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalUpgradeFilterImportSource;

@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID)
public final class FilterImportBootstrap {
    private FilterImportBootstrap() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(
                () -> FilterImportRegistry.register(ExternalUpgradeFilterImportSource.INSTANCE));
    }
}
