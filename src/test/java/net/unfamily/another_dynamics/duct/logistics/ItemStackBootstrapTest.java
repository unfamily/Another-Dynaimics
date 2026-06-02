package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ItemStackBootstrapTest {
    @Test
    void emptyStackAccessible() {
        assertFalse(ItemStack.EMPTY.isEmpty() == false && ItemStack.EMPTY.getCount() > 0);
    }
}
