package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.level.Level;

/**
 * Client-only: fluid blobs in transit along duct paths (see {@link DuctFluidTransitVisual}).
 */
public final class DuctFluidTransitClientState {

    private static final Map<BlockPos, List<DuctFluidTransitVisual>> BY_DUCT = new ConcurrentHashMap<>();

    private DuctFluidTransitClientState() {}

    /** Apply serialized fluid-in-transit from {@link net.unfamily.another_dynamics.duct.DuctBlockEntity#getUpdateTag}. */
    public static void applyDuctUpdateTag(
            BlockPos ductPos, CompoundTag tag, HolderLookup.Provider registries, long clientWorldGameTime) {
        List<DuctFluidTransitVisual> incoming =
                DuctFluidTransitVisual.listFromUpdateTag(ductPos, tag, registries, clientWorldGameTime);
        applyVisuals(ductPos, incoming);
    }

    private static void applyVisuals(BlockPos ductPos, List<DuctFluidTransitVisual> visuals) {
        BlockPos key = ductPos.immutable();
        if (visuals.isEmpty()) {
            List<DuctFluidTransitVisual> old = BY_DUCT.remove(key);
            if (old != null) {
                clearMotion(old);
            }
            return;
        }
        List<DuctFluidTransitVisual> prev = BY_DUCT.get(key);
        BY_DUCT.put(key, prev == null ? visuals : mergeFluidVisuals(prev, visuals));
    }

    /**
     * Keeps motion monotonic when packets arrive out of order by preferring the already-further-along prior visual
     * instance (smaller or equal {@code travelTicks}).
     */
    private static List<DuctFluidTransitVisual> mergeFluidVisuals(
            List<DuctFluidTransitVisual> prev, List<DuctFluidTransitVisual> incoming) {
        ArrayList<DuctFluidTransitVisual> out = new ArrayList<>(incoming.size());
        for (DuctFluidTransitVisual nv : incoming) {
            DuctFluidTransitVisual pv = findMatchingLeg(prev, nv);
            if (pv != null && pv.travelTicks <= nv.travelTicks) {
                out.add(pv);
            } else {
                out.add(nv);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static DuctFluidTransitVisual findMatchingLeg(List<DuctFluidTransitVisual> list, DuctFluidTransitVisual probe) {
        for (DuctFluidTransitVisual v : list) {
            if (v.journeyStartGameTime == probe.journeyStartGameTime
                    && v.totalTravelTicks == probe.totalTravelTicks
                    && v.edgeTicks == probe.edgeTicks
                    && v.ductPath.equals(probe.ductPath)
                    && FluidStack.isSameFluidSameComponents(v.fluid, probe.fluid)) {
                return v;
            }
        }
        return null;
    }

    public static List<DuctFluidTransitVisual> visualsAt(BlockPos ductPos) {
        List<DuctFluidTransitVisual> list = BY_DUCT.get(ductPos);
        return list != null ? list : List.of();
    }

    /** After {@code DuctFluidTransit} loads on the client (chunk data has no FluidTransitV1 list). */
    public static void syncFromFluidShipments(BlockPos ductPos, List<net.unfamily.another_dynamics.duct.logistics.FluidTransitShipment> shipments, Level level) {
        if (shipments == null || shipments.isEmpty()) {
            BY_DUCT.remove(ductPos.immutable());
            return;
        }
        long gt = level.getGameTime();
        ArrayList<DuctFluidTransitVisual> out = new ArrayList<>();
        for (var s : shipments) {
            if (s.fluid.isEmpty()) {
                continue;
            }
            out.add(DuctFluidTransitVisual.fromFluidShipment(ductPos, s, gt));
        }
        if (out.isEmpty()) {
            BY_DUCT.remove(ductPos.immutable());
        } else {
            BY_DUCT.put(ductPos.immutable(), Collections.unmodifiableList(out));
        }
    }

    public static Map<BlockPos, List<DuctFluidTransitVisual>> snapshot() {
        return Collections.unmodifiableMap(BY_DUCT);
    }

    /** Clears cached visuals when the duct block entity is removed client-side (avoids stale ghosts). */
    public static void removeAt(BlockPos ductPos) {
        applyVisuals(ductPos, List.of());
    }

    private static void clearMotion(List<DuctFluidTransitVisual> visuals) {
        for (DuctFluidTransitVisual v : visuals) {
            DuctTransitMotion.removeLeg(
                    DuctTransitMotion.legKey(v.journeyStartGameTime, v.totalTravelTicks, v.ductPath));
        }
    }
}
