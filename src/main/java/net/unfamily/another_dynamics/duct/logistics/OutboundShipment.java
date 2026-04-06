package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Pending item move: {@link #stack} is the planned kind + count (items stay in source inventory until delivery).
 * {@link #sourceFace} / {@link #destFace} select which attached inventories on source/dest ducts are used.
 * {@link #legacyOmniFaces} uses pre-face-aware behaviour (all storage faces) for old saves.
 */
public final class OutboundShipment {
    public ItemStack stack;
    public ItemStack registeredIncoming;
    public BlockPos destDuct;
    public Direction destFace;
    public int travelTicks;
    public BlockPos refundDuct;
    public Direction sourceFace;
    public boolean legacyPhysicalBuffer;
    public boolean legacyOmniFaces;

    public OutboundShipment(
            ItemStack plannedStack,
            BlockPos destDuct,
            Direction destFace,
            int travelTicks,
            BlockPos refundDuct,
            Direction sourceFace) {
        this.stack = plannedStack.copy();
        this.registeredIncoming = this.stack.copy();
        this.destDuct = destDuct.immutable();
        this.destFace = destFace;
        this.travelTicks = travelTicks;
        this.refundDuct = refundDuct.immutable();
        this.sourceFace = sourceFace;
        this.legacyPhysicalBuffer = false;
        this.legacyOmniFaces = false;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        CompoundTag st = new CompoundTag();
        stack.save(registries, st);
        t.put("Stack", st);
        t.putInt("DestX", destDuct.getX());
        t.putInt("DestY", destDuct.getY());
        t.putInt("DestZ", destDuct.getZ());
        t.putByte("DstF", (byte) destFace.ordinal());
        t.putInt("Travel", travelTicks);
        t.putInt("RefundX", refundDuct.getX());
        t.putInt("RefundY", refundDuct.getY());
        t.putInt("RefundZ", refundDuct.getZ());
        t.putByte("SrcF", (byte) sourceFace.ordinal());
        t.putBoolean("PlannedOnly", !legacyPhysicalBuffer);
        t.putBoolean("OmniLegacy", legacyOmniFaces);
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
        Direction destFace = t.contains("DstF") ? dirFromSaveByte(t.getByte("DstF")) : Direction.DOWN;
        Direction srcFace = t.contains("SrcF") ? dirFromSaveByte(t.getByte("SrcF")) : Direction.DOWN;
        OutboundShipment sh = new OutboundShipment(s, dest, destFace, travel, refund, srcFace);
        sh.legacyPhysicalBuffer = !t.contains("PlannedOnly") || !t.getBoolean("PlannedOnly");
        sh.legacyOmniFaces = !t.contains("DstF") || t.getBoolean("OmniLegacy");
        return sh;
    }

    private static Direction dirFromSaveByte(byte b) {
        int o = b & 0xFF;
        return o >= 0 && o < 6 ? Direction.values()[o] : Direction.DOWN;
    }
}
