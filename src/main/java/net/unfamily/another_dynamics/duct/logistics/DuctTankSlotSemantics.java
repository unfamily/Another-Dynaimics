package net.unfamily.another_dynamics.duct.logistics;

import java.lang.reflect.Method;

import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;

/**
 * Input / output / both classification for fluid tanks ({@link IFluidHandler}) and gas tanks (Mekanism chemical
 * handler), mirroring {@link DuctHandlerSlotSemantics} for item slots.
 */
public final class DuctTankSlotSemantics {
    public enum TankRole {
        INPUT,
        OUTPUT,
        BOTH
    }

    private static final Method FLUID_IS_VALID;

    static {
        Method m = null;
        try {
            m = IFluidHandler.class.getMethod("isFluidValid", int.class, FluidStack.class);
        } catch (ReflectiveOperationException ignored) {
        }
        FLUID_IS_VALID = m;
    }

    private DuctTankSlotSemantics() {}

    // --- Fluid ---

    public static TankRole roleForFluidTank(IFluidHandler handler, int tank) {
        if (handler == null || tank < 0 || tank >= handler.getTanks()) {
            return TankRole.BOTH;
        }
        FluidStack in = handler.getFluidInTank(tank);
        boolean canIn = canSimulateFillFluid(handler, tank, in);
        boolean canOut = canSimulateDrainFluid(handler, tank);
        if (canIn && canOut) {
            return TankRole.BOTH;
        }
        if (canIn) {
            return TankRole.INPUT;
        }
        if (canOut) {
            return TankRole.OUTPUT;
        }
        if (!in.isEmpty()) {
            return TankRole.BOTH;
        }
        return TankRole.BOTH;
    }

    public static boolean canDrainFromFluidTank(IFluidHandler handler, int tank) {
        if (!canSimulateDrainFluid(handler, tank)) {
            return false;
        }
        TankRole role = roleForFluidTank(handler, tank);
        return role != TankRole.INPUT;
    }

    public static boolean canFillFluidTank(IFluidHandler handler, int tank, FluidStack template) {
        if (template == null || template.isEmpty() || handler == null || tank < 0 || tank >= handler.getTanks()) {
            return false;
        }
        if (!canSimulateFillFluid(handler, tank, handler.getFluidInTank(tank), template)) {
            return false;
        }
        TankRole role = roleForFluidTank(handler, tank);
        return role != TankRole.OUTPUT;
    }

    private static boolean canSimulateDrainFluid(IFluidHandler handler, int tank) {
        if (handler == null || tank < 0 || tank >= handler.getTanks()) {
            return false;
        }
        FluidStack in = handler.getFluidInTank(tank);
        if (in.isEmpty()) {
            return false;
        }
        FluidStack sim =
                handler.drain(
                        new FluidStack(in.getFluid(), Math.min(1, in.getAmount())),
                        IFluidHandler.FluidAction.SIMULATE);
        return !sim.isEmpty();
    }

    private static boolean canSimulateFillFluid(IFluidHandler handler, int tank, FluidStack inTank) {
        return canSimulateFillFluid(handler, tank, inTank, null);
    }

    private static boolean canSimulateFillFluid(
            IFluidHandler handler, int tank, FluidStack inTank, FluidStack template) {
        if (handler == null || tank < 0 || tank >= handler.getTanks()) {
            return false;
        }
        FluidStack probe;
        if (template != null && !template.isEmpty()) {
            probe = new FluidStack(template.getFluid(), 1);
        } else if (inTank != null && !inTank.isEmpty()) {
            probe = new FluidStack(inTank.getFluid(), 1);
        } else {
            probe = new FluidStack(Fluids.WATER, 1);
        }
        if (!isFluidValid(handler, tank, probe)) {
            return false;
        }
        int space = handler.getTankCapacity(tank) - handler.getFluidInTank(tank).getAmount();
        if (space <= 0) {
            return false;
        }
        if (handler.getTanks() == 1) {
            return handler.fill(probe, IFluidHandler.FluidAction.SIMULATE) > 0;
        }
        if (inTank != null && !inTank.isEmpty()) {
            return FluidStack.isSameFluidSameComponents(inTank, probe);
        }
        return isFluidValid(handler, tank, probe);
    }

    private static boolean isFluidValid(IFluidHandler handler, int tank, FluidStack stack) {
        if (FLUID_IS_VALID == null) {
            return true;
        }
        try {
            return (boolean) FLUID_IS_VALID.invoke(handler, tank, stack);
        } catch (Throwable ignored) {
            return true;
        }
    }

    // --- Gas (Mekanism chemical handler, reflection) ---

    public static TankRole roleForGasTank(Object handler, int tank) {
        if (handler == null || !MekanismChemicalCompat.isLoaded()) {
            return TankRole.BOTH;
        }
        try {
            int tanks = (int) handler.getClass().getMethod("getChemicalTanks").invoke(handler);
            if (tank < 0 || tank >= tanks) {
                return TankRole.BOTH;
            }
            Object inTank = handler.getClass().getMethod("getChemicalInTank", int.class).invoke(handler, tank);
            boolean canIn = MekanismChemicalCompat.canSimulateInsertTank(handler, tank, inTank);
            boolean canOut = MekanismChemicalCompat.canSimulateDrainTank(handler, tank);
            if (canIn && canOut) {
                return TankRole.BOTH;
            }
            if (canIn) {
                return TankRole.INPUT;
            }
            if (canOut) {
                return TankRole.OUTPUT;
            }
            if (!MekanismChemicalCompat.isEmptyStack(inTank)) {
                return TankRole.BOTH;
            }
        } catch (Throwable ignored) {
        }
        return TankRole.BOTH;
    }

    public static boolean canDrainFromGasTank(Object handler, int tank) {
        if (!MekanismChemicalCompat.canSimulateDrainTank(handler, tank)) {
            return false;
        }
        return roleForGasTank(handler, tank) != TankRole.INPUT;
    }

    public static boolean canFillGasTank(Object handler, int tank, Object template) {
        if (template == null
                || MekanismChemicalCompat.isEmptyStack(template)
                || !MekanismChemicalCompat.canSimulateInsertTank(handler, tank, template)) {
            return false;
        }
        return roleForGasTank(handler, tank) != TankRole.OUTPUT;
    }
}
