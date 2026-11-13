package net.unfamily.another_dynamics.integration.iskalib;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctUnknownFluxTransportSpec;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

/**
 * Soft integration with Iskandert's Library ({@code iska_lib}). Unknown Flux classes that hard-link the library API
 * are only touched via reflection when the mod is present, so AD still loads without it.
 */
public final class IskandertLibCompat {
    public static final String MODID = "iska_lib";

    private static final String TICK_CLASS =
            "net.unfamily.another_dynamics.duct.logistics.DuctUnknownFluxServerTick";
    private static final String CAPABILITIES_CLASS =
            "net.unfamily.another_dynamics.integration.iskalib.UnknownFluxCapabilities";
    private static final String FACE_BUFFER_CLASS =
            "net.unfamily.another_dynamics.integration.iskalib.DuctUnknownFluxFaceBuffer";

    private IskandertLibCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    public static void tickUnknownFlux(DuctBlockEntity be, ServerLevel level) {
        if (!isLoaded()) {
            return;
        }
        try {
            Class.forName(TICK_CLASS).getMethod("tick", DuctBlockEntity.class, ServerLevel.class).invoke(null, be, level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to tick Unknown Flux logistics", e);
        }
    }

    public static void tickUnknownFluxStallFace(
            DuctBlockEntity be, ServerLevel level, Direction face, DuctUnknownFluxTransportSpec spec) {
        if (!isLoaded()) {
            return;
        }
        try {
            Class.forName(TICK_CLASS)
                    .getMethod(
                            "tickStallBuffersForFace",
                            DuctBlockEntity.class,
                            ServerLevel.class,
                            Direction.class,
                            DuctUnknownFluxTransportSpec.class)
                    .invoke(null, be, level, face, spec);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to stall-tick Unknown Flux logistics", e);
        }
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        if (!isLoaded()) {
            return;
        }
        try {
            Class<?> caps = Class.forName(CAPABILITIES_CLASS);
            Object blockCap = caps.getField("BLOCK").get(null);
            Class<?> faceBuf = Class.forName(FACE_BUFFER_CLASS);
            var ctor = faceBuf.getConstructor(DuctBlockEntity.class, Direction.class);
            event.registerBlockEntity(
                    (net.neoforged.neoforge.capabilities.BlockCapability) blockCap,
                    ModBlockEntities.DUCT.get(),
                    (be, side) -> {
                        DuctBlockEntity duct = (DuctBlockEntity) be;
                        Direction face = (Direction) side;
                        if (!duct.canExposeUnknownFluxBuffer(face)) {
                            return null;
                        }
                        try {
                            return ctor.newInstance(duct, face);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("Failed to create Unknown Flux face buffer", e);
                        }
                    });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to register Unknown Flux capabilities", e);
        }
    }

    /** Neighbor has an Unknown Flux capability on the given face (false if iska_lib absent). */
    public static boolean hasUnknownFluxHandler(Level level, BlockPos pos, Direction face) {
        if (!isLoaded() || level == null || pos == null || face == null) {
            return false;
        }
        try {
            Class<?> caps = Class.forName(CAPABILITIES_CLASS);
            Object blockCap = caps.getField("BLOCK").get(null);
            Object handler = level.getCapability(
                    (net.neoforged.neoforge.capabilities.BlockCapability) blockCap, pos, face);
            return handler != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
