package net.unfamily.another_dynamics.duct;

import net.unfamily.another_dynamics.duct.logistics.OutboundShipment;

/**
 * Item ducts use {@link DuctFaceNode#channelLetter} (1–26): two nodes interact on the network only when their letters
 * match. Legacy shipments without NBT {@code TC} use {@link #LEGACY_WILDCARD} and skip enforcement.
 */
public final class DuctChannelPolicy {
    public static final int LEGACY_WILDCARD = 0;
    private DuctChannelPolicy() {}

    public static boolean sameChannel(int channelLetterA, int channelLetterB) {
        return channelLetterA == channelLetterB;
    }

    public static boolean enforcesChannel(OutboundShipment shipment) {
        return shipment.transportChannel != LEGACY_WILDCARD;
    }

    /** True if {@code faceChannel} is still valid for an in-flight or completing shipment. */
    public static boolean faceMatchesShipment(int faceChannelLetter, OutboundShipment shipment) {
        if (!enforcesChannel(shipment)) {
            return true;
        }
        return sameChannel(faceChannelLetter, shipment.transportChannel);
    }
}
