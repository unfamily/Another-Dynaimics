package net.unfamily.another_dynamics.duct;

import net.minecraft.world.entity.player.Player;
import net.unfamily.another_dynamics.registry.ModAttachments;

/**
 * Resolves whether duct transit/skin should render opaque for a player and block.
 */
public final class DuctOpaqueRendering {
    private DuctOpaqueRendering() {}

    public static boolean definitionAlwaysOpaque(String logicalDuctId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalDuctId)
                .map(DuctDefinition::alwaysOpaqueRendering)
                .orElse(false);
    }

    public static boolean definitionAlwaysOpaque(DuctBlockEntity be) {
        return be.ductAlwaysOpaqueRendering();
    }

    public static boolean playerAllOpaque(Player player) {
        if (player == null) {
            return false;
        }
        return player.getData(ModAttachments.DUCT_PLAYER_OPAQUE.get()).allOpaqueActive();
    }

    public static boolean effectiveOpaque(DuctBlockEntity be, Player player) {
        if (be == null) {
            return playerAllOpaque(player);
        }
        return definitionAlwaysOpaque(be)
                || playerAllOpaque(player)
                || be.isNetworkOpaqueRendering();
    }

    public static boolean effectiveOpaque(String logicalDuctId, boolean networkOpaque, Player player) {
        return definitionAlwaysOpaque(logicalDuctId) || playerAllOpaque(player) || networkOpaque;
    }

    /** Opaque skin for duct items in hand/inventory (no network anchor). */
    public static boolean effectiveItemPreviewOpaque(String logicalDuctId, Player player) {
        return definitionAlwaysOpaque(logicalDuctId) || playerAllOpaque(player);
    }
}
