package net.unfamily.another_dynamics.registry;

import java.util.Optional;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnotherDynamicsMod.MOD_ID);

    public static final DeferredItem<BlockItem> DUCT =
            ITEMS.register(
                    "duct",
                    () ->
                            new BlockItem(
                                    ModBlocks.DUCT.get(),
                                    new Item.Properties()
                                            .component(ModDataComponents.DUCT_LOGICAL_ID.get(), DuctIds.DEFAULT_LOGICAL_ID)));

    private ModItems() {}

    /** Creative / recipe-friendly stack: one physical item, logical type in {@link ModDataComponents#DUCT_LOGICAL_ID}. */
    public static ItemStack createDuctStack(String logicalId) {
        ItemStack stack = new ItemStack(DUCT.get());
        stack.set(ModDataComponents.DUCT_LOGICAL_ID.get(), logicalId);
        return stack;
    }

    /**
     * Resolves the {@link Item} for a duct {@link DuctDefinition#logicalId()} that declares item transport in the
     * datapack. All such ducts share {@link #DUCT} with a per-stack data component.
     */
    public static Optional<Item> itemForDuctLogicalId(String logicalId) {
        if (logicalId == null || logicalId.isEmpty()) {
            return Optional.empty();
        }
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .filter(d -> d.itemTransport().isPresent())
                .map(d -> DUCT.get());
    }
}
