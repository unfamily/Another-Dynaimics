package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
import net.unfamily.another_dynamics.client.gui.DuctNodeScreen;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;

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
            DuctFieldPayload::insertionPriority,
            ByteBufCodecs.INT,
            DuctFieldPayload::extractBatch,
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
                duct.applyClientFieldUpdate(
                        Direction.values()[fo], payload.insertionPriority(), payload.extractBatch());
            });
        });

        reg.playToServer(DuctFilterUpdatePayload.TYPE, DuctFilterUpdatePayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                if (!validateDuctGuiDistance(player, payload.pos())) {
                    return;
                }
                int fo = payload.faceOrdinal();
                if (fo < 0 || fo >= Direction.values().length) {
                    return;
                }
                Direction face = Direction.values()[fo];
                duct.applyServerFilterConfig(player, face, payload.allow(), payload.deny(), payload.denyOverridesAllow());
            });
        });

        reg.playToServer(DuctListLogicPayload.TYPE, DuctListLogicPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                if (!validateDuctGuiDistance(player, payload.pos())) {
                    return;
                }
                int fo = payload.faceOrdinal();
                if (fo < 0 || fo >= Direction.values().length) {
                    return;
                }
                duct.toggleListLogicFromClient(player, Direction.values()[fo]);
            });
        });

        reg.playToClient(DuctFilterSyncPayload.TYPE, DuctFilterSyncPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(
                    () -> {
                        Direction face =
                                Direction.values()[Mth.clamp(
                                        payload.faceOrdinal(), 0, Direction.values().length - 1)];
                        DuctNodeScreen.applyClientFilterSync(
                                payload.pos(),
                                face,
                                payload.allow(),
                                payload.deny(),
                                payload.denyOverridesAllow());
                    });
        });
    }

    private static boolean validateDuctGuiDistance(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 8 * 8;
    }

    public static void sendFieldUpdate(BlockPos pos, Direction face, int insertionPriority, int extractBatch) {
        PacketDistributor.sendToServer(new DuctFieldPayload(pos, face.ordinal(), insertionPriority, extractBatch));
    }

    public static void sendFilterUpdate(
            BlockPos pos,
            Direction face,
            java.util.List<String> allow,
            java.util.List<String> deny,
            boolean denyOverridesAllow) {
        PacketDistributor.sendToServer(
                new DuctFilterUpdatePayload(pos, face.ordinal(), allow, deny, denyOverridesAllow));
    }

    public static void sendListLogicToggle(BlockPos pos, Direction face) {
        PacketDistributor.sendToServer(new DuctListLogicPayload(pos, face.ordinal()));
    }

    /**
     * Sends allow/deny strings to the client. Scheduled for the next server tick so the client's {@link DuctNodeMenu}
     * and screen exist before the packet is handled (avoids dropped or invisible updates).
     */
    public static void sendFilterSyncToPlayer(ServerPlayer player, DuctBlockEntity duct, Direction face) {
        var server = player.getServer();
        if (server == null) {
            sendFilterSyncToPlayerNow(player, duct, face);
            return;
        }
        server.execute(
                () -> {
                    if (player.hasDisconnected()) {
                        return;
                    }
                    if (player.level().getBlockEntity(duct.getBlockPos()) != duct) {
                        return;
                    }
                    sendFilterSyncToPlayerNow(player, duct, face);
                });
    }

    private static void sendFilterSyncToPlayerNow(ServerPlayer player, DuctBlockEntity duct, Direction face) {
        var node = duct.getFaceNode(face);
        PacketDistributor.sendToPlayer(
                player,
                new DuctFilterSyncPayload(
                        duct.getBlockPos(),
                        face.ordinal(),
                        java.util.List.copyOf(node.allowFilters),
                        java.util.List.copyOf(node.denyFilters),
                        node.denyOverridesAllow));
    }

    public record DuctFieldPayload(BlockPos pos, int faceOrdinal, int insertionPriority, int extractBatch)
            implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return DUCT_FIELD;
        }
    }
}
