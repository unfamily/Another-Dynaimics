package net.unfamily.another_dynamics.duct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DuctNetworkOpaquePropagationTest {

    @Test
    void playerOnlyCycleTogglesAll() {
        DuctPlayerOpaqueState off = DuctPlayerOpaqueState.DEFAULT;
        assertFalse(off.allOpaqueActive());
        DuctPlayerOpaqueState on = off.withAllOpaqueActive(true);
        assertTrue(on.allOpaqueActive());
        assertTrue(on.absoluteOpaquePreferred());
        DuctPlayerOpaqueState offAgain = on.withAllOpaqueActive(false);
        assertFalse(offAgain.allOpaqueActive());
        assertTrue(offAgain.absoluteOpaquePreferred());
    }

    @Test
    void displayStateOffWithoutPlayer() {
        assertEquals(DuctOpaqueDisplayState.OFF, DuctOpaqueDisplayState.forContext(null, null, null));
    }
}
