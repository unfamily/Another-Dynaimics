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
    /** Per-face manual disconnect (wrench): excluded from pipe adjacency and storage attachment until cleared via core click. */
    private int userDisconnectedFaceMask;

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

    /**
     * After world storage mask is recomputed, subclasses may OR bits into a persistent latch (e.g. faces that ever had
     * storage) for visuals while disconnected.
     */
    protected void mergePersistentStorageFaceLatch(int previousWorldStorageMask, int newWorldStorageMask) {}

    public final void refreshFromWorld() {
        Level level = this.level;
        if (level == null) {
            return;
        }
        DuctNetworkType net = networkType();
        int pipe = 0;
        int storageBits = 0;
        for (Direction dir : Direction.values()) {
            if ((userDisconnectedFaceMask & (1 << dir.ordinal())) != 0) {
                continue;
            }
            BlockPos n = worldPosition.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (net == DuctNetworkType.ITEM) {
                if (DuctPipeAdjacency.areItemPipeNeighbors(level, worldPosition, n)) {
                    pipe |= 1 << dir.ordinal();
                } else {
                    storageBits |= attachmentMaskForNeighbor(dir, ns, n);
                }
            } else if (DuctConnectable.isSameNetwork(ns.getBlock(), net)) {
                pipe |= 1 << dir.ordinal();
            } else {
                storageBits |= attachmentMaskForNeighbor(dir, ns, n);
            }
        }
        int storage = storageBits & ~pipe;
        int previousWorldStorageMask = storageMask;
        boolean masksChanged = pipeMask != pipe || storageMask != storage;
        pipeMask = pipe;
        storageMask = storage;
        mergePersistentStorageFaceLatch(previousWorldStorageMask, storage);
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
     * Storage bits for collision, outline, baked model, and node hit-pick: live world attachments only (same as
     * {@link #getStorageMask()}). Persisted node settings without a neighbor stay in subclasses (e.g. latch) but do not
     * add voxels.
     */
    public int getVisualStorageMask() {
        return storageMask;
    }

    /**
     * True when at least one face touches external storage (not another duct of this network).
     */
    public boolean isStorageAttachmentNode() {
        return storageMask != 0;
    }

    public int getUserDisconnectedFaceMask() {
        return userDisconnectedFaceMask;
    }

    protected void setUserDisconnectedFaceMaskForLoad(int mask) {
        this.userDisconnectedFaceMask = mask & 0xFF;
    }

    protected void orUserDisconnectedFace(Direction face) {
        userDisconnectedFaceMask |= 1 << face.ordinal();
    }

    protected boolean clearUserDisconnectedFace(Direction face) {
        int bit = 1 << face.ordinal();
        if ((userDisconnectedFaceMask & bit) == 0) {
            return false;
        }
        userDisconnectedFaceMask &= ~bit;
        return true;
    }

    protected void setConnectionMasksForLoad(int pipe, int storage) {
        this.pipeMask = pipe & 0xFF;
        this.storageMask = storage & 0xFF;
    }
}
