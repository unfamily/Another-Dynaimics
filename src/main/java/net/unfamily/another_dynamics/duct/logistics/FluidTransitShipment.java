package net.unfamily.another_dynamics.duct.logistics;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;

import net.unfamily.another_dynamics.duct.FilterGroupIds;

/**
 * Pending fluid move along a duct path: transfer runs when {@code travelTicks} reaches zero (planned-only, like modern
 * item {@link OutboundShipment}). Client receives path + fluid visual only via {@code getUpdateTag}; {@code destDuct} is
 * server-only.
 */
public final class FluidTransitShipment {
    public FluidStack fluid;
    public List<BlockPos> ductPath;
    public int totalTravelTicks;
    public int travelTicks;
    public int edgeTicks;
    public long journeyStartGameTime;
    public Direction sourceFace;
    /** Storage attachment face on {@link #destDuct}. */
    public Direction destFace;
    public BlockPos destDuct;

    /** {@link FilterGroupIds} for serialized fluid filter groups when {@code > 0}. */
    public int filterAllowGroupId;

    public FluidTransitShipment(
            FluidStack fluid,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            Direction sourceFace,
            Direction destStorageFace,
            BlockPos destDuct) {
        this.fluid = fluid.copy();
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
