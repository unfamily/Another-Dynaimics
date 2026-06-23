package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
