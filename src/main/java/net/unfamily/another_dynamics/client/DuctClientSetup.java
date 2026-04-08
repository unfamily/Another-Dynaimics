package net.unfamily.another_dynamics.client;

import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.gui.DuctNodeScreen;
import net.unfamily.another_dynamics.client.transit.DuctTransitBlockEntityRenderer;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

/**
 * Registers composite duct geometry ({@link DuctGeometryLoader}) and ensures template models are loaded for baking.
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DuctClientSetup {
    private DuctClientSetup() {}

    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(AnotherDynamicsMod.ductDefinitionLoader());
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.DUCT_NODE.get(), DuctNodeScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.DUCT.get(), DuctTransitBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(DuctGeometryLoader.ID, new DuctGeometryLoader());
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/simple_duct_center_only")));
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/simple_duct_default")));
        event.register(ModelResourceLocation.standalone(ResourceLocation.parse("another_dynamics:block/simple_duct_line")));
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
