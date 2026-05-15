package net.unfamily.another_dynamics.duct;

/**
 * Energy transport parameters from datapack {@code can_transport} (dec {@code forge_energy}).
 *
 * <p>Amounts are in FE/RF units (long). {@code rate} is ticks between network logistics actions per face.</p>
 */
public record DuctEnergyTransportSpec(
        /**
         * Amount to attempt extracting per action from a donor endpoint.
         */
        long extract,
        /**
         * Legacy datapack field (kept for pack compatibility). Throughput is not capped by this value; use
         * {@code extract} and increment modules instead.
         */
        long transfer,
        /**
         * Default ticks between energy logistics actions on a face (lower = faster).
         */
        int rateDefaultTicks,
        /**
         * Minimum ticks between actions after module scaling.
         */
        int rateMinTicks,
        /**
         * Ray color: {@code #RRGGBB} or {@code random} (server picks RGB per pulse).
         */
        String rayColor,
        /**
         * Vertex alpha for the energy ray (0 = invisible, 1 = opaque). Datapack: {@code ray_alpha}.
         */
        float rayAlpha) {
    public static DuctEnergyTransportSpec fallback() {
        return new DuctEnergyTransportSpec(1000, Integer.MAX_VALUE, 10, 10, "#e30b28", 0.85f);
    }

    public long clampedExtract() {
        return Math.max(0L, extract);
    }

    public long clampedTransfer() {
        return Math.max(0L, transfer);
    }

    public int clampedRateTicks(int requested) {
        int r = requested <= 0 ? rateDefaultTicks : requested;
        return Math.max(rateMinTicks, r);
    }
}
