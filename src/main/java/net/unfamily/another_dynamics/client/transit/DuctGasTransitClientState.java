package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Client-only: gas ("chemical") blobs in transit along duct paths (see {@link DuctGasTransitVisual}).
 */
public final class DuctGasTransitClientState {

    private static final Map<BlockPos, List<DuctGasTransitVisual>> BY_DUCT = new ConcurrentHashMap<>();

    private DuctGasTransitClientState() {}

    /** Apply serialized gas-in-transit from {@link net.unfamily.another_dynamics.duct.DuctBlockEntity#getUpdateTag}. */
    public static void applyDuctUpdateTag(
            BlockPos ductPos, CompoundTag tag, HolderLookup.Provider registries, long clientWorldGameTime) {
        List<DuctGasTransitVisual> incoming =
                DuctGasTransitVisual.listFromUpdateTag(ductPos, tag, clientWorldGameTime);
        if (incoming == null) {
            return;
        }
        applyVisuals(ductPos, incoming);
    }

    private static void applyVisuals(BlockPos ductPos, List<DuctGasTransitVisual> visuals) {
        BlockPos key = ductPos.immutable();
        if (visuals == null || visuals.isEmpty()) {
            List<DuctGasTransitVisual> old = BY_DUCT.remove(key);
            if (old != null) {
                clearMotion(old);
            }
            return;
        }
        List<DuctGasTransitVisual> prev = BY_DUCT.get(key);
        BY_DUCT.put(key, prev == null ? visuals : mergeGasVisuals(prev, visuals));
    }

    /**
     * Keeps motion monotonic when packets arrive out of order by preferring the already-further-along prior visual
     * instance (smaller or equal {@code travelTicks}).
     */
    private static List<DuctGasTransitVisual> mergeGasVisuals(
            List<DuctGasTransitVisual> prev, List<DuctGasTransitVisual> incoming) {
        ArrayList<DuctGasTransitVisual> out = new ArrayList<>(incoming.size());
        for (DuctGasTransitVisual nv : incoming) {
            DuctGasTransitVisual pv = findMatchingLeg(prev, nv);
            if (pv != null && pv.travelTicks <= nv.travelTicks) {
                out.add(pv);
            } else {
                out.add(nv);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static DuctGasTransitVisual findMatchingLeg(List<DuctGasTransitVisual> list, DuctGasTransitVisual probe) {
        for (DuctGasTransitVisual v : list) {
            if (v.journeyStartGameTime == probe.journeyStartGameTime
                    && v.totalTravelTicks == probe.totalTravelTicks
                    && v.edgeTicks == probe.edgeTicks
                    && v.ductPath.equals(probe.ductPath)
                    && v.tintRgb == probe.tintRgb) {
                return v;
            }
        }
        return null;
    }

    public static List<DuctGasTransitVisual> visualsAt(BlockPos ductPos) {
        List<DuctGasTransitVisual> list = BY_DUCT.get(ductPos);
        return list != null ? list : List.of();
    }

    /** Clears cached visuals when the duct block entity is removed client-side (avoids stale ghosts). */
    public static void removeAt(BlockPos ductPos) {
        applyVisuals(ductPos, List.of());
    }

    private static void clearMotion(List<DuctGasTransitVisual> visuals) {
        for (DuctGasTransitVisual v : visuals) {
            DuctTransitMotion.removeLeg(
                    DuctTransitMotion.legKey(v.journeyStartGameTime, v.totalTravelTicks, v.ductPath));
        }
    }

    public static Map<BlockPos, List<DuctGasTransitVisual>> snapshot() {
        return Collections.unmodifiableMap(BY_DUCT);
    }
}

