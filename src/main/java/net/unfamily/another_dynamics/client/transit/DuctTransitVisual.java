package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.unfamily.another_dynamics.duct.DuctNbtCodecs;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;
import net.unfamily.another_dynamics.duct.logistics.TransitPhase;

import org.jetbrains.annotations.Nullable;

/**
 * Client-only: ghost item along a duct path. Endpoints are pulled toward storage faces so motion reads as leaving the
 * source node and entering the destination node.
 *
 * <p>Legacy saves could use {@link TransitPhase#RETURN}; the wire always renders as {@link TransitPhase#FORWARD}.
 */
public final class DuctTransitVisual {

    public final BlockPos ownerDuct;
    public final ItemStack stack;
    public final List<BlockPos> ductPath;
    public final TransitPhase phase;
    public final int totalTravelTicks;
    public final int travelTicks;
    public final int edgeTicks;
    public final long journeyStartGameTime;
    /** Stable leg identity from server {@link OutboundShipment#incomingReservationId}. */
    public final long incomingReservationId;
    /**
     * Client-only: world game time when this leg’s progress was 0, derived at sync as
     * {@code clientGameTime - max(0, totalTravelTicks - travelTicks)} so each shipment stays distinct and motion
     * interpolates smoothly betweenPackets (unlike raw {@link #travelTicks} steps alone).
     */
    public final long progressAnchorGameTime;

    private final DuctTransitPathGeometry.OrthogonalTransitPath orthogonalPath;

    public DuctTransitVisual(
            BlockPos ownerDuct,
            ItemStack stack,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            long incomingReservationId,
            long progressAnchorGameTime,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        this.ownerDuct = ownerDuct;
        this.stack = validateGhost(stack);
        this.ductPath = ductPath;
        this.phase = TransitPhase.FORWARD;
        this.totalTravelTicks = totalTravelTicks;
        this.travelTicks = travelTicks;
        this.edgeTicks = Math.max(0, edgeTicks);
        this.journeyStartGameTime = journeyStartGameTime;
        this.incomingReservationId = incomingReservationId;
        this.progressAnchorGameTime = progressAnchorGameTime;
        Direction pathStartFace = sourceAttachFace;
        Direction pathEndFace = destAttachFace;
        this.orthogonalPath =
                DuctTransitPathGeometry.buildOrthogonalTransitPath(ductPath, ownerDuct, pathStartFace, pathEndFace);
    }

    private static ItemStack validateGhost(ItemStack stack) {
        ItemStack s = stack.copy();
        s.setCount(1);
        return s;
    }

    /**
     * Client rebuild from disk/chunk {@code DuctOutbound} (there is no TransitV1 list in saved chunk for this path).
     *
     * @param clientWorldGameTime {@link Level#getGameTime()} when applying client-side shipment data
     */
    public static DuctTransitVisual fromOutboundShipment(
            BlockPos ownerDuct, OutboundShipment s, long clientWorldGameTime) {
        int elapsed = Math.max(0, s.totalTravelTicks - s.travelTicks);
        long anchor = clientWorldGameTime - elapsed;
        return new DuctTransitVisual(
                ownerDuct,
                s.stack.copy(),
                List.copyOf(s.ductPath),
                s.totalTravelTicks,
                s.travelTicks,
                s.edgeTicks,
                s.journeyStartGameTime,
                s.incomingReservationId,
                anchor,
                s.sourceFace,
                s.destFace);
    }

    public static List<DuctTransitVisual> listFromUpdateTag(
            BlockPos ownerDuct, CompoundTag root, HolderLookup.Provider registries, long clientWorldGameTime) {
        if (!root.contains("TransitV1")) {
            return List.of();
        }
        ListTag list = root.getListOrEmpty("TransitV1");
        ArrayList<DuctTransitVisual> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            ItemStack stack = ItemStack.EMPTY;
            // Prefer the full Stack compound (includes data components/NBT) over the bare VizId.
            // VizId is kept only as a lightweight fallback for legacy packets that lack Stack.
            if (t.contains("Stack")) {
                CompoundTag stackTag = t.getCompoundOrEmpty("Stack");
                if (!stackTag.isEmpty()) {
                    stack = DuctNbtCodecs.parseItemStack(registries, stackTag).orElse(ItemStack.EMPTY);
                }
            }
            if (stack.isEmpty() && t.contains("VizId")) {
                Identifier rid = Identifier.tryParse(t.getStringOr("VizId", ""));
                if (rid != null) {
                    Item item = BuiltInRegistries.ITEM.getOptional(rid).orElse(Items.AIR);
                    if (item != Items.AIR) {
                        stack = new ItemStack(item, 1);
                    }
                }
            }
            if (stack.isEmpty()) {
                continue;
            }
            stack = stack.copy();
            stack.setCount(1);
            ListTag plist = t.getListOrEmpty("Path");
            ArrayList<BlockPos> path = new ArrayList<>(plist.size());
            for (int j = 0; j < plist.size(); j++) {
                CompoundTag pt = plist.getCompoundOrEmpty(j);
                path.add(new BlockPos(pt.getIntOr("X", 0), pt.getIntOr("Y", 0), pt.getIntOr("Z", 0)));
            }
            Direction srcFace = readOptionalFace(t, "SrcF");
            Direction dstFace = readOptionalFace(t, "DstF");
            int tot = t.getIntOr("Tot", 0);
            int tr = t.getIntOr("Tr", 0);
            int elapsed = Math.max(0, tot - tr);
            long anchor = clientWorldGameTime - elapsed;
            long inId = t.getLongOr("InId", 0L);
            out.add(
                    new DuctTransitVisual(
                            ownerDuct,
                            stack,
                            Collections.unmodifiableList(path),
                            tot,
                            tr,
                            t.getIntOr("Ed", 0),
                            t.getLongOr("J0", 0L),
                            inId,
                            anchor,
                            srcFace,
                            dstFace));
        }
        return Collections.unmodifiableList(out);
    }

    @Nullable
    private static Direction readOptionalFace(CompoundTag t, String key) {
        if (!t.contains(key)) {
            return null;
        }
        int o = t.getByteOr(key, (byte) 0) & 0xFF;
        return o < 6 ? Direction.values()[o] : null;
    }

    /**
     * Arc-length progress along the full path polyline (0 = outside source node, 1 = outside destination node).
     */
    public float progress01(@Nullable Level level, float partialTick) {
        long key =
                DuctTransitMotion.legKey(
                        journeyStartGameTime, totalTravelTicks, ductPath, incomingReservationId);
        float elapsed = DuctTransitMotion.smoothElapsedTicks(key, totalTravelTicks, travelTicks, level, partialTick);
        if (totalTravelTicks <= 0) {
            return 1f;
        }
        return Mth.clamp(elapsed / totalTravelTicks, 0f, 1f);
    }

    public Vec3 positionAt(float ignoredProgress01) {
        return positionAt(ignoredProgress01, null, 0f);
    }

    public Vec3 positionAt(float ignoredProgress01, @Nullable Level level, float partialTick) {
        if (orthogonalPath.points().length == 0) {
            return Vec3.atCenterOf(ownerDuct);
        }
        return orthogonalPath.positionAt(progress01(level, partialTick));
    }
}
