package net.unfamily.another_dynamics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FILTER_SYNC_DEBUG;
    public static final ModConfigSpec.BooleanValue DUCT_TRANSIT_DEBUG;
    public static final ModConfigSpec.BooleanValue DUCT_ROUTING_DEBUG;
    public static final ModConfigSpec.BooleanValue DUCT_CACHE_DEBUG;

    static {
        BUILDER.comment("Developer diagnostics").push("dev");
        FILTER_SYNC_DEBUG =
                BUILDER.comment(
                                "Heavy filter mirror sync/push tracing on client and dedicated server "
                                        + "(log tag [FILTER-DBG]). Disable after debugging.")
                        .define("000_filterSyncDebug", false);
        DUCT_TRANSIT_DEBUG =
                BUILDER.comment(
                                "Item transit cancel tracing on dedicated server (log tag [DUCT-TRN]). "
                                        + "Logs cancel reason, path break site, and edge coordinates.")
                        .define("001_ductTransitDebug", false);
        DUCT_ROUTING_DEBUG =
                BUILDER.comment(
                                "Duct routing cache diagnostics (log tag [DUCT-RTG]). "
                                        + "Logs BFS reachability mismatches and endpoint index builds.")
                        .define("002_ductRoutingDebug", false);
        DUCT_CACHE_DEBUG =
                BUILDER.comment(
                                "Duct network cache invalidation tracing (log tag [DUCT-CCH]). "
                                        + "Logs topology invalidates with abbreviated caller.")
                        .define("003_ductCacheDebug", false);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();
}
