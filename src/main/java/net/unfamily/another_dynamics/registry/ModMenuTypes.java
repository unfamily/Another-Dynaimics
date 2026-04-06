package net.unfamily.another_dynamics.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;

public final class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AnotherDynamicsMod.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<DuctNodeMenu>> DUCT_NODE =
            MENUS.register("duct_node", () -> IMenuTypeExtension.create(DuctNodeMenu::createClient));

    private ModMenuTypes() {}
}
