package net.unfamily.another_dynamics.duct.module;

import java.util.List;

import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Datapack duct module declaration ({@code another_dynamics:declare_module}).
 *
 * <p>Optional {@code matching_item_tags}: item stacks in those {@link TagKey}s are treated like stacks carrying
 * {@link net.unfamily.another_dynamics.registry.ModDataComponents#DUCT_MODULE_DECLARATION} with this definition's
 * {@link #id} (explicit component on the stack still wins).
 */
public record ModuleDefinition(
        Identifier id,
        int stackSize,
        int maxSlots,
        List<ModuleIncompatibility> incompatibleWith,
        /**
         * Pairwise {@code incompatible_with} checks apply only when the number of non-empty module slots on this face
         * (after the attempted operation) is greater than this value. Default {@code 1} matches legacy behavior.
         */
        int incompatibilityActivation,
        ItemQuantityModifiers itemQuantityModifiers,
        ItemQuantityModifiers itemRateModifiers,
        ItemQuantityModifiers itemSpeedModifiers,
        ItemQuantityModifiers itemSequentialStackModifiers,
        ItemQuantityModifiers fluidQuantityModifiers,
        ItemQuantityModifiers fluidRateModifiers,
        ItemQuantityModifiers fluidSpeedModifiers,
        ItemQuantityModifiers fluidSequentialStackModifiers,
        ItemQuantityModifiers gasQuantityModifiers,
        ItemQuantityModifiers gasRateModifiers,
        ItemQuantityModifiers gasSpeedModifiers,
        ItemQuantityModifiers gasSequentialStackModifiers,
        /**
         * Forge-energy lane: {@code affects[].for=energy.*} — parsed for datapack/KubeJS; no default JSON in the mod.
         */
        ItemQuantityModifiers energyQuantityModifiers,
        ItemQuantityModifiers energyRateModifiers,
        ItemQuantityModifiers energySpeedModifiers,
        /**
         * Mekanism heat lane: {@code affects[].for=heat.*} — parsed for datapack/KubeJS; no default JSON in the mod.
         */
        ItemQuantityModifiers heatQuantityModifiers,
        ItemQuantityModifiers heatRateModifiers,
        ItemQuantityModifiers heatSpeedModifiers,
        FilterSlotModifiers filterSlotsItem,
        FilterSlotModifiers filterSlotsFluid,
        FilterSlotModifiers filterSlotsGas,
        List<TagKey<Item>> matchingItemTags) {

    public static ModuleDefinition missing(Identifier id) {
        return new ModuleDefinition(
                id,
                64,
                1,
                List.of(),
                1,
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                ItemQuantityModifiers.none(),
                FilterSlotModifiers.none(),
                FilterSlotModifiers.none(),
                FilterSlotModifiers.none(),
                List.of());
    }

    /** Numeric modifiers merged with set (max wins), summed adds, multiplied mults (used for quantity, rate, speed). */
    public record ItemQuantityModifiers(boolean hasSet, int setValue, int addSum, double multProduct) {
        public static ItemQuantityModifiers none() {
            return new ItemQuantityModifiers(false, 0, 0, 1.0);
        }
    }

    /**
     * Extra filter line slots from {@code affects[].filter} when {@code for} matches {@code item}, {@code fluid}, or
     * {@code gas} (add values summed per key within that lane).
     */
    public record FilterSlotModifiers(int allowSlotAdd, int denySlotAdd, int allowHybridSlotAdd, int denyHybridSlotAdd) {
        public static FilterSlotModifiers none() {
            return new FilterSlotModifiers(0, 0, 0, 0);
        }
    }
}
