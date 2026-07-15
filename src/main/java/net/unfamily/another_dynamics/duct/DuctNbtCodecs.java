package net.unfamily.another_dynamics.duct;

import java.util.Optional;

import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 26.x helpers bridging the {@code ValueInput}/{@code ValueOutput} NBT API back onto the plain {@link CompoundTag}
 * trees used throughout the duct save/load code, so the rest of that code did not need to be rewritten wholesale.
 */
public final class DuctNbtCodecs {
    private DuctNbtCodecs() {}

    public static CompoundTag serializeHandler(ValueIOSerializable handler, HolderLookup.Provider registries) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        handler.serialize(output);
        return output.buildResult();
    }

    public static void deserializeHandler(
            ValueIOSerializable handler, HolderLookup.Provider registries, CompoundTag tag) {
        handler.deserialize(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
    }

    public static CompoundTag saveItemStack(HolderLookup.Provider registries, ItemStack stack) {
        return encode(registries, ItemStack.CODEC, stack);
    }

    public static Optional<ItemStack> parseItemStack(HolderLookup.Provider registries, CompoundTag tag) {
        return decode(registries, ItemStack.CODEC, tag);
    }

    public static CompoundTag saveFluidStack(HolderLookup.Provider registries, FluidStack stack) {
        return encode(registries, FluidStack.CODEC, stack);
    }

    public static Optional<FluidStack> parseFluidStack(HolderLookup.Provider registries, CompoundTag tag) {
        return decode(registries, FluidStack.CODEC, tag);
    }

    private static <T> CompoundTag encode(HolderLookup.Provider registries, Codec<T> codec, T value) {
        return (CompoundTag) codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value)
                .getOrThrow();
    }

    private static <T> Optional<T> decode(HolderLookup.Provider registries, Codec<T> codec, CompoundTag tag) {
        return codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result();
    }
}
