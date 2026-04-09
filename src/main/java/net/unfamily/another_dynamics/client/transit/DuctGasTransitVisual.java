package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * Client-only: small tinted volume moving along a duct path (same motion model as {@link DuctTransitVisual}).
 */
public final class DuctGasTransitVisual {

    public final BlockPos ownerDuct;
    public final int tintRgb;
    public final long amount;
    public final List<BlockPos> ductPath;
    public final int totalTravelTicks;
    public final int travelTicks;
    public final int edgeTicks;
    public final long journeyStartGameTime;
    public final long progressAnchorGameTime;

    private final DuctTransitPathGeometry.OrthogonalTransitPath orthogonalPath;

    public DuctGasTransitVisual(
            BlockPos ownerDuct,
            int tintRgb,
            long amount,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            long progressAnchorGameTime,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        this.ownerDuct = ownerDuct;
        this.tintRgb = tintRgb;
        this.amount = Math.max(0L, amount);
        this.ductPath = ductPath;
        this.totalTravelTicks = totalTravelTicks;
        this.travelTicks = travelTicks;
        this.edgeTicks = Math.max(0, edgeTicks);
        this.journeyStartGameTime = journeyStartGameTime;
        this.progressAnchorGameTime = progressAnchorGameTime;
        this.orthogonalPath =
                DuctTransitPathGeometry.buildOrthogonalTransitPath(ductPath, ownerDuct, sourceAttachFace, destAttachFace);
    }

    /**
     * @return {@code null} if the tag does not carry gas transit data (do not clear client state — avoids wiping
     *     visuals on unrelated/partial block updates).
     */
    @Nullable
    public static List<DuctGasTransitVisual> listFromUpdateTag(BlockPos ownerDuct, CompoundTag root, long clientWorldGameTime) {
        if (!root.contains("GasTransitV1", Tag.TAG_LIST)) {
            return null;
        }
        ListTag list = root.getList("GasTransitV1", Tag.TAG_COMPOUND);
        ArrayList<DuctGasTransitVisual> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int tint = t.contains("Tint", Tag.TAG_INT) ? t.getInt("Tint") : 0;
            long amt = t.contains("Amt", Tag.TAG_LONG) ? t.getLong("Amt") : 0L;
            if (amt <= 0) {
                continue;
            }
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
                    new DuctGasTransitVisual(
                            ownerDuct,
                            tint,
                            amt,
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
        Vec3[] pts = orthogonalPath.points();
        if (pts.length == 0) {
            return Vec3.atCenterOf(ownerDuct);
        }
        return orthogonalPath.positionAt(progress01);
    }
}

