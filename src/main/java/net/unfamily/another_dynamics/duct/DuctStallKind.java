package net.unfamily.another_dynamics.duct;

/**
 * Which item stall buffer on a face receives refunded / failed shipments.
 *
 * <ul>
 *   <li>{@link #OUTBOUND} — network re-send toward extract destinations (donor / filter path).
 *   <li>{@link #INBOUND} — retriever-side stall drained into the adjacent machine inventory every tick.
 * </ul>
 */
public enum DuctStallKind {
    INBOUND,
    OUTBOUND
}
