package net.unfamily.another_dynamics.duct.logistics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Short-lived cache for insert accept probes toward a duct storage face. Rejects failed destinations briefly so
 * retriever/extraction loops do not repeat expensive bulk handler scans every action tick.
 */
public final class DuctInsertProbeCache {
    private static final int REJECT_TTL_TICKS = 2;
    private static final int ACCEPT_TTL_TICKS = 2;

    private record RejectKey(BlockPos ductPos, Direction face, int itemKey) {}

    private record AcceptKey(BlockPos ductPos, Direction face, int itemKey, long tickBucket) {}

    private record CachedAccept(boolean value, long expiresAtTick) {}

    private static final Map<RejectKey, Long> rejectsUntilTick = new ConcurrentHashMap<>();
    private static final Map<AcceptKey, CachedAccept> accepts = new ConcurrentHashMap<>();

    private DuctInsertProbeCache() {}

    public static boolean isRejected(Level level, BlockPos ductPos, Direction face, ItemStack probe) {
        if (level == null || level.isClientSide() || probe.isEmpty()) {
            return false;
        }
        Long until = rejectsUntilTick.get(key(probe, ductPos, face));
        return until != null && level.getGameTime() < until;
    }

    public static void recordReject(Level level, BlockPos ductPos, Direction face, ItemStack probe) {
        if (level == null || level.isClientSide() || probe.isEmpty()) {
            return;
        }
        rejectsUntilTick.put(key(probe, ductPos, face), level.getGameTime() + REJECT_TTL_TICKS);
    }

    public static void cacheAccept(Level level, BlockPos ductPos, Direction face, ItemStack probe, boolean accepted) {
        if (level == null || level.isClientSide() || probe.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        long bucket = now / ACCEPT_TTL_TICKS;
        accepts.put(
                acceptKey(probe, ductPos, face, bucket),
                new CachedAccept(accepted, now + ACCEPT_TTL_TICKS));
        if (accepted) {
            rejectsUntilTick.remove(key(probe, ductPos, face));
        }
    }

    public static Boolean getCachedAccept(Level level, BlockPos ductPos, Direction face, ItemStack probe) {
        if (level == null || level.isClientSide() || probe.isEmpty()) {
            return null;
        }
        long now = level.getGameTime();
        CachedAccept cached = accepts.get(acceptKey(probe, ductPos, face, now / ACCEPT_TTL_TICKS));
        if (cached == null || now >= cached.expiresAtTick) {
            return null;
        }
        return cached.value;
    }

    /** Drop cached state for a face when inbound reservations or inventory change. */
    public static void invalidateFace(BlockPos ductPos, Direction face) {
        rejectsUntilTick.keySet().removeIf(k -> k.ductPos.equals(ductPos) && k.face == face);
        accepts.keySet().removeIf(k -> k.ductPos.equals(ductPos) && k.face == face);
    }

    private static RejectKey key(ItemStack probe, BlockPos ductPos, Direction face) {
        return new RejectKey(ductPos, face, itemKey(probe));
    }

    private static AcceptKey acceptKey(ItemStack probe, BlockPos ductPos, Direction face, long tickBucket) {
        return new AcceptKey(ductPos, face, itemKey(probe), tickBucket);
    }

    private static int itemKey(ItemStack stack) {
        ItemStack one = stack.copyWithCount(1);
        return 31 * one.getItem().hashCode() + one.getComponents().hashCode();
    }
}
