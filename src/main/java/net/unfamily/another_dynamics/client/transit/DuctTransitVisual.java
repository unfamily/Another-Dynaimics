package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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

    /** How far (blocks) from duct center toward the attached inventory along {@link #sourceAttachFace}. */
    private static final double NODE_ATTACH_PULL = 0.34;

    private static final double INFER_ATTACH_PULL = 0.28;

    public final BlockPos ownerDuct;
    public final ItemStack stack;
    public final List<BlockPos> ductPath;
    public final TransitPhase phase;
    public final int totalTravelTicks;
    public final int travelTicks;
    public final int edgeTicks;
    public final long journeyStartGameTime;
    /**
     * Client-only: world game time when this leg’s progress was 0, derived at sync as
     * {@code clientGameTime - max(0, totalTravelTicks - travelTicks)} so each shipment stays distinct and motion
     * interpolates smoothly betweenPackets (unlike raw {@link #travelTicks} steps alone).
     */
    public final long progressAnchorGameTime;

    private final Vec3[] pathPoints;

    public DuctTransitVisual(
            BlockPos ownerDuct,
            ItemStack stack,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            long progressAnchorGameTime,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        this.ownerDuct = ownerDuct;
        this.stack = validateGhost(stack);
        this.ductPath = ductPath;
        this.phase = TransitPhase.FORWARD;
        this.totalTravelTicks = totalTravelTicks;
        this.travelTicks = travelTicks;
        this.edgeTicks = Math.max(1, edgeTicks);
        this.journeyStartGameTime = journeyStartGameTime;
        this.progressAnchorGameTime = progressAnchorGameTime;
        Direction pathStartFace = sourceAttachFace;
        Direction pathEndFace = destAttachFace;
        this.pathPoints = buildPathPoints(ductPath, ownerDuct, pathStartFace, pathEndFace);
    }

    private static ItemStack validateGhost(ItemStack stack) {
        ItemStack s = stack.copy();
        s.setCount(1);
        return s;
    }

    private static Vec3[] buildPathPoints(
            List<BlockPos> pathList,
            BlockPos ownerFallback,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        if (pathList == null || pathList.isEmpty()) {
            return new Vec3[] {Vec3.atCenterOf(ownerFallback)};
        }
        int n = pathList.size();
        Vec3[] w = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 c = Vec3.atCenterOf(pathList.get(i));
            if (i == 0) {
                if (sourceAttachFace != null) {
                    c = outwardTowardStorage(c, sourceAttachFace);
                } else if (n >= 2) {
                    c = inferStartFromPipeDirection(pathList, c, n);
                }
            } else if (i == n - 1) {
                if (destAttachFace != null) {
                    c = outwardTowardStorage(c, destAttachFace);
                } else if (n >= 2) {
                    c = inferEndFromPipeDirection(pathList, c, n);
                }
            }
            w[i] = c;
        }
        return w;
    }

    private static Vec3 outwardTowardStorage(Vec3 ductCenter, Direction storageFaceOnDuct) {
        Vec3 step = new Vec3(storageFaceOnDuct.getStepX(), storageFaceOnDuct.getStepY(), storageFaceOnDuct.getStepZ());
        return ductCenter.add(step.scale(NODE_ATTACH_PULL));
    }

    private static Vec3 inferStartFromPipeDirection(List<BlockPos> pathList, Vec3 firstCenter, int n) {
        Vec3 toNext = Vec3.atCenterOf(pathList.get(1)).subtract(firstCenter);
        if (toNext.lengthSqr() < 1.0e-8) {
            return firstCenter;
        }
        return firstCenter.subtract(toNext.normalize().scale(INFER_ATTACH_PULL));
    }

    private static Vec3 inferEndFromPipeDirection(List<BlockPos> pathList, Vec3 lastCenter, int n) {
        Vec3 fromPrev = lastCenter.subtract(Vec3.atCenterOf(pathList.get(n - 2)));
        if (fromPrev.lengthSqr() < 1.0e-8) {
            return lastCenter;
        }
        return lastCenter.add(fromPrev.normalize().scale(INFER_ATTACH_PULL));
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
                anchor,
                s.sourceFace,
                s.destFace);
    }

    public static List<DuctTransitVisual> listFromUpdateTag(
            BlockPos ownerDuct, CompoundTag root, HolderLookup.Provider registries, long clientWorldGameTime) {
        if (!root.contains("TransitV1", Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = root.getList("TransitV1", Tag.TAG_COMPOUND);
        ArrayList<DuctTransitVisual> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            ItemStack stack = ItemStack.EMPTY;
            // Prefer the full Stack compound (includes data components/NBT) over the bare VizId.
            // VizId is kept only as a lightweight fallback for legacy packets that lack Stack.
            if (t.contains("Stack", Tag.TAG_COMPOUND)) {
                CompoundTag stackTag = t.getCompound("Stack");
                if (!stackTag.isEmpty()) {
                    stack = ItemStack.parse(registries, stackTag).orElse(ItemStack.EMPTY);
                }
            }
            if (stack.isEmpty() && t.contains("VizId", Tag.TAG_STRING)) {
                ResourceLocation rid = ResourceLocation.tryParse(t.getString("VizId"));
                if (rid != null) {
                    Item item = BuiltInRegistries.ITEM.get(rid);
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
            ListTag plist = t.getList("Path", Tag.TAG_COMPOUND);
            ArrayList<BlockPos> path = new ArrayList<>(plist.size());
            for (int j = 0; j < plist.size(); j++) {
                CompoundTag pt = plist.getCompound(j);
                path.add(new BlockPos(pt.getInt("X"), pt.getInt("Y"), pt.getInt("Z")));
            }
            Direction srcFace = readOptionalFace(t, "SrcF");
            Direction dstFace = readOptionalFace(t, "DstF");
            int tot = t.getInt("Tot");
            int tr = t.getInt("Tr");
            int elapsed = Math.max(0, tot - tr);
            long anchor = clientWorldGameTime - elapsed;
            out.add(
                    new DuctTransitVisual(
                            ownerDuct,
                            stack,
                            Collections.unmodifiableList(path),
                            tot,
                            tr,
                            t.getInt("Ed"),
                            t.getLong("J0"),
                            anchor,
                            srcFace,
                            dstFace));
        }
        return Collections.unmodifiableList(out);
    }

    @Nullable
    private static Direction readOptionalFace(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_BYTE)) {
            return null;
        }
        int o = t.getByte(key) & 0xFF;
        return o < 6 ? Direction.values()[o] : null;
    }

    /**
     * Visual progress 0 = start of leg, 1 = end (not yet delivered). Uses {@link #progressAnchorGameTime} and world
     * time for smooth frames between packet updates; anchors differ per shipment when {@link #travelTicks} differs.
     */
    public float progress01(@Nullable Level level, float partialTick) {
        if (totalTravelTicks <= 0) {
            return 1f;
        }
        if (level != null) {
            float elapsed = (level.getGameTime() + partialTick) - progressAnchorGameTime;
            return Math.clamp(elapsed / (float) totalTravelTicks, 0f, 1f);
        }
        float predictedTravel = Math.max(0f, travelTicks - partialTick);
        return Math.clamp((totalTravelTicks - predictedTravel) / (float) totalTravelTicks, 0f, 1f);
    }

    public Vec3 positionAt(float progress01) {
        Vec3[] pts = pathPoints;
        if (pts.length == 0) {
            return Vec3.atCenterOf(ownerDuct);
        }
        if (pts.length == 1) {
            return pts[0];
        }
        float p = Math.clamp(progress01, 0f, 1f);
        float scaled = p * (pts.length - 1);
        int i0 = (int) Math.floor(scaled);
        int i1 = Math.min(pts.length - 1, i0 + 1);
        float frac = scaled - i0;
        return pts[i0].lerp(pts[i1], frac);
    }
}
