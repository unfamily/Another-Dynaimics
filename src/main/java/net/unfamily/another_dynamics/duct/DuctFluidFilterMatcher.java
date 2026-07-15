package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Filter entry matching for fluid ducts. Supports {@code #namespace:tag}, {@code @mod}, {@code -id}, registry id, and
 * optional {@code ?} substring on saved NBT (no item enchant/damage macros).
 */
public final class DuctFluidFilterMatcher {
    private DuctFluidFilterMatcher() {}

    private enum Cmp {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE
    }

    public static boolean matchesFilterEntry(
            FluidStack stack,
            Fluid fluid,
            Identifier fluidId,
            String fluidIdStr,
            String fluidModId,
            String filter,
            HolderLookup.Provider registries) {
        if (filter == null || filter.isEmpty() || stack.isEmpty()) {
            return false;
        }

        if (filter.startsWith("-")) {
            String idFilter = filter.substring(1);
            return fluidIdStr.equals(idFilter);
        }

        if (filter.startsWith("&")) {
            return matchesFluidMacro(stack, filter.substring(1).trim());
        }

        if (filter.startsWith("@")) {
            String modIdFilter = filter.substring(1);
            return fluidModId.startsWith(modIdFilter);
        }

        if (filter.startsWith("#")) {
            String tagFilter = filter.substring(1);
            try {
                Identifier tagId = Identifier.parse(tagFilter);
                TagKey<Fluid> fluidTag = TagKey.create(Registries.FLUID, tagId);
                return BuiltInRegistries.FLUID.wrapAsHolder(fluid).is(fluidTag);
            } catch (Exception e) {
                return false;
            }
        }

        if (filter.startsWith("?")) {
            String nbtFilter = filter.substring(1);
            try {
                CompoundTag tag = DuctNbtCodecs.saveFluidStack(registries, stack);
                return tag.toString().contains(nbtFilter);
            } catch (Exception e) {
                return false;
            }
        }

        return fluidIdStr.equals(filter);
    }

    private static boolean matchesFluidMacro(FluidStack stack, String macro) {
        if (macro.isEmpty()) {
            return false;
        }
        String m = macro.toLowerCase();
        String key;
        if (m.startsWith("temperature")) {
            key = "temperature";
        } else if (m.startsWith("viscosity")) {
            key = "viscosity";
        } else if (m.startsWith("density")) {
            key = "density";
        } else if (m.startsWith("light")) {
            key = "light";
        } else {
            return false;
        }
        String expr = macro.substring(key.length()).trim();
        ParsedCmp p = parseCmp(expr);
        if (p == null) {
            return false;
        }
        var ft = stack.getFluid().getFluidType();
        int lhs =
                switch (key) {
                    case "temperature" -> ft.getTemperature(stack);
                    case "viscosity" -> ft.getViscosity(stack);
                    case "density" -> ft.getDensity(stack);
                    case "light" -> ft.getLightLevel(stack);
                    default -> 0;
                };
        return switch (p.cmp) {
            case EQ -> lhs == p.value;
            case NE -> lhs != p.value;
            case GT -> lhs > p.value;
            case GE -> lhs >= p.value;
            case LT -> lhs < p.value;
            case LE -> lhs <= p.value;
        };
    }

    private record ParsedCmp(Cmp cmp, int value) {}

    /**
     * Parses operator + integer, e.g. {@code ">=1000"} or {@code " = 300"}.
     */
    private static ParsedCmp parseCmp(String expr) {
        if (expr == null) {
            return null;
        }
        String s = expr.trim();
        if (s.isEmpty()) {
            return null;
        }
        Cmp cmp;
        String rhs;
        if (s.startsWith(">=")) {
            cmp = Cmp.GE;
            rhs = s.substring(2);
        } else if (s.startsWith("<=")) {
            cmp = Cmp.LE;
            rhs = s.substring(2);
        } else if (s.startsWith("!=")) {
            cmp = Cmp.NE;
            rhs = s.substring(2);
        } else if (s.startsWith(">")) {
            cmp = Cmp.GT;
            rhs = s.substring(1);
        } else if (s.startsWith("<")) {
            cmp = Cmp.LT;
            rhs = s.substring(1);
        } else if (s.startsWith("=")) {
            cmp = Cmp.EQ;
            rhs = s.substring(1);
        } else {
            return null;
        }
        try {
            int v = Integer.parseInt(rhs.trim());
            return new ParsedCmp(cmp, v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean matchesAnyNonEmptyEntry(FluidStack stack, String filterLine, HolderLookup.Provider registries) {
        if (filterLine == null || filterLine.trim().isEmpty() || stack.isEmpty()) {
            return false;
        }
        Fluid fluid = stack.getFluid();
        Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();
        return matchesFilterEntry(stack, fluid, fluidId, fluidIdStr, fluidModId, filterLine.trim(), registries);
    }
}
