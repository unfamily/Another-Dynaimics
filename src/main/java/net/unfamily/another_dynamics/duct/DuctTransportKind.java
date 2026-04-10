package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;
import java.util.List;

/**
 * Datapack {@code can_transport} discriminator ({@code dec}) mapped to transport lanes. Extensible for future kinds
 * (Mek gas, energy) without changing save layout keys ({@code Item}, {@code Fluid}, …).
 */
public enum DuctTransportKind {
    ITEM("item"),
    FLUID("fluid"),
    /** Mekanism chemicals; datapack {@code dec} is {@code mek_gas}. */
    GAS("mek_gas"),
    ENERGY("forge_energy"),
    /** Mekanism {@code IHeatHandler} on block faces; requires Mekanism. */
    HEAT("mek_heat");

    private final String jsonDec;

    DuctTransportKind(String jsonDec) {
        this.jsonDec = jsonDec;
    }

    public String jsonDec() {
        return jsonDec;
    }

    public static DuctTransportKind fromJsonDec(String dec) {
        if (dec == null) {
            return null;
        }
        for (DuctTransportKind k : values()) {
            if (k.jsonDec.equalsIgnoreCase(dec)) {
                return k;
            }
        }
        return null;
    }

    /**
     * Order used for GUI default tab and cycling: follows first occurrence in datapack {@code can_transport}, then enum
     * order for any missing kinds.
     */
    public static EnumSet<DuctTransportKind> orderedKindsFromDeclaration(List<String> transportKindsJsonOrder) {
        EnumSet<DuctTransportKind> out = EnumSet.noneOf(DuctTransportKind.class);
        if (transportKindsJsonOrder != null) {
            for (String raw : transportKindsJsonOrder) {
                DuctTransportKind k = fromJsonDec(raw);
                if (k != null) {
                    out.add(k);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(ITEM);
        }
        return out;
    }
}
