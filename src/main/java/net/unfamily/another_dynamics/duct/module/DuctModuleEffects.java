package net.unfamily.another_dynamics.duct.module;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctGasTransportSpec;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.NodeMode;

public final class DuctModuleEffects {
    private DuctModuleEffects() {}

    /** Aggregated extra filter list slots from all modules on a face. */
    public record FilterSlotBonuses(int allowAdd, int denyAdd, int allowHybridAdd, int denyHybridAdd) {
        public static final FilterSlotBonuses ZERO = new FilterSlotBonuses(0, 0, 0, 0);
    }

    public static FilterSlotBonuses filterSlotBonuses(DuctBlockEntity duct, Direction face) {
        var handler = duct.getFaceLanes(face).moduleSlots;
        int aa = 0;
        int dd = 0;
        int ah = 0;
        int dh = 0;
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
            ModuleDefinition.FilterSlotModifiers f = def.filterSlots();
            aa += f.allowSlotAdd();
            dd += f.denySlotAdd();
            ah += f.allowHybridSlotAdd();
            dh += f.denyHybridSlotAdd();
        }
        return new FilterSlotBonuses(aa, dd, ah, dh);
    }

    public static int effectiveItemAllowBank(DuctItemTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterAllowHybridSlots() + fb.allowHybridAdd())
                : Math.max(0, spec.filterAllowSlots() + fb.allowAdd());
    }

    public static int effectiveItemDenyBank(DuctItemTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterDenyHybridSlots() + fb.denyHybridAdd())
                : Math.max(0, spec.filterDenySlots() + fb.denyAdd());
    }

    public static int effectiveFluidAllowBank(DuctFluidTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterAllowHybridSlots() + fb.allowHybridAdd())
                : Math.max(0, spec.filterAllowSlots() + fb.allowAdd());
    }

    public static int effectiveFluidDenyBank(DuctFluidTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterDenyHybridSlots() + fb.denyHybridAdd())
                : Math.max(0, spec.filterDenySlots() + fb.denyAdd());
    }

    public static int effectiveGasAllowBank(DuctGasTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterAllowHybridSlots() + fb.allowHybridAdd())
                : Math.max(0, spec.filterAllowSlots() + fb.allowAdd());
    }

    public static int effectiveGasDenyBank(DuctGasTransportSpec spec, NodeMode sharedMode, FilterSlotBonuses fb) {
        return sharedMode.isHybrid()
                ? Math.max(0, spec.filterDenyHybridSlots() + fb.denyHybridAdd())
                : Math.max(0, spec.filterDenySlots() + fb.denyAdd());
    }

    /**
     * Batch bonus over {@link DuctItemTransportSpec#batchDefault()} from installed item modules on this face.
     * Set wins as max across modules; then add sums and mult product apply (datapack order in JSON is not stable — use
     * single-pass aggregate).
     */
    public static int itemExtractBatchBonus(DuctBlockEntity duct, Direction face, DuctItemTransportSpec spec) {
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
            ModuleDefinition.ItemBatchModifiers m = def.itemBatchModifiers();
            if (m.hasSet()) {
                if (!anySet || m.setValue() > bestSet) {
                    anySet = true;
                    bestSet = m.setValue();
                }
            }
            addSum += m.addSum();
            mult *= m.multProduct();
        }
        int batchDefault = spec.batchDefault();
        int base = anySet ? bestSet : batchDefault;
        int afterAdd = base + addSum;
        int afterMult = (int) Math.round(afterAdd * mult);
        return Math.max(0, afterMult - batchDefault);
    }
}
