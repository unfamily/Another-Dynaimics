package net.unfamily.another_dynamics.duct.logistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Tracks reserved incoming amounts toward a destination duct face (RAM only). Used with planned-only transfers:
 * items stay in source storage until delivery; this index prevents oversubscribing destination space.
 */
public final class DuctIncomingIndex {
    private static final Map<ResourceKey<Level>, Map<IncomingKey, List<Reservation>>> BY_DIMENSION =
            new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private DuctIncomingIndex() {}

    private record IncomingKey(BlockPos ductPos, int faceOrdinal) {
        IncomingKey {
            ductPos = ductPos.immutable();
            faceOrdinal = faceOrdinal & 0x7;
        }
    }

    public record Reservation(long id, ItemStack stack) {}

    public static long newReservationId() {
        return NEXT_ID.getAndIncrement();
    }

    public static void register(ServerLevel level, BlockPos destDuct, Direction destFace, long reservationId, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (destFace == null) {
            destFace = Direction.NORTH;
        }
        List<Reservation> list =
                BY_DIMENSION
                        .computeIfAbsent(level.dimension(), d -> new ConcurrentHashMap<>())
                        .computeIfAbsent(
                                new IncomingKey(destDuct, destFace.ordinal()),
                                p -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(new Reservation(reservationId, stack.copy()));
        }
    }

    public static void unregister(ServerLevel level, BlockPos destDuct, Direction destFace, long reservationId) {
        if (destFace == null) {
            destFace = Direction.NORTH;
        }
        Map<IncomingKey, List<Reservation>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return;
        }
        List<Reservation> list = dim.get(new IncomingKey(destDuct, destFace.ordinal()));
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (Iterator<Reservation> it = list.iterator(); it.hasNext(); ) {
                Reservation r = it.next();
                if (r != null && r.id() == reservationId) {
                    it.remove();
                    break;
                }
            }
            if (list.isEmpty()) {
                dim.remove(new IncomingKey(destDuct, destFace.ordinal()));
            }
        }
    }

    public static List<ItemStack> snapshot(ServerLevel level, BlockPos destDuct, Direction destFace) {
        if (destFace == null) {
            destFace = Direction.NORTH;
        }
        Map<IncomingKey, List<Reservation>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return List.of();
        }
        List<Reservation> list = dim.get(new IncomingKey(destDuct, destFace.ordinal()));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            List<ItemStack> out = new ArrayList<>(list.size());
            for (Reservation r : list) {
                if (r == null || r.stack() == null) {
                    continue;
                }
                out.add(r.stack().copy());
            }
            return out;
        }
    }

    /** Snapshot for delivery/resize excluding one in-flight reservation id. */
    public static List<ItemStack> snapshotExcluding(
            ServerLevel level, BlockPos destDuct, Direction destFace, long excludeReservationId) {
        if (destFace == null) {
            destFace = Direction.NORTH;
        }
        Map<IncomingKey, List<Reservation>> dim = BY_DIMENSION.get(level.dimension());
        if (dim == null) {
            return List.of();
        }
        List<Reservation> list = dim.get(new IncomingKey(destDuct, destFace.ordinal()));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            List<ItemStack> out = new ArrayList<>(list.size());
            for (Reservation r : list) {
                if (r == null || r.stack() == null) {
                    continue;
                }
                if (r.id() == excludeReservationId) {
                    continue;
                }
                out.add(r.stack().copy());
            }
            return out;
        }
    }

    // Backward-compatible overloads (used only by legacy call sites; reservation id is auto-generated).
    public static void register(ServerLevel level, BlockPos destDuct, Direction destFace, ItemStack stack) {
        register(level, destDuct, destFace, newReservationId(), stack);
    }
}
