package net.unfamily.another_dynamics.duct.logistics;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

/**
 * Overflow / refund paths for item logistics (plan cases 0–6). Prefers chest insert and duct overflow buffers; only
 * then spawns an {@link ItemEntity} so nothing is deleted silently.
 *
 * <p>Case mapping (high level): try external inventory on the relevant duct face first, then that duct's
 * {@link DuctOverflowBuffer}; chain to destination duct buffer when insert on source side cannot accept anything.
 */
public final class DuctOverflowRouting {
    private DuctOverflowRouting() {}

    public static void tryRefundToSourceNoDrop(ServerLevel level, OutboundShipment s, ItemStack stack) {
        tryRefundToSourceNoDrop(level, s, stack, new BlockPos[0]);
    }

    /**
     * Insert toward {@link OutboundShipment#refundDuct} / {@link OutboundShipment#sourceFace}; then overflow buffers on
     * refund duct, dest duct, then each distinct {@code extraOverflowDucts} (e.g. owner extractor, in-transit position);
     * last resort spawn near {@link OutboundShipment#refundDuct}.
     */
    public static void tryRefundToSourceNoDrop(
            ServerLevel level, OutboundShipment s, ItemStack stack, BlockPos... extraOverflowDucts) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack left = tryRefundInsertOnly(level, s, stack.copy());
        if (left.isEmpty()) {
            return;
        }
        Set<BlockPos> tried = new HashSet<>();
        tryAbsorbIntoNearestDuctBuffer(level, left, tried, s.refundDuct);
        if (left.isEmpty()) {
            return;
        }
        tryAbsorbIntoNearestDuctBuffer(level, left, tried, s.destDuct);
        if (left.isEmpty()) {
            return;
        }
        for (BlockPos p : extraOverflowDucts) {
            if (p == null) {
                continue;
            }
            tryAbsorbIntoNearestDuctBuffer(level, left, tried, p);
            if (left.isEmpty()) {
                return;
            }
        }
        lastResortSpawnItemAt(level, s.refundDuct, left);
    }

    private static void tryAbsorbIntoNearestDuctBuffer(
            ServerLevel level, ItemStack left, Set<BlockPos> tried, BlockPos ductPos) {
        if (left.isEmpty() || tried.contains(ductPos)) {
            return;
        }
        tried.add(ductPos.immutable());
        if (level.getBlockEntity(ductPos) instanceof DuctBlockEntity be) {
            be.getOverflowBuffer().absorb(level, be, left.copy());
            left.setCount(0);
            be.setChanged();
        }
    }

    /**
     * After extraction delivery could not insert all into destination inventory: refund insert + source overflow,
     * then destination duct overflow for any straggler (case 2 / 6).
     */
    public static void absorbExtractionDestRemainder(
            ServerLevel level,
            OutboundShipment s,
            DuctBlockEntity destBe,
            ItemStack remainder,
            BlockPos ownerScheduleDuct) {
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
            r = ItemStack.EMPTY;
        }
        if (!r.isEmpty()) {
            tryRefundToSourceNoDrop(level, s, r, ownerScheduleDuct, destBe.getBlockPos());
        }
    }

    /**
     * Retriever arrival: could not insert into retriever inventory — put back toward donor chest, then donor duct buffer;
     * if anything remains (edge), retriever duct buffer (case 4 / 5).
     */
    public static void absorbRetrieverDestRemainder(
            ServerLevel level,
            OutboundShipment s,
            DuctBlockEntity retrieverBe,
            DuctBlockEntity donorBe,
            ItemStack remainder,
            BlockPos scheduleOwnerDuct) {
        if (remainder.isEmpty()) {
            return;
        }
        ItemStack r =
                s.legacyOmniFaces
                        ? DuctCapHelper.insertIntoStorageFaces(level, s.refundDuct, donorBe, remainder.copy())
                        : DuctCapHelper.insertIntoFace(level, s.refundDuct, s.sourceFace, remainder.copy());
        if (!r.isEmpty()) {
            donorBe.getOverflowBuffer().absorb(level, donorBe, r.copy());
            r = ItemStack.EMPTY;
        }
        if (!r.isEmpty()) {
            retrieverBe.getOverflowBuffer().absorb(level, retrieverBe, r.copy());
            r = ItemStack.EMPTY;
        }
        if (!r.isEmpty()) {
            tryRefundToSourceNoDrop(level, s, r, scheduleOwnerDuct, retrieverBe.getBlockPos(), donorBe.getBlockPos());
        }
    }

    /**
     * When return leg completes: physical items in {@code s.stack} must re-enter inventory, overflow, or world.
     */
    public static void finishReturnLegAbsorb(ServerLevel level, OutboundShipment s, BlockPos ownerDuct) {
        if (s.stack.isEmpty()) {
            return;
        }
        tryRefundToSourceNoDrop(level, s, s.stack.copy(), ownerDuct);
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

    /**
     * Overflow on {@code ductPos} if loaded; else {@link #lastResortSpawnItemAt}. Prefer
     * {@link #tryRefundToSourceNoDrop(ServerLevel, OutboundShipment, ItemStack, BlockPos...)} when a shipment is available.
     */
    public static void absorbAtDuctOrVoid(ServerLevel level, BlockPos ductPos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (level.getBlockEntity(ductPos) instanceof DuctBlockEntity be) {
            be.getOverflowBuffer().absorb(level, be, stack.copy());
            be.setChanged();
        } else {
            lastResortSpawnItemAt(level, ductPos, stack);
        }
    }

    private static void lastResortSpawnItemAt(ServerLevel level, BlockPos near, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        double x = near.getX() + 0.5;
        double y = near.getY() + 0.25;
        double z = near.getZ() + 0.5;
        ItemEntity entity = new ItemEntity(level, x, y, z, stack.copy());
        entity.setPickUpDelay(10);
        entity.setDeltaMovement(Vec3.ZERO);
        level.addFreshEntity(entity);
    }
}
