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
    public static final ModConfigSpec.IntValue PROJECT_DUCT_CONVERT_MAX_PER_ACTION;

    public static final ModConfigSpec.IntValue SEQUENCE_LIST_COUNT;
    public static final ModConfigSpec.IntValue SEQUENCE_STEP_COUNT;

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
        PROJECT_DUCT_CONVERT_MAX_PER_ACTION =
                BUILDER.comment(
                                "Max Project Duct blocks converted in one Shift+duct-item action. Large networks "
                                        + "must be converted in batches (click again). Prevents server freezes.")
                        .defineInRange("102_projectDuctConvertMaxPerAction", 64, 1, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.comment("Machine tuning").push("machines");
        SEQUENCE_LIST_COUNT =
                BUILDER.comment(
                                "Number of Sequence Lists on the Sequential Buffer (and Settings Copier "
                                        + "Sequential Configure). Applies to newly opened GUIs / new block entities; "
                                        + "existing machines resize on load.")
                        .defineInRange("200_sequenceListCount", 10, 1, Integer.MAX_VALUE);
        SEQUENCE_STEP_COUNT =
                BUILDER.comment(
                                "Max Sequence Tasks (steps) per Sequence List on the Sequential Buffer "
                                        + "(and Settings Copier Sequential Configure).")
                        .defineInRange("201_sequenceStepCount", 50, 1, Integer.MAX_VALUE);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Effective stall slot count from config (clamped). */
    public static int ductStallSlots() {
        return DUCT_STALL_SLOTS.get();
    }

    /** Max Project Duct → definitive duct conversions per click (at least 1). */
    public static int projectDuctConvertMaxPerAction() {
        return Math.max(1, PROJECT_DUCT_CONVERT_MAX_PER_ACTION.get());
    }

    /** Sequence List count (at least 1). */
    public static int sequenceListCount() {
        return Math.max(1, SEQUENCE_LIST_COUNT.get());
    }

    /** Sequence Task/step capacity per list (at least 1). */
    public static int sequenceStepCount() {
        return Math.max(1, SEQUENCE_STEP_COUNT.get());
    }

    /** Persist common config after programmatic {@link ModConfigSpec.ConfigValue#set} updates. */
    public static void saveCommon() {
        SPEC.save();
    }
}
