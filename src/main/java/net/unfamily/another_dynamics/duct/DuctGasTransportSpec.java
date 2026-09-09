package net.unfamily.another_dynamics.duct;

/**
 * Gas transport parameters from datapack ({@code dec} {@code mek_gas}); Mekanism chemical units.
 * <p><strong>Material-lane family:</strong> parallel changes often belong in {@link DuctItemTransportSpec},
 * {@link DuctFluidTransportSpec}, and universal declarations that include item/fluid/gas.
 */
public record DuctGasTransportSpec(
        long batchDefault,
        long batchMax,
        int rateDefaultTicks,
        int rateMinTicks,
        int speedDefault,
        int speedMin,
        int sequentialStackDefault,
        int sequentialStackMax,
        int filterAllowSlots,
        int filterDenySlots,
        int filterAllowHybridSlots,
        int filterDenyHybridSlots,
        /**
         * When {@code false}, this duct type blocks radioactive gas: it cannot appear on radioactive routes, and it
         * splits the {@linkplain net.unfamily.another_dynamics.duct.logistics.DuctPathfinder#connectedRadioactiveGasDucts
         * radioactive-capable gas subgraph} (only ducts linked through {@code true} edges "see" each other for
         * radioactive logistics).
         */
        boolean moveRadioactive) {
    public static final long UNLIMITED_BATCH = Long.MAX_VALUE;
    public static final int HARD_SEQUENTIAL_STACK_CAP = DuctItemTransportSpec.HARD_SEQUENTIAL_STACK_CAP;

    public static DuctGasTransportSpec fallback() {
        return new DuctGasTransportSpec(1000, UNLIMITED_BATCH, 30, 1, 20, 0, 1, HARD_SEQUENTIAL_STACK_CAP, 4, 4, 2, 2, true);
    }

    public int effectiveSpeed(int configured) {
        int s = configured <= 0 ? speedDefault : configured;
        int min = Math.max(0, speedMin);
        return Math.max(min, s);
    }

    public long clampedBatch(long requested) {
        long max = batchMax < 0 ? UNLIMITED_BATCH : Math.max(1L, batchMax);
        return Math.min(Math.max(0L, requested), max);
    }

    public long extractBatchSettingCap(long moduleBonus) {
        long base = batchDefault + moduleBonus;
        if (batchMax < 0) {
            return Math.max(1L, base);
        }
        return Math.max(1L, Math.min(batchMax, base));
    }

    /**
     * Floors at {@code rate.min} (at least 1). Never remaps {@code 0}/negative to the datapack default —
     * aggressive module {@code rate.mult} must land on the minimum interval, not reset to base rate.
     */
    public int clampedRateTicks(int requested) {
        int min = Math.max(1, rateMinTicks);
        return Math.max(min, requested);
    }

    /** Edge cost for {@link net.unfamily.another_dynamics.duct.logistics.DuctPathfinder} (ticks per duct block). */
    public long edgeTravelTicks() {
        return Math.max(0L, effectiveSpeed(speedDefault));
    }

    public int extractSequentialStackSettingCap(int moduleBonus) {
        int base = sequentialStackDefault + moduleBonus;
        int capped;
        if (sequentialStackMax < 0) {
            capped = Math.max(1, base);
        } else {
            capped = Math.max(1, Math.min(sequentialStackMax, base));
        }
        return Math.min(HARD_SEQUENTIAL_STACK_CAP, capped);
    }

    public int clampedSequentialStack(int requested, int settingCap) {
        int cap = Math.max(1, Math.min(HARD_SEQUENTIAL_STACK_CAP, settingCap));
        int v = requested <= 0 ? sequentialStackDefault : requested;
        return Math.max(1, Math.min(cap, v));
    }
}

