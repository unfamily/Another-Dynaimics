package net.unfamily.another_dynamics.client;

import com.mojang.serialization.MapCodec;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierItemProperties;

import org.jspecify.annotations.Nullable;

/**
 * 26.x replacements for legacy {@code ItemProperties} predicates on the settings copier
 * ({@code another_dynamics:copier_filter} / {@code another_dynamics:copier_filled}).
 */
public final class SettingsCopierConditionalProperties {
    private SettingsCopierConditionalProperties() {}

    public record CopierFilter() implements ConditionalItemModelProperty {
        public static final MapCodec<CopierFilter> MAP_CODEC = MapCodec.unit(new CopierFilter());

        @Override
        public boolean get(
                ItemStack stack,
                @Nullable ClientLevel level,
                @Nullable LivingEntity owner,
                int seed,
                ItemDisplayContext displayContext) {
            return SettingsCopierItemProperties.isFilterMode(stack);
        }

        @Override
        public MapCodec<CopierFilter> type() {
            return MAP_CODEC;
        }
    }

    public record CopierFilled() implements ConditionalItemModelProperty {
        public static final MapCodec<CopierFilled> MAP_CODEC = MapCodec.unit(new CopierFilled());

        @Override
        public boolean get(
                ItemStack stack,
                @Nullable ClientLevel level,
                @Nullable LivingEntity owner,
                int seed,
                ItemDisplayContext displayContext) {
            return SettingsCopierItemProperties.copierFilled(stack) > 0.5F;
        }

        @Override
        public MapCodec<CopierFilled> type() {
            return MAP_CODEC;
        }
    }

    public record CopierSequential() implements ConditionalItemModelProperty {
        public static final MapCodec<CopierSequential> MAP_CODEC = MapCodec.unit(new CopierSequential());

        @Override
        public boolean get(
                ItemStack stack,
                @Nullable ClientLevel level,
                @Nullable LivingEntity owner,
                int seed,
                ItemDisplayContext displayContext) {
            return SettingsCopierItemProperties.isSequentialMode(stack);
        }

        @Override
        public MapCodec<CopierSequential> type() {
            return MAP_CODEC;
        }
    }
}
