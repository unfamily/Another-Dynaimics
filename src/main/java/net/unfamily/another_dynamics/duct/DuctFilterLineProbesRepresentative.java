package net.unfamily.another_dynamics.duct;

import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Builds a single stack for an allow-line string so cap/limit math can be evaluated per line.
 * Lines that only match via macros/NBT ({@code &}, {@code ?}) return empty and are treated as satisfiable elsewhere.
 */
public final class DuctFilterLineProbesRepresentative {
    private DuctFilterLineProbesRepresentative() {}

    public static ItemStack itemStackForAllowLine(String raw, HolderLookup.Provider registries) {
        if (raw == null) {
            return ItemStack.EMPTY;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("&") || line.startsWith("?")) {
            return ItemStack.EMPTY;
        }
        if (line.startsWith("-")) {
            line = line.substring(1).trim();
        }
        if (line.startsWith("@")) {
            String mod = line.substring(1);
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null && id.getNamespace().startsWith(mod)) {
                    return new ItemStack(item);
                }
            }
            return ItemStack.EMPTY;
        }
        if (line.startsWith("#")) {
            String tagFilter = line.substring(1);
            try {
                Identifier tagId = Identifier.parse(tagFilter);
                TagKey<Item> itemTag = ItemTags.create(tagId);
                for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(itemTag)) {
                    return new ItemStack(holder.value());
                }
            } catch (Exception ignored) {
            }
            return ItemStack.EMPTY;
        }
        Identifier id = Identifier.tryParse(line);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        try {
            Optional<Holder.Reference<Item>> holder =
                    registries.lookupOrThrow(Registries.ITEM).get(ResourceKey.create(Registries.ITEM, id));
            if (holder.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(holder.get(), 1);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    public static FluidStack fluidStackForAllowLine(String raw, HolderLookup.Provider registries) {
        if (raw == null) {
            return FluidStack.EMPTY;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("&") || line.startsWith("?")) {
            return FluidStack.EMPTY;
        }
        if (line.startsWith("-")) {
            line = line.substring(1).trim();
        }
        if (line.startsWith("@")) {
            String mod = line.substring(1);
            for (Fluid fluid : BuiltInRegistries.FLUID) {
                Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id != null && id.getNamespace().startsWith(mod)) {
                    return new FluidStack(fluid, 1);
                }
            }
            return FluidStack.EMPTY;
        }
        if (line.startsWith("#")) {
            String tagFilter = line.substring(1);
            try {
                Identifier tagId = Identifier.parse(tagFilter);
                TagKey<Fluid> fluidTag = TagKey.create(Registries.FLUID, tagId);
                for (var holder : BuiltInRegistries.FLUID.getTagOrEmpty(fluidTag)) {
                    return new FluidStack(holder.value(), 1);
                }
            } catch (Exception ignored) {
            }
            return FluidStack.EMPTY;
        }
        Identifier id = Identifier.tryParse(line);
        if (id == null) {
            return FluidStack.EMPTY;
        }
        try {
            Optional<Holder.Reference<Fluid>> holder =
                    registries.lookupOrThrow(Registries.FLUID).get(ResourceKey.create(Registries.FLUID, id));
            if (holder.isEmpty()) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(holder.get(), 1);
        } catch (RuntimeException e) {
            return FluidStack.EMPTY;
        }
    }
}
