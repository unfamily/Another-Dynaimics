package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.DuctNetworkType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Optional developer tracing for in-flight duct logistics (config {@code dev.ductTransitDebug},
 * {@code dev.ductRoutingDebug}, {@code dev.ductCacheDebug}).
 */
public final class DuctTransitDebugLog {
    private static final Logger LOG = AnotherDynamicsMod.LOGGER;

    private DuctTransitDebugLog() {}

    public enum CancelReason {
        STALE,
        PATH_BREAK,
        DEST_DISCONNECT,
        NEGATIVE_TRAVEL,
        DELIVERY_FAIL,
        KIND_CAP_EVICTION,
        OTHER
    }

    /** Sub-reason when {@link CancelReason#DELIVERY_FAIL} or delivery defer tracing. */
    public enum DeliveryFailDetail {
        DEST_BE_MISSING,
        CHANNEL_MISMATCH,
        REDSTONE_OFF,
        FILTER_REJECT,
        INSERT_CAP_ZERO,
        INSERTED_ZERO,
        PROBE_REJECTED,
        DEFER_TIMEOUT
    }

    public static void itemCancel(ServerLevel level, OutboundShipment s, CancelReason reason) {
        itemCancel(level, s, reason, null);
    }

    public static void itemCancel(
            ServerLevel level, OutboundShipment s, CancelReason reason, @Nullable DeliveryFailDetail detail) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        DuctTransitTopology.PathBreakSite site =
                reason == CancelReason.PATH_BREAK
                        ? DuctTransitTopology.classifyItemShipmentPathBreak(level, s)
                        : DuctTransitTopology.PathBreakSite.INTACT;
        int brokenIdx =
                reason == CancelReason.PATH_BREAK
                        ? DuctTransitTopology.firstBrokenPathEdge(level, s.ductPath).orElse(-1)
                        : -1;
        int componentSize =
                DuctNetworkCache.connectedDucts(level, s.refundDuct, DuctNetworkType.ITEM).size();
        LOG.info(
                "[DUCT-TRN] cancel reason={} detail={} site={} brokenEdge={} source={} dest={} pathLen={} "
                        + "travelTicks={} totalTravelTicks={} deferSpent={} componentSize={} count={} item={}",
                reason,
                detail,
                site,
                brokenIdx,
                s.refundDuct,
                s.destDuct,
                s.ductPath != null ? s.ductPath.size() : 0,
                s.travelTicks,
                s.totalTravelTicks,
                s.deliveryDeferSpent,
                componentSize,
                s.stack.isEmpty() ? 0 : s.stack.getCount(),
                s.stack.isEmpty() ? "empty" : s.stack.getHoverName().getString());
        if (reason == CancelReason.PATH_BREAK && brokenIdx >= 0 && s.ductPath != null && brokenIdx < s.ductPath.size() - 1) {
            BlockPos a = s.ductPath.get(brokenIdx);
            BlockPos b = s.ductPath.get(brokenIdx + 1);
            LOG.info("[DUCT-TRN] broken edge {} <-> {} loadedA={} loadedB={}", a, b, level.isLoaded(a), level.isLoaded(b));
        }
    }

    public static void extractionGatedByUnsatisfiableTasks(ServerLevel level, BlockPos ductPos, int blockedKinds) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        if ((t + ductPos.asLong()) % 100L != 0L) {
            return;
        }
        LOG.info("[DUCT-TRN] extraction gated blockedKinds={} duct={}", blockedKinds, ductPos);
    }

    public static void extractionGatedByOverflow(ServerLevel level, BlockPos ductPos, int overflowLines) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        if ((t + ductPos.asLong()) % 100L != 0L) {
            return;
        }
        LOG.info("[DUCT-TRN] extraction gated overflowLines={} duct={}", overflowLines, ductPos);
    }

    /** Min ticks between {@link #stallDrainFailed} lines for the same duct face (dedupe burst within a tick). */
    private static final long STALL_DRAIN_FAILED_LOG_INTERVAL = 40L;

    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> STALL_DRAIN_FAILED_LAST_LOG =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void stallDrainFailed(ServerLevel level, BlockPos ductPos, Direction face, String reason) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        long key = ductPos.asLong() * 6L + face.ordinal();
        Long last = STALL_DRAIN_FAILED_LAST_LOG.get(key);
        if (last != null && t - last < STALL_DRAIN_FAILED_LOG_INTERVAL) {
            return;
        }
        STALL_DRAIN_FAILED_LAST_LOG.put(key, t);
        LOG.info("[DUCT-TRN] stall drain failed duct={} face={} reason={}", ductPos, face, reason);
    }

    public static void scheduleExtract(
            ServerLevel level,
            BlockPos sourceDuct,
            BlockPos destDuct,
            Direction destFace,
            int extracted,
            int destCap,
            ItemStack stack) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        if ((t + sourceDuct.asLong()) % 40L != 0L) {
            return;
        }
        LOG.info(
                "[DUCT-TRN] schedule extract={} destCap={} source={} dest={} face={} item={}",
                extracted,
                destCap,
                sourceDuct,
                destDuct,
                destFace,
                stack.isEmpty() ? "empty" : stack.getHoverName().getString());
    }

    public static void deliveryPartialInsert(ServerLevel level, OutboundShipment s, int inserted, int remainder) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        if ((t + s.destDuct.asLong()) % 40L != 0L) {
            return;
        }
        LOG.info(
                "[DUCT-TRN] delivery partial insert inserted={} remainder={} source={} dest={} item={}",
                inserted,
                remainder,
                s.refundDuct,
                s.destDuct,
                s.stack.isEmpty() ? "empty" : s.stack.getHoverName().getString());
    }

    public static void itemDeliveryStallRemainder(
            ServerLevel level,
            OutboundShipment s,
            ItemStack refund,
            DeliveryFailDetail detail) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        if ((t + s.destDuct.asLong()) % 40L != 0L) {
            return;
        }
        LOG.info(
                "[DUCT-TRN] delivery stall remainder detail={} source={} dest={} count={} item={}",
                detail,
                s.refundDuct,
                s.destDuct,
                refund.isEmpty() ? 0 : refund.getCount(),
                refund.isEmpty() ? "empty" : refund.getHoverName().getString());
    }

    public static void itemDeliveryStallImmediate(
            ServerLevel level,
            OutboundShipment s,
            DeliveryFailDetail detail,
            ItemStack refund,
            int pendingSame,
            int physicalCap) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        long t = level.getGameTime();
        if ((t + s.destDuct.asLong()) % 40L != 0L) {
            return;
        }
        LOG.info(
                "[DUCT-TRN] delivery stall immediate detail={} source={} dest={} pendingSame={} physicalCap={} "
                        + "count={} item={}",
                detail,
                s.refundDuct,
                s.destDuct,
                pendingSame,
                physicalCap,
                refund.isEmpty() ? 0 : refund.getCount(),
                refund.isEmpty() ? "empty" : refund.getHoverName().getString());
    }

    public static void itemDeliveryDefer(ServerLevel level, OutboundShipment s, DeliveryFailDetail detail) {
        if (!Config.DUCT_TRANSIT_DEBUG.get()) {
            return;
        }
        LOG.info(
                "[DUCT-TRN] delivery defer detail={} source={} dest={} deferTicks={} deferSpent={} count={} item={}",
                detail,
                s.refundDuct,
                s.destDuct,
                s.deliveryDeferTicks,
                s.deliveryDeferSpent,
                s.stack.isEmpty() ? 0 : s.stack.getCount(),
                s.stack.isEmpty() ? "empty" : s.stack.getHoverName().getString());
    }

    public static void bfsReachabilityMismatch(
            ServerLevel level,
            BlockPos from,
            BlockPos to,
            DuctNetworkType network,
            long componentId,
            int memberCount,
            boolean radioactiveGasSubgraph) {
        if (!Config.DUCT_ROUTING_DEBUG.get()) {
            return;
        }
        LOG.warn(
                "[DUCT-RTG] BFS unreachable but member: from={} to={} network={} componentId={} members={} radioactive={}",
                from,
                to,
                network,
                componentId,
                memberCount,
                radioactiveGasSubgraph);
    }

    public static void cacheInvalidate(
            ServerLevel level, @Nullable DuctNetworkType network, int topologyGeneration, int componentCount) {
        if (!Config.DUCT_CACHE_DEBUG.get()) {
            return;
        }
        String caller = abbreviatedCaller();
        LOG.info(
                "[DUCT-CCH] invalidate network={} topologyGen={} components~={} caller={}",
                network != null ? network : "ALL",
                topologyGeneration,
                componentCount,
                caller);
    }

    public static void endpointCacheBuild(
            ServerLevel level, long componentId, DuctNetworkType networkType, int endpointCount, int memberCount) {
        if (!Config.DUCT_ROUTING_DEBUG.get()) {
            return;
        }
        LOG.info(
                "[DUCT-RTG] endpoint index build componentId={} network={} endpoints={} members={}",
                componentId,
                networkType,
                endpointCount,
                memberCount);
    }

    private static String abbreviatedCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 8); i++) {
            String cn = stack[i].getClassName();
            if (!cn.equals(DuctTransitDebugLog.class.getName())
                    && !cn.equals(DuctNetworkCache.class.getName())
                    && cn.startsWith("net.unfamily.another_dynamics")) {
                return stack[i].getClassName() + "." + stack[i].getMethodName();
            }
        }
        return "unknown";
    }
}
