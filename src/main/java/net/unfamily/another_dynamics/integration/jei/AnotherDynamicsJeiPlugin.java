package net.unfamily.another_dynamics.integration.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.registry.ModDataComponents;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Registers duct {@link ModItems#DUCT} subtypes by {@link ModDataComponents#DUCT_LOGICAL_ID} so JEI lists every
 * logical duct (matching the creative tab) instead of collapsing to one stack.
 */
@JeiPlugin
public final class AnotherDynamicsJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_ID =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(ModItems.DUCT.get(), DUCT_LOGICAL_ID_INTERPRETER);
    }

    public static final ISubtypeInterpreter<ItemStack> DUCT_LOGICAL_ID_INTERPRETER = new ISubtypeInterpreter<>() {
        @Override
        public Object getSubtypeData(ItemStack ingredient, UidContext context) {
            String id = ingredient.get(ModDataComponents.DUCT_LOGICAL_ID.get());
            if (id == null || id.isEmpty()) {
                id = DuctIds.DEFAULT_LOGICAL_ID;
            }
            return id;
        }

        @Override
        public String getLegacyStringSubtypeInfo(ItemStack ingredient, UidContext context) {
            String id = ingredient.get(ModDataComponents.DUCT_LOGICAL_ID.get());
            if (id == null || id.isEmpty()) {
                return DuctIds.DEFAULT_LOGICAL_ID;
            }
            return id;
        }
    };
}
