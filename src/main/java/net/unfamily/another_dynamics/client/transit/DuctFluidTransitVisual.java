package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.unfamily.another_dynamics.duct.DuctNbtCodecs;

import org.jetbrains.annotations.Nullable;

/**
 * Client-only: small fluid-textured volume moving along a duct path (same motion model as {@link DuctTransitVisual}).
 */
public final class DuctFluidTransitVisual {

    public final BlockPos ownerDuct;
    public final FluidStack fluid;
    public final List<BlockPos> ductPath;
    public final int totalTravelTicks;
    public final int travelTicks;
    public final int edgeTicks;
    public final long journeyStartGameTime;
    public final long progressAnchorGameTime;

    private final DuctTransitPathGeometry.OrthogonalTransitPath orthogonalPath;

    public DuctFluidTransitVisual(
            BlockPos ownerDuct,
            FluidStack fluid,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            long progressAnchorGameTime,
            @Nullable Direction sourceAttachFace,
            @Nullable Direction destAttachFace) {
        this.ownerDuct = ownerDuct;
        this.fluid = fluid.isEmpty() ? FluidStack.EMPTY : fluid.copy();
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
     * Client rebuild from disk/chunk {@code DuctFluidTransit} (there is no FluidTransitV1 list in saved chunk for this path).
     *
     * @param clientWorldGameTime {@link Level#getGameTime()} when applying client-side shipment data
     */
    public static DuctFluidTransitVisual fromFluidShipment(
            BlockPos ownerDuct, net.unfamily.another_dynamics.duct.logistics.FluidTransitShipment s, long clientWorldGameTime) {
        int elapsed = Math.max(0, s.totalTravelTicks - s.travelTicks);
        long anchor = clientWorldGameTime - elapsed;
        return new DuctFluidTransitVisual(
                ownerDuct,
                s.fluid.copy(),
                List.copyOf(s.ductPath),
                s.totalTravelTicks,
                s.travelTicks,
                s.edgeTicks,
                s.journeyStartGameTime,
                anchor,
                s.sourceFace,
                s.destFace);
    }

    public static List<DuctFluidTransitVisual> listFromUpdateTag(
            BlockPos ownerDuct, CompoundTag root, HolderLookup.Provider registries, long clientWorldGameTime) {
        if (!root.contains("FluidTransitV1")) {
            return List.of();
        }
        ListTag list = root.getListOrEmpty("FluidTransitV1");
        ArrayList<DuctFluidTransitVisual> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            FluidStack fs = FluidStack.EMPTY;
            if (t.contains("Fluid")) {
                fs = DuctNbtCodecs.parseFluidStack(registries, t.getCompoundOrEmpty("Fluid")).orElse(FluidStack.EMPTY);
            }
            if (fs.isEmpty()) {
                continue;
            }
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
            out.add(
                    new DuctFluidTransitVisual(
                            ownerDuct,
                            fs,
                            Collections.unmodifiableList(path),
                            tot,
                            tr,
                            t.getIntOr("Ed", 0),
                            t.getLongOr("J0", 0L),
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

    public float progress01(@Nullable Level level, float partialTick) {
        long key = DuctTransitMotion.legKey(journeyStartGameTime, totalTravelTicks, ductPath);
        float elapsed = DuctTransitMotion.smoothElapsedTicks(key, totalTravelTicks, travelTicks, level, partialTick);
        if (totalTravelTicks <= 0) {
            return 1f;
        }
        return net.minecraft.util.Mth.clamp(elapsed / totalTravelTicks, 0f, 1f);
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
