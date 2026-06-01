package net.unfamily.another_dynamics.duct;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FilterLineTextUtilTest {

    @Test
    void emptyAndNull() {
        assertEquals("", FilterLineTextUtil.stripWrappingQuotes(""));
        assertEquals("", FilterLineTextUtil.stripWrappingQuotes(null));
    }

    @Test
    void stripsSingleQuotes() {
        assertEquals("minecraft:stone", FilterLineTextUtil.stripWrappingQuotes("'minecraft:stone'"));
    }

    @Test
    void stripsDoubleQuotes() {
        assertEquals("minecraft:stone", FilterLineTextUtil.stripWrappingQuotes("\"minecraft:stone\""));
    }

    @Test
    void stripsMixedWrappers() {
        assertEquals("minecraft:stone", FilterLineTextUtil.stripWrappingQuotes("'\"minecraft:stone\"'"));
    }

    @Test
    void preservesInnerQuotes() {
        assertEquals(
                "?{id:\"minecraft:stick\"}",
                FilterLineTextUtil.stripWrappingQuotes("'?{id:\"minecraft:stick\"}'"));
    }

    @Test
    void noChangeWithoutWrappers() {
        assertEquals("-minecraft:iron_ingot", FilterLineTextUtil.stripWrappingQuotes("-minecraft:iron_ingot"));
    }
}
