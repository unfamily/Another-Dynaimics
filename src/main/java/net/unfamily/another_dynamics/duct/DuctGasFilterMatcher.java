package net.unfamily.another_dynamics.duct;

import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import java.util.List;

/**
 * Single gas ("chemical") filter entry matching.
 *
 * <p>Supported prefixes:
 * <ul>
 *   <li>{@code -namespace:path} exact id</li>
 *   <li>{@code @namespace} modid/namespace</li>
 *   <li>{@code #namespace:path} tag id</li>
 * </ul>
 *
 * <p>Macros {@code &} and NBT {@code ?} are intentionally unsupported for gas for now.</p>
 */
public final class DuctGasFilterMatcher {
    private DuctGasFilterMatcher() {}

    public static boolean matchesFilterEntry(Object chemicalStack, String filterLine) {
        if (filterLine == null) {
            return false;
        }
        String filter = filterLine.trim();
        if (filter.isEmpty() || chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
            return false;
        }

        String idStr = MekanismChemicalCompat.getTypeRegistryName(chemicalStack);
        if (idStr == null || idStr.isEmpty()) {
            return false;
        }

        if (filter.startsWith("-")) {
            return idStr.equals(filter.substring(1));
        }
        if (filter.startsWith("@")) {
            try {
                ResourceLocation id = ResourceLocation.parse(idStr);
                String want = filter.substring(1);
                return id.getNamespace().startsWith(want);
            } catch (Exception e) {
                return false;
            }
        }
        if (filter.startsWith("#")) {
            String wantTag = filter.substring(1);
            List<String> tags = MekanismChemicalCompat.getTagIds(chemicalStack);
            for (String t : tags) {
                if (wantTag.equals(t)) {
                    return true;
                }
            }
            return false;
        }

        // Bare id means exact id
        return idStr.equals(filter);
    }
}

