package net.unfamily.another_dynamics.duct;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalUpgradeFilterImportSource;
import net.unfamily.another_dynamics.item.RemoteNodeSelectorItem;
import net.unfamily.another_dynamics.registry.ModItems;

/** Reads a bound duct endpoint from held destination tools (runtime-only optional mod integration). */
public final class DestinationToolEndpointReader {
    private static final Identifier EXTERNAL_FILTER_REMOTE_NODE_TOOL =
            Identifier.fromNamespaceAndPath(
                    ExternalUpgradeFilterImportSource.COMPANION_NAMESPACE, "filter_destination_tool");
    private static final String EXTERNAL_DIRECTIONAL_COMPONENT =
            ExternalUpgradeFilterImportSource.COMPANION_NAMESPACE + ":directional_position";

    private DestinationToolEndpointReader() {}

    /**
     * @return bound endpoint, or {@code null} when the stack is empty, not a destination tool, or the tool has no
     *         destination set (empty destination clears the filter line binding).
     */
    @Nullable
    public static DuctDirectionalEndpoint readFromItemStack(
            ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() == ModItems.REMOTE_NODE_SELECTOR.get()) {
            return RemoteNodeSelectorItem.getEndpoint(stack);
        }
        if (!ExternalUpgradeFilterImportSource.isCompanionModLoaded()) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!EXTERNAL_FILTER_REMOTE_NODE_TOOL.equals(id)) {
            return null;
        }
        return readExternalDirectionalComponent(stack, registries);
    }

    public static boolean isDestinationTool(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() == ModItems.REMOTE_NODE_SELECTOR.get()) {
            return true;
        }
        if (!ExternalUpgradeFilterImportSource.isCompanionModLoaded()) {
            return false;
        }
        return EXTERNAL_FILTER_REMOTE_NODE_TOOL.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    @Nullable
    private static DuctDirectionalEndpoint readExternalDirectionalComponent(
            ItemStack stack, HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
        if (!(encoded instanceof CompoundTag root)) {
            return null;
        }
        if (!root.contains("components", Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag components = root.getCompound("components");
        if (!components.contains(EXTERNAL_DIRECTIONAL_COMPONENT, Tag.TAG_COMPOUND)) {
            return null;
        }
        return DuctDirectionalEndpoint.fromTag(components.getCompound(EXTERNAL_DIRECTIONAL_COMPONENT));
    }
}
