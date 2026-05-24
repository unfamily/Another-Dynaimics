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
        int filterAllowSlots,
        int filterDenySlots,
        int filterAllowHybridSlots,
        int filterDenyHybridSlots) {
    public static final int UNLIMITED_BATCH_MB = Integer.MAX_VALUE;

    public static DuctFluidTransportSpec fallback() {
        return new DuctFluidTransportSpec(1000, UNLIMITED_BATCH_MB, 30, 1, 20, 0, 4, 4, 2, 2);
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

    public int clampedRateTicks(int requested) {
        int r = requested <= 0 ? rateDefaultTicks : requested;
        return Math.max(rateMinTicks, r);
    }

    /** Edge cost for {@link net.unfamily.another_dynamics.duct.logistics.DuctPathfinder} (ticks per duct block). */
    public long edgeTravelTicks() {
        return Math.max(0L, effectiveSpeed(speedDefault));
    }
}
