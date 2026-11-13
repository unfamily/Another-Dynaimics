package net.unfamily.another_dynamics.duct;

/**
 * Unknown Flux parameters from datapack ({@code dec} {@code unknown_flux}). Requires optional {@code iska_lib}.
 */
public record DuctUnknownFluxTransportSpec(
        long extract,
        long transfer,
        int rateDefaultTicks,
        int rateMinTicks,
        String rayColor,
        float rayAlpha) {
    public static DuctUnknownFluxTransportSpec fallback() {
        return new DuctUnknownFluxTransportSpec(1000, Long.MAX_VALUE, 1, 1, "#2b576c", 0.85f);
    }

    public long clampedExtract() {
        return Math.max(0L, extract);
    }

    public long clampedTransfer() {
        return Math.max(0L, transfer);
    }

    public int clampedRateTicks(int requested) {
        int min = Math.max(1, rateMinTicks);
        return Math.max(min, requested);
    }
}
