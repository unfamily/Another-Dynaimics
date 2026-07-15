package net.unfamily.another_dynamics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.gui.DuctNodeScreen;
import net.unfamily.another_dynamics.client.gui.SettingsCopierScreen;
import net.unfamily.another_dynamics.client.project.ProjectDuctBlockStateModel;
import net.unfamily.another_dynamics.client.project.ProjectDuctGeometryLoader;
import net.unfamily.another_dynamics.duct.DuctDefinitionsReloadedEvent;
import net.unfamily.another_dynamics.client.transit.DuctTransitBlockEntityRenderer;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

/**
 * Client model/GUI hooks for composite ducts. Registered on the mod bus from {@link AnotherDynamicsMod}.
 */
public final class DuctClientSetup {
    private DuctClientSetup() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(DuctClientSetup::onDuctDefinitionsReloaded);
    }

    private static void onDuctDefinitionsReloaded(DuctDefinitionsReloadedEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(DuctRenderingSupport::invalidateGlobalGeometryCacheForReload);
        }
    }

    public static void onAddClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_definitions"),
                AnotherDynamicsMod.ductDefinitionLoader());
    }

    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.DUCT_NODE.get(), DuctNodeScreen::new);
        event.register(ModMenuTypes.SETTINGS_COPIER_HUB.get(), SettingsCopierScreen::new);
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.DUCT.get(), DuctTransitBlockEntityRenderer::new);
    }

    public static void onRegisterModelLoaders(ModelEvent.RegisterLoaders event) {
        event.register(DuctGeometryLoader.ID, new DuctGeometryLoader());
        event.register(ProjectDuctGeometryLoader.ID, new ProjectDuctGeometryLoader());
    }

    public static void onRegisterSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(DuctItemSpecialRenderer.ID, new DuctItemSpecialRenderer.Unbaked().type());
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        DuctBlockStateModels.onModifyBakingResult(event);
    }

    public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        java.util.function.Function<SpriteId, TextureAtlasSprite> spriteGetter = DuctRenderingSupport.blockAtlasSpriteGetter();
        var cache = DuctRenderingSupport.bakeAllGeometries(spriteGetter, null);
        DuctRenderingSupport.updateGlobalGeometryCache(cache);
        DuctRenderingSupport.updateOverlaySprites(
                spriteGetter.apply(
                        DuctRenderingSupport.blockSprite(
                                Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/nodes"))),
                spriteGetter.apply(
                        DuctRenderingSupport.blockSprite(
                                Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "block/node_buffer"))));
        DuctRenderingSupport.updateProjectGeometry(ProjectDuctBlockStateModel.bakeGeometry(spriteGetter));
    }
}
