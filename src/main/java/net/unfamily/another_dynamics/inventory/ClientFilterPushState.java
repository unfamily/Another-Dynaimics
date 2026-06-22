package net.unfamily.another_dynamics.inventory;

import net.unfamily.another_dynamics.duct.DuctFaceNode;

/**
 * Tracks whether client filter mirrors were loaded from the server and edited locally. Used to avoid pushing empty
 * filter buffers on GUI close before sync or without user edits.
 */
final class ClientFilterPushState {
    private static final int ALL_BANKS_MASK = (1 << DuctFaceNode.FilterBank.values().length) - 1;

    private int syncTransportKind = -1;
    private int syncBankMask;
    private boolean dirty;

    void onServerFilterSync(int transportKindOrdinal, int filterBankOrdinal) {
        if (transportKindOrdinal != syncTransportKind) {
            syncTransportKind = transportKindOrdinal;
            syncBankMask = 0;
        }
        if (filterBankOrdinal >= 0 && filterBankOrdinal < DuctFaceNode.FilterBank.values().length) {
            syncBankMask |= 1 << filterBankOrdinal;
        }
        boolean wasDirty = dirty;
        dirty = false;
        FilterSyncDebugLog.log(
                "CLIENT",
                "PUSH_STATE_SYNC",
                FilterSyncDebugLog.pushState(transportKindOrdinal, hydrated(), dirty, syncBankMask)
                        + " bank="
                        + FilterSyncDebugLog.bankName(filterBankOrdinal)
                        + " wasDirty="
                        + wasDirty);
    }

    void markDirty(int transportKindOrdinal) {
        dirty = true;
        FilterSyncDebugLog.log(
                "CLIENT",
                "PUSH_STATE_DIRTY",
                FilterSyncDebugLog.pushState(transportKindOrdinal, hydrated(), dirty, syncBankMask)
                        + " from="
                        + FilterSyncDebugLog.caller(4));
    }

    boolean hydrated() {
        return syncBankMask == ALL_BANKS_MASK;
    }

    boolean dirty() {
        return dirty;
    }

    boolean shouldPushOnClose() {
        return hydrated() && dirty;
    }

    void reset() {
        syncTransportKind = -1;
        syncBankMask = 0;
        dirty = false;
    }

    int syncTransportKind() {
        return syncTransportKind;
    }

    int syncBankMask() {
        return syncBankMask;
    }
}
