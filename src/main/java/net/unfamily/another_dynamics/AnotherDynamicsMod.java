package net.unfamily.another_dynamics;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.unfamily.another_dynamics.duct.DuctDefinitionLoader;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModBlocks;
import net.unfamily.another_dynamics.registry.ModItems;

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
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.TYPES.register(modEventBus);

        modEventBus.addListener(BuildCreativeModeTabContentsEvent.class, AnotherDynamicsMod::onBuildCreativeTab);

        NeoForge.EVENT_BUS.addListener(AnotherDynamicsMod::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(DUCT_LOADER);
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(ModItems.ITEM_DUCT.get());
        }
    }
}
