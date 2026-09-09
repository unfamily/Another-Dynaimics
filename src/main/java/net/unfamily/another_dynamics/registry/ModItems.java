package net.unfamily.another_dynamics.registry;

import java.util.Optional;
import java.util.function.UnaryOperator;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockItem;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctModuleItem;
import net.unfamily.another_dynamics.item.RemoteNodeSelectorItem;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.item.SummaryTooltipBlockItem;
import net.unfamily.another_dynamics.item.SummaryTooltipItem;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnotherDynamicsMod.MOD_ID);
    private static final boolean MEKANISM_LOADED = ModList.get().isLoaded("mekanism");

    public static final DeferredItem<Item> NETHERITE_NUGGET = ITEMS.registerSimpleItem("netherite_nugget");

    public static final DeferredItem<Item> RESONANTING_CONDUCTOR = ITEMS.registerSimpleItem("resonanting_conductor");

    public static final DeferredItem<Item> ENDER_ACCELERANT = ITEMS.registerSimpleItem("ender_accellerant");

    /** GuideME navigation icon only (Filters page); not listed in the creative tab. */
    public static final DeferredItem<Item> GUIDE_FILTER_ICON = ITEMS.registerSimpleItem("guide_filter_icon");

    /** GuideME navigation icon only (Transport kinds page); not listed in the creative tab. */
    public static final DeferredItem<Item> GUIDE_TRANSPORT_ICON = ITEMS.registerSimpleItem("guide_transport_icon");

    public static final DeferredItem<SettingsCopierItem> SETTINGS_COPIER =
            ITEMS.registerItem("settings_copier", SettingsCopierItem::new, props -> props.stacksTo(1));

    public static final DeferredItem<SummaryTooltipItem> BULKY_WRENCH =
            ITEMS.registerItem(
                    "bulky_wrench",
                    props -> new SummaryTooltipItem(props, "item.another_dynamics.bulky_wrench.tooltip.summary"),
                    props -> props.stacksTo(1));

    public static final DeferredItem<RemoteNodeSelectorItem> REMOTE_NODE_SELECTOR =
            ITEMS.registerItem("remote_node_selector", RemoteNodeSelectorItem::new, props -> props.stacksTo(1));

    public static final DeferredItem<DuctBlockItem> DUCT =
            ITEMS.registerItem(
                    "duct",
                    props -> new DuctBlockItem(ModBlocks.DUCT.get(), props),
                    UnaryOperator.identity());

    public static final DeferredItem<SummaryTooltipBlockItem> PROJECT_DUCT =
            ITEMS.registerItem(
                    "project_duct",
                    props ->
                            new SummaryTooltipBlockItem(
                                    ModBlocks.PROJECT_DUCT.get(),
                                    props,
                                    "item.another_dynamics.project_duct.tooltip.summary"),
                    UnaryOperator.identity());

    public static final DeferredItem<DuctModuleItem> INC_MODULE_0 = registerModule("inc_module_0");
    public static final DeferredItem<DuctModuleItem> INC_MODULE_1 = registerModule("inc_module_1");
    public static final DeferredItem<DuctModuleItem> INC_MODULE_2 = registerModule("inc_module_2");
    public static final DeferredItem<DuctModuleItem> INC_MODULE_3 = registerModule("inc_module_3");
    public static final DeferredItem<DuctModuleItem> INC_MODULE_4 = registerModule("inc_module_4");

    public static final DeferredItem<DuctBlockItem> FLUID_DUCT =
            ITEMS.registerItem(
                    "fluid_duct",
                    props ->
                            new DuctBlockItem(
                                    ModBlocks.FLUID_DUCT.get(),
                                    props,
                                    "another_dynamics:fluid_duct"),
                    UnaryOperator.identity());

    public static final DeferredItem<DuctBlockItem> ITEM_FLUID_DUCT =
            ITEMS.registerItem(
                    "item_fluid_duct",
                    props ->
                            new DuctBlockItem(
                                    ModBlocks.ITEM_FLUID_DUCT.get(),
                                    props,
                                    "another_dynamics:item_fluid_duct"),
                    UnaryOperator.identity());

    public static final DeferredItem<SummaryTooltipBlockItem> SEQUENTIAL_BUFFER =
            ITEMS.registerItem(
                    "sequential_buffer",
                    props ->
                            new SummaryTooltipBlockItem(
                                    ModBlocks.SEQUENTIAL_BUFFER.get(),
                                    props,
                                    "item.another_dynamics.sequential_buffer.tooltip.summary"),
                    UnaryOperator.identity());

    public static final DeferredItem<SummaryTooltipBlockItem> MACHINE_CONNECTOR =
            ITEMS.registerItem(
                    "machine_connector",
                    props ->
                            new SummaryTooltipBlockItem(
                                    ModBlocks.MACHINE_CONNECTOR.get(),
                                    props,
                                    "item.another_dynamics.machine_connector.tooltip.summary"),
                    UnaryOperator.identity());

    /** Only registered when Mekanism is present. */
    public static final DeferredItem<DuctBlockItem> GAS_DUCT =
            MEKANISM_LOADED && ModBlocks.GAS_DUCT != null
                    ? ITEMS.registerItem(
                            "gas_duct",
                            props ->
                                    new DuctBlockItem(
                                            ModBlocks.GAS_DUCT.get(),
                                            props,
                                            "another_dynamics:gas_duct"),
                            UnaryOperator.identity())
                    : null;

    private ModItems() {}

    private static DeferredItem<DuctModuleItem> registerModule(String id) {
        return ITEMS.registerItem(
                id,
                props ->
                        new DuctModuleItem(
                                props.component(
                                        ModDataComponents.DUCT_MODULE_DECLARATION.get(),
                                        Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, id))),
                UnaryOperator.identity());
    }

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
