package net.unfamily.another_dynamics.inventory;

import java.util.EnumMap;

import net.unfamily.another_dynamics.duct.DuctTransportKind;

/** Per-transport-kind client filter mirrors for multi-lane duct menus. */
final class ClientFilterMirrorStore {
    private final EnumMap<DuctTransportKind, ClientFilterLaneMirror> mirrors =
            new EnumMap<>(DuctTransportKind.class);

    ClientFilterMirrorStore() {
        for (DuctTransportKind kind : DuctTransportKind.values()) {
            mirrors.put(kind, new ClientFilterLaneMirror());
        }
    }

    ClientFilterLaneMirror mirror(DuctTransportKind kind) {
        return mirrors.get(kind);
    }

    ClientFilterLaneMirror mirror(int transportKindOrdinal) {
        DuctTransportKind[] kinds = DuctTransportKind.values();
        if (transportKindOrdinal < 0 || transportKindOrdinal >= kinds.length) {
            return mirrors.get(DuctTransportKind.ITEM);
        }
        return mirrors.get(kinds[transportKindOrdinal]);
    }
}
