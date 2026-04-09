package net.unfamily.another_dynamics.client.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;

/**
 * Client-only: latest transit snapshots keyed by duct block entity that owns the pending shipment. Data describes a
 * visual simulation only; items are applied to inventories when the server finishes the leg.
 */
public final class DuctTransitClientState {

    private static final Map<BlockPos, List<DuctTransitVisual>> BY_DUCT = new ConcurrentHashMap<>();

    private DuctTransitClientState() {}

    public static void onDuctUpdateTag(
            BlockPos ductPos, CompoundTag tag, HolderLookup.Provider registries, long clientWorldGameTime) {
        if (!tag.contains("TransitV1", Tag.TAG_LIST)) {
            return;
        }
        List<DuctTransitVisual> visuals =
                DuctTransitVisual.listFromUpdateTag(ductPos, tag, registries, clientWorldGameTime);
        applyVisuals(ductPos, visuals);
    }

    /** After {@code DuctOutbound} loads on the client (chunk data has no TransitV1 list). */
    public static void syncFromOutboundShipments(BlockPos ductPos, List<OutboundShipment> shipments, Level level) {
        if (shipments.isEmpty()) {
            BY_DUCT.remove(ductPos.immutable());
            return;
        }
        long gt = level.getGameTime();
        ArrayList<DuctTransitVisual> out = new ArrayList<>();
        for (OutboundShipment s : shipments) {
            if (s.stack.isEmpty()) {
                continue;
            }
            out.add(DuctTransitVisual.fromOutboundShipment(ductPos, s, gt));
        }
        if (out.isEmpty()) {
            BY_DUCT.remove(ductPos.immutable());
        } else {
            applyVisuals(ductPos, Collections.unmodifiableList(out));
        }
    }

    private static void applyVisuals(BlockPos ductPos, List<DuctTransitVisual> visuals) {
        BlockPos key = ductPos.immutable();
        if (visuals.isEmpty()) {
            BY_DUCT.remove(key);
            return;
        }
        BY_DUCT.put(key, visuals);
    }

    /** Active visuals for the duct block that owns {@code OutboundShipment} state (source of sync packet). */
    public static List<DuctTransitVisual> visualsAt(BlockPos ductPos) {
        List<DuctTransitVisual> list = BY_DUCT.get(ductPos);
        return list != null ? list : List.of();
    }

    public static Map<BlockPos, List<DuctTransitVisual>> snapshot() {
        return Collections.unmodifiableMap(BY_DUCT);
    }

    public static void removeAt(BlockPos ductPos) {
        BY_DUCT.remove(ductPos.immutable());
    }
}
