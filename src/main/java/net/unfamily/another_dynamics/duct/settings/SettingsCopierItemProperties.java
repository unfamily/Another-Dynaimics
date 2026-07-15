package net.unfamily.another_dynamics.duct.settings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Item model predicates for {@link net.unfamily.another_dynamics.item.SettingsCopierItem}.
 * Two booleans (0/1) avoid ambiguous {@code >=} matching on a single float threshold.
 * See {@code assets/.../models/item/settings_copier.json} override order.
 */
public final class SettingsCopierItemProperties {
    /** 1 = {@link SettingsCopierStoreKind#FILTER} (see {@link ModDataComponents#SETTINGS_COPIER_FILTER}). */
    public static final Identifier COPIER_FILTER =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "copier_filter");
    /** 1 = snapshot present in {@link ModDataComponents#DUCT_FACE_SETTINGS}. */
    public static final Identifier COPIER_FILLED =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "copier_filled");

    private SettingsCopierItemProperties() {}

    public static float copierFilter(ItemStack stack) {
        return isFilterMode(stack) ? 1.0F : 0.0F;
    }

    public static float copierFilled(ItemStack stack) {
        return DuctFaceSettingsSnapshot.hasStoredSettings(stack) ? 1.0F : 0.0F;
    }

    /**
     * Authoritative filter mode: component first, then snapshot {@link SettingsCopierStoreKind#TAG}.
     */
    public static boolean isFilterMode(ItemStack stack) {
        var filterType = ModDataComponents.SETTINGS_COPIER_FILTER.get();
        if (stack.has(filterType)) {
            return Boolean.TRUE.equals(stack.get(filterType));
        }
        if (!DuctFaceSettingsSnapshot.hasStoredSettings(stack)) {
            return false;
        }
        CompoundTag tag = stack.get(ModDataComponents.DUCT_FACE_SETTINGS.get());
        return tag != null
                && tag.contains(SettingsCopierStoreKind.TAG)
                && SettingsCopierStoreKind.fromTag(
                                tag.getByteOr(SettingsCopierStoreKind.TAG, (byte) 0))
                        == SettingsCopierStoreKind.FILTER;
    }
}
