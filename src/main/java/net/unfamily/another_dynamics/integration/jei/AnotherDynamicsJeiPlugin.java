package net.unfamily.another_dynamics.integration.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.gui.DuctNodeScreen;
import net.unfamily.another_dynamics.client.gui.SettingsCopierScreen;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.integration.jei.ghost.AnDynamicsGhostIngredientHandler;
import net.unfamily.another_dynamics.registry.ModDataComponents;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Registers duct {@link ModItems#DUCT} subtypes by {@link ModDataComponents#DUCT_LOGICAL_ID} so JEI lists every
 * logical duct (matching the creative tab) instead of collapsing to one stack.
 *
 * Also registers ghost ingredient handlers for drag-and-drop support in GUI elements.
 */
@JeiPlugin
public final class AnotherDynamicsJeiPlugin implements IModPlugin {

    private static final Identifier PLUGIN_ID =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "jei_plugin"
        );

    @Override
    public Identifier getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(
            ModItems.DUCT.get(),
            DUCT_LOGICAL_ID_INTERPRETER
        );
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Register the ghost ingredient handler for DuctNodeScreen
        // This enables drag-and-drop from JEI into the filter calibration slot
        registration.addGhostIngredientHandler(
            DuctNodeScreen.class,
            new AnDynamicsGhostIngredientHandler<>()
        );
        registration.addGhostIngredientHandler(
            SettingsCopierScreen.class,
            new AnDynamicsGhostIngredientHandler<>()
        );
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeState.setRuntime(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRuntimeState.clearRuntime();
    }

    public static final ISubtypeInterpreter<
        ItemStack
    > DUCT_LOGICAL_ID_INTERPRETER = new ISubtypeInterpreter<>() {
        @Override
        public Object getSubtypeData(ItemStack ingredient, UidContext context) {
            String id = ingredient.get(ModDataComponents.DUCT_LOGICAL_ID.get());
            if (id == null || id.isEmpty()) {
                id = DuctIds.DEFAULT_LOGICAL_ID;
            }
            return id;
        }
    };
}
