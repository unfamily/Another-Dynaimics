package net.unfamily.another_dynamics.duct;

/**
 * Energy transport parameters from datapack {@code can_transport} (dec {@code forge_energy}).
 *
 * <p>Amounts are in FE/RF units (long).</p>
 */
public record DuctEnergyTransportSpec(
        /**
         * Amount to attempt extracting per action from a donor endpoint.
         */
        long extract,
        /**
         * Maximum transfer supported by this duct type. Effective transfer across a multi-duct path is the minimum
         * {@code transfer} value across all ducts in the chosen route (bottleneck rule).
         */
        long transfer,
        /**
         * Ray color: {@code #RRGGBB} or {@code random} (server picks RGB per pulse).
         */
        String rayColor,
        /**
         * Vertex alpha for the energy ray (0 = invisible, 1 = opaque). Datapack: {@code ray_alpha}.
         */
        float rayAlpha) {
    public static DuctEnergyTransportSpec fallback() {
        return new DuctEnergyTransportSpec(1000, 8000, "#e30b28", 0.85f);
    }

    public long clampedExtract() {
        return Math.max(0L, extract);
    }

    public long clampedTransfer() {
        return Math.max(0L, transfer);
    }
}

