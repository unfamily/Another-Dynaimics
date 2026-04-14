package net.unfamily.another_dynamics.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.unfamily.another_dynamics.duct.module.ModuleDefinition;
import net.unfamily.another_dynamics.duct.module.ModuleDefinition.ItemQuantityModifiers;

/**
 * Gray module hints using {@code gui.another_dynamics.module.line.*}. Rate uses ×2/×3 style only (no ÷/≤). Quantity
 * omits ≤ on set caps and appends {@code suffix.item} or {@code suffix.mb} next to values for item / fluid / gas
 * lanes.
 */
public final class ModuleUpgradeTooltip {
    private static final double EPS = 1e-9;

    private static final String LINE_SPEED = "gui.another_dynamics.module.line.speed";
    private static final String LINE_FILTER = "gui.another_dynamics.module.line.filter";
    private static final String LINE_RATE = "gui.another_dynamics.module.line.rate";
    private static final String LINE_QUANTITY = "gui.another_dynamics.module.line.quantity";
    private static final String LINE_ENERGY = "gui.another_dynamics.module.line.energy";
    private static final String SUFFIX_ITEM = "gui.another_dynamics.module.suffix.item";
    private static final String SUFFIX_MB = "gui.another_dynamics.module.suffix.mb";

    private ModuleUpgradeTooltip() {}

    public static void appendStatLines(ModuleDefinition def, List<Component> out) {
        ItemQuantityModifiers[] qty = {
            def.itemQuantityModifiers(),
            def.fluidQuantityModifiers(),
            def.gasQuantityModifiers(),
            def.energyQuantityModifiers(),
            def.heatQuantityModifiers()
        };
        ItemQuantityModifiers[] rate = {
            def.itemRateModifiers(),
            def.fluidRateModifiers(),
            def.gasRateModifiers(),
            def.energyRateModifiers(),
            def.heatRateModifiers()
        };
        ItemQuantityModifiers[] speed = {
            def.itemSpeedModifiers(),
            def.fluidSpeedModifiers(),
            def.gasSpeedModifiers(),
            def.energySpeedModifiers(),
            def.heatSpeedModifiers()
        };
        ItemQuantityModifiers[] energy = {
            def.energyQuantityModifiers(),
            def.energyRateModifiers(),
            def.energySpeedModifiers()
        };

        appendMergedSingle(out, LINE_SPEED, speed, ModuleUpgradeTooltip::formatSpeedLane);
        appendFilterAllowOnly(out, def);
        appendMergedSingle(out, LINE_RATE, rate, ModuleUpgradeTooltip::formatRateLane);
        appendMergedSingle(out, LINE_ENERGY, energy, ModuleUpgradeTooltip::formatEnergyLane);
        appendQuantityAtMostTwo(out, qty);
    }

