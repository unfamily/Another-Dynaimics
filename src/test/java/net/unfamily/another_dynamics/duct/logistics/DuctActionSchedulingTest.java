package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuctActionSchedulingTest {
    @Test
    void staggerOffsetIsWithinRate() {
        BlockPos pos = new BlockPos(12, 64, -7);
        for (Direction dir : Direction.values()) {
            int offset = DuctActionScheduling.staggerOffset(pos, dir, 20);
            assertTrue(offset >= 0 && offset < 20, "offset out of range for " + dir);
        }
    }

    @Test
    void staggerOffsetIsStable() {
        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(
                DuctActionScheduling.staggerOffset(pos, Direction.NORTH, 10),
                DuctActionScheduling.staggerOffset(pos, Direction.NORTH, 10));
    }

    @Test
    void differentFacesSpreadStaggerOffsets() {
        BlockPos pos = new BlockPos(100, 64, 200);
        int distinct = 0;
        int last = -1;
        for (Direction dir : Direction.values()) {
            int offset = DuctActionScheduling.staggerOffset(pos, dir, 20);
            if (offset != last) {
                distinct++;
                last = offset;
            }
        }
        assertTrue(distinct >= 4, "expected stagger spread across faces");
    }
}
