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
        /** Max allow-pattern entries per bank for non-hybrid modes and for legacy root filter lists. */
        int filterAllowSlots,
        int filterDenySlots,
        /** Max allow/deny entries per bank when the face is in a hybrid mode ({@code Extr/Filt}, {@code Retr/Extr}). */
        int filterAllowHybridSlots,
        int filterDenyHybridSlots
) {
    public static final int UNLIMITED_BATCH = Integer.MAX_VALUE;

    public static DuctItemTransportSpec fallback() {
        return new DuctItemTransportSpec(8, UNLIMITED_BATCH, 10, 1, 20, 0, 3, 3, 3, 3);
    }

    /**
     * Datapack {@code speed}: ticks per duct block on a path for billed travel.
     * {@code speed.min} may be {@code 0} for instant travel ({@code 0} ticks per block).
     */
    public int effectiveSpeed(int configured) {
        int s = configured <= 0 ? speedDefault : configured;
        int min = Math.max(0, speedMin);
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
