package net.unfamily.another_dynamics;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.unfamily.another_dynamics.client.DuctClientSetup;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.unfamily.another_dynamics.duct.DuctDefinitionLoader;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModAttachments;
import net.unfamily.another_dynamics.registry.ModCreativeTabs;
import net.unfamily.another_dynamics.registry.ModDataComponents;
import net.unfamily.another_dynamics.registry.ModItems;
import net.unfamily.another_dynamics.registry.ModMenuTypes;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.item.RemoteNodeSelectorEvents;

@Mod(AnotherDynamicsMod.MOD_ID)
public final class AnotherDynamicsMod {
    public static final String MOD_ID = "another_dynamics";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DuctDefinitionLoader DUCT_LOADER = new DuctDefinitionLoader();

    /** Shared loader instance; registered on server and client so definitions (and textures) exist before model bake. */
    public static DuctDefinitionLoader ductDefinitionLoader() {
        return DUCT_LOADER;
    }

    public AnotherDynamicsMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.debug("Loading {}", MOD_ID);
        ModBlocks.BLOCKS.register(modEventBus);
        ModDataComponents.TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.TYPES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModAttachments.TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(ModNetwork::register);
        modEventBus.addListener(AnotherDynamicsMod::onRegisterCapabilities);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(AnotherDynamicsMod::onAddReloadListeners);
        NeoForge.EVENT_BUS.register(RemoteNodeSelectorEvents.class);

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modEventBus.addListener(FMLClientSetupEvent.class, DuctClientSetup::onClientSetup);
            modEventBus.addListener(AddClientReloadListenersEvent.class, DuctClientSetup::onAddClientReloadListeners);
            modEventBus.addListener(RegisterMenuScreensEvent.class, DuctClientSetup::onRegisterMenuScreens);
            modEventBus.addListener(ModelEvent.RegisterLoaders.class, DuctClientSetup::onRegisterModelLoaders);
            modEventBus.addListener(RegisterSpecialModelRendererEvent.class, DuctClientSetup::onRegisterSpecialModelRenderers);
            modEventBus.addListener(ModelEvent.ModifyBakingResult.class, DuctClientSetup::onModifyBakingResult);
            modEventBus.addListener(ModelEvent.BakingCompleted.class, DuctClientSetup::onBakingCompleted);
            // Transit BER still on 1.21.1 API; register after migrating DuctTransitBlockEntityRenderer to submit().
        }

        initOptionalIntegrations();
    }

    private static void initOptionalIntegrations() {
        if (!ModList.get().isLoaded("ftbultimine")) {
            return;
        }
        try {
            Class.forName("net.unfamily.another_dynamics.integration.ftbultimine.FTBUltimineCompat")
                    .getMethod("register")
                    .invoke(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to register FTB Ultimine duct selection compatibility", e);
        }
    }

    private static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(DUCT_LOADER);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.DUCT.get(),
                (be, side) -> ((DuctBlockEntity) be).energyBufferCapability(side));
    }

}
