package net.unfamily.another_dynamics.duct.logistics;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Visual-only fluid blob moving along a duct path (logic already applied; this exists for client animation like
 * {@link OutboundShipment} for items).
 */
public final class FluidTransitShipment {
    public FluidStack fluid;
    public List<BlockPos> ductPath;
    public int totalTravelTicks;
    public int travelTicks;
    public int edgeTicks;
    public long journeyStartGameTime;
    public Direction sourceFace;
    public Direction destFace;

    public FluidTransitShipment(
            FluidStack fluid,
            List<BlockPos> ductPath,
            int totalTravelTicks,
            int travelTicks,
            int edgeTicks,
            long journeyStartGameTime,
            Direction sourceFace,
            Direction destFace) {
        this.fluid = fluid.copy();
        this.ductPath = ductPath;
        this.totalTravelTicks = totalTravelTicks;
        this.travelTicks = travelTicks;
        this.edgeTicks = Math.max(0, edgeTicks);
        this.journeyStartGameTime = journeyStartGameTime;
        this.sourceFace = sourceFace;
        this.destFace = destFace;
    }
}
