package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared connection geometry: pipe faces (same {@link DuctNetworkType}) vs external attachment faces.
 * Subclasses define how non-pipe neighbors count as attachments (items, fluids, etc.).
 */
public abstract class AbstractDuctBlockEntity extends BlockEntity {
    private int pipeMask;
    private int storageMask;

    protected AbstractDuctBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected abstract DuctNetworkType networkType();

    /**
     * Bitmask for {@code dir} if the neighbor should count as an external attachment (not a duct pipe).
     * Item ducts use {@link net.neoforged.neoforge.capabilities.Capabilities.ItemHandler}.
     */
    protected abstract int attachmentMaskForNeighbor(Direction dir, BlockState neighborState, BlockPos neighborPos);

    /**
     * Invoked after {@link #refreshFromWorld()} updates masks (e.g. item node resets when geometry is pipe-only).
     */
    protected void onAfterConnectionRefresh(boolean masksChanged) {}

    public final void refreshFromWorld() {
        Level level = this.level;
        if (level == null) {
            return;
        }
        DuctNetworkType net = networkType();
        int pipe = 0;
        int storageBits = 0;
        for (Direction dir : Direction.values()) {
            BlockPos n = worldPosition.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (DuctConnectable.isSameNetwork(ns.getBlock(), net)) {
                pipe |= 1 << dir.ordinal();
            } else {
                storageBits |= attachmentMaskForNeighbor(dir, ns, n);
            }
        }
        int storage = storageBits & ~pipe;
        boolean masksChanged = pipeMask != pipe || storageMask != storage;
        pipeMask = pipe;
        storageMask = storage;
        if (masksChanged) {
            requestModelDataUpdate();
            if (!level.isClientSide()) {
                setChanged();
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
        onAfterConnectionRefresh(masksChanged);
    }

    public int getPipeMask() {
        return pipeMask;
    }

    public int getStorageMask() {
        return storageMask;
    }

    /**
     * True when at least one face touches external storage (not another duct of this network).
     */
    public boolean isStorageAttachmentNode() {
        return storageMask != 0;
    }

    protected void setConnectionMasksForLoad(int pipe, int storage) {
        this.pipeMask = pipe & 0xFF;
        this.storageMask = storage & 0xFF;
    }
}
