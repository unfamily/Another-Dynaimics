package net.unfamily.another_dynamics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FILTER_SYNC_DEBUG;
    public static final ModConfigSpec.BooleanValue DUCT_TRANSIT_DEBUG;
    public static final ModConfigSpec.BooleanValue DUCT_ROUTING_DEBUG;
    public static final ModConfigSpec.BooleanValue DUCT_CACHE_DEBUG;
    public static final ModConfigSpec.BooleanValue CABLE_FACADES_CONFIG_SEED;

    public static final ModConfigSpec.BooleanValue DUCT_GLOBAL_STALL_DRAIN_GUARD;
    public static final ModConfigSpec.IntValue DUCT_STALL_SLOTS;

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
        CABLE_FACADES_CONFIG_SEED =
                BUILDER.comment(
                                "One-shot: when Cable Facades is present, append another_dynamics:* to its "
                                        + "blocks whitelist once, then set this false. API/tag registration still "
                                        + "runs every launch regardless.")
                        .define("004_cableFacadesConfigSeed", true);
        BUILDER.pop();

        BUILDER.comment("Duct logistics tuning").push("logistics");
        DUCT_GLOBAL_STALL_DRAIN_GUARD =
                BUILDER.comment(
                                "Whole-duct safety-net throttle for the item stall drain scan. When true, a duct whose "
                                        + "stall drain makes no progress is skipped entirely for a few ticks (lighter on "
                                        + "TPS). Set false to keep only the finer per-face throttle.")
                        .define("100_ductGlobalStallDrainGuard", true);
        DUCT_STALL_SLOTS =
                BUILDER.comment(
                                "Per-face stall buffer slot count (outbound + inbound item stalls, and matching "
                                        + "fluid/gas stall slots). Also used as the distinct stalled-kind / overflow "
                                        + "busy threshold. Applies to newly created ducts; existing blocks keep their "
                                        + "saved buffer size until rebuilt.")
                        .defineInRange("101_ductStallSlots", 5, 1, 27);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Effective stall slot count from config (clamped). */
    public static int ductStallSlots() {
        return DUCT_STALL_SLOTS.get();
    }

    /** Persist common config after programmatic {@link ModConfigSpec.ConfigValue#set} updates. */
    public static void saveCommon() {
        SPEC.save();
    }
}
