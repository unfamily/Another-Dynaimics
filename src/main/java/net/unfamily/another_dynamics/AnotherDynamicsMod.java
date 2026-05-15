package net.unfamily.another_dynamics;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
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
import net.unfamily.another_dynamics.network.ModNetwork;

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

        NeoForge.EVENT_BUS.addListener(AnotherDynamicsMod::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DUCT_LOADER);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.DUCT.get(),
                (be, side) -> ((DuctBlockEntity) be).energyBufferCapability(side));
    }

}
