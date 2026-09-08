package net.unfamily.another_dynamics.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Loads GuideME integration via reflection so {@link AnotherDynamicsMod} never references guideme on dedicated servers.
 * Must run from the mod constructor on the physical client, before the first resource reload.
 */
public final class GuideMeRegistration {
    private static final String IMPL_CLASS = "net.unfamily.another_dynamics.guide.AnotherDynamicsGuide";

    private GuideMeRegistration() {}

    public static void register() {
        if (FMLEnvironment.getDist() != Dist.CLIENT || !ModList.get().isLoaded("guideme")) {
            return;
        }
        try {
            Class.forName(IMPL_CLASS).getMethod("registerClient").invoke(null);
        } catch (ReflectiveOperationException e) {
            AnotherDynamicsMod.LOGGER.error("Failed to register GuideME guide", e);
        }
    }
}
