package net.unfamily.another_dynamics.duct.logistics;

/** Reason a shipment was cancelled or refunded (optional dev logging). */
public enum LogisticsCancelReason {
    INSERT_FAIL,
    FILTER_DEST,
    FILTER_EXTRACT,
    STALE,
    KIND_CAP,
    RESIZE_ZERO,
    CHANNEL,
    REDSTONE,
    DISCONNECTED,
    ENTRY_FIRST_SKIP,
    MEDIA_DELIVERY_SKIP,
    MEDIA_FILTER_DEST,
    WRENCH_DISCONNECT
}
