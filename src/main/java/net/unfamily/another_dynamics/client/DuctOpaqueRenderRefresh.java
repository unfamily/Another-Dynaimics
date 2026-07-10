package net.unfamily.another_dynamics.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Duct quads depend on per-player opaque mode but chunk meshes cache baked geometry; refresh when the attachment flips.
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, value = Dist.CLIENT)
public final class DuctOpaqueRenderRefresh {
    private static boolean lastOpaque = false;

    private DuctOpaqueRenderRefresh() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.levelRenderer == null) {
            lastOpaque = false;
            return;
        }
        boolean now =
                mc.player.getData(ModAttachments.DUCT_PLAYER_OPAQUE.get()).allOpaqueActive();
        if (now != lastOpaque) {
            lastOpaque = now;
            mc.levelRenderer.allChanged();
        }
    }
}
