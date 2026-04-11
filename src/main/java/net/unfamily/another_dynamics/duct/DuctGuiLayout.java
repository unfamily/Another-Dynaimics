package net.unfamily.another_dynamics.duct;

import net.minecraft.util.Mth;

/**
 * Shared GUI geometry for duct node screen and datapack clamping of {@link DuctDefinition#upgradeSlotCount()}.
 */
public final class DuctGuiLayout {
    /** Matches first chrome button row in {@link net.unfamily.another_dynamics.client.gui.DuctNodeScreen}. */
    public static final int ROW1_Y = 32;

    /**
     * Gui-local Y of the first upgrade slot frame (SINGLE_SLOT blit). Must match {@link net.unfamily.another_dynamics.inventory.DuctNodeMenu#SLOT_UPGRADE_BACKGROUND_Y0}.
     */
    public static final int UPGRADE_COLUMN_FIRST_SLOT_Y = ROW1_Y + 1;

    /**
     * Must stay in sync with {@link net.unfamily.another_dynamics.inventory.DuctNodeMenu#PLAYER_SLOTS_Y} (player band top).
     */
    private static final int PLAYER_SLOTS_GUI_Y = 171;

    /** Bottom of upgrade column must stay above player inventory with this margin (gui-local pixels). */
    public static final int UPGRADE_COLUMN_BOTTOM_MARGIN = 8;

    /**
     * Hard cap for datapack {@code upgrade_slots} (and menu slot count). Open-menu sync uses a single byte; keep
     * {@code <= 255}.
     */
    public static final int MAX_UPGRADE_SLOTS = 12;

    private DuctGuiLayout() {}

    /**
     * Rough count of upgrade slots that fit between {@link #UPGRADE_COLUMN_FIRST_SLOT_Y} and the player band on
     * {@code node.png} without overlap; {@link #clampUpgradeSlotCount} uses {@link #MAX_UPGRADE_SLOTS} instead.
     */
    public static int maxUpgradeSlotsByLayout() {
        int bottomSafe = PLAYER_SLOTS_GUI_Y - UPGRADE_COLUMN_BOTTOM_MARGIN;
        return Math.max(0, (bottomSafe - UPGRADE_COLUMN_FIRST_SLOT_Y) / 18);
    }

    public static int clampUpgradeSlotCount(int requested) {
        return Mth.clamp(requested, 0, MAX_UPGRADE_SLOTS);
    }
}
