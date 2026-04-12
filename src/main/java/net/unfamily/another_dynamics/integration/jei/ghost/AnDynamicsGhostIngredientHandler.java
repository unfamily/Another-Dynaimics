package net.unfamily.another_dynamics.integration.jei.ghost;

import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;

/**
 * JEI Ghost Ingredient Handler for Another-Dynamics screens.
 * Enables drag-and-drop functionality from JEI overlays into GUI elements that implement
 * {@link IAnDynamicsGhostTarget}.
 *
 * This handler scans screens for ghost-capable targets and creates drop zones for dragged
 * ingredients (items, fluids, gases, etc.).
 */
public class AnDynamicsGhostIngredientHandler<
    T extends Screen
> implements IGhostIngredientHandler<T> {

    /**
     * Called by JEI when an ingredient is being dragged over a screen.
     * Returns a list of valid drop targets for the given ingredient.
     *
     * @param gui The screen being hovered over
     * @param ingredient The typed ingredient being dragged
     * @param doStart True if the ingredient was just picked up, false if still hovering
     * @return List of valid drop targets for this ingredient
     */
    @Override
    public <I> List<Target<I>> getTargetsTyped(
        T gui,
        ITypedIngredient<I> ingredient,
        boolean doStart
    ) {
        List<Target<I>> targets = new ArrayList<>();

        // Check if the screen itself implements the ghost target interface
        if (gui instanceof IAnDynamicsGhostTarget ghostTarget) {
            tryAddTarget(targets, ghostTarget, ingredient.getIngredient());
        }

        return targets;
    }

    /**
     * Attempt to add a ghost ingredient target if the ingredient is supported.
     */
    @SuppressWarnings("unchecked")
    private <I> void tryAddTarget(
        List<Target<I>> targets,
        IAnDynamicsGhostTarget ghostTarget,
        I ingredient
    ) {
        // Get the consumer for this target
        IAnDynamicsGhostTarget.IGhostIngredientConsumer consumer =
            ghostTarget.getGhostHandler();
        if (consumer == null) {
            return;
        }

        // Validate the ingredient - this determines if we can accept it
        Object validatedIngredient = consumer.supportedTarget(ingredient);
        if (validatedIngredient == null) {
            // Ingredient not supported by this target
            return;
        }

        // Get the target area where drops are accepted
        Rect2i area = ghostTarget.getGhostTargetArea();
        if (area == null) {
            // No valid drop area defined
            return;
        }

        // Create and add the target
        Target<I> target = new Target<I>() {
            @Override
            public Rect2i getArea() {
                return area;
            }

            @Override
            public void accept(I ingredientDropped) {
                // Accept the dropped ingredient - call the consumer with validated ingredient
                consumer.accept(validatedIngredient);
            }
        };

        targets.add(target);
    }

    /**
     * Called by JEI when the drag operation completes.
     * Used for cleanup if needed.
     */
    @Override
    public void onComplete() {
        // No cleanup needed for basic implementation
    }

    /**
     * Return true to let JEI handle target highlighting.
     */
    @Override
    public boolean shouldHighlightTargets() {
        return true;
    }
}
