package net.unfamily.another_dynamics.duct.settings;

import java.util.EnumSet;
import java.util.Optional;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Captures / restores per-face duct <em>configuration</em> for the {@link SettingsCopierItem} (not physical contents).
 * <p><strong>Universal {@code all} format:</strong> NBT keys ({@code Shared}, {@code Item}, {@code Fluid}, {@code Gas},
 * {@code EnergyHeat}) match {@link DuctFaceLanes#saveCopierSettings} / {@link DuctFaceLanes#loadCopierSettings} on a
 * universal duct face — copying from universal and pasting onto another universal is near 1:1 for GUI settings. Single-kind
 * ducts store only the lanes they support ({@link DuctDefinition#enabledTransportKinds}).
 * <p>Never copied (see {@link DuctFaceLanes} copier exclusion list): module items, stall buffers, action tick cursors,
 * copy-slot stack, client-only state. Cross-duct paste: only lanes the target definition supports are applied.
 */
public final class DuctFaceSettingsSnapshot {
    /** v3+: energy/heat GUI fields via {@link DuctFaceLanes#saveCopierSettings} (modules explicitly excluded). */
    public static final int FORMAT_VERSION = 3;
    /** Still readable when pasting older copiers. */
    private static final int FORMAT_VERSION_LEGACY = 2;

    public static final String KEY_FMT = "Fmt";
    private static final String KEY_SHARED = "Shared";
    private static final String KEY_ITEM = "Item";
    private static final String KEY_FLUID = "Fluid";
    private static final String KEY_GAS = "Gas";
    private static final String KEY_ENERGY_HEAT = "EnergyHeat";

    private DuctFaceSettingsSnapshot() {}

    public static boolean acceptsSnapshotFormat(int fmt) {
        return fmt == FORMAT_VERSION || fmt == FORMAT_VERSION_LEGACY;
    }

    public static boolean hasStoredSettings(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof SettingsCopierItem)) {
            return false;
        }
        CompoundTag tag = stack.get(ModDataComponents.DUCT_FACE_SETTINGS.get());
        if (tag == null || tag.isEmpty() || !tag.contains(KEY_FMT, Tag.TAG_INT)) {
            return false;
        }
        return acceptsSnapshotFormat(tag.getInt(KEY_FMT));
    }

    public static SettingsCopierStoreKind getStoreKind(ItemStack stack) {
        return SettingsCopierStoreKind.getMode(stack);
    }

    public static boolean isAllPayload(CompoundTag tag) {
        if (tag == null || !tag.contains(KEY_FMT, Tag.TAG_INT)) {
            return false;
        }
        if (!acceptsSnapshotFormat(tag.getInt(KEY_FMT))) {
            return false;
        }
        return SettingsCopierStoreKind.fromCompound(tag) == SettingsCopierStoreKind.ALL;
    }

    /** Copier in the used hand, or the other hand if it holds stored settings. */
    public static Optional<ItemStack> findCopierWithData(Player player, ItemStack usedStack) {
        if (usedStack.getItem() instanceof SettingsCopierItem && hasStoredSettings(usedStack)) {
            return Optional.of(usedStack);
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack other = player.getItemInHand(hand);
            if (other != usedStack
                    && other.getItem() instanceof SettingsCopierItem
                    && hasStoredSettings(other)) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    /** Copier on menu cursor or in either player hand (for GUI copy/paste). */
    public static Optional<ItemStack> findCopierInHands(Player player, ItemStack menuCarried) {
        if (!menuCarried.isEmpty() && menuCarried.getItem() instanceof SettingsCopierItem) {
            return Optional.of(menuCarried);
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof SettingsCopierItem) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    public static CompoundTag capture(DuctBlockEntity be, Direction face, HolderLookup.Provider registries) {
        DuctFaceLanes lanes = be.getFaceLanes(face);
        EnumSet<DuctTransportKind> kinds =
                be.ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));

        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FMT, FORMAT_VERSION);
        root.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.ALL.toTag());
        lanes.saveCopierSettings(registries, root, kinds);
        return root;
    }

    public static boolean apply(
            DuctBlockEntity be, Direction face, CompoundTag data, HolderLookup.Provider registries, Player player) {
        if (data == null || data.isEmpty() || !isAllPayload(data)) {
            return false;
        }
        EnumSet<DuctTransportKind> kinds =
                be.ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        DuctFaceLanes lanes = be.getFaceLanes(face);

        int fmt = data.getInt(KEY_FMT);
        if (fmt == FORMAT_VERSION) {
            lanes.loadCopierSettings(registries, data, kinds);
        } else {
            applyLegacyV2(lanes, data, registries, kinds);
        }

        be.finishFaceSettingsRestore(face);
        return true;
    }

    public static CompoundTag captureFromLanes(
            DuctFaceLanes lanes, HolderLookup.Provider registries, EnumSet<DuctTransportKind> kinds) {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FMT, FORMAT_VERSION);
        root.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.ALL.toTag());
        lanes.saveCopierSettings(registries, root, kinds);
        return root;
    }

    /** Pre-v3 copiers: shared + item/fluid/gas only (no modules / energy-heat block). */
    public static void applyLegacyToLanes(
            DuctFaceLanes lanes,
            CompoundTag data,
            HolderLookup.Provider registries,
            EnumSet<DuctTransportKind> kinds) {
        applyLegacyV2(lanes, data, registries, kinds);
    }

    /** Pre-v3 copiers: shared + item/fluid/gas only (no modules / energy-heat block). */
    private static void applyLegacyV2(
            DuctFaceLanes lanes,
            CompoundTag data,
            HolderLookup.Provider registries,
            EnumSet<DuctTransportKind> kinds) {
        if (data.contains(KEY_SHARED, Tag.TAG_COMPOUND)) {
            lanes.loadSharedSettings(data.getCompound(KEY_SHARED));
            lanes.ensureTransportEnabledMask(kinds);
        }
        if (data.contains(KEY_ITEM, Tag.TAG_COMPOUND) && kinds.contains(DuctTransportKind.ITEM)) {
            lanes.item.loadSettings(registries, data.getCompound(KEY_ITEM));
        }
        if (data.contains(KEY_FLUID, Tag.TAG_COMPOUND) && kinds.contains(DuctTransportKind.FLUID)) {
            lanes.fluid.loadSettings(registries, data.getCompound(KEY_FLUID));
        }
        if (data.contains(KEY_GAS, Tag.TAG_COMPOUND) && kinds.contains(DuctTransportKind.GAS)) {
            lanes.gas.loadSettings(registries, data.getCompound(KEY_GAS));
        }
    }

    public static Optional<CompoundTag> readFromCopier(ItemStack stack) {
        if (!hasStoredSettings(stack)) {
            return Optional.empty();
        }
        return Optional.of(stack.get(ModDataComponents.DUCT_FACE_SETTINGS.get()).copy());
    }

    public static void writeToCopier(ItemStack stack, CompoundTag snapshot) {
        stack.set(ModDataComponents.DUCT_FACE_SETTINGS.get(), snapshot);
        SettingsCopierStoreKind.setMode(stack, SettingsCopierStoreKind.fromCompound(snapshot));
    }
}