    private static void appendMergedSingle(
            List<Component> out,
            String key,
            ItemQuantityModifiers[] lanes,
            java.util.function.Function<ItemQuantityModifiers, String> formatter) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (ItemQuantityModifiers m : lanes) {
            String s = formatter.apply(m);
            if (s != null) {
                distinct.add(s);
            }
        }
        if (distinct.isEmpty()) {
            return;
        }
        String value = distinct.size() == 1 ? distinct.iterator().next() : String.join(" \u00b7 ", distinct);
        out.add(line(key, value));
    }

    private record QuantityEntry(String value, String suffixKey) {}

    private static void appendQuantityAtMostTwo(List<Component> out, ItemQuantityModifiers[] qtyLanes) {
        String[] suffixForLane = {SUFFIX_ITEM, SUFFIX_MB, SUFFIX_MB, null, null};
        List<QuantityEntry> entries = new ArrayList<>();
        for (int i = 0; i < qtyLanes.length; i++) {
            String v = formatQuantityLane(qtyLanes[i]);
            if (v == null) {
                continue;
            }
            String sk = suffixForLane[i];
            QuantityEntry e = new QuantityEntry(v, sk);
            boolean dup = entries.stream().anyMatch(x -> x.value.equals(e.value) && Objects.equals(x.suffixKey, e.suffixKey));
            if (!dup) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        if (entries.size() == 1) {
            out.add(quantityLine(entries.get(0)));
            return;
        }
        if (entries.size() == 2) {
            out.add(quantityLine(entries.get(0)));
            out.add(quantityLine(entries.get(1)));
            return;
        }
        out.add(quantityLine(entries.get(0)));
        out.add(mergeQuantityTail(entries.subList(1, entries.size())));
    }

    /** Second quantity line: first entry uses full label; further entries append “ · value suffix”. */
    private static MutableComponent mergeQuantityTail(List<QuantityEntry> tail) {
        MutableComponent row = quantityLine(tail.get(0));
        for (int i = 1; i < tail.size(); i++) {
            QuantityEntry ex = tail.get(i);
            row.append(Component.literal(" \u00b7 ").withStyle(ChatFormatting.GRAY));
            row.append(Component.literal(ex.value()).withStyle(ChatFormatting.GRAY));
            if (ex.suffixKey() != null) {
                row.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
                row.append(Component.translatable(ex.suffixKey()).withStyle(ChatFormatting.GRAY));
            }
        }
        return row;
    }

    private static MutableComponent quantityLine(QuantityEntry e) {
        MutableComponent row = Component.translatable(LINE_QUANTITY, e.value()).withStyle(ChatFormatting.GRAY);
        if (e.suffixKey() != null) {
            row.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
            row.append(Component.translatable(e.suffixKey()).withStyle(ChatFormatting.GRAY));
        }
        return row;
    }

    /**
     * Extra filter allow-slot add: each transport kind gets its own bonus in-game ({@link DuctModuleEffects}); do not
     * sum across kinds. Show one number when item/fluid/gas agree; otherwise sorted distinct positive adds joined with
     * middle dot.
     */
    private static void appendFilterAllowOnly(List<Component> out, ModuleDefinition def) {
        LinkedHashSet<Integer> distinct = new LinkedHashSet<>();
        int item = def.filterSlotsItem().allowSlotAdd();
        int fluid = def.filterSlotsFluid().allowSlotAdd();
        int gas = def.filterSlotsGas().allowSlotAdd();
        if (item > 0) {
            distinct.add(item);
        }
        if (fluid > 0) {
            distinct.add(fluid);
        }
        if (gas > 0) {
            distinct.add(gas);
        }
        if (distinct.isEmpty()) {
            return;
        }
        if (distinct.size() == 1) {
            out.add(line(LINE_FILTER, distinct.iterator().next()));
            return;
        }
        ArrayList<Integer> sorted = new ArrayList<>(distinct);
        sorted.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append(sorted.get(i));
        }
        out.add(line(LINE_FILTER, sb.toString()));
    }

    private static String formatSpeedLane(ItemQuantityModifiers m) {
        if (inactive(m)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (m.hasSet()) {
            parts.add("\u2264" + m.setValue());
        }
        if (m.addSum() != 0) {
            parts.add(String.format(Locale.ROOT, "%+d", m.addSum()));
        }
        double mult = m.multProduct();
        if (Math.abs(mult - 1.0) > EPS) {
            if (mult < 1.0 - EPS) {
                parts.add("\u00d7" + reciprocalDisplay(mult));
            } else {
                parts.add("\u00d7" + formatMultPlain(mult));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * Extract rate: ×2/×3 style (mult &lt; 1 shown as ×reciprocal like speed). Optional tick cap / add after, no ≤ or
     * ÷.
     */
    private static String formatRateLane(ItemQuantityModifiers m) {
        if (inactive(m)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        double mult = m.multProduct();
        if (Math.abs(mult - 1.0) > EPS) {
            if (mult < 1.0 - EPS) {
                parts.add("\u00d7" + reciprocalDisplay(mult));
            } else {
                parts.add("\u00d7" + formatMultPlain(mult));
            }
        }
        if (m.hasSet()) {
            parts.add(String.valueOf(m.setValue()));
        }
        if (m.addSum() != 0) {
            parts.add(String.format(Locale.ROOT, "%+d", m.addSum()));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * Energy extraction: ×N style multiplier (mult &lt; 1 shown as ×reciprocal). Optional cap / add after.
     */
    private static String formatEnergyLane(ItemQuantityModifiers m) {
        if (inactive(m)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        double mult = m.multProduct();
        if (Math.abs(mult - 1.0) > EPS) {
            if (mult < 1.0 - EPS) {
                parts.add("\u00d7" + reciprocalDisplay(mult));
            } else {
                parts.add("\u00d7" + formatMultPlain(mult));
            }
        }
        if (m.hasSet()) {
            parts.add(String.valueOf(m.setValue()));
        }
        if (m.addSum() != 0) {
            parts.add(String.format(Locale.ROOT, "%+d", m.addSum()));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Quantity: no ≤ on set; mult as ×N. */
    private static String formatQuantityLane(ItemQuantityModifiers m) {
        if (inactive(m)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (m.hasSet()) {
            parts.add(String.valueOf(m.setValue()));
        }
        if (m.addSum() != 0) {
            parts.add(String.format(Locale.ROOT, "%+d", m.addSum()));
        }
        if (Math.abs(m.multProduct() - 1.0) > EPS) {
            parts.add("\u00d7" + formatMultPlain(m.multProduct()));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static boolean inactive(ItemQuantityModifiers m) {
        return Math.abs(m.multProduct() - 1.0) < EPS && !m.hasSet() && m.addSum() == 0;
    }

    private static Component line(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GRAY);
    }

    private static int reciprocalDisplay(double mult) {
        return (int) Math.round(1.0 / mult);
    }

    private static String formatMultPlain(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-5) {
            return Integer.toString((int) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
