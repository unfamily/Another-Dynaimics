package net.unfamily.another_dynamics.integration.jei;

import java.util.Optional;

import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-only runtime state bridge for optional JEI integration.
 *
 * <p>This class is only loaded when JEI is present (via {@link AnotherDynamicsJeiPlugin}).</p>
 */
public final class JeiRuntimeState {
    private static volatile IJeiRuntime RUNTIME;

    private JeiRuntimeState() {}

    static void setRuntime(IJeiRuntime runtime) {
        RUNTIME = runtime;
    }

    static void clearRuntime() {
        RUNTIME = null;
    }

    /**
     * True when JEI wants keyboard input (e.g. search box focused) or its recipes GUI is currently open.
     * Used to prevent container screens from reacting to Esc/E and other keys while JEI is actively handling them.
     */
    public static boolean jeiHasKeyboardFocusOrRecipesGuiOpen() {
        IJeiRuntime rt = RUNTIME;
        if (rt == null) {
            return false;
        }
        try {
            IIngredientListOverlay overlay = rt.getIngredientListOverlay();
            if (overlay != null && overlay.isListDisplayed() && overlay.hasKeyboardFocus()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            IRecipesGui recipesGui = rt.getRecipesGui();
            if (recipesGui != null) {
                Optional<Screen> parent = recipesGui.getParentScreen();
                if (parent != null && parent.isPresent()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}

