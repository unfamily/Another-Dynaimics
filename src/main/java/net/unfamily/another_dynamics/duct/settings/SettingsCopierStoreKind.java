package net.unfamily.another_dynamics.duct.settings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/** Stored payload / GUI mode for {@link net.unfamily.another_dynamics.item.SettingsCopierItem}. */
public enum SettingsCopierStoreKind {
    ALL,
    FILTER,
    /** Six-face Pipez→AD import (and whole-duct paste). */
    WHOLE,
    /** Sequential Buffer Sequence Lists. */
    SEQUENTIAL;

    /** Legacy key inside {@link ModDataComponents#DUCT_FACE_SETTINGS} snapshot root. */
    public static final String TAG = "Kind";

    public byte toTag() {
        return (byte) ordinal();
    }

    public static SettingsCopierStoreKind fromTag(byte b) {
        int o = b & 0xFF;
        if (o == FILTER.ordinal()) {
            return FILTER;
        }
        if (o == WHOLE.ordinal()) {
            return WHOLE;
        }
        if (o == SEQUENTIAL.ordinal()) {
            return SEQUENTIAL;
        }
        return ALL;
    }

    public static SettingsCopierStoreKind fromCompound(CompoundTag tag) {
        if (tag != null && tag.contains(TAG, Tag.TAG_BYTE)) {
            return fromTag(tag.getByte(TAG));
        }
        return ALL;
    }

    /** Authoritative mode: FILTER > SEQUENTIAL > WHOLE > ALL. */
    public static SettingsCopierStoreKind getMode(ItemStack stack) {
        if (isFilterMode(stack)) {
            return FILTER;
        }
        if (isSequentialMode(stack)) {
            return SEQUENTIAL;
        }
        CompoundTag tag = stack.get(ModDataComponents.DUCT_FACE_SETTINGS.get());
        if (tag != null && fromCompound(tag) == WHOLE) {
            return WHOLE;
        }
        return ALL;
    }

    /** @see SettingsCopierItemProperties#isFilterMode */
    public static boolean isFilterMode(ItemStack stack) {
        return SettingsCopierItemProperties.isFilterMode(stack);
    }

    /** @see SettingsCopierItemProperties#isSequentialMode */
    public static boolean isSequentialMode(ItemStack stack) {
        return SettingsCopierItemProperties.isSequentialMode(stack);
    }

    public static void setMode(ItemStack stack, SettingsCopierStoreKind kind) {
        if (kind == FILTER) {
            stack.set(ModDataComponents.SETTINGS_COPIER_FILTER.get(), true);
            stack.remove(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL.get());
        } else if (kind == SEQUENTIAL) {
            stack.set(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL.get(), true);
            stack.remove(ModDataComponents.SETTINGS_COPIER_FILTER.get());
        } else {
            stack.remove(ModDataComponents.SETTINGS_COPIER_FILTER.get());
            stack.remove(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL.get());
        }
    }

    public static void clear(ItemStack stack) {
        stack.remove(ModDataComponents.DUCT_FACE_SETTINGS.get());
        stack.remove(ModDataComponents.SETTINGS_COPIER_FILTER.get());
        stack.remove(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL.get());
        stack.remove(ModDataComponents.SETTINGS_COPIER_SEQUENTIAL_DATA.get());
    }
}
