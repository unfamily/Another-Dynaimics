package net.unfamily.another_dynamics.duct;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.unfamily.another_dynamics.network.DuctGuiFeedbackPayload;
import net.unfamily.another_dynamics.network.ModNetwork;

/** Settings Copier user feedback: copy uses GUI transient text; paste uses colored action bar. */
public final class SettingsCopierFeedback {
    private SettingsCopierFeedback() {}

    public static void notifyCopied(ServerPlayer player) {
        ModNetwork.sendDuctGuiFeedback(player, DuctGuiFeedbackPayload.COPIED);
    }

    public static void notifyPasted(Player player) {
        notifyPasteActionBar(player, true);
    }

    public static void notifyPasteFailed(Player player) {
        notifyPasteActionBar(player, false);
    }

    private static void notifyPasteActionBar(Player player, boolean success) {
        player.displayClientMessage(
                Component.translatable(
                                success
                                        ? "message.another_dynamics.settings_copier.pasted"
                                        : "message.another_dynamics.settings_copier.paste_failed")
                        .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED),
                true);
    }
}
