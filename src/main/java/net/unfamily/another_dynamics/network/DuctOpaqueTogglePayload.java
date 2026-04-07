package net.unfamily.another_dynamics.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/** Client -> server: flip {@link net.unfamily.another_dynamics.registry.ModAttachments#DUCT_TRANSIT_OPAQUE} for the player. */
public record DuctOpaqueTogglePayload() implements CustomPacketPayload {
    public static final DuctOpaqueTogglePayload INSTANCE = new DuctOpaqueTogglePayload();

    public static final CustomPacketPayload.Type<DuctOpaqueTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_opaque_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctOpaqueTogglePayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
