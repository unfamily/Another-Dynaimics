package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.unfamily.another_dynamics.registry.ModAttachments;

/** Effective opaque label state for duct GUI (off / network / all). */
public enum DuctOpaqueDisplayState {
    OFF,
    NETWORK,
    ALL;

    public static DuctOpaqueDisplayState forContext(Player player, Level level, BlockPos anchor) {
        if (player != null && player.getData(ModAttachments.DUCT_PLAYER_OPAQUE.get()).allOpaqueActive()) {
            return ALL;
        }
        if (level != null && anchor != null && DuctNetworkOpaquePropagation.componentHasNetworkOpaque(level, anchor)) {
            return NETWORK;
        }
        return OFF;
    }

    public String labelKey() {
        return switch (this) {
            case OFF -> "gui.another_dynamics.duct_node.opaque_rendering.off";
            case NETWORK -> "gui.another_dynamics.duct_node.opaque_rendering.network";
            case ALL -> "gui.another_dynamics.duct_node.opaque_rendering.all";
        };
    }

    public String tooltipKey() {
        return switch (this) {
            case OFF -> "gui.another_dynamics.duct_node.opaque_rendering.tooltip.off";
            case NETWORK -> "gui.another_dynamics.duct_node.opaque_rendering.tooltip.network";
            case ALL -> "gui.another_dynamics.duct_node.opaque_rendering.tooltip.all";
        };
    }
}
