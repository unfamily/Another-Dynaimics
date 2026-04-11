package net.unfamily.another_dynamics.duct.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.registry.ModDataComponents;

public final class DuctModuleHelper {
    private DuctModuleHelper() {}

    public static boolean hasDeclaration(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(ModDataComponents.DUCT_MODULE_DECLARATION.get());
    }

    /** Only the {@code duct_module_declaration} component, if set. */
    public static Optional<ResourceLocation> declarationId(ItemStack stack) {
        if (!hasDeclaration(stack)) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.get(ModDataComponents.DUCT_MODULE_DECLARATION.get()));
    }

    /**
     * Effective module definition id: explicit declaration component, else first match among
     * {@link ModuleDefinition#matchingItemTags()} (if several definitions match, the smallest id lexicographically wins).
     */
    public static Optional<ResourceLocation> resolvedDeclarationId(ItemStack stack) {
        Optional<ResourceLocation> explicit = declarationId(stack);
        if (explicit.isPresent()) {
            return explicit;
        }
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        List<ResourceLocation> matches = new ArrayList<>();
        for (ModuleDefinition def : ModuleDefinitionRegistry.all()) {
            boolean hit = false;
            for (TagKey<Item> tag : def.matchingItemTags()) {
                if (stack.is(tag)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                matches.add(def.id());
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Collections.min(matches));
    }

    public static boolean matchesIncompatibility(ModuleDefinition def, ItemStack other, HolderLookup.Provider registries) {
        for (ModuleIncompatibility inc : def.incompatibleWith()) {
            if (inc.matches(other, registries)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code candidate} cannot coexist with {@code occupant} in another slot. Uses legacy gating (always apply
     * incompatibility when it matches).
     */
    public static boolean modulesConflict(ItemStack candidate, ItemStack occupant, HolderLookup.Provider registries) {
        return modulesConflict(candidate, occupant, registries, Integer.MAX_VALUE);
    }

    /**
     * True when {@code candidate} cannot coexist with {@code occupant} in another slot. Pairwise {@code incompatible_with}
     * is evaluated only when {@code filledModuleSlotsAfterOp} {@code > min(A.incompatibilityActivation, B.incompatibilityActivation)}.
     */
    public static boolean modulesConflict(
            ItemStack candidate,
            ItemStack occupant,
            HolderLookup.Provider registries,
            int filledModuleSlotsAfterOp) {
        if (candidate.isEmpty() || occupant.isEmpty()) {
            return false;
        }
        Optional<ResourceLocation> ca = resolvedDeclarationId(candidate);
        Optional<ResourceLocation> ob = resolvedDeclarationId(occupant);
        if (ca.isEmpty() && ob.isEmpty()) {
            return false;
        }
        ModuleDefinition da = ca.map(id -> ModuleDefinitionRegistry.get(id).orElse(null)).orElse(null);
        ModuleDefinition db = ob.map(id -> ModuleDefinitionRegistry.get(id).orElse(null)).orElse(null);
        int ta = da != null ? da.incompatibilityActivation() : 1;
        int tb = db != null ? db.incompatibilityActivation() : 1;
        int threshold = Math.min(ta, tb);
        if (filledModuleSlotsAfterOp <= threshold) {
            return false;
        }
        if (ca.isPresent() && da != null && matchesIncompatibility(da, occupant, registries)) {
            return true;
        }
        if (ob.isPresent() && db != null && matchesIncompatibility(db, candidate, registries)) {
            return true;
        }
        return false;
    }

    /** True when both definitions declare the same {@code incompatible_with} multiset (order-independent). */
    public static boolean sameSwapGroup(ModuleDefinition a, ModuleDefinition b) {
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(swapGroupSignature(a), swapGroupSignature(b));
    }

    public static boolean sameSwapGroup(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Optional<ResourceLocation> ia = resolvedDeclarationId(a);
        Optional<ResourceLocation> ib = resolvedDeclarationId(b);
        if (ia.isEmpty() || ib.isEmpty()) {
            return false;
        }
        ModuleDefinition da = ModuleDefinitionRegistry.get(ia.get()).orElse(null);
        ModuleDefinition db = ModuleDefinitionRegistry.get(ib.get()).orElse(null);
        return sameSwapGroup(da, db);
    }

    private static List<String> swapGroupSignature(ModuleDefinition def) {
        return def.incompatibleWith().stream()
                .map(DuctModuleHelper::incompatibilitySortKey)
                .sorted()
                .toList();
    }

    private static String incompatibilitySortKey(ModuleIncompatibility inc) {
        if (inc instanceof ModuleIncompatibility.TagRef tr) {
            return "T#" + tr.tag().location();
        }
        if (inc instanceof ModuleIncompatibility.ItemRef ir) {
            return "I#" + BuiltInRegistries.ITEM.getKey(ir.item());
        }
        return "";
    }
}
