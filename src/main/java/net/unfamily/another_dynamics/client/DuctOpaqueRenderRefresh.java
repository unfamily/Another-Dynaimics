package net.unfamily.another_dynamics.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Duct quads depend on per-player opaque mode but chunk meshes cache baked geometry; refresh when the attachment
 * flips. Also exposes a stable {@link #snapshotPlayerAllOpaque()} for async chunk meshing (player may be null there).
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class DuctOpaqueRenderRefresh {
    private static boolean lastOpaque = false;
    /** Last known player "All opaque" flag; safe for chunk rebuild threads. */
    private static volatile boolean snapshotAllOpaque = false;

    private DuctOpaqueRenderRefresh() {}

    /** Player All-opaque as observed on the client tick thread (never reads {@code Minecraft.player} off-thread). */
    public static boolean snapshotPlayerAllOpaque() {
        return snapshotAllOpaque;
    }

    /** Force a full level mesh rebuild (e.g. network opaque flipped on one or more ducts). */
    public static void requestFullMeshRefresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.levelRenderer == null) {
            lastOpaque = false;
            snapshotAllOpaque = false;
            return;
        }
        boolean now =
                mc.player.getData(ModAttachments.DUCT_PLAYER_OPAQUE.get()).allOpaqueActive();
        snapshotAllOpaque = now;
        if (now != lastOpaque) {
            lastOpaque = now;
            mc.levelRenderer.allChanged();
        }
    }
}
