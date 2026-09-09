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
    /** Client BER view distance for in-duct transit ghosts. */
    public static final ModConfigSpec.IntValue DUCT_TRANSIT_RENDER_DISTANCE;

    /** Batch size per convert pulse while a progressive Project Duct job runs. */
    public static final ModConfigSpec.IntValue PROJECT_DUCT_CONVERT_MAX_PER_ACTION;
    /** Server ticks between convert pulses (1 = every tick). */
    public static final ModConfigSpec.IntValue PROJECT_DUCT_CONVERT_TICK_INTERVAL;
    /** Max Project Duct blocks converted by one Shift+duct job. */
    public static final ModConfigSpec.IntValue PROJECT_DUCT_CONVERT_MAX_PER_JOB;
    /** Wall-clock budget (ms) per server tick for convert/replace job pulses. */
    public static final ModConfigSpec.IntValue DUCT_JOB_TICK_BUDGET_MS;

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
                                "One-shot: when Cable Facades is present, ensure its blocks whitelist has "
                                        + "another_dynamics:duct and another_dynamics:project_duct only (removes "
                                        + "legacy another_dynamics:* which matched Sequential Buffer), then set false. "
                                        + "API registration still runs every launch.")
                        .define("004A_cableFacadesConfigSeed", true);
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
        DUCT_TRANSIT_RENDER_DISTANCE =
                BUILDER.comment(
                                "Client max view distance (blocks) for duct transit ghosts (items/fluids/gas). "
                                        + "Effective distance is min(this, Minecraft render distance in blocks).")
                        .defineInRange("102_ductTransitRenderDistance", 38, 16, 256);

        // Project Duct convert — keys 200–299 only
        PROJECT_DUCT_CONVERT_MAX_PER_ACTION =
                BUILDER.comment(
                                "Project Duct / mass-replace blocks processed per pulse while a job runs "
                                        + "(Shift+duct item). Pulse wall-clock scales 1:1 with this (~1 ms/block); "
                                        + "a batch of 64 never budgets more than ~64 ms.")
                        .defineInRange("200_projectDuctConvertBatchSize", 16, 1, Integer.MAX_VALUE);
        PROJECT_DUCT_CONVERT_TICK_INTERVAL =
                BUILDER.comment(
                                "Server ticks between Project Duct / mass-replace pulses (after discovery / first "
                                        + "deferred pulse). 5 ≈ four times per second. 1 = every tick.")
                        .defineInRange("201_projectDuctConvertTickInterval", 5, 1, Integer.MAX_VALUE);
        PROJECT_DUCT_CONVERT_MAX_PER_JOB =
                BUILDER.comment(
                                "Max Project Duct / mass-replace blocks handled by one Shift+duct job "
                                        + "(spread across pulses). Click again if the network is larger.")
                        .defineInRange("202_projectDuctConvertMaxPerJob", 1024, 1, Integer.MAX_VALUE);
        DUCT_JOB_TICK_BUDGET_MS =
                BUILDER.comment(
                                "Wall-clock budget (ms) for discovery slices and as an optional CAP on convert/"
                                        + "replace pulses. Convert pulse time is min(this, batchSize) ms — default "
                                        + "should stay high so batch size alone decides how long a pulse may run.")
                        .defineInRange("203_ductJobTickBudgetMs", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

        // Sequence / machines — keys 300+
        SEQUENCE_LIST_COUNT =
                BUILDER.comment(
                                "Number of Sequence Lists on the Sequential Buffer (and Settings Copier "
                                        + "Sequential Configure). Applies to newly opened GUIs / new block entities; "
                                        + "existing machines resize on load.")
                        .defineInRange("300_sequenceListCount", 10, 1, Integer.MAX_VALUE);
        SEQUENCE_STEP_COUNT =
                BUILDER.comment(
                                "Max Sequence Tasks (steps) per Sequence List on the Sequential Buffer "
                                        + "(and Settings Copier Sequential Configure).")
                        .defineInRange("301_sequenceStepCount", 50, 1, Integer.MAX_VALUE);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Effective stall slot count from config (clamped). */
    public static int ductStallSlots() {
        return DUCT_STALL_SLOTS.get();
    }

    /** Configured transit BER max view distance in blocks (before client render-distance cap). */
    public static int ductTransitRenderDistance() {
        return DUCT_TRANSIT_RENDER_DISTANCE.get();
    }

    /** Conversions per pulse during a progressive Project Duct convert job (at least 1). */
    public static int projectDuctConvertBatchSize() {
        return Math.max(1, PROJECT_DUCT_CONVERT_MAX_PER_ACTION.get());
    }

    /** @deprecated use {@link #projectDuctConvertBatchSize()} */
    @Deprecated
    public static int projectDuctConvertMaxPerAction() {
        return projectDuctConvertBatchSize();
    }

    /** Server ticks between convert pulses (at least 1). */
    public static int projectDuctConvertTickInterval() {
        return Math.max(1, PROJECT_DUCT_CONVERT_TICK_INTERVAL.get());
    }

    /** Max conversions for one Shift+duct job (at least batch size). */
    public static int projectDuctConvertMaxPerJob() {
        return Math.max(projectDuctConvertBatchSize(), PROJECT_DUCT_CONVERT_MAX_PER_JOB.get());
    }

    /** Wall-clock floor in nanoseconds (discovery slices / minimum pulse time). */
    public static long ductJobTickBudgetNanos() {
        return Math.max(1L, DUCT_JOB_TICK_BUDGET_MS.get()) * 1_000_000L;
    }

    /**
     * Pulse time for a convert/replace batch of {@code plannedBlocks}.
     * Allots ~1 ms per block and never exceeds the batch count in ms (batch 64 → ≤ 64 ms).
     * {@link #DUCT_JOB_TICK_BUDGET_MS} is an optional lower CAP for TPS (default unlimited).
     */
    public static long ductJobPulseBudgetNanos(int plannedBlocks) {
        long forBatchMs = Math.max(1L, plannedBlocks);
        long configuredMs = Math.max(1L, DUCT_JOB_TICK_BUDGET_MS.get());
        long ms = Math.min(configuredMs, forBatchMs);
        return ms * 1_000_000L;
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
