package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctChannelPolicy;
import java.util.Optional;

/**
 * Pending item move: {@link #stack} is the payload and count. Modern ducts set {@link #sourceExtractCommitted} at
 * schedule time (same as fluid/gas drain-at-schedule). When {@code sourceExtractCommitted} is false, items stay in
 * source storage until {@code finish*} runs (extract-at-delivery). Legacy saves may still use committed or
 * {@link #legacyPhysicalBuffer} paths.
 * {@link #sourceFace} / {@link #destFace} select which attached inventories on source/dest ducts are used.
 * {@link #transportChannel} is {@link net.unfamily.another_dynamics.duct.DuctFaceNode} letter (1–26) for both ends;
 * {@link DuctChannelPolicy#LEGACY_WILDCARD}
 * from old saves skips enforcement.
 * {@link #legacyOmniFaces} uses pre-face-aware behaviour (all storage faces) for old saves.
 */
public final class OutboundShipment {
    public ItemStack stack;
    public ItemStack registeredIncoming;
    /** Id used to track this shipment's reservation entry in {@link DuctIncomingIndex}. */
    public long incomingReservationId;
    public BlockPos destDuct;
    public Direction destFace;
    public int travelTicks;
    public BlockPos refundDuct;
    public Direction sourceFace;
    /** See {@link DuctChannelPolicy}. */
    public int transportChannel;
    public boolean legacyPhysicalBuffer;
    public boolean legacyOmniFaces;
    /** True when {@link #stack} was physically extracted from source at schedule time (modern duct queue). */
    public boolean sourceExtractCommitted;

    /** Current leg phase for visuals. For planned-only shipments, RETURN is purely visual. */
    public TransitPhase transitPhase = TransitPhase.FORWARD;

    /** Active path for the current leg (ordered start -> end, inclusive). */
    public List<BlockPos> ductPath = List.of();

    /** Total ticks for the current leg (initial value of {@link #travelTicks}). */
    public int totalTravelTicks;

    /** Ticks per path node (usually {@link DuctPathfinder#edgeTravelTicks}). */
    public int edgeTicks = 1;

    /** Server game time when this leg started (for client smoothing). */
    public long journeyStartGameTime;

    /** Ticks remaining before retrying delivery after a transient destination failure. */
    public int deliveryDeferTicks;

    /** Total defer ticks consumed on this leg (budget cap prevents infinite wait at destination). */
    public int deliveryDeferSpent;

    public OutboundShipment(
            ItemStack plannedStack,
            BlockPos destDuct,
            Direction destFace,
            int travelTicks,
            BlockPos refundDuct,
            Direction sourceFace,
            int transportChannel) {
        this.stack = plannedStack.copy();
        this.registeredIncoming = this.stack.copy();
        this.incomingReservationId = DuctIncomingIndex.newReservationId();
        this.destDuct = destDuct.immutable();
        this.destFace = destFace;
        this.travelTicks = travelTicks;
        this.refundDuct = refundDuct.immutable();
        this.sourceFace = sourceFace;
        this.transportChannel = transportChannel;
        this.legacyPhysicalBuffer = false;
        this.legacyOmniFaces = false;
        this.totalTravelTicks = travelTicks;
        this.sourceExtractCommitted = false;
    }

