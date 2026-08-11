package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * Single gas ("chemical") filter entry matching.
 *
 * <p>Supports {@code -id}, {@code @mod}, and {@code &...} macros (no {@code #} tags or {@code ?} NBT matching).</p>
 */
public final class DuctGasFilterMatcher {
    private DuctGasFilterMatcher() {}

    private enum Cmp {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE
    }

    public static boolean matchesAnyNonEmptyEntry(
            Object chemicalStack, String filterLine, @Nullable HolderLookup.Provider registries) {
        if (filterLine == null || filterLine.trim().isEmpty()) {
            return false;
        }
        return matchesFilterEntry(chemicalStack, filterLine.trim(), registries);
    }

    public static boolean matchesFilterEntry(
            Object chemicalStack,
            String filter,
            @SuppressWarnings("unused") @Nullable HolderLookup.Provider registries) {
        if (filter == null || filter.isEmpty() || chemicalStack == null || MekanismChemicalCompat.isEmptyStack(chemicalStack)) {
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
                Identifier id = Identifier.parse(idStr);
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

        if (filter.startsWith("&")) {
            return matchesGasMacro(chemicalStack, filter.substring(1).trim());
        }

        return idStr.equals(filter);
    }

    private static boolean matchesGasMacro(Object stack, String macro) {
        if (macro.isEmpty()) {
            return false;
        }
        String m = macro.toLowerCase().trim();
        if (DuctFilterSpecialKeys.isAnythingElseMacroBody(m)) {
            return true;
        }
        if (m.equals("radioactive")) {
            return MekanismChemicalCompat.isRadioactive(stack);
        }
        // Before "radioactive*" so "radioactivity..." is not parsed as "radioactive" + "ivity...".
        if (m.startsWith("radioactivity")) {
            String expr = m.substring("radioactivity".length()).trim();
            ParsedCmpDouble p = parseCmpDouble(expr);
            if (p == null) {
                return false;
            }
            return compareDouble(MekanismChemicalCompat.getRadioactivityPerUnit(stack), p);
        }
        if (m.startsWith("radioactive")) {
            String rest = m.substring("radioactive".length()).trim();
            if (rest.isEmpty()) {
                return MekanismChemicalCompat.isRadioactive(stack);
            }
            ParsedCmpDouble p = parseCmpDouble(rest);
            if (p == null) {
                return false;
            }
            return compareDouble(MekanismChemicalCompat.getRadioactivityTotal(stack), p);
        }
        if (m.startsWith("tint")) {
            String expr = m.substring("tint".length()).trim();
            ParsedCmpInt p = parseCmpInt(expr);
            if (p == null) {
                return false;
            }
            int lhs = MekanismChemicalCompat.getTint(stack);
            return switch (p.cmp) {
                case EQ -> lhs == p.value;
                case NE -> lhs != p.value;
                case GT -> lhs > p.value;
                case GE -> lhs >= p.value;
                case LT -> lhs < p.value;
                case LE -> lhs <= p.value;
            };
        }
        return false;
    }

    private static boolean compareDouble(double lhs, ParsedCmpDouble p) {
        double rhs = p.value;
        return switch (p.cmp) {
            case EQ -> lhs == rhs;
            case NE -> lhs != rhs;
            case GT -> lhs > rhs;
            case GE -> lhs >= rhs;
            case LT -> lhs < rhs;
            case LE -> lhs <= rhs;
        };
    }

    private record ParsedCmpInt(Cmp cmp, int value) {}

    private record ParsedCmpDouble(Cmp cmp, double value) {}

    private static ParsedCmpInt parseCmpInt(String expr) {
        ParsedCmpDouble d = parseCmpDouble(expr);
        if (d == null) {
            return null;
        }
        if (Double.isNaN(d.value) || Double.isInfinite(d.value)) {
            return null;
        }
        return new ParsedCmpInt(d.cmp, (int) Math.round(d.value));
    }

    private static ParsedCmpDouble parseCmpDouble(String expr) {
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
            return new ParsedCmpDouble(cmp, Double.parseDouble(rhs.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
