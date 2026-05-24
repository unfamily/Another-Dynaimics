package net.unfamily.another_dynamics.duct.logistics;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Fluid handler operations that respect {@link DuctTankSlotSemantics} per internal tank index.
 */
public final class DuctFluidCapHelper {
    private DuctFluidCapHelper() {}

    /** Simulate fill (mB) into input/both tanks only. */
    public static int simulateFill(IFluidHandler handler, FluidStack stack) {
        if (handler == null || stack.isEmpty()) {
            return 0;
        }
        if (handler.getTanks() <= 1) {
            return handler.fill(stack, IFluidHandler.FluidAction.SIMULATE);
        }
        int fillableSpace = 0;
        for (int t = 0; t < handler.getTanks(); t++) {
            if (!DuctTankSlotSemantics.canFillFluidTank(handler, t, stack)) {
                continue;
            }
            FluidStack in = handler.getFluidInTank(t);
            if (!in.isEmpty() && !FluidStack.isSameFluidSameComponents(in, stack)) {
                continue;
            }
            fillableSpace += Math.max(0, handler.getTankCapacity(t) - in.getAmount());
        }
        if (fillableSpace <= 0) {
            return 0;
        }
        FluidStack probe = new FluidStack(stack.getFluid(), Math.min(stack.getAmount(), fillableSpace));
        return Math.min(handler.fill(probe, IFluidHandler.FluidAction.SIMULATE), fillableSpace);
    }

    /** Simulate matching-fluid drain up to {@code maxMb} from output/both tanks (aggregated). */
    public static FluidStack simulateDrainMatching(IFluidHandler handler, FluidStack template, int maxMb) {
        if (handler == null || template.isEmpty() || maxMb <= 0) {
            return FluidStack.EMPTY;
        }
        int need = maxMb;
        FluidStack total = FluidStack.EMPTY;
        for (int t = 0; t < handler.getTanks() && need > 0; t++) {
            if (!DuctTankSlotSemantics.canDrainFromFluidTank(handler, t)) {
                continue;
            }
            FluidStack in = handler.getFluidInTank(t);
            if (in.isEmpty() || !FluidStack.isSameFluidSameComponents(in, template)) {
                continue;
            }
            while (need > 0) {
                in = handler.getFluidInTank(t);
                if (in.isEmpty()) {
                    break;
                }
                int take = Math.min(need, in.getAmount());
                if (take <= 0) {
                    break;
                }
                FluidStack sim = handler.drain(new FluidStack(in.getFluid(), take), IFluidHandler.FluidAction.SIMULATE);
                if (sim.isEmpty()) {
                    break;
                }
                if (total.isEmpty()) {
                    total = new FluidStack(sim.getFluid(), sim.getAmount());
                } else {
                    total = new FluidStack(total.getFluid(), total.getAmount() + sim.getAmount());
                }
                need -= sim.getAmount();
                if (sim.getAmount() < take) {
                    break;
                }
            }
        }
        return total;
    }

    /** First non-empty fluid found on drainable tanks (for discovery). */
    public static FluidStack drainProbe(IFluidHandler handler, int maxMb) {
        if (handler == null || maxMb <= 0) {
            return FluidStack.EMPTY;
        }
        for (int t = 0; t < handler.getTanks(); t++) {
            if (!DuctTankSlotSemantics.canDrainFromFluidTank(handler, t)) {
                continue;
            }
            FluidStack in = handler.getFluidInTank(t);
            if (in.isEmpty()) {
                continue;
            }
            int take = Math.min(maxMb, in.getAmount());
            FluidStack sim = handler.drain(new FluidStack(in.getFluid(), take), IFluidHandler.FluidAction.SIMULATE);
            if (!sim.isEmpty()) {
                return sim;
            }
        }
        return FluidStack.EMPTY;
    }

    public static FluidStack drainMatching(IFluidHandler handler, FluidStack template, int maxMb) {
        if (handler == null || template.isEmpty() || maxMb <= 0) {
            return FluidStack.EMPTY;
        }
        FluidStack sim = simulateDrainMatching(handler, template, maxMb);
        if (sim.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return handler.drain(sim.copy(), IFluidHandler.FluidAction.EXECUTE);
    }

    public static int executeFill(IFluidHandler handler, FluidStack stack) {
        if (handler == null || stack.isEmpty()) {
            return 0;
        }
        int sim = simulateFill(handler, stack);
        if (sim <= 0) {
            return 0;
        }
        FluidStack chunk = new FluidStack(stack.getFluid(), Math.min(sim, stack.getAmount()));
        return handler.fill(chunk, IFluidHandler.FluidAction.EXECUTE);
    }
}
