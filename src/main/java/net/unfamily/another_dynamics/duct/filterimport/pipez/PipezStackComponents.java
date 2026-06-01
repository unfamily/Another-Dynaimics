package net.unfamily.another_dynamics.duct.filterimport.pipez;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;

/** Reads Pipez upgrade data components from stacks without a compile-time Pipez dependency. */
final class PipezStackComponents {
    private static final String PIPEZ = "pipez";

    private PipezStackComponents() {}

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
        if (!root.contains("components", Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag components = root.getCompound("components");
        String key = PIPEZ + ":" + channel.pipezComponentId();
        if (components.contains(key, Tag.TAG_COMPOUND)) {
            return components.getCompound(key);
        }
        return null;
    }

    static boolean hasAnyPipezChannel(ItemStack stack, HolderLookup.Provider registries) {
        for (FilterImportChannel ch : FilterImportChannel.values()) {
            if (readChannelData(stack, ch, registries) != null) {
                return true;
            }
        }
        return false;
    }

    static boolean isPipezUpgrade(ItemStack stack) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && PIPEZ.equals(id.getNamespace()) && id.getPath().endsWith("_upgrade");
    }
}
