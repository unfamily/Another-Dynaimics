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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctChannelPolicy;
import java.util.Optional;

/**
 * Pending item move: {@link #stack} is the planned template and count. Items stay in the source storage until
 * {@code finish*} runs (extract-at-delivery with {@code n = min(planned, source, dest)}). When
 * {@link #sourceExtractCommitted} is true (NBT {@code SrcXfr} from older worlds), items were removed at schedule time
 * and the legacy committed path applies until those tasks complete.
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
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey != null) {
            t.putString("FbItem", itemKey.toString());
            t.putInt("FbCnt", stack.getCount());
        }
        return t;
    }

    public static OutboundShipment load(HolderLookup.Provider registries, CompoundTag t) {
        ItemStack s = parsePlannedStack(registries, t);
        BlockPos dest = new BlockPos(t.getInt("DestX"), t.getInt("DestY"), t.getInt("DestZ"));
        int travel = t.getInt("Travel");
        BlockPos refund =
                t.contains("RefundX")
                        ? new BlockPos(t.getInt("RefundX"), t.getInt("RefundY"), t.getInt("RefundZ"))
                        : dest;
        Direction destFace = t.contains("DstF") ? dirFromSaveByte(t.getByte("DstF")) : Direction.DOWN;
        Direction srcFace = t.contains("SrcF") ? dirFromSaveByte(t.getByte("SrcF")) : Direction.DOWN;
        int tc = t.contains("TC") ? t.getInt("TC") : DuctChannelPolicy.LEGACY_WILDCARD;
        OutboundShipment sh = new OutboundShipment(s, dest, destFace, travel, refund, srcFace, tc);
        sh.legacyPhysicalBuffer = !t.contains("PlannedOnly") || !t.getBoolean("PlannedOnly");
        sh.legacyOmniFaces = !t.contains("DstF") || t.getBoolean("OmniLegacy");
        if (t.contains("Phase", Tag.TAG_BYTE)) {
            sh.transitPhase = TransitPhase.fromOrdinal(t.getByte("Phase"));
        }
        if (t.contains("TotTr", Tag.TAG_INT)) {
            sh.totalTravelTicks = t.getInt("TotTr");
        } else {
            sh.totalTravelTicks = travel;
        }
        if (t.contains("EdgeW", Tag.TAG_INT)) {
            sh.edgeTicks = Math.max(0, t.getInt("EdgeW"));
        }
        if (t.contains("JStart", Tag.TAG_LONG)) {
            sh.journeyStartGameTime = t.getLong("JStart");
        }
        if (t.contains("DuctPath", Tag.TAG_LIST)) {
            ListTag list = t.getList("DuctPath", Tag.TAG_COMPOUND);
            ArrayList<BlockPos> path = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pt = list.getCompound(i);
                path.add(new BlockPos(pt.getInt("X"), pt.getInt("Y"), pt.getInt("Z")).immutable());
            }
            sh.ductPath = Collections.unmodifiableList(path);
        } else if (!refund.equals(dest)) {
            // Backward compatible: minimal two-node path for visuals.
            sh.ductPath = List.of(refund.immutable(), dest.immutable());
        } else {
            sh.ductPath = List.of(refund.immutable());
        }
        sh.registeredIncoming = sh.stack.copy();
        sh.sourceExtractCommitted = t.getBoolean("SrcXfr");
        sh.incomingReservationId = t.contains("InId", Tag.TAG_LONG) ? t.getLong("InId") : DuctIncomingIndex.newReservationId();
        return sh;
    }

    /**
     * Restores the template stack for a planned shipment. Primary path is {@link ItemStack#parse}; adds fallbacks so
     * in-flight tasks are not dropped on world reload when the codec path returns empty (provider/registry edge cases).
     */
    private static ItemStack parsePlannedStack(HolderLookup.Provider registries, CompoundTag root) {
        CompoundTag stackTag = root.getCompound("Stack");
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
        if (root.contains("FbItem", Tag.TAG_STRING)) {
            return fromRegistryItemId(registries, root.getString("FbItem"), root.getInt("FbCnt"));
        }
        return ItemStack.EMPTY;
    }

    /** Handles compact {@code id} + {@code count}/{@code Count} compounds (older or alternate serialization). */
    private static ItemStack tryLegacyIdCountStack(HolderLookup.Provider registries, CompoundTag stackTag) {
        if (!stackTag.contains("id", Tag.TAG_STRING)) {
            return ItemStack.EMPTY;
        }
        String id = stackTag.getString("id");
        int c = 1;
        if (stackTag.contains("count", Tag.TAG_INT)) {
            c = stackTag.getInt("count");
        } else if (stackTag.contains("Count", Tag.TAG_INT)) {
            c = stackTag.getInt("Count");
        }
        return fromRegistryItemId(registries, id, c);
    }

    private static ItemStack fromRegistryItemId(HolderLookup.Provider registries, String idStr, int count) {
        if (idStr == null || idStr.isEmpty() || count <= 0 || registries == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(idStr);
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
