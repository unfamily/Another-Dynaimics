package net.unfamily.another_dynamics.duct;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.unfamily.another_dynamics.duct.module.DuctFaceModuleItemHandler;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;

/**
 * Per-face transport lanes. Item, fluid, and gas keep separate filters, channels, routing fields, and amount fields.
 * <p>
 * <strong>Shared for the whole face</strong>: {@link #nodeMode}, {@link #redstoneMode}, and {@link #moduleSlots}
 * (one module column for all lanes including energy/heat bonuses). Each lane keeps its own copy-settings slot in
 * {@link DuctFaceNode#guiSlots}. RF and Mek heat logistics use separate throttle state on this object so hybrid ducts do
 * not share {@link DuctFaceNode#ticksUntilAction} with the item lane.
 */
public final class DuctFaceLanes {
    private static final byte REDSTONE_FMT_V1 = 1;
    private static final String NBT_MODULES = "Modules";
    private static final String NBT_MODULES_LEGACY = "Upgrades";
    private static final String NBT_ENERGY_HEAT = "EnergyHeat";

    private final DuctBlockEntity duct;
    private final Direction face;

    public NodeMode nodeMode = NodeMode.NONE;
    /**
     * 0 = ignored (always enabled), 1 = low (enabled when NOT powered), 2 = high (enabled when powered), 3 = disabled.
     */
    public int redstoneMode = 3;

    /** Shared module column; size follows {@link DuctDefinition#moduleSlotCount()} (clamped). */
    public DuctFaceModuleItemHandler moduleSlots;

    public final DuctFaceNode item;
    public final DuctFaceNode fluid;
    public final DuctFaceNode gas;

    /**
     * RF logistics throttle / round-robin (not stored on the item {@link DuctFaceNode} — avoids clashes on hybrid
     * ducts).
     */
    public int energyTicksUntilAction;
    public int energyRoundRobinCursor;

    /** Mek heat logistics throttle / round-robin (same rationale as {@link #energyTicksUntilAction}). */
    public int heatTicksUntilAction;
    public int heatRoundRobinCursor;

    public DuctFaceLanes(DuctBlockEntity duct, Direction face, int moduleSlotCount) {
        this.duct = duct;
        this.face = face;
        int n = Math.max(0, moduleSlotCount);
        this.moduleSlots = new DuctFaceModuleItemHandler(duct, face, n);
        this.item = new DuctFaceNode(duct::setChanged);
        this.fluid = new DuctFaceNode(duct::setChanged);
        this.gas = new DuctFaceNode(duct::setChanged);
    }