    public static List<BlockPos> copyPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> out = new ArrayList<>(path.size());
        for (BlockPos p : path) {
            out.add(p.immutable());
        }
        return Collections.unmodifiableList(out);
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
        t.putInt("TC", transportChannel);
        t.putBoolean("PlannedOnly", !legacyPhysicalBuffer);
        t.putBoolean("OmniLegacy", legacyOmniFaces);
        t.putByte("Phase", (byte) transitPhase.ordinal());
        t.putInt("TotTr", totalTravelTicks);
        t.putInt("EdgeW", edgeTicks);
        t.putLong("JStart", journeyStartGameTime);
        ListTag plist = new ListTag();
        for (BlockPos p : ductPath) {
            CompoundTag pt = new CompoundTag();
            pt.putInt("X", p.getX());
            pt.putInt("Y", p.getY());
            pt.putInt("Z", p.getZ());
            plist.add(pt);
        }
        t.put("DuctPath", plist);
        t.putBoolean("SrcXfr", sourceExtractCommitted);
        t.putLong("InId", incomingReservationId);
        Identifier itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey != null) {
            t.putString("FbItem", itemKey.toString());
            t.putInt("FbCnt", stack.getCount());
        }
        return t;
    }

    public static OutboundShipment load(HolderLookup.Provider registries, CompoundTag t) {
        ItemStack s = parsePlannedStack(registries, t);
        BlockPos dest = new BlockPos(t.getIntOr("DestX", 0), t.getIntOr("DestY", 0), t.getIntOr("DestZ", 0));
        int travel = t.getIntOr("Travel", 0);
        BlockPos refund =
                t.contains("RefundX")
                        ? new BlockPos(t.getIntOr("RefundX", 0), t.getIntOr("RefundY", 0), t.getIntOr("RefundZ", 0))
                        : dest;
        Direction destFace = t.contains("DstF") ? dirFromSaveByte(t.getByteOr("DstF", (byte) 0)) : Direction.DOWN;
        Direction srcFace = t.contains("SrcF") ? dirFromSaveByte(t.getByteOr("SrcF", (byte) 0)) : Direction.DOWN;
        int tc = t.contains("TC") ? t.getIntOr("TC", 0) : DuctChannelPolicy.LEGACY_WILDCARD;
        OutboundShipment sh = new OutboundShipment(s, dest, destFace, travel, refund, srcFace, tc);
        sh.legacyPhysicalBuffer = !t.contains("PlannedOnly") || !t.getBooleanOr("PlannedOnly", false);
        sh.legacyOmniFaces = !t.contains("DstF") || t.getBooleanOr("OmniLegacy", false);
        if (t.contains("Phase")) {
            sh.transitPhase = TransitPhase.fromOrdinal(t.getByteOr("Phase", (byte) 0));
        }
        if (t.contains("TotTr")) {
            sh.totalTravelTicks = t.getIntOr("TotTr", 0);
        } else {
            sh.totalTravelTicks = travel;
        }
        if (t.contains("EdgeW")) {
            sh.edgeTicks = Math.max(0, t.getIntOr("EdgeW", 0));
        }
        if (t.contains("JStart")) {
            sh.journeyStartGameTime = t.getLongOr("JStart", 0L);
        }
        if (t.contains("DuctPath")) {
            ListTag list = t.getListOrEmpty("DuctPath");
            ArrayList<BlockPos> path = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pt = list.getCompoundOrEmpty(i);
                path.add(new BlockPos(pt.getIntOr("X", 0), pt.getIntOr("Y", 0), pt.getIntOr("Z", 0)).immutable());
            }
            sh.ductPath = Collections.unmodifiableList(path);
        } else if (!refund.equals(dest)) {
            // Backward compatible: minimal two-node path for visuals.
            sh.ductPath = List.of(refund.immutable(), dest.immutable());
        } else {
            sh.ductPath = List.of(refund.immutable());
        }
        sh.registeredIncoming = sh.stack.copy();
        sh.sourceExtractCommitted = t.getBooleanOr("SrcXfr", false);
        sh.incomingReservationId = t.contains("InId") ? t.getLongOr("InId", 0L) : DuctIncomingIndex.newReservationId();
        return sh;
    }

    /**
     * Restores the template stack for a planned shipment. Primary path is {@link ItemStack#parse}; adds fallbacks so
     * in-flight tasks are not dropped on world reload when the codec path returns empty (provider/registry edge cases).
     */
    private static ItemStack parsePlannedStack(HolderLookup.Provider registries, CompoundTag root) {
        CompoundTag stackTag = root.getCompoundOrEmpty("Stack");
        if (!stackTag.isEmpty()) {
            Optional<ItemStack> primary = ItemStack.parse(registries, stackTag);
            if (primary.isPresent() && !primary.get().isEmpty()) {
                return primary.get().copy();
            }
            ItemStack legacy = tryLegacyIdCountStack(registries, stackTag);
            if (!legacy.isEmpty()) {
                return legacy;
            }
        }
        if (root.contains("FbItem")) {
            return fromRegistryItemId(registries, root.getStringOr("FbItem", ""), root.getIntOr("FbCnt", 0));
        }
        return ItemStack.EMPTY;
    }

    /** Handles compact {@code id} + {@code count}/{@code Count} compounds (older or alternate serialization). */
    private static ItemStack tryLegacyIdCountStack(HolderLookup.Provider registries, CompoundTag stackTag) {
        if (!stackTag.contains("id")) {
            return ItemStack.EMPTY;
        }
        String id = stackTag.getStringOr("id", "");
        int c = 1;
        if (stackTag.contains("count")) {
            c = stackTag.getIntOr("count", 0);
        } else if (stackTag.contains("Count")) {
            c = stackTag.getIntOr("Count", 0);
        }
        return fromRegistryItemId(registries, id, c);
    }

    private static ItemStack fromRegistryItemId(HolderLookup.Provider registries, String idStr, int count) {
        if (idStr == null || idStr.isEmpty() || count <= 0 || registries == null) {
            return ItemStack.EMPTY;
        }
        Identifier rl = Identifier.tryParse(idStr);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        try {
            Optional<Holder.Reference<Item>> holder =
                    registries.lookupOrThrow(Registries.ITEM).get(ResourceKey.create(Registries.ITEM, rl));
            if (holder.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(holder.get(), count);
        } catch (RuntimeException ex) {
            return ItemStack.EMPTY;
        }
    }

    private static Direction dirFromSaveByte(byte b) {
        int o = b & 0xFF;
        return o >= 0 && o < 6 ? Direction.values()[o] : Direction.DOWN;
    }

    public int elapsedOnLeg() {
        return Math.max(0, totalTravelTicks - travelTicks);
    }

    public int currentPathIndex() {
        if (ductPath.isEmpty()) {
            return 0;
        }
        if (edgeTicks <= 0) {
            return ductPath.size() - 1;
        }
        int idx = elapsedOnLeg() / edgeTicks;
        return Math.min(ductPath.size() - 1, idx);
    }

    public void resetLeg(TransitPhase phase, List<BlockPos> path, int totalTicks, int edgeTicks, long gameTime) {
        this.transitPhase = phase;
        this.ductPath = copyPath(path);
        this.totalTravelTicks = totalTicks;
        this.travelTicks = totalTicks;
        this.edgeTicks = Math.max(0, edgeTicks);
        this.journeyStartGameTime = gameTime;
    }
}
