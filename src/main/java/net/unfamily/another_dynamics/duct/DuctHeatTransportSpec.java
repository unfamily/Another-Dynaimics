package net.unfamily.another_dynamics.duct;

/**
 * Mekanism heat parameters from datapack ({@code dec} {@code mek_heat}).
 * <p><strong>Flux-lane family:</strong> parallel changes often belong in {@link DuctEnergyTransportSpec}, {@link
 * DuctFaceLanes} heat fields, {@link net.unfamily.another_dynamics.duct.logistics.DuctHeatServerTick}, and universal
 * ducts that enable heat (copier {@code EnergyHeat} block).
 */
public record DuctHeatTransportSpec(double insulation) {
    public static DuctHeatTransportSpec fallback() {
        return new DuctHeatTransportSpec(10);
    }

    public double clampedInsulation() {
        return Math.max(0.0, insulation);
    }
}
