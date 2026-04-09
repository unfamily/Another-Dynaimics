package net.unfamily.another_dynamics.integration.mekanism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Mekanism chemical (\"gas\") interop helpers.
 *
 * <p>Implementation is reflection-based so the project compiles and runs cleanly without Mekanism present.</p>
 */
public final class MekanismChemicalCompat {
    private MekanismChemicalCompat() {}

    public static final String MODID = "mekanism";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    @Nullable
    public static Object getChemicalHandlerOnFace(Level level, BlockPos ductPos, Direction ductOutwardFace) {
        if (level == null || !isLoaded()) {
            return null;
        }
        try {
            BlockPos adj = ductPos.relative(ductOutwardFace);
            Direction ctx = ductOutwardFace.getOpposite();
            @SuppressWarnings("unchecked")
            BlockCapability<Object, @Nullable Direction> cap = (BlockCapability<Object, @Nullable Direction>) chemicalCapability();
            return level.getCapability(cap, adj, ctx);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Capability lookup on a specific block/side (no offset).
     *
     * <p>Use this for probing external blocks during attachment discovery.</p>
     */
    @Nullable
    public static Object getChemicalHandlerAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (level == null || pos == null || !isLoaded()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            BlockCapability<Object, @Nullable Direction> cap =
                    (BlockCapability<Object, @Nullable Direction>) chemicalCapability();
            return level.getCapability(cap, pos, side);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object chemicalCapability() throws ReflectiveOperationException {
        // mekanism.common.capabilities.Capabilities.CHEMICAL.block()
        Class<?> c = Class.forName("mekanism.common.capabilities.Capabilities");
        Object chemicalCap = c.getField("CHEMICAL").get(null);
        return chemicalCap.getClass().getMethod("block").invoke(chemicalCap);
    }

    /** Returns a Mekanism ChemicalStack instance (or EMPTY) for a probe drain. */
    public static Object drainProbe(Object handler, long maxAmount) {
        Object empty = emptyStack();
        if (handler == null || maxAmount <= 0) {
            return empty;
        }
        try {
            int tanks = (int) handler.getClass().getMethod("getChemicalTanks").invoke(handler);
            Object actionSim = actionSimulate();
            for (int i = 0; i < tanks; i++) {
                Object inTank = handler.getClass().getMethod("getChemicalInTank", int.class).invoke(handler, i);
                if (isEmptyStack(inTank)) {
                    continue;
                }
                long amt = getAmount(inTank);
                long want = Math.min(maxAmount, amt);
                if (want <= 0) {
                    continue;
                }
                Object extracted =
                        handler.getClass()
                                .getMethod("extractChemical", int.class, long.class, actionSim.getClass())
                                .invoke(handler, i, want, actionSim);
                if (!isEmptyStack(extracted) && getAmount(extracted) > 0) {
                    return extracted;
                }
            }
        } catch (Throwable ignored) {
        }
        return empty;
    }

    /** Simulates insertion and returns inserted amount. */
    public static long simulateInsert(Object handler, Object stack) {
        if (handler == null || stack == null || isEmptyStack(stack)) {
            return 0L;
        }
        try {
            long before = getAmount(stack);
            Object left = handler.getClass().getMethod("insertChemical", stack.getClass(), actionSimulate().getClass())
                    .invoke(handler, stack, actionSimulate());
            long after = isEmptyStack(left) ? 0L : getAmount(left);
            return Math.max(0L, before - after);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Executes extraction (across tanks) and returns extracted stack (or EMPTY). */
    public static Object extractAny(Object handler, long amount) {
        Object empty = emptyStack();
        if (handler == null || amount <= 0) {
            return empty;
        }
        try {
            Object actionExec = actionExecute();
            Object extracted =
                    handler.getClass()
                            .getMethod("extractChemical", long.class, actionExec.getClass())
                            .invoke(handler, amount, actionExec);
            return extracted != null ? extracted : empty;
        } catch (Throwable ignored) {
            return empty;
        }
    }

    /** Executes insertion and returns remaining stack (or EMPTY). */
    public static Object insertExecute(Object handler, Object stack) {
        Object empty = emptyStack();
        if (handler == null || stack == null || isEmptyStack(stack)) {
            return empty;
        }
        try {
            Object actionExec = actionExecute();
            Object left =
                    handler.getClass()
                            .getMethod("insertChemical", stack.getClass(), actionExec.getClass())
                            .invoke(handler, stack, actionExec);
            return left != null ? left : empty;
        } catch (Throwable ignored) {
            return empty;
        }
    }

    public static Object copyWithAmount(Object stack, long amount) {
        if (stack == null) {
            return emptyStack();
        }
        try {
            return stack.getClass().getMethod("copyWithAmount", long.class).invoke(stack, amount);
        } catch (Throwable ignored) {
            return emptyStack();
        }
    }

    @Nullable
    public static String getTypeRegistryName(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return null;
        }
        try {
            Object rl = stack.getClass().getMethod("getTypeRegistryName").invoke(stack);
            return rl != null ? rl.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns tag ids like {@code mekanism:foo} for the chemical in this stack. */
    public static List<String> getTagIds(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return List.of();
        }
        try {
            Object streamObj = stack.getClass().getMethod("getTags").invoke(stack);
            if (!(streamObj instanceof Stream<?> stream)) {
                return List.of();
            }
            ArrayList<String> out = new ArrayList<>();
            stream.forEach(
                    t -> {
                        if (t == null) {
                            return;
                        }
                        try {
                            Object rl = t.getClass().getMethod("location").invoke(t);
                            if (rl != null) {
                                out.add(rl.toString());
                            }
                        } catch (Throwable ignored2) {
                            // ignore
                        }
                    });
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static boolean isEmptyStack(Object stack) {
        if (stack == null) {
            return true;
        }
        try {
            return (boolean) stack.getClass().getMethod("isEmpty").invoke(stack);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static long getAmount(Object stack) {
        if (stack == null) {
            return 0L;
        }
        try {
            return (long) stack.getClass().getMethod("getAmount").invoke(stack);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static Object emptyStack() {
        try {
            Class<?> cls = Class.forName("mekanism.api.chemical.ChemicalStack");
            return cls.getField("EMPTY").get(null);
        } catch (Throwable ignored) {
            // Sentinel: null means empty in callers that only use reflection wrappers.
            return new Object();
        }
    }

    private static Object actionSimulate() throws ReflectiveOperationException {
        Class<?> a = Class.forName("mekanism.api.Action");
        return a.getField("SIMULATE").get(null);
    }

    private static Object actionExecute() throws ReflectiveOperationException {
        Class<?> a = Class.forName("mekanism.api.Action");
        return a.getField("EXECUTE").get(null);
    }
}

