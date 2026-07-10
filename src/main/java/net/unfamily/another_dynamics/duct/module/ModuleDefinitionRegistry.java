package net.unfamily.another_dynamics.duct.module;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.Identifier;

public final class ModuleDefinitionRegistry {
    private static Map<Identifier, ModuleDefinition> all = Map.of();

    private ModuleDefinitionRegistry() {}

    public static void replaceAll(Map<Identifier, ModuleDefinition> next) {
        all = Map.copyOf(next);
    }

    public static Optional<ModuleDefinition> get(Identifier id) {
        return Optional.ofNullable(all.get(id));
    }

    public static Collection<ModuleDefinition> all() {
        return Collections.unmodifiableCollection(all.values());
    }
}
