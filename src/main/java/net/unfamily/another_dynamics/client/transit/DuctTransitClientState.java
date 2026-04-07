package net.unfamily.another_dynamics.client.transit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * Client-only: latest transit snapshots keyed by duct BE position.
 */
public final class DuctTransitClientState {
    private static final Map<BlockPos, List<DuctTransitVisual>> BY_DUCT = new ConcurrentHashMap<>();

    private DuctTransitClientState() {}

    public static void onDuctUpdateTag(BlockPos ductPos, CompoundTag tag, HolderLookup.Provider registries) {
        List<DuctTransitVisual> visuals = DuctTransitVisual.listFromUpdateTag(ductPos, tag, registries);
        if (visuals.isEmpty()) {
            BY_DUCT.remove(ductPos);
        } else {
            BY_DUCT.put(ductPos.immutable(), visuals);
        }
    }

    public static Map<BlockPos, List<DuctTransitVisual>> snapshot() {
        return Collections.unmodifiableMap(BY_DUCT);
    }
}

