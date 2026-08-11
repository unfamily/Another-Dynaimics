package net.unfamily.another_dynamics.duct;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.network.DuctGuiFeedbackPayload;
import net.unfamily.another_dynamics.network.ModNetwork;

/** Settings Copier user feedback: copy uses GUI transient text; paste uses colored action bar. */
public final class SettingsCopierFeedback {
    private SettingsCopierFeedback() {}

    public static void notifyCopied(ServerPlayer player) {
        ModNetwork.sendDuctGuiFeedback(player, DuctGuiFeedbackPayload.COPIED);
    }

    /** Pipez → Settings Copier whole-pipe import (no duct GUI open). */
    public static void notifyPipezImported(Player player) {
        sendActionBar(
                player,
                Component.translatable("message.another_dynamics.settings_copier.pipez_imported")
                        .withStyle(ChatFormatting.GREEN));
    }

    public static void notifyPasted(Player player) {
        notifyPasteActionBar(player, true);
    }

    public static void notifyPasteFailed(Player player) {
        notifyPasteActionBar(player, false);
    }

    public static void notifyPasteKindNotSet(Player player) {
        sendActionBar(
                player,
                Component.translatable("message.another_dynamics.settings_copier.paste_kind_not_set")
                        .withStyle(ChatFormatting.RED));
    }

    public static void notifyPasteKindMismatch(Player player, FilterListMaterialKind kind) {
        sendActionBar(
                player,
                Component.translatable(
                                "message.another_dynamics.settings_copier.paste_kind_mismatch",
                                kind.displayName())
                        .withStyle(ChatFormatting.RED));
    }

    public static void notifyWrongMode(ServerPlayer player) {
        ModNetwork.sendDuctGuiFeedback(player, DuctGuiFeedbackPayload.WRONG_MODE);
    }

    private static void notifyPasteActionBar(Player player, boolean success) {
        sendActionBar(
                player,
                Component.translatable(
                                success
                                        ? "message.another_dynamics.settings_copier.pasted"
                                        : "message.another_dynamics.settings_copier.paste_failed")
                        .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static void sendActionBar(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true);
        } else {
            player.sendSystemMessage(message);
        }
    }
}
