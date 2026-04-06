package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Items held on a storage-attached duct node: either traveling toward {@link #destDuct} or stuck
 * ({@link #travelTicks} {@code < 0}). {@link #refundDuct} is the node whose inventory receives refunds
 * (extractor self, or donor for retriever pulls).
 */
public final class OutboundShipment {
    public static final int STUCK_TICKS = -1;

    public ItemStack stack;
    public BlockPos destDuct;
    public int travelTicks;
    public BlockPos refundDuct;

    public OutboundShipment(ItemStack stack, BlockPos destDuct, int travelTicks, BlockPos refundDuct) {
        this.stack = stack.copy();
        this.destDuct = destDuct.immutable();
        this.travelTicks = travelTicks;
        this.refundDuct = refundDuct.immutable();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        CompoundTag st = new CompoundTag();
        stack.save(registries, st);
        t.put("Stack", st);
        t.putInt("DestX", destDuct.getX());
        t.putInt("DestY", destDuct.getY());
        t.putInt("DestZ", destDuct.getZ());
        t.putInt("Travel", travelTicks);
        t.putInt("RefundX", refundDuct.getX());
        t.putInt("RefundY", refundDuct.getY());
        t.putInt("RefundZ", refundDuct.getZ());
        return t;
    }

    public static OutboundShipment load(HolderLookup.Provider registries, CompoundTag t) {
        ItemStack s = ItemStack.parse(registries, t.getCompound("Stack")).orElse(ItemStack.EMPTY);
        BlockPos dest = new BlockPos(t.getInt("DestX"), t.getInt("DestY"), t.getInt("DestZ"));
        int travel = t.getInt("Travel");
        BlockPos refund =
                t.contains("RefundX")
                        ? new BlockPos(t.getInt("RefundX"), t.getInt("RefundY"), t.getInt("RefundZ"))
                        : dest;
        return new OutboundShipment(s, dest, travel, refund);
    }
}
