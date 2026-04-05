package net.unfamily.another_dynamics.registry;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctIds;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnotherDynamicsMod.MOD_ID);

    public static final DeferredItem<BlockItem> ITEM_DUCT = ITEMS.register(
            "item_duct",
            () -> new BlockItem(ModBlocks.ITEM_DUCT.get(), new Item.Properties()));

    private ModItems() {}

    /**
     * Resolves the {@link Item} for a duct {@link DuctDefinition#logicalId()}: known deferred items first, then
     * {@code another_dynamics:<logicalId>} in the item registry.
     */
    public static Optional<Item> itemForDuctLogicalId(String logicalId) {
        if (DuctIds.ITEM_DUCT.equals(logicalId)) {
            return Optional.of(ITEM_DUCT.get());
        }
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, logicalId));
    }
}
