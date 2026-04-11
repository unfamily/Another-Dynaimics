package net.unfamily.another_dynamics.duct;

/**
 * Gas transport parameters from datapack {@code can_transport} (dec {@code mek_gas}).
 *
 * <p>Amounts are in Mekanism chemical units (long). We keep naming generic (\"amount\") so the spec remains usable even
 * if Mekanism tweaks exact internal scaling.</p>
 */
public record DuctGasTransportSpec(
        long batchDefault,
        long batchMax,
        int rateDefaultTicks,
        int rateMinTicks,
        int speedDefault,
        int speedMin,
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

    public static DuctGasTransportSpec fallback() {
        return new DuctGasTransportSpec(1000, UNLIMITED_BATCH, 30, 1, 20, 0, 4, 4, 2, 2, true);
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

    public int clampedRateTicks(int requested) {
        int r = requested <= 0 ? rateDefaultTicks : requested;
        return Math.max(rateMinTicks, r);
    }

    /** Edge cost for {@link net.unfamily.another_dynamics.duct.logistics.DuctPathfinder} (ticks per duct block). */
    public long edgeTravelTicks() {
        return Math.max(0L, effectiveSpeed(speedDefault));
    }
}

