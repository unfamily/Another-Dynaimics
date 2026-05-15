package net.unfamily.another_dynamics.integration.mekanism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.BlockCapability;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private static Object chemicalItemCapability() throws ReflectiveOperationException {
        Class<?> c = Class.forName("mekanism.common.capabilities.Capabilities");
        Object chemicalCap = c.getField("CHEMICAL").get(null);
        return chemicalCap.getClass().getMethod("item").invoke(chemicalCap);
    }

    /** Mekanism {@link ItemStack} chemical handler, or {@code null}. */
    @Nullable
    public static Object getChemicalHandlerItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isLoaded()) {
            return null;
        }
        try {
            Object itemCap = chemicalItemCapability();
            return stack.getClass().getMethod("getCapability", itemCap.getClass()).invoke(stack, itemCap);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Stack to put back in the player's hand after a chemical fill (tanks, cells, etc.). */
    public static ItemStack getContainerItem(Object handler, ItemStack fallback) {
        if (handler == null || fallback == null) {
            return fallback;
        }
        try {
            Object container = handler.getClass().getMethod("getContainer").invoke(handler);
            if (container instanceof ItemStack stack && !stack.isEmpty()) {
                return stack;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * Non-destructive sample of the first non-empty chemical stored in an item (tanks, cells, etc.).
     * Returns a Mek {@code ChemicalStack} copy with amount 1, or {@link #emptyStack()}.
     */
    public static Object sampleFromItemStack(ItemStack stack) {
        Object empty = emptyStack();
        Object handler = getChemicalHandlerItem(stack);
        if (handler == null) {
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
                if (amt <= 0) {
                    continue;
                }
                long drainAmt = Math.min(amt, 1L);
                Object extracted =
                        handler.getClass()
                                .getMethod("extractChemical", int.class, long.class, actionSim.getClass())
                                .invoke(handler, i, drainAmt, actionSim);
                if (!isEmptyStack(extracted) && getAmount(extracted) > 0) {
                    return copyWithAmount(extracted, getAmount(extracted));
                }
            }
        } catch (Throwable ignored) {
        }
        return empty;
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

    /** Simulation: at most {@code maxAmount} of one chemical drained from the handler (or EMPTY). */
    public static Object simulateExtractChemical(Object handler, long maxAmount) {
        Object empty = emptyStack();
        if (handler == null || maxAmount <= 0) {
            return empty;
        }
        try {
            Object actionSim = actionSimulate();
            Object extracted =
                    handler.getClass()
                            .getMethod("extractChemical", long.class, actionSim.getClass())
                            .invoke(handler, maxAmount, actionSim);
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

    /** Returns Mekanism chemical tint (RRGGBB) or 0 on failure. */
    public static int getTint(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return 0;
        }
        try {
            return (int) stack.getClass().getMethod("getChemicalTint").invoke(stack);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean isRadioactive(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return false;
        }
        try {
            return (boolean) stack.getClass().getMethod("isRadioactive").invoke(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * {@link mekanism.api.chemical.Chemical#getRadioactivity()} for the stack's chemical type (not scaled by amount).
     */
    public static double getRadioactivityPerUnit(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return 0.0;
        }
        try {
            Object chemical = stack.getClass().getMethod("getChemical").invoke(stack);
            if (chemical == null) {
                return 0.0;
            }
            return (double) chemical.getClass().getMethod("getRadioactivity").invoke(chemical);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** Total radiation {@code getRadioactivity() * amount} on the stack, or 0. */
    public static double getRadioactivityTotal(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return 0.0;
        }
        try {
            return (double) stack.getClass().getMethod("getRadioactivity").invoke(stack);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /**
     * {@code new ChemicalStack(Holder, long)} via reflection. Uses {@link net.minecraft.core.Holder}, not {@code
     * holder.getClass()}, because the public ctor takes {@code Holder<Chemical>} while lookups return {@code Reference}.
     */
    private static Object chemicalStackHolderAmountForDisplay(Object chemicalHolder, long amount) {
        Object empty = emptyStack();
        if (chemicalHolder == null || amount <= 0) {
            return empty;
        }
        try {
            Class<?> cs = Class.forName("mekanism.api.chemical.ChemicalStack");
            Class<?> holderIface = Class.forName("net.minecraft.core.Holder");
            return cs.getConstructor(holderIface, long.class).newInstance(chemicalHolder, amount);
        } catch (Throwable ignored) {
            return empty;
        }
    }

    /** When {@link HolderLookup.Provider} lookup fails on the client, use Mekanism's {@code CHEMICAL_REGISTRY}. */
    private static Object chemicalStackFromBuiltinRegistryHolder(String idStr, long amount) {
        Object empty = emptyStack();
        if (!isLoaded() || idStr == null || idStr.isEmpty() || amount <= 0) {
            return empty;
        }
        try {
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            Object reg = api.getField("CHEMICAL_REGISTRY").get(null);
            ResourceLocation rl = ResourceLocation.parse(idStr);
            Object holderOpt = reg.getClass().getMethod("getHolder", ResourceLocation.class).invoke(reg, rl);
            if (!(holderOpt instanceof Optional<?> ho) || ho.isEmpty()) {
                return empty;
            }
            return chemicalStackHolderAmountForDisplay(ho.get(), amount);
        } catch (Throwable ignored) {
            return empty;
        }
    }

    /**
     * Mekanism {@code ChemicalStack} for GUI previews (tint icon), or {@link #emptyStack()} if the id is unknown.
     */
    public static Object firstChemicalInTagForDisplay(String tagId, long amount, HolderLookup.Provider registries) {
        Object empty = emptyStack();
        if (!isLoaded() || registries == null || tagId == null || tagId.isEmpty() || amount <= 0) {
            return empty;
        }
        try {
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            ResourceKey<?> regName = (ResourceKey<?>) api.getField("CHEMICAL_REGISTRY_NAME").get(null);
            ResourceLocation loc = ResourceLocation.parse(tagId);
            // Registry key is reflect-loaded; raw ResourceKey avoids generic capture mismatch on TagKey.create.
            @SuppressWarnings({"unchecked", "rawtypes"})
            TagKey<?> tagKey = TagKey.create((ResourceKey) regName, loc);
            Object registry = registries.getClass().getMethod("lookupOrThrow", ResourceKey.class).invoke(registries, regName);
            Object tagOpt = registry.getClass().getMethod("get", TagKey.class).invoke(registry, tagKey);
            if (!(tagOpt instanceof Optional<?> to) || to.isEmpty()) {
                return empty;
            }
            Object holderSet = to.get();
            try (java.util.stream.Stream<?> stream =
                    (java.util.stream.Stream<?>) holderSet.getClass().getMethod("stream").invoke(holderSet)) {
                java.util.Optional<?> first = stream.findFirst();
                if (first.isEmpty()) {
                    return empty;
                }
                Object holder = first.get();
                return chemicalStackHolderAmountForDisplay(holder, amount);
            }
        } catch (Throwable ignored) {
            return empty;
        }
    }

    public static Object firstChemicalInModForDisplay(String modIdPrefix, long amount, HolderLookup.Provider registries) {
        Object empty = emptyStack();
        if (!isLoaded() || registries == null || modIdPrefix == null || modIdPrefix.isEmpty() || amount <= 0) {
            return empty;
        }
        try {
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            ResourceKey<?> regName = (ResourceKey<?>) api.getField("CHEMICAL_REGISTRY_NAME").get(null);
            Object registry = registries.getClass().getMethod("lookupOrThrow", ResourceKey.class).invoke(registries, regName);
            Object streamObj = registry.getClass().getMethod("listElementIds").invoke(registry);
            if (!(streamObj instanceof java.util.stream.Stream<?>)) {
                return empty;
            }
            try (java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) streamObj) {
                java.util.Optional<?> firstKey =
                        stream
                                .filter(
                                        rk -> {
                                            try {
                                                ResourceLocation loc =
                                                        (ResourceLocation) rk.getClass().getMethod("location").invoke(rk);
                                                return loc != null && loc.getNamespace().startsWith(modIdPrefix);
                                            } catch (Throwable e) {
                                                return false;
                                            }
                                        })
                                .findFirst();
                if (firstKey.isEmpty()) {
                    return empty;
                }
                Object chemKey = firstKey.get();
                Object holderOpt = registry.getClass().getMethod("get", ResourceKey.class).invoke(registry, chemKey);
                if (!(holderOpt instanceof Optional<?> ho) || ho.isEmpty()) {
                    return empty;
                }
                Object holder = ho.get();
                return chemicalStackHolderAmountForDisplay(holder, amount);
            }
        } catch (Throwable ignored) {
            return empty;
        }
    }

    /**
     * Persists a chemical stack for world save (registry id + amount). No-op if Mekanism is absent or stack is empty.
     */
    public static void saveGasStackToTag(Object stack, CompoundTag tag) {
        if (!isLoaded() || stack == null || isEmptyStack(stack)) {
            return;
        }
        String id = getTypeRegistryName(stack);
        long amt = getAmount(stack);
        if (id == null || id.isEmpty() || amt <= 0) {
            return;
        }
        tag.putString("ChemId", id);
        tag.putLong("Amt", amt);
    }

    /** Restores a stack from {@link #saveGasStackToTag}, or {@link #emptyStack()} if invalid / Mek not loaded. */
    public static Object loadGasStackFromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (!isLoaded() || tag == null || !tag.contains("ChemId")) {
            return emptyStack();
        }
        String id = tag.getString("ChemId");
        long amt = tag.contains("Amt") ? tag.getLong("Amt") : tag.getLong("Amount");
        return chemicalStackFromIdForDisplay(id, amt, registries);
    }

    public static Object chemicalStackFromIdForDisplay(String idStr, long amount, HolderLookup.Provider registries) {
        Object empty = emptyStack();
        if (!isLoaded() || idStr == null || idStr.isEmpty() || amount <= 0) {
            return empty;
        }
        if (registries != null) {
            try {
                Class<?> api = Class.forName("mekanism.api.MekanismAPI");
                ResourceKey<?> regName = (ResourceKey<?>) api.getField("CHEMICAL_REGISTRY_NAME").get(null);
                ResourceLocation rl = ResourceLocation.parse(idStr);
                @SuppressWarnings({"unchecked", "rawtypes"})
                ResourceKey<?> chemKey = ResourceKey.create((ResourceKey) regName, rl);
                Object registry =
                        registries.getClass().getMethod("lookupOrThrow", ResourceKey.class).invoke(registries, regName);
                Object holderOpt = registry.getClass().getMethod("get", ResourceKey.class).invoke(registry, chemKey);
                if (holderOpt instanceof Optional<?> ho && ho.isPresent()) {
                    Object built = chemicalStackHolderAmountForDisplay(ho.get(), amount);
                    if (!isEmptyStack(built)) {
                        return built;
                    }
                }
            } catch (Throwable ignored) {
                // Use built-in registry below (common on client when sync is late).
            }
        }
        Object fromBuiltin = chemicalStackFromBuiltinRegistryHolder(idStr, amount);
        return !isEmptyStack(fromBuiltin) ? fromBuiltin : empty;
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

