package net.unfamily.another_dynamics.duct;

/**
 * Mekanism heat transport from datapack {@code can_transport} ({@code dec} {@code mek_heat}).
 *
 * <p>Heat moved per tick uses Mekanism {@code IHeatHandler#handleHeat(double)} units.
 */
public record DuctHeatTransportSpec(double extract, double transfer) {
    public static DuctHeatTransportSpec fallback() {
        return new DuctHeatTransportSpec(200, 20_000);
    }

    public double clampedExtract() {
        return Math.max(0.0, extract);
    }

    public double clampedTransfer() {
        return Math.max(0.0, transfer);
    }
}
