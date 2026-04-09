package net.unfamily.another_dynamics.duct;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class DuctDefinitionRegistry {
    private static Map<ResourceLocation, DuctDefinition> definitions = Map.of();
    private static Map<String, DuctDefinition> definitionsByLogicalId = Map.of();
    private static volatile int version = 0;

    private DuctDefinitionRegistry() {}

    public static void replaceAll(Map<ResourceLocation, DuctDefinition> next) {
        definitions = Collections.unmodifiableMap(new HashMap<>(next));
        Map<String, DuctDefinition> logical = new HashMap<>();
        for (DuctDefinition def : definitions.values()) {
            DuctDefinition prev = logical.put(def.logicalId(), def);
            if (prev != null) {
                AnotherDynamicsMod.LOGGER.warn(
                        "Duplicate duct logical id '{}' (was {}, now {}); using last",
                        def.logicalId(),
                        prev.dataId(),
                        def.dataId());
            }
        }
        definitionsByLogicalId = Collections.unmodifiableMap(logical);
        version++;
    }

    /** Monotonically increasing counter incremented every time the registry is replaced. */
    public static int version() {
        return version;
    }

    public static Map<ResourceLocation, DuctDefinition> all() {
        return definitions;
    }

    /** Key is the resource path under {@code load/}, e.g. {@code another_dynamics:load/duct}. */
    public static Optional<DuctDefinition> get(ResourceLocation dataId) {
        return Optional.ofNullable(definitions.get(dataId));
    }

    /** Key matches JSON {@code id} as a full resource location string, e.g. {@link DuctIds#DEFAULT_LOGICAL_ID}. */
    public static Optional<DuctDefinition> getByLogicalId(String logicalId) {
        return Optional.ofNullable(definitionsByLogicalId.get(logicalId));
    }

    /** Item duct transport spec from datapack or built-in defaults. */
    public static DuctItemTransportSpec itemDuctTransportSpec() {
        return getByLogicalId(DuctIds.DEFAULT_LOGICAL_ID)
                .map(DuctDefinition::itemTransportOrFallback)
                .orElseGet(DuctItemTransportSpec::fallback);
    }

    /** Fluid duct transport spec from datapack or built-in defaults. */
    public static DuctFluidTransportSpec fluidDuctTransportSpec() {
        return getByLogicalId(DuctIds.DEFAULT_LOGICAL_ID)
                .map(DuctDefinition::fluidTransportOrFallback)
                .orElseGet(DuctFluidTransportSpec::fallback);
    }
}
