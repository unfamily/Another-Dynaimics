package net.unfamily.another_dynamics.registry;

import java.util.Comparator;
import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AnotherDynamicsMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DUCTS =
            CREATIVE_MODE_TABS.register("ducts", ModCreativeTabs::buildDuctsTab);

    private ModCreativeTabs() {}

    private static CreativeModeTab buildDuctsTab() {
        return CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.another_dynamics.ducts"))
                .icon(ModCreativeTabs::tabIconStack)
                .displayItems((parameters, output) -> {
                    DuctDefinitionRegistry.all().values().stream()
                            .filter(DuctDefinition::putInCreativeMenu)
                            .sorted(Comparator.comparing(DuctDefinition::logicalId))
                            .map(d -> ModItems.itemForDuctLogicalId(d.logicalId()))
                            .flatMap(Optional::stream)
                            .map(ItemStack::new)
                            .forEach(output::accept);
                })
                .build();
    }

    /**
     * Prefer {@link DuctIds#ITEM_DUCT} when that definition is creative-enabled; otherwise first creative-enabled
     * duct that resolves to an item; finally barrier.
     */
    private static ItemStack tabIconStack() {
        Optional<DuctDefinition> itemDuctDef = DuctDefinitionRegistry.getByLogicalId(DuctIds.ITEM_DUCT);
        if (itemDuctDef.isPresent()
                && itemDuctDef.get().putInCreativeMenu()
                && ModItems.itemForDuctLogicalId(DuctIds.ITEM_DUCT).isPresent()) {
            return new ItemStack(ModItems.ITEM_DUCT.get());
        }
        return DuctDefinitionRegistry.all().values().stream()
                .filter(DuctDefinition::putInCreativeMenu)
                .sorted(Comparator.comparing(DuctDefinition::logicalId))
                .map(d -> ModItems.itemForDuctLogicalId(d.logicalId()))
                .flatMap(Optional::stream)
                .findFirst()
                .map(ItemStack::new)
                .orElseGet(() -> new ItemStack(Items.BARRIER));
    }
}
