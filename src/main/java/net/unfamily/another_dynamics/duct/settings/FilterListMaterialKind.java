package net.unfamily.another_dynamics.duct.settings;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;

/**
 * Material interpretation for a portable filter list ({@link SettingsCopierStoreKind#FILTER}).
 * {@link #NONE} means the list is not configured for editing until the player picks a kind.
 */
public enum FilterListMaterialKind {
    NONE,
    ITEM,
    FLUID,
    GAS;

    private static final FilterListMaterialKind[] VALUES = values();

    public static FilterListMaterialKind fromOrdinal(int o) {
        if (o < 0 || o >= VALUES.length) {
            return NONE;
        }
        return VALUES[o];
    }

    /** Lane used for ghost sync and paste when not {@link #NONE}. */
    public DuctTransportKind toTransportKind() {
        return switch (this) {
            case ITEM -> DuctTransportKind.ITEM;
            case FLUID -> DuctTransportKind.FLUID;
            case GAS -> DuctTransportKind.GAS;
            case NONE -> DuctTransportKind.ITEM;
        };
    }

    public FilterListMaterialKind next() {
        return switch (this) {
            case NONE -> ITEM;
            case ITEM -> FLUID;
            case FLUID -> GAS;
            case GAS -> NONE;
        };
    }

    public static FilterListMaterialKind fromTransportKind(DuctTransportKind lane) {
        return switch (lane) {
            case FLUID -> FLUID;
            case GAS -> GAS;
            case ITEM, ENERGY, HEAT -> ITEM;
        };
    }

    public static FilterListMaterialKind fromImportChannel(FilterImportChannel channel) {
        return switch (channel) {
            case ITEM -> ITEM;
            case FLUID -> FLUID;
            case GAS -> GAS;
        };
    }

    public Component displayName() {
        return Component.translatable(
                "item.another_dynamics.settings_copier.filter_kind." + name().toLowerCase());
    }

    /** Whether this face has the transport lane required for paste. */
    public boolean canPasteToDuctFace(DuctBlockEntity duct, Direction face) {
        if (this == NONE) {
            return false;
        }
        DuctTransportKind lane = toTransportKind();
        if (!duct.ductDefinition()
                .map(DuctDefinition::enabledTransportKinds)
                .map(kinds -> kinds.contains(lane))
                .orElse(lane == DuctTransportKind.ITEM)) {
            return false;
        }
        return duct.isTransportKindEnabled(face, lane);
    }

    /** Client: transport enabled mask from {@link net.unfamily.another_dynamics.duct.DuctMenuSync#TRANSPORT_ENABLED_MASK}. */
    public boolean isEnabledInTransportMask(int transportEnabledMask) {
        if (this == NONE) {
            return false;
        }
        return (transportEnabledMask & (1 << toTransportKind().ordinal())) != 0;
    }
}
