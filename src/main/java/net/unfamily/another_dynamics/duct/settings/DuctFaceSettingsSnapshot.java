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
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Captures / restores per-face duct node settings (shared face state, modules, item/fluid/gas lanes) for the
 * {@link SettingsCopierItem}. Cross-duct: only lanes the target definition supports are applied.
 */
public final class DuctFaceSettingsSnapshot {
    public static final int FORMAT_VERSION = 1;

    private static final String KEY_FMT = "Fmt";
    private static final String KEY_SHARED = "Shared";
    private static final String KEY_MODULES = "Modules";
    private static final String KEY_ITEM = "Item";
    private static final String KEY_FLUID = "Fluid";
    private static final String KEY_GAS = "Gas";

    private DuctFaceSettingsSnapshot() {}

    public static boolean hasStoredSettings(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof SettingsCopierItem)) {
            return false;
        }
        CompoundTag tag = stack.get(ModDataComponents.DUCT_FACE_SETTINGS);
        return tag != null && !tag.isEmpty() && tag.contains(KEY_FMT, Tag.TAG_INT);
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
        lanes.ensureTransportEnabledMask(kinds);

        CompoundTag root = new CompoundTag();
        root.putInt(KEY_FMT, FORMAT_VERSION);

        CompoundTag shared = new CompoundTag();
        shared.putByte("NodeMode", (byte) lanes.nodeMode.ordinal());
        shared.putByte("RedstoneMode", (byte) lanes.redstoneMode);
        shared.putInt("TransportMask", lanes.transportEnabledMask);
        root.put(KEY_SHARED, shared);

        root.put(KEY_MODULES, lanes.moduleSlots.serializeNBT(registries));

        CompoundTag itemTag = new CompoundTag();
        lanes.item.saveSettings(registries, itemTag);
        root.put(KEY_ITEM, itemTag);

        if (kinds.contains(DuctTransportKind.FLUID)) {
            CompoundTag fluidTag = new CompoundTag();
            lanes.fluid.saveSettings(registries, fluidTag);
            root.put(KEY_FLUID, fluidTag);
        }
        if (kinds.contains(DuctTransportKind.GAS)) {
            CompoundTag gasTag = new CompoundTag();
            lanes.gas.saveSettings(registries, gasTag);
            root.put(KEY_GAS, gasTag);
        }
        return root;
    }

    public static boolean apply(
            DuctBlockEntity be, Direction face, CompoundTag data, HolderLookup.Provider registries, Player player) {
        if (data == null || data.isEmpty() || !data.contains(KEY_FMT, Tag.TAG_INT)) {
            return false;
        }
        if (data.getInt(KEY_FMT) != FORMAT_VERSION) {
            return false;
        }
        EnumSet<DuctTransportKind> kinds =
                be.ductDefinition().map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        DuctFaceLanes lanes = be.getFaceLanes(face);

        if (data.contains(KEY_SHARED, Tag.TAG_COMPOUND)) {
            applySharedSettings(lanes, data.getCompound(KEY_SHARED), kinds);
        }

        if (data.contains(KEY_MODULES, Tag.TAG_COMPOUND)) {
            applyModules(lanes, data.getCompound(KEY_MODULES), registries);
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

        be.finishFaceSettingsRestore(face);
        return true;
    }

    public static Optional<CompoundTag> readFromCopier(ItemStack stack) {
        if (!hasStoredSettings(stack)) {
            return Optional.empty();
        }
        return Optional.of(stack.get(ModDataComponents.DUCT_FACE_SETTINGS).copy());
    }

    public static void writeToCopier(ItemStack stack, CompoundTag snapshot) {
        stack.set(ModDataComponents.DUCT_FACE_SETTINGS, snapshot);
    }

    private static void applySharedSettings(DuctFaceLanes lanes, CompoundTag shared, EnumSet<DuctTransportKind> kinds) {
        lanes.nodeMode = NodeMode.fromOrdinal(shared.getByte("NodeMode"));
        lanes.redstoneMode = shared.getByte("RedstoneMode") & 0xFF;
        if (shared.contains("TransportMask", Tag.TAG_INT)) {
            lanes.transportEnabledMask = shared.getInt("TransportMask");
        }
        lanes.ensureTransportEnabledMask(kinds);
    }

    private static void applyModules(DuctFaceLanes lanes, CompoundTag modulesTag, HolderLookup.Provider registries) {
        ItemStackHandler probe = new ItemStackHandler(modulesTag.contains("Size", Tag.TAG_INT) ? modulesTag.getInt("Size") : 0);
        if (probe.getSlots() <= 0) {
            return;
        }
        probe.deserializeNBT(registries, modulesTag);
        int limit = Math.min(probe.getSlots(), lanes.moduleSlots.getSlots());
        for (int i = 0; i < limit; i++) {
            lanes.moduleSlots.setStackInSlot(i, probe.getStackInSlot(i).copy());
        }
        for (int i = limit; i < lanes.moduleSlots.getSlots(); i++) {
            lanes.moduleSlots.setStackInSlot(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }
}
