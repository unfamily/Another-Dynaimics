package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * Datapack duct declaration. {@link #defaultTexture} is read from {@code rendering.default_texture} and is the
 * authoritative block texture for composite duct rendering until multi-layer rules exist.
 *
 * <p>Optional {@link #compositeModelDefault} / {@link #compositeModelLine} are {@link ResourceLocation}s of block
 * models under {@code assets/&lt;ns&gt;/models/...} (same convention as {@code namespace:block/path}). Piece names
 * must match the engine ({@code center}, {@code con_*}, {@code node_*}, and a {@code center} element in the line
 * model).</p>
 *
 * <p>Optional {@link #sound} is a block step/place/break group name (e.g. {@code metal}, {@code metallic}, {@code copper});
 * see {@link DuctSoundTypes#resolve}.</p>
 *
 * <p>{@link #restrictionsConnectWithCompatible}: when {@code false}, item ducts only connect as pipe to neighbors with
 * the same {@link #logicalId}. {@link #disabledFeatures} / {@link #forbiddenFeatures} use keys from
 * {@link DuctFeatureKeys}.</p>
 */
public record DuctDefinition(
        ResourceLocation dataId,
        String logicalId,
        Optional<String> translationKey,
        List<String> transportKinds,
        Optional<DuctItemTransportSpec> itemTransport,
        Optional<DuctFluidTransportSpec> fluidTransport,
        Optional<DuctGasTransportSpec> gasTransport,
        Optional<DuctEnergyTransportSpec> energyTransport,
        Optional<DuctHeatTransportSpec> heatTransport,
        ResourceLocation defaultTexture,
        Optional<ResourceLocation> compositeModelDefault,
        Optional<ResourceLocation> compositeModelLine,
        boolean putInCreativeMenu,
        Optional<String> sound,
        boolean restrictionsConnectWithCompatible,
        Set<String> disabledFeatures,
        Set<String> forbiddenFeatures,
        boolean alwaysOpaqueRendering
) {
    public DuctItemTransportSpec itemTransportOrFallback() {
        return itemTransport.orElseGet(DuctItemTransportSpec::fallback);
    }

    public DuctFluidTransportSpec fluidTransportOrFallback() {
        return fluidTransport.orElseGet(DuctFluidTransportSpec::fallback);
    }

    public DuctGasTransportSpec gasTransportOrFallback() {
        return gasTransport.orElseGet(DuctGasTransportSpec::fallback);
    }

    public DuctEnergyTransportSpec energyTransportOrFallback() {
        return energyTransport.orElseGet(DuctEnergyTransportSpec::fallback);
    }

    public DuctHeatTransportSpec heatTransportOrFallback() {
        return heatTransport.orElseGet(DuctHeatTransportSpec::fallback);
    }

    /** Kinds enabled for this duct, in datapack order (then stable enum fill). */
    public EnumSet<DuctTransportKind> enabledTransportKinds() {
        return DuctTransportKind.orderedKindsFromDeclaration(transportKinds);
    }

    /**
     * Menu transport order (hub + lanes). Matches {@link DuctBlockEntity#orderedMenuTransportKinds()} for the same
     * definition (client uses {@link DuctDefinitionRegistry#getByLogicalId(String)} with the open-menu logical id).
     */
    public static List<DuctTransportKind> orderedMenuTransportKinds(Optional<DuctDefinition> defOpt) {
        EnumSet<DuctTransportKind> kinds =
                defOpt.map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        List<DuctTransportKind> out = new ArrayList<>();
        if (defOpt.isPresent()) {
            for (String dec : defOpt.get().transportKinds()) {
                DuctTransportKind k = DuctTransportKind.fromJsonDec(dec);
                if (k != null && kinds.contains(k) && !out.contains(k)) {
                    out.add(k);
                }
            }
        }
        for (DuctTransportKind k : DuctTransportKind.values()) {
            if (kinds.contains(k) && !out.contains(k)) {
                out.add(k);
            }
        }
        if (out.isEmpty()) {
            out.add(DuctTransportKind.ITEM);
        }
        return out;
    }

    /** Synonym for JSON field {@code connect_with_compatible}. */
    public boolean connectWithCompatible() {
        return restrictionsConnectWithCompatible;
    }
}
