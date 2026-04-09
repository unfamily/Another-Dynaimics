package net.unfamily.another_dynamics.client.transit;

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
        List<DuctGasTransitVisual> visuals =
                DuctGasTransitVisual.listFromUpdateTag(ductPos, tag, clientWorldGameTime);
        BlockPos key = ductPos.immutable();
        if (visuals.isEmpty()) {
            BY_DUCT.remove(key);
        } else {
            BY_DUCT.put(key, visuals);
        }
    }

    public static List<DuctGasTransitVisual> visualsAt(BlockPos ductPos) {
        List<DuctGasTransitVisual> list = BY_DUCT.get(ductPos);
        return list != null ? list : List.of();
    }

    /** Clears cached visuals when the duct block entity is removed client-side (avoids stale ghosts). */
    public static void removeAt(BlockPos ductPos) {
        BY_DUCT.remove(ductPos.immutable());
    }

    public static Map<BlockPos, List<DuctGasTransitVisual>> snapshot() {
        return Collections.unmodifiableMap(BY_DUCT);
    }
}

