package net.unfamily.another_dynamics.duct.settings;

import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/** Stored payload / GUI mode for {@link net.unfamily.another_dynamics.item.SettingsCopierItem}. */
public enum SettingsCopierStoreKind {
    ALL,
    FILTER;

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
        return ALL;
    }

    public static SettingsCopierStoreKind fromCompound(net.minecraft.nbt.CompoundTag tag) {
        if (tag != null && tag.contains(TAG)) {
            return fromTag(tag.getByteOr(TAG, (byte) 0));
        }
        return ALL;
    }

    /** Authoritative mode for tooltips, hub GUI, model predicates, and Copy/Paste checks. */
    public static SettingsCopierStoreKind getMode(ItemStack stack) {
        return isFilterMode(stack) ? FILTER : ALL;
    }

    /** @see SettingsCopierItemProperties#isFilterMode */
    public static boolean isFilterMode(ItemStack stack) {
        return SettingsCopierItemProperties.isFilterMode(stack);
    }

    public static void setMode(ItemStack stack, SettingsCopierStoreKind kind) {
        if (kind == FILTER) {
            stack.set(ModDataComponents.SETTINGS_COPIER_FILTER.get(), true);
        } else {
            stack.remove(ModDataComponents.SETTINGS_COPIER_FILTER.get());
        }
    }

    public static void clear(ItemStack stack) {
        stack.remove(ModDataComponents.DUCT_FACE_SETTINGS.get());
        stack.remove(ModDataComponents.SETTINGS_COPIER_FILTER.get());
    }
}
