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
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.registry.ModAttachments;

@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {
    public static final CustomPacketPayload.Type<DuctFieldPayload> DUCT_FIELD =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_field"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctFieldPayload> DUCT_FIELD_STREAM = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            DuctFieldPayload::pos,
            ByteBufCodecs.VAR_INT,
            DuctFieldPayload::faceOrdinal,
            ByteBufCodecs.VAR_INT,
            DuctFieldPayload::transportKindOrdinal,
            ByteBufCodecs.INT,
            DuctFieldPayload::insertionPriority,
            ByteBufCodecs.INT,
            DuctFieldPayload::extractBatch,
            ByteBufCodecs.VAR_INT,
            DuctFieldPayload::eligibilityModeOrdinal,
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
                        Direction.values()[fo],
                        payload.transportKindOrdinal(),
                        payload.insertionPriority(),
                        payload.extractBatch(),
                        payload.eligibilityModeOrdinal());
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
                DuctTransportKind[] kinds = DuctTransportKind.values();
                DuctTransportKind laneKind =
                        kinds[Mth.clamp(payload.transportKindOrdinal(), 0, kinds.length - 1)];
                DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.values()[
                        Mth.clamp(payload.filterBankOrdinal(), 0, DuctFaceNode.FilterBank.values().length - 1)];
                duct.applyServerFilterConfig(
                        player,
                        face,
                        laneKind,
                        bank,
                        payload.allow(),
                        payload.deny(),
                        payload.allowCaps(),
                        payload.allowCaps2(),
                        payload.allowGroupIds(),
                        payload.denyOverridesAllow());
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
                DuctTransportKind[] kinds = DuctTransportKind.values();
                DuctTransportKind laneKind =
                        kinds[Mth.clamp(payload.transportKindOrdinal(), 0, kinds.length - 1)];
                DuctFaceNode.FilterBank bank = DuctFaceNode.FilterBank.values()[
                        Mth.clamp(payload.filterBankOrdinal(), 0, DuctFaceNode.FilterBank.values().length - 1)];
                duct.toggleListLogicFromClient(player, Direction.values()[fo], laneKind, bank);
            });
        });

        reg.playToServer(DuctOpaqueTogglePayload.TYPE, DuctOpaqueTogglePayload.STREAM_CODEC, (_payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                var att = ModAttachments.DUCT_TRANSIT_OPAQUE.get();
                boolean next = !player.getData(att);
                player.setData(att, next);
            });
        });

        reg.playToServer(DuctSelfFeedPayload.TYPE, DuctSelfFeedPayload.STREAM_CODEC, (payload, ctx) -> {
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
                duct.setSelfFeedFromClient(player, Direction.values()[fo], payload.enabled());
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
                                payload.transportKindOrdinal(),
                                payload.filterBankOrdinal(),
                                payload.allow(),
                                payload.deny(),
                                payload.allowCaps(),
                                payload.allowCaps2(),
                                payload.allowGroupIds(),
                                payload.denyOverridesAllow());
                    });
        });

    }

    private static boolean validateDuctGuiDistance(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 8 * 8;
    }

    public static void sendFieldUpdate(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int insertionPriority,
            int extractBatch,
            int eligibilityModeOrdinal) {
        PacketDistributor.sendToServer(
                new DuctFieldPayload(
                        pos, face.ordinal(), transportKindOrdinal, insertionPriority, extractBatch, eligibilityModeOrdinal));
    }

    public static void sendFilterUpdate(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            java.util.List<String> allow,
            java.util.List<String> deny,
            java.util.List<Integer> allowCaps,
            java.util.List<Integer> allowCaps2,
            java.util.List<Integer> allowGroupIds,
            boolean denyOverridesAllow) {
        PacketDistributor.sendToServer(
                new DuctFilterUpdatePayload(
                        pos,
                        face.ordinal(),
                        transportKindOrdinal,
                        filterBankOrdinal,
                        allow,
                        deny,
                        allowCaps,
                        allowCaps2,
                        allowGroupIds,
                        denyOverridesAllow));
    }

    public static void sendListLogicToggle(BlockPos pos, Direction face, int transportKindOrdinal, int filterBankOrdinal) {
        PacketDistributor.sendToServer(
                new DuctListLogicPayload(pos, face.ordinal(), transportKindOrdinal, filterBankOrdinal));
    }

    public static void sendSelfFeedSet(BlockPos pos, Direction face, boolean enabled) {
        PacketDistributor.sendToServer(new DuctSelfFeedPayload(pos, face.ordinal(), enabled));
    }

    /** Toggles duct opaque rendering preference (player attachment); server authoritative. */
    public static void sendDuctOpaqueToggle() {
        PacketDistributor.sendToServer(DuctOpaqueTogglePayload.INSTANCE);
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
        var node = duct.activeMenuFaceNode(face);
        int tk = duct.menuActiveTransportKind().ordinal();
        for (DuctFaceNode.FilterBank bank : DuctFaceNode.FilterBank.values()) {
            java.util.List<Integer> caps2 =
                    bank == DuctFaceNode.FilterBank.FILTER
                            ? java.util.List.copyOf(node.filterBankKeepCaps())
                            : java.util.List.of();
            java.util.List<Integer> groups =
                    bank == DuctFaceNode.FilterBank.EXTRACTOR
                            ? java.util.Collections.nCopies(node.bankAllowFilters(bank).size(), 0)
                            : java.util.List.copyOf(node.bankAllowGroupIds(bank));
            PacketDistributor.sendToPlayer(
                    player,
                    new DuctFilterSyncPayload(
                            duct.getBlockPos(),
                            face.ordinal(),
                            tk,
                            bank.ordinal(),
                            java.util.List.copyOf(node.bankAllowFilters(bank)),
                            java.util.List.copyOf(node.bankDenyFilters(bank)),
                            java.util.List.copyOf(node.bankAllowCaps(bank)),
                            caps2,
                            groups,
                            node.bankDenyOverridesAllow(bank)));
        }
    }

    public record DuctFieldPayload(
            BlockPos pos,
            int faceOrdinal,
            int transportKindOrdinal,
            int insertionPriority,
            int extractBatch,
            int eligibilityModeOrdinal)
            implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return DUCT_FIELD;
        }
    }
}
