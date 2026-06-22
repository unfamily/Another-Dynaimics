package net.unfamily.another_dynamics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FILTER_SYNC_DEBUG;
    public static final ModConfigSpec.BooleanValue LOGISTICS_CANCEL_DEBUG;

    static {
        BUILDER.comment("Developer diagnostics").push("dev");
        FILTER_SYNC_DEBUG =
                BUILDER.comment(
                                "Heavy filter mirror sync/push tracing on client and dedicated server "
                                        + "(log tag [FILTER-DBG]). Disable after debugging.")
                        .define("000_filterSyncDebug", false);
        LOGISTICS_CANCEL_DEBUG =
                BUILDER.comment("Log item/fluid/gas shipment cancel reasons (log tag [LOGISTICS-CANCEL]).")
                        .define("001_logisticsCancelDebug", false);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
