package net.unfamily.another_dynamics.registry;

import java.util.Optional;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockItem;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnotherDynamicsMod.MOD_ID);
    private static final boolean MEKANISM_LOADED = ModList.get().isLoaded("mekanism");

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

    /** Only registered when Mekanism is present. */
    public static final DeferredItem<DuctBlockItem> GAS_DUCT =
            MEKANISM_LOADED && ModBlocks.GAS_DUCT != null
                    ? ITEMS.register(
                            "gas_duct",
                            () ->
                                    new DuctBlockItem(
                                            ModBlocks.GAS_DUCT.get(),
                                            new Item.Properties(),
                                            "another_dynamics:gas_duct"))
                    : null;

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
                            // Single physical item/block for all duct definitions.
                            // The logical id drives enabled transport kinds and visuals.
                            return DUCT.get();
                        });
    }
}
