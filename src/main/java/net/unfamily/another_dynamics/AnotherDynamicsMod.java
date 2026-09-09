package net.unfamily.another_dynamics;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.unfamily.another_dynamics.client.GuideMeRegistration;
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
        modEventBus.addListener(AnotherDynamicsMod::onCommonSetup);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(AnotherDynamicsMod::onAddReloadListeners);
        NeoForge.EVENT_BUS.register(RemoteNodeSelectorEvents.class);
        NeoForge.EVENT_BUS.register(DuctJumpAssistEvents.class);
        NeoForge.EVENT_BUS.register(net.unfamily.another_dynamics.duct.project.ProjectDuctConvertJobs.class);
        NeoForge.EVENT_BUS.register(net.unfamily.another_dynamics.duct.DuctReplaceJobs.class);
        NeoForge.EVENT_BUS.register(net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation.class);
        NeoForge.EVENT_BUS.register(net.unfamily.another_dynamics.command.AdDebugCommands.class);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            GuideMeRegistration.register();
        }

        initOptionalIntegrations();
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("cable_facades")) {
            try {
                Class.forName("net.unfamily.another_dynamics.integration.cablefacades.CableFacadesCompat")
                        .getMethod("register", FMLCommonSetupEvent.class)
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

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DUCT_LOADER);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.DUCT.get(),
                (be, side) -> ((DuctBlockEntity) be).energyBufferCapability(side));
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.SEQUENTIAL_BUFFER.get(),
                (be, side) ->
                        ((net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity) be)
                                .itemCapability(side));
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.SEQUENTIAL_BUFFER.get(),
                (be, side) ->
                        ((net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity) be)
                                .fluidCapability(side));
        net.unfamily.another_dynamics.machine.connector.MachineConnectorCapabilities.register(event);
    }

}
