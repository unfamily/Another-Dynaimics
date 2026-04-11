package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Per-face transport lanes. Item, fluid, and gas keep separate filters, channels, routing fields, and amount fields.
 * <p>
 * <strong>Shared for the whole face</strong>: {@link #nodeMode}, {@link #redstoneMode}, and {@link #upgradeSlots}
 * (one upgrade column for all item/fluid/gas lanes). Each lane keeps its own copy-settings slot in
 * {@link DuctFaceNode#guiSlots}.
 */
public final class DuctFaceLanes {
    private static final byte REDSTONE_FMT_V1 = 1;

    private final Runnable onChanged;

    public NodeMode nodeMode = NodeMode.NONE;
    /**
     * 0 = ignored (always enabled), 1 = low (enabled when NOT powered), 2 = high (enabled when powered), 3 = disabled.
     */
    public int redstoneMode = 3;

    /** Shared upgrade column; size follows {@link DuctDefinition#upgradeSlotCount()} (clamped). */
    public ItemStackHandler upgradeSlots;

    public final DuctFaceNode item;
    public final DuctFaceNode fluid;
    public final DuctFaceNode gas;

    public DuctFaceLanes(Runnable onChanged, int upgradeSlotCount) {
        this.onChanged = onChanged;
        int n = Math.max(0, upgradeSlotCount);
        this.upgradeSlots = createUpgradeHandler(n);
        this.item = new DuctFaceNode(onChanged);
        this.fluid = new DuctFaceNode(onChanged);
        this.gas = new DuctFaceNode(onChanged);
    }

    private ItemStackHandler createUpgradeHandler(int size) {
        return new ItemStackHandler(size) {
            @Override
            protected void onContentsChanged(int slot) {
                onChanged.run();
            }
        };
    }

    /**
     * Resizes the shared upgrade handler, preserving stacks in the overlapping prefix.
     */
    public void resizeUpgradeSlots(int want) {
        want = Math.max(0, want);
        if (upgradeSlots.getSlots() == want) {
            return;
        }
        ItemStackHandler prev = upgradeSlots;
        upgradeSlots = createUpgradeHandler(want);
        for (int i = 0; i < Math.min(want, prev.getSlots()); i++) {
            upgradeSlots.setStackInSlot(i, prev.getStackInSlot(i).copy());
        }
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        CompoundTag shared = new CompoundTag();
        shared.putByte("NodeMode", (byte) nodeMode.ordinal());
        shared.putByte("RedstoneMode", (byte) redstoneMode);
        shared.putByte("RsFmt", REDSTONE_FMT_V1);
        tag.put("Shared", shared);

        tag.put("Upgrades", upgradeSlots.serializeNBT(registries));

        CompoundTag itemTag = new CompoundTag();
        item.save(registries, itemTag);
        tag.put("Item", itemTag);
        CompoundTag fluidTag = new CompoundTag();
        fluid.save(registries, fluidTag);
        tag.put("Fluid", fluidTag);
        CompoundTag gasTag = new CompoundTag();
        gas.save(registries, gasTag);
        tag.put("Gas", gasTag);
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        if (tag.contains("Shared", Tag.TAG_COMPOUND)) {
            loadShared(tag.getCompound("Shared"));
        } else {
            CompoundTag itemLike = tag.contains("Item", Tag.TAG_COMPOUND) ? tag.getCompound("Item") : tag;
            loadSharedFromLegacyNodeTag(itemLike);
        }

        // Upgrade column size must match {@link DuctBlockEntity#ensureFaceLaneUpgradeSlotCapacitiesMatchDefinition()}
        // before load (logical duct id). Do not resize to GUI max here or reload shrinks stacks to the wrong cap.
        if (tag.contains("Upgrades", Tag.TAG_COMPOUND)) {
            upgradeSlots.deserializeNBT(registries, tag.getCompound("Upgrades"));
        } else {
            migrateLegacyUpgradesFromItemNodeGui(registries, tag);
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
    }

    private void migrateLegacyUpgradesFromItemNodeGui(HolderLookup.Provider registries, CompoundTag faceTag) {
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
        ItemStackHandler legacy = new ItemStackHandler(6);
        legacy.deserializeNBT(registries, nodeGuiHost.getCompound("NodeGui"));
        int limit = Math.min(5, upgradeSlots.getSlots());
        for (int i = 0; i < limit; i++) {
            upgradeSlots.setStackInSlot(i, legacy.getStackInSlot(i).copy());
        }
    }

    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root) {
        loadSharedFromLegacyNodeTag(root);
        if (!root.contains("Upgrades", Tag.TAG_COMPOUND)) {
            migrateLegacyUpgradesFromItemNodeGui(registries, root);
        } else {
            upgradeSlots.deserializeNBT(registries, root.getCompound("Upgrades"));
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
    }

    public void clampFilterSizes(DuctItemTransportSpec itemSpec, DuctFluidTransportSpec fluidSpec, DuctGasTransportSpec gasSpec) {
        item.clampFilterSizes(itemSpec, nodeMode);
        fluid.clampFilterSizes(fluidSpec, nodeMode);
        gas.clampFilterSizes(gasSpec, nodeMode);
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
