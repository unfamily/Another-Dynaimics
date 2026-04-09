package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
        List<DuctFluidTransitVisual> visuals =
                DuctFluidTransitVisual.listFromUpdateTag(ductPos, tag, registries, clientWorldGameTime);
        BlockPos key = ductPos.immutable();
        if (visuals.isEmpty()) {
            BY_DUCT.remove(key);
        } else {
            BY_DUCT.put(key, visuals);
        }
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
        BY_DUCT.remove(ductPos.immutable());
    }
}
