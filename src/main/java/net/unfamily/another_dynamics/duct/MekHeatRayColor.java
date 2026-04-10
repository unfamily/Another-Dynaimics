package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Particle tint from temperature, following Mekanism {@code HeatUtils#getColorFromTemp} RGB curve (hot → more blue).
 */
public final class MekHeatRayColor {
    /** Mekanism {@code HeatAPI#AMBIENT_TEMP} when biome ambient is unavailable. */
    public static final double DEFAULT_AMBIENT_KELVIN = 300.0;

    private MekHeatRayColor() {}

    public static int argbForDust(ServerLevel level, double absoluteKelvin, int blockX, int blockY, int blockZ) {
        double ambient = ambientKelvin(level, blockX, blockY, blockZ);
        double deltaK = absoluteKelvin - ambient;
        return argbFromDeltaKelvin(deltaK);
    }

    public static double ambientKelvin(Level level, int x, int y, int z) {
        if (level == null) {
            return DEFAULT_AMBIENT_KELVIN;
        }
        try {
            Class<?> heatApi = Class.forName("mekanism.api.heat.HeatAPI");
            var m = heatApi.getMethod("getAmbientTemp", net.minecraft.world.level.LevelReader.class, BlockPos.class);
            Object v = m.invoke(null, level, new BlockPos(x, y, z));
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return DEFAULT_AMBIENT_KELVIN;
    }

    /**
     * @param deltaKelvin temperature above local ambient (same sense as Mek {@code HeatUtils} {@code temperature} term).
     */
    public static int argbFromDeltaKelvin(double deltaKelvin) {
        double absTemp = deltaKelvin + DEFAULT_AMBIENT_KELVIN;
        absTemp /= 100.0;

        double effectiveTemp = absTemp;
        if (effectiveTemp < 10) {
            effectiveTemp = 10;
        }
        if (effectiveTemp > 400) {
            effectiveTemp = 400;
        }

        double tmpCalc;
        double red;
        double blue;
        if (effectiveTemp <= 66) {
            red = 1;
        } else {
            tmpCalc = effectiveTemp - 60;
            tmpCalc = 329.698727446 * Math.pow(tmpCalc, -0.1332047592);
            red = tmpCalc / 255.0;
        }

        if (effectiveTemp <= 66) {
            tmpCalc = effectiveTemp;
            tmpCalc = 99.4708025861 * Math.log(tmpCalc) - 161.1195681661;
        } else {
            tmpCalc = effectiveTemp - 60;
            tmpCalc = 288.1221695283 * Math.pow(tmpCalc, -0.0755148492);
        }
        double green = tmpCalc / 255.0;

        if (effectiveTemp >= 66) {
            blue = 1;
        } else if (effectiveTemp <= 19) {
            blue = 0;
        } else {
            tmpCalc = effectiveTemp - 10;
            tmpCalc = 138.5177312231 * Math.log(tmpCalc) - 305.0447927307;
            blue = tmpCalc / 255.0;
        }

        double alpha = deltaKelvin / 1_000.0;
        red = Mth.clamp(red, 0.0, 1.0);
        green = Mth.clamp(green, 0.0, 1.0);
        blue = Mth.clamp(blue, 0.0, 1.0);
        alpha = Mth.clamp(alpha, 0.35, 1.0);

        int a = (int) Math.round(alpha * 255.0);
        int r = (int) Math.round(red * 255.0);
        int g = (int) Math.round(green * 255.0);
        int b = (int) Math.round(blue * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
