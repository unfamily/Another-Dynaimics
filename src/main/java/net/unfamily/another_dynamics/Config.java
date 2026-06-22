package net.unfamily.another_dynamics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FILTER_SYNC_DEBUG;

    static {
        BUILDER.comment("Developer diagnostics").push("dev");
        FILTER_SYNC_DEBUG =
                BUILDER.comment(
                                "Heavy filter mirror sync/push tracing on client and dedicated server "
                                        + "(log tag [FILTER-DBG]). Disable after debugging.")
                        .define("000_filterSyncDebug", false);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();
}
