package net.unfamily.another_dynamics.duct.filterimport.external;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;

/** Reads upgrade filter data components from stacks without a compile-time mod dependency. */
final class ExternalUpgradeStackComponents {
    private ExternalUpgradeStackComponents() {}

    @Nullable
    static CompoundTag readChannelData(ItemStack stack, FilterImportChannel channel, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return null;
        }
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
        if (!(encoded instanceof CompoundTag root)) {
            return null;
        }
        if (!root.contains("components")) {
            return null;
        }
        CompoundTag components = root.getCompoundOrEmpty("components");
        String key = ExternalUpgradeFilterImportSource.COMPANION_NAMESPACE + ":" + channel.componentSuffix();
        if (components.contains(key)) {
            return components.getCompoundOrEmpty(key);
        }
        return null;
    }

    static boolean hasAnyChannelData(ItemStack stack, HolderLookup.Provider registries) {
        for (FilterImportChannel ch : FilterImportChannel.values()) {
            if (readChannelData(stack, ch, registries) != null) {
                return true;
            }
        }
        return false;
    }

    static boolean isUpgradeItem(ItemStack stack) {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null
                && ExternalUpgradeFilterImportSource.COMPANION_NAMESPACE.equals(id.getNamespace())
                && id.getPath().endsWith("_upgrade");
    }
}
