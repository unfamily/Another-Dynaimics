package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

/**
 * Client -> server: duct node GUI button id (same ids as vanilla {@code ServerboundContainerButtonClickPacket} would
 * carry). Validated against the player's open {@link net.unfamily.another_dynamics.inventory.DuctNodeMenu} so server
 * logic runs even when vanilla {@link net.minecraft.world.inventory.AbstractContainerMenu#stillValid} rejects the
 * packet (which would otherwise drop all hub/mode/redstone/channel clicks while unrelated actions still work).
 */
public record DuctMenuButtonPayload(BlockPos pos, int faceOrdinal, int buttonId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DuctMenuButtonPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_menu_button"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctMenuButtonPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    DuctMenuButtonPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    DuctMenuButtonPayload::faceOrdinal,
                    ByteBufCodecs.VAR_INT,
                    DuctMenuButtonPayload::buttonId,
                    DuctMenuButtonPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
