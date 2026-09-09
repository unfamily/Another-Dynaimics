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
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterConditionalItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.unfamily.another_dynamics.client.DuctClientSetup;
import net.unfamily.another_dynamics.client.GuideMeRegistration;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.unfamily.another_dynamics.duct.DuctDefinitionLoader;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportBootstrap;
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
import net.unfamily.another_dynamics.duct.DuctJumpAssistEvents;

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
        modEventBus.addListener(FilterImportBootstrap::onCommonSetup);
        modEventBus.addListener(AnotherDynamicsMod::onCommonSetup);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(AnotherDynamicsMod::onAddReloadListeners);
        NeoForge.EVENT_BUS.register(RemoteNodeSelectorEvents.class);
        NeoForge.EVENT_BUS.register(DuctJumpAssistEvents.class);
        NeoForge.EVENT_BUS.register(net.unfamily.another_dynamics.duct.project.ProjectDuctConvertJobs.class);
        NeoForge.EVENT_BUS.register(net.unfamily.another_dynamics.duct.DuctReplaceJobs.class);

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            GuideMeRegistration.register();
            modEventBus.addListener(FMLClientSetupEvent.class, DuctClientSetup::onClientSetup);
            modEventBus.addListener(AddClientReloadListenersEvent.class, DuctClientSetup::onAddClientReloadListeners);
            modEventBus.addListener(RegisterMenuScreensEvent.class, DuctClientSetup::onRegisterMenuScreens);
            modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class, DuctClientSetup::onRegisterRenderers);
            modEventBus.addListener(ModelEvent.RegisterLoaders.class, DuctClientSetup::onRegisterModelLoaders);
            modEventBus.addListener(RegisterSpecialModelRendererEvent.class, DuctClientSetup::onRegisterSpecialModelRenderers);
            modEventBus.addListener(
                    RegisterConditionalItemModelPropertyEvent.class,
                    DuctClientSetup::onRegisterConditionalItemModelProperties);
            modEventBus.addListener(ModelEvent.ModifyBakingResult.class, DuctClientSetup::onModifyBakingResult);
            modEventBus.addListener(ModelEvent.BakingCompleted.class, DuctClientSetup::onBakingCompleted);
        }

        initOptionalIntegrations();
    }

    private static void onCommonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("cable_facades")) {
            try {
                Class.forName("net.unfamily.another_dynamics.integration.cablefacades.CableFacadesCompat")
                        .getMethod("register", net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class)
                        .invoke(null, event);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("Failed to register Cable Facades compatibility", e);
            }
        }
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
        event.addListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "duct_definitions"), DUCT_LOADER);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.Energy.BLOCK,
                ModBlockEntities.DUCT.get(),
                (be, side) -> {
                    var storage = ((DuctBlockEntity) be).energyBufferCapability(side);
                    return storage == null ? null : new net.unfamily.another_dynamics.duct.logistics.LegacyEnergyStorageHandler(storage);
                });
        event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                ModBlockEntities.SEQUENTIAL_BUFFER.get(),
                (be, side) ->
                        ((net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity) be)
                                .itemCapability(side));
        event.registerBlockEntity(
                Capabilities.Fluid.BLOCK,
                ModBlockEntities.SEQUENTIAL_BUFFER.get(),
                (be, side) ->
                        ((net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity) be)
                                .fluidCapability(side));
    }

}
