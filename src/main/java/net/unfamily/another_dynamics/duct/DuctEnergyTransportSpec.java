package net.unfamily.another_dynamics.duct;

/**
 * Energy (FE/RF) parameters from datapack ({@code dec} {@code forge_energy}).
 * <p><strong>Flux-lane family:</strong> parallel changes often belong in {@link DuctHeatTransportSpec}, {@link
 * DuctFaceLanes} energy fields, {@link net.unfamily.another_dynamics.duct.logistics.DuctEnergyServerTick}, and universal
 * ducts that enable energy (copier {@code EnergyHeat} block).
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
        return new DuctEnergyTransportSpec(1000, Integer.MAX_VALUE, 1, 1, "#e30b28", 0.85f);
    }

    public long clampedExtract() {
        return Math.max(0L, extract);
    }

    public long clampedTransfer() {
        return Math.max(0L, transfer);
    }

    /**
     * Floors at {@code rate.min} (at least 1). Never remaps {@code 0}/negative to the datapack default —
     * aggressive module {@code rate.mult} must land on the minimum interval, not reset to base rate.
     */
    public int clampedRateTicks(int requested) {
        int min = Math.max(1, rateMinTicks);
        return Math.max(min, requested);
    }
}