    /**
     * Resizes the shared module handler, preserving stacks in the overlapping prefix.
     */
    public void resizeModuleSlots(int want) {
        want = Math.max(0, want);
        if (moduleSlots.getSlots() == want) {
            return;
        }
        DuctFaceModuleItemHandler prev = moduleSlots;
        moduleSlots = new DuctFaceModuleItemHandler(duct, face, want);
        for (int i = 0; i < Math.min(want, prev.getSlots()); i++) {
            moduleSlots.setStackInSlot(i, prev.getStackInSlot(i).copy());
        }
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        CompoundTag shared = new CompoundTag();
        shared.putByte("NodeMode", (byte) nodeMode.ordinal());
        shared.putByte("RedstoneMode", (byte) redstoneMode);
        shared.putByte("RsFmt", REDSTONE_FMT_V1);
        tag.put("Shared", shared);

        tag.put(NBT_MODULES, moduleSlots.serializeNBT(registries));

        CompoundTag itemTag = new CompoundTag();
        item.save(registries, itemTag);
        tag.put("Item", itemTag);
        CompoundTag fluidTag = new CompoundTag();
        fluid.save(registries, fluidTag);
        tag.put("Fluid", fluidTag);
        CompoundTag gasTag = new CompoundTag();
        gas.save(registries, gasTag);
        tag.put("Gas", gasTag);

        CompoundTag eh = new CompoundTag();
        eh.putInt("EnergyTicks", energyTicksUntilAction);
        eh.putInt("EnergyRr", energyRoundRobinCursor);
        eh.putInt("HeatTicks", heatTicksUntilAction);
        eh.putInt("HeatRr", heatRoundRobinCursor);
        tag.put(NBT_ENERGY_HEAT, eh);
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        if (tag.contains("Shared", Tag.TAG_COMPOUND)) {
            loadShared(tag.getCompound("Shared"));
        } else {
            CompoundTag itemLike = tag.contains("Item", Tag.TAG_COMPOUND) ? tag.getCompound("Item") : tag;
            loadSharedFromLegacyNodeTag(itemLike);
        }

        // Module column size must match {@link DuctBlockEntity#ensureFaceLaneModuleSlotCapacitiesMatchDefinition()}
        // before load (logical duct id). Do not resize to GUI max here or reload shrinks stacks to the wrong cap.
        if (tag.contains(NBT_MODULES, Tag.TAG_COMPOUND)) {
            moduleSlots.deserializeNBT(registries, tag.getCompound(NBT_MODULES));
        } else if (tag.contains(NBT_MODULES_LEGACY, Tag.TAG_COMPOUND)) {
            moduleSlots.deserializeNBT(registries, tag.getCompound(NBT_MODULES_LEGACY));
        } else {
            migrateLegacyModulesFromItemNodeGui(registries, tag);
        }

        CompoundTag rawItem = tag.contains("Item", Tag.TAG_COMPOUND) ? tag.getCompound("Item") : tag;
        item.load(registries, stripSharedKeys(rawItem));
        applyLegacyAmountField(item, rawItem, nodeMode);
        if (tag.contains("Fluid", Tag.TAG_COMPOUND)) {
            CompoundTag rawFluid = tag.getCompound("Fluid");
            fluid.load(registries, stripSharedKeys(rawFluid));
            applyLegacyAmountField(fluid, rawFluid, nodeMode);
        }
        if (tag.contains("Gas", Tag.TAG_COMPOUND)) {
            CompoundTag rawGas = tag.getCompound("Gas");
            gas.load(registries, stripSharedKeys(rawGas));
            applyLegacyAmountField(gas, rawGas, nodeMode);
        }

        if (tag.contains(NBT_ENERGY_HEAT, Tag.TAG_COMPOUND)) {
            CompoundTag eh = tag.getCompound(NBT_ENERGY_HEAT);
            energyTicksUntilAction = eh.getInt("EnergyTicks");
            energyRoundRobinCursor = eh.getInt("EnergyRr");
            heatTicksUntilAction = eh.getInt("HeatTicks");
            heatRoundRobinCursor = eh.getInt("HeatRr");
        } else {
            energyTicksUntilAction = 0;
            energyRoundRobinCursor = 0;
            heatTicksUntilAction = 0;
            heatRoundRobinCursor = 0;
        }
    }

