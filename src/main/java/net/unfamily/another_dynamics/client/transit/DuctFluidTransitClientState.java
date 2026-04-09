package net.unfamily.another_dynamics.client.transit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

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

    public static Map<BlockPos, List<DuctFluidTransitVisual>> snapshot() {
        return Collections.unmodifiableMap(BY_DUCT);
    }
}
