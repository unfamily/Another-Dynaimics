package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: advance (or retreat) opaque cycle for duct at {@link #anchor()} (or player-only if no duct). */
public record DuctOpaqueTogglePayload(BlockPos anchor, boolean backwards) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctOpaqueTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_opaque_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctOpaqueTogglePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctOpaqueTogglePayload::anchor,
                    net.minecraft.network.codec.ByteBufCodecs.BOOL,
                    DuctOpaqueTogglePayload::backwards,
                    DuctOpaqueTogglePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
