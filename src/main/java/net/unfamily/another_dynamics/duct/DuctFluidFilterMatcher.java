package net.unfamily.another_dynamics.duct;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Filter entry matching for fluid ducts. Supports {@code #namespace:tag}, {@code @mod}, {@code -id}, registry id, and
 * optional {@code ?} substring on saved NBT (no item enchant/damage macros).
 */
public final class DuctFluidFilterMatcher {
    private DuctFluidFilterMatcher() {}

    public static boolean matchesFilterEntry(
            FluidStack stack,
            Fluid fluid,
            ResourceLocation fluidId,
            String fluidIdStr,
            String fluidModId,
            String filter,
            HolderLookup.Provider registries) {
        if (filter == null || filter.isEmpty() || stack.isEmpty()) {
            return false;
        }

        if (filter.startsWith("-")) {
            String idFilter = filter.substring(1);
            return fluidIdStr.equals(idFilter);
        }

        if (filter.startsWith("@")) {
            String modIdFilter = filter.substring(1);
            return fluidModId.startsWith(modIdFilter);
        }

        if (filter.startsWith("#")) {
            String tagFilter = filter.substring(1);
            try {
                ResourceLocation tagId = ResourceLocation.parse(tagFilter);
                TagKey<Fluid> fluidTag = TagKey.create(Registries.FLUID, tagId);
                return BuiltInRegistries.FLUID.wrapAsHolder(fluid).is(fluidTag);
            } catch (Exception e) {
                return false;
            }
        }

        if (filter.startsWith("?")) {
            String nbtFilter = filter.substring(1);
            try {
                Tag tag = stack.save(registries);
                if (tag instanceof CompoundTag compoundTag) {
                    return compoundTag.toString().contains(nbtFilter);
                }
            } catch (Exception e) {
                return false;
            }
            return false;
        }

        return fluidIdStr.equals(filter);
    }

    public static boolean matchesAnyNonEmptyEntry(FluidStack stack, String filterLine, HolderLookup.Provider registries) {
        if (filterLine == null || filterLine.trim().isEmpty() || stack.isEmpty()) {
            return false;
        }
        Fluid fluid = stack.getFluid();
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        String fluidIdStr = fluidId.toString();
        String fluidModId = fluidId.getNamespace();
        return matchesFilterEntry(stack, fluid, fluidId, fluidIdStr, fluidModId, filterLine.trim(), registries);
    }
}
