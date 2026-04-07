package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Overflow / refund paths for item logistics (plan cases 0–6). Never drops items.
 *
 * <p>Case mapping (high level): try external inventory on the relevant duct face first, then that duct's
 * {@link DuctOverflowBuffer}; chain to destination duct buffer when insert on source side cannot accept anything.
 */
public final class DuctOverflowRouting {
    private DuctOverflowRouting() {}

    /** Insert toward {@link OutboundShipment#refundDuct} / {@link OutboundShipment#sourceFace}; overflow on that duct. */
    public static void tryRefundToSourceNoDrop(ServerLevel level, OutboundShipment s, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack left = tryRefundInsertOnly(level, s, stack);
        if (left.isEmpty()) {
            return;
        }
        if (level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity src) {
            src.getOverflowBuffer().absorb(level, src, left.copy());
            return;
        }
        if (level.getBlockEntity(s.destDuct) instanceof DuctBlockEntity dest) {
            dest.getOverflowBuffer().absorb(level, dest, left.copy());
        }
    }

    /**
     * After extraction delivery could not insert all into destination inventory: refund insert + source overflow,
     * then destination duct overflow for any straggler (case 2 / 6).
     */
    public static void absorbExtractionDestRemainder(
            ServerLevel level, OutboundShipment s, DuctBlockEntity destBe, ItemStack remainder) {
        if (remainder.isEmpty()) {
            return;
        }
        ItemStack r = tryRefundInsertOnly(level, s, remainder.copy());
        if (!r.isEmpty() && level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity srcBe) {
            srcBe.getOverflowBuffer().absorb(level, srcBe, r.copy());
            r = ItemStack.EMPTY;
        }
        if (!r.isEmpty()) {
            destBe.getOverflowBuffer().absorb(level, destBe, r.copy());
        }
    }

    /**
     * Retriever arrival: could not insert into retriever inventory — put back toward donor chest, then donor duct buffer;
     * if anything remains (edge), retriever duct buffer (case 4 / 5).
     */
    public static void absorbRetrieverDestRemainder(
            ServerLevel level, OutboundShipment s, DuctBlockEntity retrieverBe, DuctBlockEntity donorBe, ItemStack remainder) {
        if (remainder.isEmpty()) {
            return;
        }
        ItemStack r =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, donorBe, remainder.copy())
                        : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, remainder.copy());
        if (!r.isEmpty()) {
            donorBe.getOverflowBuffer().absorb(level, donorBe, r.copy());
        }
    }

    /**
     * When return leg completes: physical items in {@code s.stack} must re-enter the world or internal buffer.
     */
    public static void finishReturnLegAbsorb(ServerLevel level, OutboundShipment s) {
        if (s.stack.isEmpty()) {
            return;
        }
        tryRefundToSourceNoDrop(level, s, s.stack.copy());
        s.stack = ItemStack.EMPTY;
    }

    private static ItemStack tryRefundInsertOnly(ServerLevel level, OutboundShipment s, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!(level.getBlockEntity(s.refundDuct) instanceof DuctBlockEntity duct)) {
            return stack.copy();
        }
        return s.legacyOmniFaces
                ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, duct, stack.copy())
                : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, stack.copy());
    }

    /** If no duct BE (chunk unloaded), park at global fallback position overflow — callers should avoid unload. */
    public static void absorbAtDuctOrVoid(ServerLevel level, BlockPos ductPos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (level.getBlockEntity(ductPos) instanceof DuctBlockEntity be) {
            be.getOverflowBuffer().absorb(level, be, stack.copy());
            be.setChanged();
        }
    }
}
