package net.unfamily.another_dynamics.duct;

/**
 * Mekanism heat transport from datapack {@code can_transport} ({@code dec} {@code mek_heat}).
 *
 * <p>Heat moved per tick uses Mekanism {@code IHeatHandler#handleHeat(double)} units.
 * Insulation value reduces thermal losses during transfer (higher = better).
 */
public record DuctHeatTransportSpec(double insulation) {
    public static DuctHeatTransportSpec fallback() {
        return new DuctHeatTransportSpec(10);
    }

    public double clampedInsulation() {
        return Math.max(0.0, insulation);
    }
}
