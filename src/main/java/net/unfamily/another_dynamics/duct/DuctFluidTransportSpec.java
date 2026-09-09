package net.unfamily.another_dynamics.duct;

/**
 * Fluid transport parameters from datapack ({@code dec} {@code fluid}); amounts in mB.
 * <p><strong>Material-lane family:</strong> parallel changes often belong in {@link DuctItemTransportSpec},
 * {@link DuctGasTransportSpec}, and universal declarations that include item/fluid/gas.
 */
public record DuctFluidTransportSpec(
        int batchDefaultMb,
        int batchMaxMb,
        int rateDefaultTicks,
        int rateMinTicks,
        int speedDefault,
        int speedMin,
        int sequentialStackDefault,
        int sequentialStackMax,
        int filterAllowSlots,
        int filterDenySlots,
        int filterAllowHybridSlots,
        int filterDenyHybridSlots) {
    public static final int UNLIMITED_BATCH_MB = Integer.MAX_VALUE;
    public static final int HARD_SEQUENTIAL_STACK_CAP = DuctItemTransportSpec.HARD_SEQUENTIAL_STACK_CAP;

    public static DuctFluidTransportSpec fallback() {
        return new DuctFluidTransportSpec(1000, UNLIMITED_BATCH_MB, 30, 1, 20, 0, 1, HARD_SEQUENTIAL_STACK_CAP, 4, 4, 2, 2);
    }

    public int effectiveSpeed(int configured) {
        int s = configured <= 0 ? speedDefault : configured;
        int min = Math.max(0, speedMin);
        return Math.max(min, s);
    }

    public int clampedBatchMb(int requested) {
        int max = batchMaxMb < 0 ? UNLIMITED_BATCH_MB : Math.max(1, batchMaxMb);
        return Math.min(Math.max(0, requested), max);
    }

    public int extractBatchSettingCapMb(int moduleBonusMb) {
        int base = batchDefaultMb + moduleBonusMb;
        if (batchMaxMb < 0) {
            return Math.max(1, base);
        }
        return Math.max(1, Math.min(batchMaxMb, base));
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
