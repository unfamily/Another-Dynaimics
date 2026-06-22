package net.unfamily.another_dynamics.duct.logistics;

/** Item stall buffer lane: inbound (delivery/refund toward attached inventory) vs outbound (extract/network resend). */
public enum DuctStallKind {
    INBOUND,
    OUTBOUND
}
