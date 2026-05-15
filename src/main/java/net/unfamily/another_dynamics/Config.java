package net.unfamily.another_dynamics;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue ENERGY_ACTION_RATE_TICKS = BUILDER.comment(
                    "Ticks between energy logistics actions per duct face (20 ticks = 1 second). "
                            + "Lower values increase throughput and CPU cost.")
            .defineInRange("energyActionRateTicks", 10, 1, 200);

    private static final ModConfigSpec.IntValue HEAT_ACTION_RATE_TICKS = BUILDER.comment(
                    "Ticks between Mekanism heat logistics actions per duct face (20 ticks = 1 second). "
                            + "Lower values increase throughput and CPU cost.")
            .defineInRange("heatActionRateTicks", 10, 1, 200);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Bumped on common-config load/reload so active ducts reset their action throttle immediately. */
    private static volatile int logisticsRateStamp;

    private Config() {}

    public static int energyActionRateTicks() {
        return ENERGY_ACTION_RATE_TICKS.get();
    }

    public static int heatActionRateTicks() {
        return HEAT_ACTION_RATE_TICKS.get();
    }

    public static void onLogisticsRateConfigChanged() {
        logisticsRateStamp++;
    }

    /** Resets per-face action counters when the common config rate changes. */
    public static void applyLogisticsRateStamp(DuctFaceLanes lanes) {
        int stamp = logisticsRateStamp;
        if (lanes.logisticsRateStampSeen != stamp) {
            lanes.logisticsRateStampSeen = stamp;
            lanes.energyTicksUntilAction = 0;
            lanes.heatTicksUntilAction = 0;
        }
    }
}
