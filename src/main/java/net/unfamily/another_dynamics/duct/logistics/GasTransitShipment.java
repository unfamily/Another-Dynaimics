package net.unfamily.another_dynamics.duct.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

import net.unfamily.another_dynamics.duct.FilterGroupIds;

/**
 * Pending gas ("chemical") move along a duct path: transfer runs when {@code travelTicks} reaches zero.
 *
 * <p>Stored stack is {@code Object} to keep Mekanism optional and avoid hard dependencies.</p>
 */
public final class GasTransitShipment {
    /** Mekanism ChemicalStack instance (reflection-only). */
    public Object stack;
    public List<BlockPos> ductPath;
    public int totalTravelTicks;
    public int travelTicks;
    public int edgeTicks;
    public long journeyStartGameTime;
    public Direction sourceFace;
    /** Storage attachment face on {@link #destDuct}. */
    public Direction destFace;
    public BlockPos destDuct;

    /** {@link FilterGroupIds} when {@code > 0} (parity with fluid/item grouped filter execution). */
    public int filterAllowGroupId;

    public GasTransitShipment(
            Object stack,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            Direction sourceFace,
            Direction destStorageFace,
            BlockPos destDuct) {
        this.stack = stack;
        this.ductPath = ductPath;
        this.totalTravelTicks = totalTravelTicks;
        this.travelTicks = travelTicks;
        this.edgeTicks = Math.max(0, edgeTicks);
        this.journeyStartGameTime = journeyStartGameTime;
        this.sourceFace = sourceFace;
        this.destFace = destStorageFace;
        this.destDuct = destDuct.immutable();
        this.filterAllowGroupId = 0;
    }
}

