package net.unfamily.another_dynamics.duct;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.world.level.block.SoundType;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Maps datapack {@code sound} strings to vanilla {@link SoundType} constants (lowercase field names),
 * with a few aliases. Unknown values fall back to {@link SoundType#COPPER} with a log line.
 */
public final class DuctSoundTypes {
    private static final Map<String, SoundType> BY_KEY = buildLookup();

    private DuctSoundTypes() {}

    /** Sound for a duct when the definition is missing or has no {@code sound}: vanilla copper duct feel. */
    public static SoundType forLogicalDuct(String logicalId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .flatMap(DuctDefinition::sound)
                .map(DuctSoundTypes::resolve)
                .orElse(SoundType.COPPER);
    }

    /**
     * Resolves a non-null, non-blank sound name from JSON. For default when the key is omitted, use
     * {@link #forLogicalDuct} instead.
     */
    public static SoundType resolve(String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return SoundType.COPPER;
        }
        String key = soundName.trim().toLowerCase(Locale.ROOT);
        SoundType mapped = BY_KEY.get(key);
        if (mapped != null) {
            return mapped;
        }
        AnotherDynamicsMod.LOGGER.warn(
                "Unknown duct sound '{}', using copper. Use a vanilla SoundType field name (lowercase) or alias metallic/rock.",
                soundName);
        return SoundType.COPPER;
    }

    /** Immutable map of accepted keys (lowercase) for docs / tooling. */
    public static Map<String, SoundType> acceptedKeys() {
        return BY_KEY;
    }

    private static Map<String, SoundType> buildLookup() {
        Map<String, SoundType> map = new LinkedHashMap<>();
        for (Field field : SoundType.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != SoundType.class) {
                continue;
            }
            try {
                SoundType value = (SoundType) field.get(null);
                if (value != null) {
                    map.put(field.getName().toLowerCase(Locale.ROOT), value);
                }
            } catch (IllegalAccessException ignored) {
                // skip inaccessible fields
            }
        }
        // Pack-friendly aliases (do not override a real SoundType field of the same name).
        map.putIfAbsent("metallic", SoundType.METAL);
        map.putIfAbsent("rock", SoundType.STONE);
        map.putIfAbsent("iron", SoundType.METAL);
        return Collections.unmodifiableMap(map);
    }
}
