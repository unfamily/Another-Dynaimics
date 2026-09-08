package net.unfamily.another_dynamics.duct.settings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.machine.sequential.SettingsCopierSequentialSnapshot;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Item model predicates for {@link net.unfamily.another_dynamics.item.SettingsCopierItem}.
 * Two booleans (0/1) avoid ambiguous {@code >=} matching on a single float threshold.
 * See {@code assets/.../items/settings_copier.json} condition order.
 */
public final class SettingsCopierItemProperties {
    /** 1 = {@link SettingsCopierStoreKind#FILTER} (see {@link ModDataComponents#SETTINGS_COPIER_FILTER}). */
    public static final Identifier COPIER_FILTER =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "copier_filter");
    /** 1 = {@link SettingsCopierStoreKind#SEQUENTIAL} (see {@link ModDataComponents#SETTINGS_COPIER_SEQUENTIAL}). */
    public static final Identifier COPIER_SEQUENTIAL =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "copier_sequential");
    /** 1 = snapshot present (duct face settings or sequential data). */
    public static final Identifier COPIER_FILLED =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "copier_filled");

    private SettingsCopierItemProperties() {}

    public static float copierFilter(ItemStack stack) {
        return isFilterMode(stack) ? 1.0F : 0.0F;
    }

    public static float copierSequential(ItemStack stack) {
        return isSequentialMode(stack) ? 1.0F : 0.0F;
    }

    public static float copierFilled(ItemStack stack) {
        if (isSequentialMode(stack)) {
            return SettingsCopierSequentialSnapshot.hasStoredSettings(stack) ? 1.0F : 0.0F;
        }
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

    /**
     * Authoritative sequential mode: component first, then presence of sequential snapshot data.
     */
    public static boolean isSequentialMode(ItemStack stack) {
        var sequentialType = ModDataComponents.SETTINGS_COPIER_SEQUENTIAL.get();
        if (stack.has(sequentialType)) {
            return Boolean.TRUE.equals(stack.get(sequentialType));
        }
        return SettingsCopierSequentialSnapshot.hasStoredSettings(stack);
    }
}
