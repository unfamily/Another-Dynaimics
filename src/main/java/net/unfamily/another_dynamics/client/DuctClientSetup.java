package net.unfamily.another_dynamics.client;

import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierItemProperties;
import net.unfamily.another_dynamics.duct.DuctDefinitionsReloadedEvent;
import net.unfamily.another_dynamics.client.gui.DuctNodeScreen;
import net.unfamily.another_dynamics.client.gui.SettingsCopierScreen;
import net.unfamily.another_dynamics.client.project.ProjectDuctGeometryLoader;
import net.unfamily.another_dynamics.client.transit.DuctTransitBlockEntityRenderer;
import net.unfamily.another_dynamics.machine.sequential.SequentialBufferScreen;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModItems;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

/**
 * Registers composite duct geometry ({@link DuctGeometryLoader}) and ensures template models are loaded for baking.
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, value = Dist.CLIENT)
public final class DuctClientSetup {
    private DuctClientSetup() {}

    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions ductItemExtensions =
                new IClientItemExtensions() {
                    private DuctItemRenderer renderer;

                    @Override
                    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                        if (this.renderer == null) {
                            this.renderer = new DuctItemRenderer();
                        }
                        return this.renderer;
                    }
                };
        event.registerItem(ductItemExtensions, ModItems.DUCT.get());
        event.registerItem(ductItemExtensions, ModItems.FLUID_DUCT.get());
        event.registerItem(ductItemExtensions, ModItems.ITEM_FLUID_DUCT.get());
        if (ModItems.GAS_DUCT != null) {
            event.registerItem(ductItemExtensions, ModItems.GAS_DUCT.get());
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(DuctClientSetup::onDuctDefinitionsReloaded);
        event.enqueueWork(
                () -> {
                    ItemProperties.register(
                            ModItems.SETTINGS_COPIER.get(),
                            SettingsCopierItemProperties.COPIER_FILTER,
                            (ItemStack stack,
                                    net.minecraft.client.multiplayer.ClientLevel level,
                                    net.minecraft.world.entity.LivingEntity entity,
                                    int seed) -> SettingsCopierItemProperties.copierFilter(stack));
                    ItemProperties.register(
                            ModItems.SETTINGS_COPIER.get(),
                            SettingsCopierItemProperties.COPIER_SEQUENTIAL,
                            (ItemStack stack,
                                    net.minecraft.client.multiplayer.ClientLevel level,
                                    net.minecraft.world.entity.LivingEntity entity,
                                    int seed) -> SettingsCopierItemProperties.copierSequential(stack));
                    ItemProperties.register(
                            ModItems.SETTINGS_COPIER.get(),
                            SettingsCopierItemProperties.COPIER_FILLED,
                            (ItemStack stack,
                                    net.minecraft.client.multiplayer.ClientLevel level,
                                    net.minecraft.world.entity.LivingEntity entity,
                                    int seed) -> SettingsCopierItemProperties.copierFilled(stack));
                });
    }

    private static void onDuctDefinitionsReloaded(DuctDefinitionsReloadedEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(DuctBakedModel::invalidateGlobalGeometryCacheForReload);
        }
    }

    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(AnotherDynamicsMod.ductDefinitionLoader());
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.DUCT_NODE.get(), DuctNodeScreen::new);
        event.register(ModMenuTypes.SETTINGS_COPIER_HUB.get(), SettingsCopierScreen::new);
        event.register(ModMenuTypes.SEQUENTIAL_BUFFER.get(), SequentialBufferScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.DUCT.get(), DuctTransitBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(DuctGeometryLoader.ID, new DuctGeometryLoader());
        event.register(ProjectDuctGeometryLoader.ID, new ProjectDuctGeometryLoader());
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/simple_duct_center_only")));
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/simple_duct_default")));
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/simple_duct_line")));
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/project_duct_default")));
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/project_duct_line")));
        event.register(
                ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "item/settings_copier_all_0")));
        event.register(
                ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "item/settings_copier_all_1")));
        event.register(
                ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "item/settings_copier_filter_0")));
        event.register(
                ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "item/settings_copier_filter_1")));
        event.register(
                ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(
                                AnotherDynamicsMod.MOD_ID, "item/settings_copier_sequential_0")));
        event.register(
                ModelResourceLocation.standalone(
                        ResourceLocation.fromNamespaceAndPath(
                                AnotherDynamicsMod.MOD_ID, "item/settings_copier_sequential_1")));
    }

    /**
     * Fired after ALL models are baked and the texture atlas is fully stitched.
     * Mirrors the {@code onBakingCompleted} pattern from Custom-Machinery: use this hook to build
     * any data that requires the complete atlas, then store it for runtime use.
     */
    @SubscribeEvent
    public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        Function<Material, TextureAtlasSprite> spriteGetter = mat ->
                Minecraft.getInstance().getTextureAtlas(mat.atlasLocation()).apply(mat.texture());
        Map<String, DuctCompositeGeometry> cache = DuctBakedModel.bakeAllGeometries(spriteGetter, null);
        DuctBakedModel.updateGlobalGeometryCache(cache);
    }
}
