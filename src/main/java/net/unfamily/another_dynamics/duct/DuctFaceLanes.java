package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Per-face transport lanes. Item and fluid keep separate filters, channels, routing fields, upgrades, and amount fields.
 * <p>
 * <strong>Shared for the whole face</strong> (same for every enabled {@link DuctTransportKind}): {@link #nodeMode} and
 * {@link #redstoneMode}. Tick cadence is per lane ({@link DuctFaceNode#ticksUntilAction}) so item and fluid rates stay independent.
 */
public final class DuctFaceLanes {
    private static final byte REDSTONE_FMT_V1 = 1;

    public NodeMode nodeMode = NodeMode.NONE;
    /**
     * 0 = ignored (always enabled), 1 = low (enabled when NOT powered), 2 = high (enabled when powered), 3 = disabled.
     */
    public int redstoneMode = 3;

    public final DuctFaceNode item;
    public final DuctFaceNode fluid;
    public final DuctFaceNode gas;

    public DuctFaceLanes(Runnable onChanged) {
        this.item = new DuctFaceNode(onChanged);
        this.fluid = new DuctFaceNode(onChanged);
        this.gas = new DuctFaceNode(onChanged);
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        CompoundTag shared = new CompoundTag();
        shared.putByte("NodeMode", (byte) nodeMode.ordinal());
        shared.putByte("RedstoneMode", (byte) redstoneMode);
        shared.putByte("RsFmt", REDSTONE_FMT_V1);
        tag.put("Shared", shared);

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

    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root) {
        loadSharedFromLegacyNodeTag(root);
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
            if (redstoneMode == 3) {
                redstoneMode = 0;
            } else if (redstoneMode >= 4) {
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
            if (redstoneMode == 3) {
                redstoneMode = 0;
            } else if (redstoneMode >= 4) {
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
