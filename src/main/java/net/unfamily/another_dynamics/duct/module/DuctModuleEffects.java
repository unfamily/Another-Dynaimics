package net.unfamily.another_dynamics.duct.module;

import java.util.function.Function;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctEnergyTransportSpec;
import net.unfamily.another_dynamics.duct.DuctFeatureKeys;
import net.unfamily.another_dynamics.duct.DuctFeaturePolicy;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctGasTransportSpec;
import net.unfamily.another_dynamics.duct.DuctHeatTransportSpec;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.NodeMode;

public final class DuctModuleEffects {
    private DuctModuleEffects() {}

    /** Per-transport-type extra filter list slots from all modules on a face. */
    public record FilterSlotBonuses(PerKind item, PerKind fluid, PerKind gas) {
        public record PerKind(int allowAdd, int denyAdd, int allowHybridAdd, int denyHybridAdd) {
            public static final PerKind ZERO = new PerKind(0, 0, 0, 0);
        }

        public static final FilterSlotBonuses ZERO = new FilterSlotBonuses(PerKind.ZERO, PerKind.ZERO, PerKind.ZERO);
    }

    public static FilterSlotBonuses filterSlotBonuses(DuctBlockEntity duct, Direction face) {
        var handler = duct.getFaceLanes(face).moduleSlots;
        int ia = 0, id = 0, iah = 0, idh = 0;
        int fa = 0, fd = 0, fah = 0, fdh = 0;
        int ga = 0, gd = 0, gah = 0, gdh = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            var idOpt = DuctModuleHelper.resolvedDeclarationId(s);
            if (idOpt.isEmpty()) {
                continue;
            }
            ModuleDefinition def = ModuleDefinitionRegistry.get(idOpt.get()).orElse(null);
            if (def == null) {
                continue;
            }
            ModuleDefinition.FilterSlotModifiers fi = def.filterSlotsItem();
            ia += fi.allowSlotAdd();
            id += fi.denySlotAdd();
            iah += fi.allowHybridSlotAdd();
            idh += fi.denyHybridSlotAdd();
            ModuleDefinition.FilterSlotModifiers ff = def.filterSlotsFluid();
            fa += ff.allowSlotAdd();
            fd += ff.denySlotAdd();
            fah += ff.allowHybridSlotAdd();
            fdh += ff.denyHybridSlotAdd();
            ModuleDefinition.FilterSlotModifiers fg = def.filterSlotsGas();
            ga += fg.allowSlotAdd();
            gd += fg.denySlotAdd();
            gah += fg.allowHybridSlotAdd();
            gdh += fg.denyHybridSlotAdd();
        }
        return new FilterSlotBonuses(
                new FilterSlotBonuses.PerKind(ia, id, iah, idh),
                new FilterSlotBonuses.PerKind(fa, fd, fah, fdh),
                new FilterSlotBonuses.PerKind(ga, gd, gah, gdh));
    }

    public static int effectiveItemAllowBank(DuctItemTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return effectiveAllowBankItem(spec, sharedMode, fb.item());
    }

    public static int effectiveItemDenyBank(DuctItemTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return effectiveDenyBankItem(spec, sharedMode, fb.item());
    }

    public static int effectiveFluidAllowBank(DuctFluidTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return effectiveAllowBankFluid(spec, sharedMode, fb.fluid());
    }

    public static int effectiveFluidDenyBank(DuctFluidTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return effectiveDenyBankFluid(spec, sharedMode, fb.fluid());
    }

    public static int effectiveGasAllowBank(DuctGasTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return effectiveAllowBankGas(spec, sharedMode, fb.gas());
    }

    public static int effectiveGasDenyBank(DuctGasTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return effectiveDenyBankGas(spec, sharedMode, fb.gas());
    }

    private static int effectiveAllowBankItem(
            DuctItemTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses.PerKind lane) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterAllowHybridSlots() + lane.allowHybridAdd())
                : Math.max(0, spec.filterAllowSlots() + lane.allowAdd());
    }

    private static int effectiveDenyBankItem(
            DuctItemTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses.PerKind lane) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterDenyHybridSlots() + lane.denyHybridAdd())
                : Math.max(0, spec.filterDenySlots() + lane.denyAdd());
    }

    private static int effectiveAllowBankFluid(
            DuctFluidTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses.PerKind lane) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterAllowHybridSlots() + lane.allowHybridAdd())
                : Math.max(0, spec.filterAllowSlots() + lane.allowAdd());
    }

    private static int effectiveDenyBankFluid(
            DuctFluidTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses.PerKind lane) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterDenyHybridSlots() + lane.denyHybridAdd())
                : Math.max(0, spec.filterDenySlots() + lane.denyAdd());
    }

    private static int effectiveAllowBankGas(
            DuctGasTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses.PerKind lane) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterAllowHybridSlots() + lane.allowHybridAdd())
                : Math.max(0, spec.filterAllowSlots() + lane.allowAdd());
    }

    private static int effectiveDenyBankGas(
            DuctGasTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses.PerKind lane) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterDenyHybridSlots() + lane.denyHybridAdd())
                : Math.max(0, spec.filterDenySlots() + lane.denyAdd());
    }

    public static int itemExtractBatchBonus(DuctBlockEntity duct, Direction face, DuctItemTransportSpec spec) {
        return extractBatchBonusMbOrItems(duct, face, spec.batchDefault(), ModuleDefinition::itemQuantityModifiers);
    }

    public static int fluidExtractBatchBonusMb(DuctBlockEntity duct, Direction face, DuctFluidTransportSpec spec) {
        return extractBatchBonusMbOrItems(duct, face, spec.batchDefaultMb(), ModuleDefinition::fluidQuantityModifiers);
    }

    /**
     * Bonus chemical amount over {@link DuctGasTransportSpec#batchDefault()} (same stacking rules as item/fluid
     * quantity).
     */
    public static long gasExtractBatchBonus(DuctBlockEntity duct, Direction face, DuctGasTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return 0L;
        }
        var handler = duct.getFaceLanes(face).moduleSlots;
        boolean anySet = false;
        long bestSet = 0L;
        long addSum = 0L;
        double mult = 1.0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            var id = DuctModuleHelper.resolvedDeclarationId(s);
            if (id.isEmpty()) {
                continue;
            }
            ModuleDefinition def = ModuleDefinitionRegistry.get(id.get()).orElse(null);
            if (def == null) {
                continue;
            }
            ModuleDefinition.ItemQuantityModifiers m = def.gasQuantityModifiers();
            if (m.hasSet()) {
                long v = m.setValue();
                if (!anySet || v > bestSet) {
                    anySet = true;
                    bestSet = v;
                }
            }
            addSum += m.addSum();
            mult *= m.multProduct();
        }
        long batchDefault = spec.batchDefault();
        long base = anySet ? bestSet : batchDefault;
        long afterAdd = base + addSum;
        long afterMult = Math.round(afterAdd * mult);
        return Math.max(0L, afterMult - batchDefault);
    }

    private static int extractBatchBonusMbOrItems(
            DuctBlockEntity duct,
            Direction face,
            int batchDefault,
            Function<ModuleDefinition, ModuleDefinition.ItemQuantityModifiers> pick) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return 0;
        }
        var handler = duct.getFaceLanes(face).moduleSlots;
        boolean anySet = false;
        int bestSet = 0;
        int addSum = 0;
        double mult = 1.0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            var id = DuctModuleHelper.resolvedDeclarationId(s);
            if (id.isEmpty()) {
                continue;
            }
            ModuleDefinition def = ModuleDefinitionRegistry.get(id.get()).orElse(null);
            if (def == null) {
                continue;
            }
            ModuleDefinition.ItemQuantityModifiers m = pick.apply(def);
            if (m.hasSet()) {
                if (!anySet || m.setValue() > bestSet) {
                    anySet = true;
                    bestSet = m.setValue();
                }
            }
            addSum += m.addSum();
            mult *= m.multProduct();
        }
        int base = anySet ? bestSet : batchDefault;
        int afterAdd = base + addSum;
        int afterMult = (int) Math.round(afterAdd * mult);
        return Math.max(0, afterMult - batchDefault);
    }

    private static boolean faceHasNonEmptyModule(DuctBlockEntity duct, Direction face) {
        var handler = duct.getFaceLanes(face).moduleSlots;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean faceModuleEffectsEnabled(DuctBlockEntity duct, Direction face) {
        return DuctFeaturePolicy.isUsable(
                duct.ductDefinition().orElse(null), DuctFeatureKeys.SPECIAL_MODULES, faceHasNonEmptyModule(duct, face));
    }

    private static ModuleDefinition.ItemQuantityModifiers aggregateTimingLike(
            DuctBlockEntity duct, Direction face, Function<ModuleDefinition, ModuleDefinition.ItemQuantityModifiers> pick) {
        var handler = duct.getFaceLanes(face).moduleSlots;
        boolean anySet = false;
        int bestSet = 0;
        int addSum = 0;
        double mult = 1.0;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (s.isEmpty()) {
                continue;
            }
            var id = DuctModuleHelper.resolvedDeclarationId(s);
            if (id.isEmpty()) {
                continue;
            }
            ModuleDefinition def = ModuleDefinitionRegistry.get(id.get()).orElse(null);
            if (def == null) {
                continue;
            }
            ModuleDefinition.ItemQuantityModifiers m = pick.apply(def);
            if (m.hasSet()) {
                if (!anySet || m.setValue() > bestSet) {
                    anySet = true;
                    bestSet = m.setValue();
                }
            }
            addSum += m.addSum();
            mult *= m.multProduct();
        }
        return new ModuleDefinition.ItemQuantityModifiers(anySet, bestSet, addSum, mult);
    }

    private static int applyStackedTimingToBase(ModuleDefinition.ItemQuantityModifiers m, int datapackDefault) {
        int base = m.hasSet() ? m.setValue() : datapackDefault;
        return Math.max(0, (int) Math.round((base + m.addSum()) * m.multProduct()));
    }

    public static int effectiveItemActionRateTicks(DuctBlockEntity duct, Direction face, DuctItemTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return spec.clampedRateTicks(spec.rateDefaultTicks());
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::itemRateModifiers);
        int raw = applyStackedTimingToBase(agg, spec.rateDefaultTicks());
        return spec.clampedRateTicks(raw);
    }

    public static long effectiveItemEdgeTravelTicks(DuctBlockEntity duct, Direction face, DuctItemTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            int ticks = spec.effectiveSpeed(spec.speedDefault());
            return Math.max(0L, ticks);
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::itemSpeedModifiers);
        int baseTicks = spec.effectiveSpeed(spec.speedDefault());
        return Math.max(0L, applyStackedTimingToBase(agg, baseTicks));
    }

    public static int effectiveFluidActionRateTicks(DuctBlockEntity duct, Direction face, DuctFluidTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return spec.clampedRateTicks(spec.rateDefaultTicks());
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::fluidRateModifiers);
        int raw = applyStackedTimingToBase(agg, spec.rateDefaultTicks());
        return spec.clampedRateTicks(raw);
    }

    public static long effectiveFluidEdgeTravelTicks(DuctBlockEntity duct, Direction face, DuctFluidTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return Math.max(0L, spec.edgeTravelTicks());
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::fluidSpeedModifiers);
        int baseTicks = spec.effectiveSpeed(spec.speedDefault());
        return Math.max(0L, applyStackedTimingToBase(agg, baseTicks));
    }

    public static int effectiveGasActionRateTicks(DuctBlockEntity duct, Direction face, DuctGasTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return spec.clampedRateTicks(spec.rateDefaultTicks());
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::gasRateModifiers);
        int raw = applyStackedTimingToBase(agg, spec.rateDefaultTicks());
        return spec.clampedRateTicks(raw);
    }

    public static long effectiveGasEdgeTravelTicks(DuctBlockEntity duct, Direction face, DuctGasTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return Math.max(0L, spec.edgeTravelTicks());
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::gasSpeedModifiers);
        int baseTicks = spec.effectiveSpeed(spec.speedDefault());
        return Math.max(0L, applyStackedTimingToBase(agg, baseTicks));
    }

    /** Throttle interval for energy logistics on {@code face} (base {@code defaultTicks} when no modules). */
    public static int effectiveEnergyActionRateTicks(DuctBlockEntity duct, Direction face, int defaultTicks) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return Math.max(1, defaultTicks);
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::energyRateModifiers);
        int raw = applyStackedTimingToBase(agg, defaultTicks);
        return Math.max(1, raw);
    }

    /** Max FE attempted per energy action after {@code affects[].for=energy.quantity}. */
    public static int effectiveEnergyExtractPerAction(DuctBlockEntity duct, Direction face, DuctEnergyTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return (int) Math.min(spec.clampedExtract(), Integer.MAX_VALUE);
        }
        ModuleDefinition.ItemQuantityModifiers agg =
                aggregateTimingLike(duct, face, ModuleDefinition::energyQuantityModifiers);
        int base = (int) Math.min(spec.clampedExtract(), Integer.MAX_VALUE);
        return Math.max(0, applyStackedTimingToBase(agg, base));
    }

    public static int effectiveHeatActionRateTicks(DuctBlockEntity duct, Direction face, int defaultTicks) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return Math.max(1, defaultTicks);
        }
        ModuleDefinition.ItemQuantityModifiers agg = aggregateTimingLike(duct, face, ModuleDefinition::heatRateModifiers);
        int raw = applyStackedTimingToBase(agg, defaultTicks);
        return Math.max(1, raw);
    }

    /**
     * Max heat moved per heat action (Mek units), after {@code affects[].for=heat.quantity}. {@code heatSpeedModifiers}
     * are parsed but not applied to heat logistics yet.
     */
    public static double effectiveHeatExtractPerAction(DuctBlockEntity duct, Direction face, DuctHeatTransportSpec spec) {
        if (!faceModuleEffectsEnabled(duct, face)) {
            return spec.clampedExtract();
        }
        ModuleDefinition.ItemQuantityModifiers agg =
                aggregateTimingLike(duct, face, ModuleDefinition::heatQuantityModifiers);
        double base = spec.clampedExtract();
        double v = (agg.hasSet() ? agg.setValue() : base) + agg.addSum();
        v *= agg.multProduct();
        return Math.max(0.0, v);
    }
}
