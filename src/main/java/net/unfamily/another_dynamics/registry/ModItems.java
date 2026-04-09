package net.unfamily.another_dynamics.registry;

import java.util.Optional;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockItem;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctTransportKind;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnotherDynamicsMod.MOD_ID);

    public static final DeferredItem<DuctBlockItem> DUCT =
            ITEMS.register(
                    "duct",
                    () ->
                            new DuctBlockItem(
                                    ModBlocks.DUCT.get(),
                                    new Item.Properties()));

    public static final DeferredItem<DuctBlockItem> FLUID_DUCT =
            ITEMS.register(
                    "fluid_duct",
                    () ->
                            new DuctBlockItem(
                                    ModBlocks.FLUID_DUCT.get(),
                                    new Item.Properties(),
                                    "another_dynamics:fluid_duct"));

    public static final DeferredItem<DuctBlockItem> ITEM_FLUID_DUCT =
            ITEMS.register(
                    "item_fluid_duct",
                    () ->
                            new DuctBlockItem(
                                    ModBlocks.ITEM_FLUID_DUCT.get(),
                                    new Item.Properties(),
                                    "another_dynamics:item_fluid_duct"));

    private ModItems() {}

    /** Creative / recipe-friendly stack: block item matches enabled transport kinds; logical id in component. */
    public static ItemStack createDuctStack(String logicalId) {
        ItemStack stack = new ItemStack(itemForDuctLogicalId(logicalId).orElse(DUCT.get()));
        stack.set(ModDataComponents.DUCT_LOGICAL_ID.get(), logicalId);
        return stack;
    }

    /**
     * Block item to place for this duct definition: item-only, fluid-only, or hybrid block.
     */
    public static Optional<Item> itemForDuctLogicalId(String logicalId) {
        if (logicalId == null || logicalId.isEmpty()) {
            return Optional.empty();
        }
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .map(
                        d -> {
                            var kinds = d.enabledTransportKinds();
                            boolean hasItem = kinds.contains(DuctTransportKind.ITEM);
                            boolean hasFluid = kinds.contains(DuctTransportKind.FLUID);
                            if (hasItem && hasFluid) {
                                return ITEM_FLUID_DUCT.get();
                            }
                            if (hasFluid && !hasItem) {
                                return FLUID_DUCT.get();
                            }
                            return DUCT.get();
                        });
    }
}
