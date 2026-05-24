package net.unfamily.another_dynamics.duct.settings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Item model predicate input for {@link net.unfamily.another_dynamics.item.SettingsCopierItem}.
 * 0 = empty, 0.5 = all snapshot, 1 = filter snapshot (explicit components / Kind only).
 */
public final class SettingsCopierItemProperties {
    public static final ResourceLocation COPIER_STATE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "copier_state");

    /** Empty (base texture). */
    public static final float STATE_EMPTY = 0.0F;
    /** Stored all-mode snapshot. */
    public static final float STATE_ALL = 0.5F;
    /** Stored filter-mode snapshot. */
    public static final float STATE_FILTER = 1.0F;

    private SettingsCopierItemProperties() {}

    public static float copierState(ItemStack stack) {
        if (!DuctFaceSettingsSnapshot.hasStoredSettings(stack)) {
            return STATE_EMPTY;
        }
        return isFilterMode(stack) ? STATE_FILTER : STATE_ALL;
    }

    /** Filter when {@link ModDataComponents#SETTINGS_COPIER_FILTER} or snapshot {@link SettingsCopierStoreKind#TAG} says so. */
    public static boolean isFilterMode(ItemStack stack) {
        if (Boolean.TRUE.equals(stack.get(ModDataComponents.SETTINGS_COPIER_FILTER.get()))) {
            return true;
        }
        if (!DuctFaceSettingsSnapshot.hasStoredSettings(stack)) {
            return false;
        }
        CompoundTag tag = stack.get(ModDataComponents.DUCT_FACE_SETTINGS.get());
        return tag != null
                && tag.contains(SettingsCopierStoreKind.TAG, Tag.TAG_BYTE)
                && SettingsCopierStoreKind.fromTag(tag.getByte(SettingsCopierStoreKind.TAG))
                        == SettingsCopierStoreKind.FILTER;
    }
}
