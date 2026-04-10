package net.unfamily.another_dynamics.integration.mekanism;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;

import org.jetbrains.annotations.Nullable;

/**
 * Optional Mekanism heat capability access without hard-loading Mek classes when the mod is absent.
 */
public final class MekanismHeatCompat {
    public static final String MODID = "mekanism";

    private static boolean resolved;
    private static Object heatBlockCapability;
    private static Method getTotalTemperature;
    private static Method getTotalHeatCapacity;
    private static Method handleHeat;

    private MekanismHeatCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!isLoaded()) {
            return;
        }
        try {
            Class<?> caps = Class.forName("mekanism.common.capabilities.Capabilities");
            heatBlockCapability = caps.getField("HEAT").get(null);
            Class<?> iface = Class.forName("mekanism.api.heat.IHeatHandler");
            getTotalTemperature = iface.getMethod("getTotalTemperature");
            getTotalHeatCapacity = iface.getMethod("getTotalHeatCapacity");
            handleHeat = iface.getMethod("handleHeat", double.class);
        } catch (ReflectiveOperationException e) {
            heatBlockCapability = null;
        }
    }

    public static boolean isHeatCapabilityAvailable() {
        resolve();
        return heatBlockCapability != null && handleHeat != null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static Object getHeatHandler(Level level, BlockPos pos, @Nullable Direction side) {
        resolve();
        if (heatBlockCapability == null || level == null) {
            return null;
        }
        try {
            return level.getCapability((BlockCapability<Object, Direction>) heatBlockCapability, pos, side);
        } catch (Throwable t) {
            return null;
        }
    }

    public static double getTotalTemperature(Object handler) {
        resolve();
        if (handler == null || getTotalTemperature == null) {
            return 0.0;
        }
        try {
            return ((Number) getTotalTemperature.invoke(handler)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return 0.0;
        }
    }

    public static double getTotalHeatCapacity(Object handler) {
        resolve();
        if (handler == null || getTotalHeatCapacity == null) {
            return 0.0;
        }
        try {
            return ((Number) getTotalHeatCapacity.invoke(handler)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return 0.0;
        }
    }

    public static void handleHeat(Object handler, double amount) {
        resolve();
        if (handler == null || handleHeat == null) {
            return;
        }
        try {
            handleHeat.invoke(handler, amount);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