    private void migrateLegacyModulesFromItemNodeGui(HolderLookup.Provider registries, CompoundTag faceTag) {
        CompoundTag nodeGuiHost = null;
        if (faceTag.contains("Item", Tag.TAG_COMPOUND)) {
            CompoundTag itemTag = faceTag.getCompound("Item");
            if (itemTag.contains("NodeGui", Tag.TAG_COMPOUND)) {
                nodeGuiHost = itemTag;
            }
        }
        if (nodeGuiHost == null && faceTag.contains("NodeGui", Tag.TAG_COMPOUND)) {
            nodeGuiHost = faceTag;
        }
        if (nodeGuiHost == null) {
            return;
        }
        net.neoforged.neoforge.items.ItemStackHandler legacy = new net.neoforged.neoforge.items.ItemStackHandler(6);
        legacy.deserializeNBT(registries, nodeGuiHost.getCompound("NodeGui"));
        int limit = Math.min(5, moduleSlots.getSlots());
        for (int i = 0; i < limit; i++) {
            moduleSlots.setStackInSlot(i, legacy.getStackInSlot(i).copy());
        }
    }

    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root) {
        loadSharedFromLegacyNodeTag(root);
        if (root.contains(NBT_MODULES, Tag.TAG_COMPOUND)) {
            moduleSlots.deserializeNBT(registries, root.getCompound(NBT_MODULES));
        } else if (root.contains(NBT_MODULES_LEGACY, Tag.TAG_COMPOUND)) {
            moduleSlots.deserializeNBT(registries, root.getCompound(NBT_MODULES_LEGACY));
        } else {
            migrateLegacyModulesFromItemNodeGui(registries, root);
        }
        item.loadFromLegacyRootTag(registries, stripSharedKeys(root), nodeMode);
    }

    /**
     * When a face is not a pipe segment end, reset shared mode/redstone and both lane defaults (including per-lane ticks).
     */
    public void resetPipeSegmentDefaults() {
        nodeMode = NodeMode.NONE;
        redstoneMode = 3;
        item.resetPipeSegmentDefaults();
        fluid.resetPipeSegmentDefaults();
        gas.resetPipeSegmentDefaults();
        energyTicksUntilAction = 0;
        energyRoundRobinCursor = 0;
        heatTicksUntilAction = 0;
        heatRoundRobinCursor = 0;
    }

    public void clampFilterSizes(DuctItemTransportSpec itemSpec, DuctFluidTransportSpec fluidSpec, DuctGasTransportSpec gasSpec) {
        DuctModuleEffects.FilterSlotBonuses fb = DuctModuleEffects.filterSlotBonuses(duct, face);
        item.clampFilterSizes(itemSpec, nodeMode, fb);
        fluid.clampFilterSizes(fluidSpec, nodeMode, fb);
        gas.clampFilterSizes(gasSpec, nodeMode, fb);
    }

    private void loadShared(CompoundTag tag) {
        nodeMode = NodeMode.fromOrdinal(tag.getByte("NodeMode"));
        redstoneMode = tag.getByte("RedstoneMode") & 0xFF;
        int rsFmt = tag.contains("RsFmt") ? tag.getByte("RsFmt") & 0xFF : 0;
        if (rsFmt < REDSTONE_FMT_V1) {
            // Legacy saves without RsFmt: only clamp invalid ordinals. Do not map 3 -> 0 (that turned "disabled" into
            // "ignored" in UI and broke parity with new defaults).
            if (redstoneMode >= 4) {
                redstoneMode = 3;
            }
        } else if (redstoneMode >= 4) {
            redstoneMode = 3;
        }
    }

    private void loadSharedFromLegacyNodeTag(CompoundTag tag) {
        nodeMode = NodeMode.fromOrdinal(tag.getByte("NodeMode"));
        redstoneMode = tag.getByte("RedstoneMode") & 0xFF;
        int rsFmt = tag.contains("RsFmt") ? tag.getByte("RsFmt") & 0xFF : 0;
        if (rsFmt < REDSTONE_FMT_V1) {
            if (redstoneMode >= 4) {
                redstoneMode = 3;
            }
        } else if (redstoneMode >= 4) {
            redstoneMode = 3;
        }
    }

    private static CompoundTag stripSharedKeys(CompoundTag src) {
        CompoundTag c = src.copy();
        c.remove("NodeMode");
        c.remove("RedstoneMode");
        c.remove("RsFmt");
        return c;
    }

    private static void applyLegacyAmountField(DuctFaceNode lane, CompoundTag raw, NodeMode mode) {
        if (raw.contains("InsertionPriority")
                || raw.contains("InsPriority")
                || raw.contains("ExtractBatch")
                || !raw.contains("AmountField")) {
            return;
        }
        int legacy = raw.getInt("AmountField");
        if (mode.usesInsertionPriorityField()) {
            lane.insertionPriority = legacy;
        } else if (mode.usesExtractBatchField()) {
            lane.extractBatch = legacy;
        } else {
            lane.insertionPriority = legacy;
        }
    }
}
