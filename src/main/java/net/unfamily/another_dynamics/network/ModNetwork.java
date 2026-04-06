package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;

@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {
    public static final CustomPacketPayload.Type<DuctFieldPayload> DUCT_FIELD =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_field"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctFieldPayload> DUCT_FIELD_STREAM = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            DuctFieldPayload::pos,
            ByteBufCodecs.VAR_INT,
            DuctFieldPayload::faceOrdinal,
            ByteBufCodecs.INT,
            DuctFieldPayload::value,
            DuctFieldPayload::new);

    private ModNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(AnotherDynamicsMod.MOD_ID);
        reg.playToServer(DUCT_FIELD, DUCT_FIELD_STREAM, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                int fo = payload.faceOrdinal();
                if (fo < 0 || fo >= Direction.values().length) {
                    return;
                }
                if (player.distanceToSqr(payload.pos().getX() + 0.5, payload.pos().getY() + 0.5, payload.pos().getZ() + 0.5)
                        > 8 * 8) {
                    return;
                }
                duct.applyClientFieldUpdate(Direction.values()[fo], payload.value());
            });
        });
    }

    public static void sendFieldUpdate(BlockPos pos, Direction face, int value) {
        PacketDistributor.sendToServer(new DuctFieldPayload(pos, face.ordinal(), value));
    }

    public record DuctFieldPayload(BlockPos pos, int faceOrdinal, int value) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return DUCT_FIELD;
        }
    }
}
