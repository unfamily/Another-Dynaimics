package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.items.IItemHandler;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

public final class ItemDuctBlockEntity extends BlockEntity implements MenuProvider {
    private int pipeMask;
    private int storageMask;

    public ItemDuctBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ITEM_DUCT.get(), pos, state);
    }

    /** Client-only: keeps visuals in sync when neighbors appear before {@code neighborChanged} runs (e.g. new duct next to existing). */
    public static void clientTick(Level level, BlockPos pos, BlockState state, ItemDuctBlockEntity be) {
        be.refreshFromWorld();
    }

    /**
     * Recomputes pipe/storage masks from the world. Server persists and syncs; client updates only for rendering
     * (avoids waiting for a neighbor block update packet).
     */
    public void refreshFromWorld() {
        if (level == null) {
            return;
        }
        int pipe = 0;
        int storage = 0;
        for (Direction dir : Direction.values()) {
            BlockPos n = worldPosition.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (ns.getBlock() instanceof ItemDuctBlock) {
                pipe |= 1 << dir.ordinal();
            } else if (!ns.isAir()) {
                IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, n, dir.getOpposite());
                if (cap != null && cap.getSlots() > 0) {
                    storage |= 1 << dir.ordinal();
                }
            }
        }
        storage &= ~pipe;
        if (pipeMask == pipe && storageMask == storage) {
            return;
        }
        pipeMask = pipe;
        storageMask = storage;
        requestModelDataUpdate();
        if (!level.isClientSide) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("PipeMask", (byte) pipeMask);
        tag.putByte("StorageMask", (byte) storageMask);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pipeMask = tag.getByte("PipeMask") & 0xFF;
        storageMask = tag.getByte("StorageMask") & 0xFF;
        requestModelDataUpdate();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putByte("PipeMask", (byte) pipeMask);
        tag.putByte("StorageMask", (byte) storageMask);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder()
                .with(DuctModelProperties.PIPE_MASK, pipeMask)
                .with(DuctModelProperties.STORAGE_MASK, storageMask)
                .build();
    }

    public int getPipeMask() {
        return pipeMask;
    }

    public int getStorageMask() {
        return storageMask;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.another_dynamics.duct_node");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new DuctNodeMenu(containerId, playerInventory, this);
    }
}
