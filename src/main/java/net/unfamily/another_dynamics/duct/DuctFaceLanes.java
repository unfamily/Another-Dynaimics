package net.unfamily.another_dynamics.duct;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.unfamily.another_dynamics.duct.module.DuctFaceModuleItemHandler;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.EnumSet;

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
    public int redstoneMode = 0;

    /**
     * Per-face enable mask for {@link DuctTransportKind} (bit {@code 1 << kind.ordinal()}). Universal ducts can disable
     * individual transport lanes without changing datapack {@code can_transport}.
     */
    public int transportEnabledMask = -1;

    /** Shared module column; size follows {@link DuctDefinition#moduleSlotCount()} (clamped). */
    public DuctFaceModuleItemHandler moduleSlots;

    /** Physical buffered items that could not be refunded; when full, this face is stalled. */
    public final ItemStackHandler stalledBuffer = new ItemStackHandler(5);

    /** Physical buffered fluids (mB) that could not be refunded; up to 5 entries. */
    public final FluidStack[] stalledFluids = new FluidStack[5];

    /**
     * Physical buffered gases ("chemicals") that could not be refunded; stored as tags to keep Mekanism optional.
     * Each entry is a {@link CompoundTag} holding {@code ChemId}/{@code Amt}.
     */
    public final CompoundTag[] stalledGas = new CompoundTag[5];

    /** Marker counts for stalled energy / heat (clearing deletes). */
    public int stalledEnergyCount;
    public int stalledHeatCount;

    /** Shift+click clearing arming window (gameTime), per face. */
    public long armedClearFluidUntilGameTime;
    public long armedClearGasUntilGameTime;

    public final DuctFaceNode item;
    public final DuctFaceNode fluid;
    public final DuctFaceNode gas;

    /**
     * RF logistics throttle / round-robin (not stored on the item {@link DuctFaceNode} — avoids clashes on hybrid
     * ducts).
     */
    public int energyTicksUntilAction;
    public int energyRoundRobinCursor;
    /** RF routing for this face (including {@link NodeMode#NONE} pass-through from external push). */
    public RoutingMode energyRoutingMode = RoutingMode.NEAREST_FIRST;
    public RoutingMode energyRoutingModeExtractor = RoutingMode.NEAREST_FIRST;
    public RoutingMode energyRoutingModeRetriever = RoutingMode.NEAREST_FIRST;
    /** Per-face FE buffer for external connectors (e.g. Flux Networks). */
    public int energyBufferFe;
    /** Last game time an energy ray was sent for this face (visual throttle; not persisted). */
    public long lastEnergyRayGameTime = -1L;

    /** Mek heat logistics throttle / round-robin (same rationale as {@link #energyTicksUntilAction}). */
    public int heatTicksUntilAction;
    public int heatRoundRobinCursor;
    public RoutingMode heatRoutingMode = RoutingMode.NEAREST_FIRST;
    public RoutingMode heatRoutingModeExtractor = RoutingMode.NEAREST_FIRST;
    public RoutingMode heatRoutingModeRetriever = RoutingMode.NEAREST_FIRST;

    public DuctFaceLanes(DuctBlockEntity duct, Direction face, int moduleSlotCount) {
        this.duct = duct;
        this.face = face;
        int n = Math.max(0, moduleSlotCount);
        this.moduleSlots = new DuctFaceModuleItemHandler(duct, face, n);
        this.item = new DuctFaceNode(duct::setChanged);
        this.fluid = new DuctFaceNode(duct::setChanged);
        this.gas = new DuctFaceNode(duct::setChanged);
        for (int i = 0; i < stalledFluids.length; i++) {
            stalledFluids[i] = FluidStack.EMPTY;
            stalledGas[i] = null;
        }
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

    public static int defaultTransportEnabledMask(EnumSet<DuctTransportKind> enabledKinds) {
        int mask = 0;
        if (enabledKinds != null) {
            for (DuctTransportKind k : enabledKinds) {
                mask |= 1 << k.ordinal();
            }
        }
        if (mask == 0) {
            mask = 1 << DuctTransportKind.ITEM.ordinal();
        }
        return mask;
    }

    public void ensureTransportEnabledMask(EnumSet<DuctTransportKind> enabledKinds) {
        if (transportEnabledMask < 0) {
            transportEnabledMask = defaultTransportEnabledMask(enabledKinds);
        }
    }

    public boolean isTransportKindEnabled(DuctTransportKind kind, EnumSet<DuctTransportKind> ductKinds) {
        ensureTransportEnabledMask(ductKinds);
        if (ductKinds == null || !ductKinds.contains(kind)) {
            return false;
        }
        return (transportEnabledMask & (1 << kind.ordinal())) != 0;
    }

    public void toggleTransportKind(DuctTransportKind kind, EnumSet<DuctTransportKind> ductKinds) {
        ensureTransportEnabledMask(ductKinds);
        if (ductKinds == null || !ductKinds.contains(kind)) {
            return;
        }
        int bit = 1 << kind.ordinal();
        transportEnabledMask ^= bit;
        duct.setChanged();
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        CompoundTag shared = new CompoundTag();
        shared.putByte("NodeMode", (byte) nodeMode.ordinal());
        shared.putByte("RedstoneMode", (byte) redstoneMode);
        shared.putByte("RsFmt", REDSTONE_FMT_V1);
        shared.putInt("TransportMask", transportEnabledMask);
        tag.put("Shared", shared);

        tag.put(NBT_MODULES, moduleSlots.serializeNBT(registries));
        tag.put("StallBuf", stalledBuffer.serializeNBT(registries));
        tag.put("StallFluids", saveStalledFluids(registries));
        tag.put("StallGas", saveStalledGas());
        CompoundTag stallMeta = new CompoundTag();
        stallMeta.putInt("Energy", stalledEnergyCount);
        stallMeta.putInt("Heat", stalledHeatCount);
        stallMeta.putLong("ArmFluid", armedClearFluidUntilGameTime);
        stallMeta.putLong("ArmGas", armedClearGasUntilGameTime);
        tag.put("StallMeta", stallMeta);

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
        eh.putInt("EnergyBuf", energyBufferFe);
        eh.putByte("EnergyRt", (byte) energyRoutingMode.ordinal());
        eh.putByte("EnergyRtEx", (byte) energyRoutingModeExtractor.ordinal());
        eh.putByte("EnergyRtRe", (byte) energyRoutingModeRetriever.ordinal());
        eh.putInt("HeatTicks", heatTicksUntilAction);
        eh.putInt("HeatRr", heatRoundRobinCursor);
        eh.putByte("HeatRt", (byte) heatRoutingMode.ordinal());
        eh.putByte("HeatRtEx", (byte) heatRoutingModeExtractor.ordinal());
        eh.putByte("HeatRtRe", (byte) heatRoutingModeRetriever.ordinal());
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

        if (tag.contains("StallBuf", Tag.TAG_COMPOUND)) {
            stalledBuffer.deserializeNBT(registries, tag.getCompound("StallBuf"));
        } else {
            for (int i = 0; i < stalledBuffer.getSlots(); i++) {
                stalledBuffer.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        loadStalledFluids(registries, tag);
        loadStalledGas(tag);
        if (tag.contains("StallMeta", Tag.TAG_COMPOUND)) {
            CompoundTag meta = tag.getCompound("StallMeta");
            stalledEnergyCount = meta.getInt("Energy");
            stalledHeatCount = meta.getInt("Heat");
            armedClearFluidUntilGameTime = meta.getLong("ArmFluid");
            armedClearGasUntilGameTime = meta.getLong("ArmGas");
        } else {
            stalledEnergyCount = 0;
            stalledHeatCount = 0;
            armedClearFluidUntilGameTime = 0L;
            armedClearGasUntilGameTime = 0L;
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
            energyBufferFe = eh.getInt("EnergyBuf");
            if (eh.contains("EnergyRt", Tag.TAG_BYTE)) {
                energyRoutingMode = RoutingMode.fromOrdinal(eh.getByte("EnergyRt"));
                energyRoutingModeExtractor = RoutingMode.fromOrdinal(eh.getByte("EnergyRtEx"));
                energyRoutingModeRetriever = RoutingMode.fromOrdinal(eh.getByte("EnergyRtRe"));
            } else {
                energyRoutingMode = item.routingMode;
                energyRoutingModeExtractor = item.routingModeExtractor;
                energyRoutingModeRetriever = item.routingModeRetriever;
            }
            heatTicksUntilAction = eh.getInt("HeatTicks");
            heatRoundRobinCursor = eh.getInt("HeatRr");
            if (eh.contains("HeatRt", Tag.TAG_BYTE)) {
                heatRoutingMode = RoutingMode.fromOrdinal(eh.getByte("HeatRt"));
                heatRoutingModeExtractor = RoutingMode.fromOrdinal(eh.getByte("HeatRtEx"));
                heatRoutingModeRetriever = RoutingMode.fromOrdinal(eh.getByte("HeatRtRe"));
            } else {
                heatRoutingMode = item.routingMode;
                heatRoutingModeExtractor = item.routingModeExtractor;
                heatRoutingModeRetriever = item.routingModeRetriever;
            }
        } else {
            energyTicksUntilAction = 0;
            energyRoundRobinCursor = 0;
            energyBufferFe = 0;
            energyRoutingMode = item.routingMode;
            energyRoutingModeExtractor = item.routingModeExtractor;
            energyRoutingModeRetriever = item.routingModeRetriever;
            heatTicksUntilAction = 0;
            heatRoundRobinCursor = 0;
            heatRoutingMode = item.routingMode;
            heatRoutingModeExtractor = item.routingModeExtractor;
            heatRoutingModeRetriever = item.routingModeRetriever;
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
        redstoneMode = 0;
        item.resetPipeSegmentDefaults();
        fluid.resetPipeSegmentDefaults();
        gas.resetPipeSegmentDefaults();
        for (int i = 0; i < stalledFluids.length; i++) {
            stalledFluids[i] = FluidStack.EMPTY;
            stalledGas[i] = null;
        }
        stalledEnergyCount = 0;
        stalledHeatCount = 0;
        armedClearFluidUntilGameTime = 0L;
        armedClearGasUntilGameTime = 0L;
        energyTicksUntilAction = 0;
        energyRoundRobinCursor = 0;
        energyBufferFe = 0;
        energyRoutingMode = RoutingMode.NEAREST_FIRST;
        energyRoutingModeExtractor = RoutingMode.NEAREST_FIRST;
        energyRoutingModeRetriever = RoutingMode.NEAREST_FIRST;
        heatTicksUntilAction = 0;
        heatRoundRobinCursor = 0;
        heatRoutingMode = RoutingMode.NEAREST_FIRST;
        heatRoutingModeExtractor = RoutingMode.NEAREST_FIRST;
        heatRoutingModeRetriever = RoutingMode.NEAREST_FIRST;
        transportEnabledMask = -1;
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
        transportEnabledMask = tag.contains("TransportMask", Tag.TAG_INT) ? tag.getInt("TransportMask") : -1;
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

    private ListTag saveStalledFluids(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < stalledFluids.length; i++) {
            FluidStack fs = stalledFluids[i];
            if (fs == null || fs.isEmpty()) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putByte("Slot", (byte) i);
            e.put("Fluid", (CompoundTag) fs.save(registries));
            list.add(e);
        }
        return list;
    }

    private void loadStalledFluids(HolderLookup.Provider registries, CompoundTag tag) {
        for (int i = 0; i < stalledFluids.length; i++) {
            stalledFluids[i] = FluidStack.EMPTY;
        }
        if (!tag.contains("StallFluids", Tag.TAG_LIST)) {
            return;
        }
        ListTag list = tag.getList("StallFluids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int slot = e.getByte("Slot") & 0xFF;
            if (slot < 0 || slot >= stalledFluids.length) {
                continue;
            }
            if (e.contains("Fluid", Tag.TAG_COMPOUND)) {
                stalledFluids[slot] = FluidStack.parse(registries, e.getCompound("Fluid")).orElse(FluidStack.EMPTY);
            }
        }
    }

    private ListTag saveStalledGas() {
        ListTag list = new ListTag();
        for (int i = 0; i < stalledGas.length; i++) {
            CompoundTag g = stalledGas[i];
            if (g == null || g.isEmpty()) {
                continue;
            }
            CompoundTag e = new CompoundTag();
            e.putByte("Slot", (byte) i);
            e.put("Gas", g.copy());
            list.add(e);
        }
        return list;
    }

    private void loadStalledGas(CompoundTag tag) {
        for (int i = 0; i < stalledGas.length; i++) {
            stalledGas[i] = null;
        }
        if (!tag.contains("StallGas", Tag.TAG_LIST)) {
            return;
        }
        ListTag list = tag.getList("StallGas", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            int slot = e.getByte("Slot") & 0xFF;
            if (slot < 0 || slot >= stalledGas.length) {
                continue;
            }
            if (e.contains("Gas", Tag.TAG_COMPOUND)) {
                CompoundTag g = e.getCompound("Gas");
                // Sanitize: only keep if it has some payload fields.
                if (g.contains("ChemId") || g.contains("Amt") || g.contains("Amount")) {
                    stalledGas[slot] = g.copy();
                }
            }
        }
    }
}
