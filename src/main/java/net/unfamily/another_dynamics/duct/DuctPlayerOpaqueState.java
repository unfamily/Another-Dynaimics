package net.unfamily.another_dynamics.duct;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Per-player duct opaque preferences: {@link #allOpaqueActive} (All / global client view) and
 * {@link #absoluteOpaquePreferred} (remembered after using All).
 */
public record DuctPlayerOpaqueState(boolean allOpaqueActive, boolean absoluteOpaquePreferred) {
    public static final DuctPlayerOpaqueState DEFAULT = new DuctPlayerOpaqueState(true, true);

    public static final Codec<DuctPlayerOpaqueState> CODEC =
            Codec.withAlternative(
                    RecordCodecBuilder.create(
                            inst ->
                                    inst.group(
                                                    Codec.BOOL.fieldOf("all").forGetter(DuctPlayerOpaqueState::allOpaqueActive),
                                                    Codec.BOOL
                                                            .fieldOf("preferred")
                                                            .forGetter(DuctPlayerOpaqueState::absoluteOpaquePreferred))
                                            .apply(inst, DuctPlayerOpaqueState::new)),
                    Codec.BOOL,
                    legacy -> new DuctPlayerOpaqueState(legacy, legacy));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctPlayerOpaqueState> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    DuctPlayerOpaqueState::allOpaqueActive,
                    ByteBufCodecs.BOOL,
                    DuctPlayerOpaqueState::absoluteOpaquePreferred,
                    DuctPlayerOpaqueState::new);

    public DuctPlayerOpaqueState withAllOpaqueActive(boolean active) {
        return new DuctPlayerOpaqueState(active, active || absoluteOpaquePreferred);
    }
}
