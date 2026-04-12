package net.unfamily.another_dynamics.integration.jei.ghost;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for GUI elements that support JEI ghost ingredient drops.
 * Implement this on your screen or widget to enable drag-and-drop from JEI.
 */
public interface IAnDynamicsGhostTarget {
    /**
     * Get the ghost ingredient consumer for this target.
     * Return null if this target doesn't support ghost ingredients.
     */
    @Nullable
    IGhostIngredientConsumer getGhostHandler();

    /**
     * Get the screen-space rectangle where drops are accepted.
     * This should return the bounding box of the slot/widget.
     *
     * @return The rectangle in screen coordinates where drops are allowed
     */
    @Nullable
    default Rect2i getGhostTargetArea() {
        return null;
    }

    /**
     * The border size in pixels to exclude from the clickable area.
     * Default is 0, but can be 1 for slots to exclude the texture border.
     */
    default int borderSize() {
        return 0;
    }

    /**
     * Base consumer interface for ghost ingredients.
     */
    interface IGhostIngredientConsumer {
        /**
         * Validate if this ingredient is supported.
         * Return non-null if supported, null otherwise.
         * The returned object is what will be passed to accept().
         */
        @Nullable
        Object supportedTarget(Object ingredient);

        /**
         * Accept the dropped ingredient and perform the action.
         */
        void accept(Object ingredient);
    }

    /**
     * Consumer for ItemStack ingredients.
     */
    interface IGhostItemConsumer extends IGhostIngredientConsumer {
        @Nullable
        @Override
        default ItemStack supportedTarget(Object ingredient) {
            return ingredient instanceof ItemStack stack && !stack.isEmpty()
                ? stack
                : null;
        }
    }

    /**
     * Consumer for FluidStack ingredients.
     */
    interface IGhostFluidConsumer extends IGhostIngredientConsumer {
        @Nullable
        @Override
        default FluidStack supportedTarget(Object ingredient) {
            return ingredient instanceof FluidStack stack && !stack.isEmpty()
                ? stack
                : null;
        }
    }

    /**
     * Consumer for gas/chemical ingredients (Mekanism Chemical type).
     * For custom implementations, the ingredient should be the chemical stack object.
     */
    interface IGhostGasConsumer extends IGhostIngredientConsumer {
        @Nullable
        @Override
        default Object supportedTarget(Object ingredient) {
            // Basic check - subclasses should override for specific gas types
            return ingredient != null ? ingredient : null;
        }
    }
}
