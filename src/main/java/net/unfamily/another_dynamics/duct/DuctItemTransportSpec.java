package net.unfamily.another_dynamics.duct;

/**
 * Item transport parameters from datapack {@code can_transport} (dec {@code item}).
 */
public record DuctItemTransportSpec(
        int batchDefault,
        int batchMax,
        int rateDefaultTicks,
        int rateMinTicks,
        int speedDefault,
        int speedMin,
        int filterAllowSlots,
        int filterDenySlots
) {
    public static final int UNLIMITED_BATCH = Integer.MAX_VALUE;

    public static DuctItemTransportSpec fallback() {
        return new DuctItemTransportSpec(8, UNLIMITED_BATCH, 10, 1, 20, 1, 3, 3);
    }

    /** Datapack {@code speed}: ticks per duct block on a path for billed travel (clamped; avoids zero). */
    public int effectiveSpeed(int configured) {
        int s = configured <= 0 ? speedDefault : configured;
        int min = Math.max(1, speedMin);
        return Math.max(min, s);
    }

    public int clampedBatch(int requested) {
        int max = batchMax < 0 ? UNLIMITED_BATCH : Math.max(1, batchMax);
        return Math.min(Math.max(0, requested), max);
    }

    /**
     * Max extract/retrieve batch the player may configure: {@code batchDefault + upgradeBonus}, then limited by datapack
     * {@code batch.max} when that value is non-negative; when {@code batch.max} is negative, only default + bonus applies.
     */
    public int extractBatchSettingCap(int upgradeBonus) {
        int base = batchDefault + upgradeBonus;
        if (batchMax < 0) {
            return Math.max(1, base);
        }
        return Math.max(1, Math.min(batchMax, base));
    }

    public int clampedRateTicks(int requested) {
        int r = requested <= 0 ? rateDefaultTicks : requested;
        return Math.max(rateMinTicks, r);
    }
}
