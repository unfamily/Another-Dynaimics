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
                            .filter(d -> ModItems.itemForDuctLogicalId(d.logicalId()).isPresent())
                            .sorted(Comparator.comparing(DuctDefinition::logicalId))
                            .map(d -> ModItems.createDuctStack(d.logicalId()))
                            .forEach(output::accept);
                    output.accept(ModItems.INC_MODULE_0.get().getDefaultInstance());
                    output.accept(ModItems.INC_MODULE_1.get().getDefaultInstance());
                    output.accept(ModItems.INC_MODULE_2.get().getDefaultInstance());
                    output.accept(ModItems.INC_MODULE_3.get().getDefaultInstance());
                    output.accept(ModItems.INC_MODULE_4.get().getDefaultInstance());
                    output.accept(ModItems.INC_MODULE_5.get().getDefaultInstance());
                    output.accept(ModItems.INC_MODULE_6.get().getDefaultInstance());
                    output.accept(ModItems.FIL_MODULE_0.get().getDefaultInstance());
                    output.accept(ModItems.FIL_MODULE_1.get().getDefaultInstance());
                    output.accept(ModItems.FIL_MODULE_2.get().getDefaultInstance());
                    output.accept(ModItems.FIL_MODULE_3.get().getDefaultInstance());
                    output.accept(ModItems.FIL_MODULE_4.get().getDefaultInstance());
                    output.accept(ModItems.NETHERITE_NUGGET.get().getDefaultInstance());
                    output.accept(ModItems.RESONANTING_CONDUCTOR.get().getDefaultInstance());
                    output.accept(ModItems.ENDER_ACCELERANT.get().getDefaultInstance());
                    output.accept(ModItems.SETTINGS_COPIER.get().getDefaultInstance());
                    output.accept(ModItems.BULKY_WRENCH.get().getDefaultInstance());
                    output.accept(ModItems.PROJECT_DUCT.get().getDefaultInstance());
                })
                .build();
    }

    /**
     * Prefer {@link DuctIds#DEFAULT_LOGICAL_ID} when that definition is creative-enabled; otherwise first creative-enabled
     * duct that resolves to an item; finally barrier.
     */
    private static ItemStack tabIconStack() {
        Optional<DuctDefinition> itemDuctDef = DuctDefinitionRegistry.getByLogicalId(DuctIds.DEFAULT_LOGICAL_ID);
        if (itemDuctDef.isPresent()
                && itemDuctDef.get().putInCreativeMenu()
                && ModItems.itemForDuctLogicalId(DuctIds.DEFAULT_LOGICAL_ID).isPresent()) {
            return ModItems.createDuctStack(DuctIds.DEFAULT_LOGICAL_ID);
        }
        return DuctDefinitionRegistry.all().values().stream()
                .filter(DuctDefinition::putInCreativeMenu)
                .filter(d -> ModItems.itemForDuctLogicalId(d.logicalId()).isPresent())
                .sorted(Comparator.comparing(DuctDefinition::logicalId))
                .map(d -> ModItems.createDuctStack(d.logicalId()))
                .findFirst()
                .orElseGet(() -> new ItemStack(Items.BARRIER));
    }
}
