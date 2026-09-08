package net.unfamily.another_dynamics.machine.sequential;

import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Helpers for Settings Copier {@link SettingsCopierStoreKind#SEQUENTIAL} snapshots stored in
 * {@link ModDataComponents#SETTINGS_COPIER_SEQUENTIAL_DATA}.
 */
public final class SettingsCopierSequentialSnapshot {
    /** 0 = machine-wide, 1 = single Sequence List. */
    public static final String KIND_TAG = "SeqKind";

    private SettingsCopierSequentialSnapshot() {}

    public static boolean hasStoredSettings(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof SettingsCopierItem)) {
            return false;
        }
        CompoundTag tag = stack.get(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL_DATA.get());
        return tag != null && !tag.isEmpty();
    }

    public static Optional<CompoundTag> read(ItemStack stack) {
        if (!hasStoredSettings(stack)) {
            return Optional.empty();
        }
        CompoundTag tag = stack.get(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL_DATA.get());
        return tag == null ? Optional.empty() : Optional.of(tag.copy());
    }

    public static void write(ItemStack stack, CompoundTag snapshot) {
        SettingsCopierStoreKind.clear(stack);
        SettingsCopierStoreKind.setMode(stack, SettingsCopierStoreKind.SEQUENTIAL);
        stack.set(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL_DATA.get(), snapshot.copy());
    }

    public static Optional<ItemStack> findCopierInHands(Player player, ItemStack menuCarried) {
        if (!menuCarried.isEmpty() && menuCarried.getItem() instanceof SettingsCopierItem) {
            return Optional.of(menuCarried);
        }
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof SettingsCopierItem) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    public static Optional<ItemStack> findCopierWithSequentialData(Player player, ItemStack usedStack) {
        if (usedStack.getItem() instanceof SettingsCopierItem
                && SettingsCopierStoreKind.getMode(usedStack) == SettingsCopierStoreKind.SEQUENTIAL
                && hasStoredSettings(usedStack)) {
            return Optional.of(usedStack);
        }
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack other = player.getItemInHand(hand);
            if (other != usedStack
                    && other.getItem() instanceof SettingsCopierItem
                    && SettingsCopierStoreKind.getMode(other) == SettingsCopierStoreKind.SEQUENTIAL
                    && hasStoredSettings(other)) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }
}
