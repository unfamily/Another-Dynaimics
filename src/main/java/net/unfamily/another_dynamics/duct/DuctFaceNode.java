package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;

/**
 * Per-face attachment state on a {@link DuctBlockEntity}. A hybrid duct may eventually host several transport lanes
 * per direction (e.g. item + fluid); today this holds the <strong>item</strong> node only (modes, GUI slots, tick cadence).
 */
public final class DuctFaceNode {
    public NodeMode nodeMode = NodeMode.NONE;
    public RoutingMode routingMode = RoutingMode.NEAREST_FIRST;
    /**
     * Extract modes: batch size. Insert modes (NONE, FILTERING_INSERTION): insertion priority.
     */
    public int amountField;
    public int channelLetter = 1;
    public int redstoneMode;
    public int roundRobinCursor;
    public int ticksUntilAction;

    public final ItemStackHandler guiSlots;

    public DuctFaceNode(Runnable onChanged) {
        this.guiSlots =
                new ItemStackHandler(DuctNodeMenu.MACHINE_SLOTS) {
                    @Override
                    protected void onContentsChanged(int slot) {
                        onChanged.run();
                    }
                };
    }

    public void resetPipeSegmentDefaults() {
        nodeMode = NodeMode.NONE;
        amountField = 0;
        roundRobinCursor = 0;
    }

    public void save(HolderLookup.Provider registries, CompoundTag tag) {
        tag.putByte("NodeMode", (byte) nodeMode.ordinal());
        tag.putByte("RoutingMode", (byte) routingMode.ordinal());
        tag.putInt("AmountField", amountField);
        tag.putByte("Channel", (byte) channelLetter);
        tag.putByte("RedstoneMode", (byte) redstoneMode);
        tag.putInt("RrCursor", roundRobinCursor);
        tag.putInt("TicksAct", ticksUntilAction);
        tag.put("NodeGui", guiSlots.serializeNBT(registries));
    }

    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        nodeMode = NodeMode.fromOrdinal(tag.getByte("NodeMode"));
        routingMode = RoutingMode.fromOrdinal(tag.getByte("RoutingMode"));
        if (tag.contains("AmountField")) {
            amountField = tag.getInt("AmountField");
        } else if (tag.contains("InsPriority")) {
            amountField = tag.getInt("InsPriority");
        }
        channelLetter = tag.contains("Channel") ? tag.getByte("Channel") & 0xFF : 1;
        redstoneMode = tag.getByte("RedstoneMode") & 0xFF;
        roundRobinCursor = tag.getInt("RrCursor");
        ticksUntilAction = tag.contains("TicksAct") ? tag.getInt("TicksAct") : 0;
        if (tag.contains("NodeGui", Tag.TAG_COMPOUND)) {
            guiSlots.deserializeNBT(registries, tag.getCompound("NodeGui"));
        }
    }

    /** Copy legacy single-node NBT into this face (world upgrade). */
    public void loadFromLegacyRootTag(HolderLookup.Provider registries, CompoundTag root) {
        if (root.contains("NodeGui", Tag.TAG_COMPOUND)) {
            guiSlots.deserializeNBT(registries, root.getCompound("NodeGui"));
        }
        nodeMode = NodeMode.fromOrdinal(root.getByte("NodeMode"));
        routingMode = RoutingMode.fromOrdinal(root.getByte("RoutingMode"));
        if (root.contains("AmountField")) {
            amountField = root.getInt("AmountField");
        } else if (root.contains("InsPriority")) {
            amountField = root.getInt("InsPriority");
        }
        channelLetter = root.contains("Channel") ? root.getByte("Channel") & 0xFF : 1;
        redstoneMode = root.getByte("RedstoneMode") & 0xFF;
        roundRobinCursor = root.getInt("RrCursor");
        ticksUntilAction = 0;
    }
}
