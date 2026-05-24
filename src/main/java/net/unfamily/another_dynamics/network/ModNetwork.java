package net.unfamily.another_dynamics.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.gui.AbstractUniversalDuctScreen;
import net.unfamily.another_dynamics.client.EnergyRayClient;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.inventory.UniversalDuctMenu;

import org.jetbrains.annotations.Nullable;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.registry.ModAttachments;
import net.minecraft.world.InteractionHand;
import java.util.List;

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

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(AnotherDynamicsMod.MOD_ID);
        reg.playToServer(DUCT_FIELD, DUCT_FIELD_STREAM, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                SettingsCopierVirtualSession session = virtualCopierSession(player);
                if (session != null) {
                    session.applyClientFieldUpdate(
                            payload.transportKindOrdinal(),
                            payload.insertionPriority(),
                            payload.extractBatch(),
                            payload.eligibilityModeOrdinal());
                    ((SettingsCopierMenu) player.containerMenu).broadcastChanges();
                    return;
                }
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                int fo = payload.faceOrdinal();
                if (fo < 0 || fo >= Direction.values().length) {
                    return;
                }
                if (!validateDuctGuiInteraction(player, payload.pos())) {
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
                SettingsCopierVirtualSession session = virtualCopierSession(player);
                if (session != null) {
                    DuctTransportKind[] kinds = DuctTransportKind.values();
                    DuctTransportKind laneKind =
                            kinds[Mth.clamp(payload.transportKindOrdinal(), 0, kinds.length - 1)];
                    DuctFaceNode.FilterBank bank =
                            DuctFaceNode.FilterBank.values()[
                                    Mth.clamp(
                                            payload.filterBankOrdinal(),
                                            0,
                                            DuctFaceNode.FilterBank.values().length - 1)];
                    session.applyFilterConfig(
                            laneKind,
                            bank,
                            payload.allow(),
                            payload.deny(),
                            payload.allowCaps(),
                            payload.allowCaps2(),
                            payload.denyOverridesAllow());
                    ((SettingsCopierMenu) player.containerMenu).broadcastChanges();
                    return;
                }
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                if (!validateDuctGuiInteraction(player, payload.pos())) {
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
                        payload.denyOverridesAllow());
            });
        });

        reg.playToServer(DuctListLogicPayload.TYPE, DuctListLogicPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                SettingsCopierVirtualSession session = virtualCopierSession(player);
                if (session != null) {
                    DuctTransportKind[] kinds = DuctTransportKind.values();
                    DuctTransportKind laneKind =
                            kinds[Mth.clamp(payload.transportKindOrdinal(), 0, kinds.length - 1)];
                    DuctFaceNode.FilterBank bank =
                            DuctFaceNode.FilterBank.values()[
                                    Mth.clamp(
                                            payload.filterBankOrdinal(),
                                            0,
                                            DuctFaceNode.FilterBank.values().length - 1)];
                    session.toggleListLogic(laneKind, bank);
                    ((SettingsCopierMenu) player.containerMenu).broadcastChanges();
                    return;
                }
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                if (!validateDuctGuiInteraction(player, payload.pos())) {
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

        reg.playToServer(DuctMenuButtonPayload.TYPE, DuctMenuButtonPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (player.containerMenu instanceof SettingsCopierMenu copier && copier.isVirtualLayer()) {
                    copier.clickMenuButton(player, payload.buttonId());
                    copier.broadcastChanges();
                    return;
                }
                if (!(player.containerMenu instanceof DuctNodeMenu menu)) {
                    return;
                }
                DuctBlockEntity linked = menu.linkedDuctBlockEntity();
                if (linked == null || linked.isRemoved()) {
                    return;
                }
                if (!menu.getDuctBlockPos().equals(payload.pos())) {
                    return;
                }
                int fo = payload.faceOrdinal();
                if (fo < 0 || fo >= Direction.values().length) {
                    return;
                }
                if (menu.getAccessFace() != Direction.values()[fo]) {
                    return;
                }
                if (player.level().getBlockEntity(payload.pos()) != linked) {
                    return;
                }
                if (!validateDuctGuiInteraction(player, payload.pos())) {
                    return;
                }
                menu.clickMenuButton(player, payload.buttonId());
                menu.broadcastChanges();
            });
        });

        reg.playToServer(
                DuctEnergyBufferLimitsPayload.TYPE,
                DuctEnergyBufferLimitsPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        SettingsCopierVirtualSession session = virtualCopierSession(player);
                        if (session != null) {
                            session.applyEnergyBufferLimits(
                                    payload.extractLimitFe(), payload.insertLimitFe());
                            ((SettingsCopierMenu) player.containerMenu).broadcastChanges();
                            return;
                        }
                        BlockEntity be = player.level().getBlockEntity(payload.pos());
                        if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                            return;
                        }
                        if (!validateDuctGuiInteraction(player, payload.pos())) {
                            return;
                        }
                        int fo = payload.faceOrdinal();
                        if (fo < 0 || fo >= Direction.values().length) {
                            return;
                        }
                        duct.applyEnergyBufferLimitsFromClient(
                                Direction.values()[fo],
                                payload.extractLimitFe(),
                                payload.insertLimitFe());
                    });
                });

        reg.playToServer(DuctSelfFeedPayload.TYPE, DuctSelfFeedPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                SettingsCopierVirtualSession session = virtualCopierSession(player);
                if (session != null) {
                    session.setSelfFeed(payload.enabled());
                    ((SettingsCopierMenu) player.containerMenu).broadcastChanges();
                    return;
                }
                BlockEntity be = player.level().getBlockEntity(payload.pos());
                if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                    return;
                }
                if (!validateDuctGuiInteraction(player, payload.pos())) {
                    return;
                }
                int fo = payload.faceOrdinal();
                if (fo < 0 || fo >= Direction.values().length) {
                    return;
                }
                duct.setSelfFeedFromClient(player, Direction.values()[fo], payload.enabled());
            });
        });

        reg.playToServer(
                SettingsCopierHubActionPayload.TYPE,
                SettingsCopierHubActionPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        if (!(player.containerMenu instanceof SettingsCopierMenu copier) || !copier.isHubLayer()) {
                            return;
                        }
                        InteractionHand hand = copier.getHand();
                        ItemStack stack = copier.copierStack(player);
                        if (stack.isEmpty() || !(stack.getItem() instanceof SettingsCopierItem)) {
                            return;
                        }
                        switch (payload.action()) {
                            case SettingsCopierHubActionPayload.ACTION_CONFIGURE -> copier.enterVirtual(player);
                            case SettingsCopierHubActionPayload.ACTION_MODE_TOGGLE -> {
                                SettingsCopierStoreKind next =
                                        SettingsCopierStoreKind.getMode(stack) == SettingsCopierStoreKind.FILTER
                                                ? SettingsCopierStoreKind.ALL
                                                : SettingsCopierStoreKind.FILTER;
                                SettingsCopierStoreKind.clear(stack);
                                SettingsCopierStoreKind.setMode(stack, next);
                                player.setItemInHand(hand, stack);
                                sendSettingsCopierStackSync(player, stack);
                            }
                            case SettingsCopierHubActionPayload.ACTION_RENAME -> {
                                String name = payload.renameText();
                                if (name == null || name.isBlank()) {
                                    stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
                                } else {
                                    stack.set(
                                            net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                                            net.minecraft.network.chat.Component.literal(name.trim()));
                                }
                                player.setItemInHand(hand, stack);
                                sendSettingsCopierStackSync(player, stack);
                            }
                            default -> {}
                        }
                    });
                });

        reg.playToServer(
                SettingsCopierReturnToHubPayload.TYPE,
                SettingsCopierReturnToHubPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        if (player.containerMenu instanceof SettingsCopierMenu menu && menu.isVirtualLayer()) {
                            menu.returnToHub(player);
                        }
                    });
                });

        reg.playToClient(EnergyRayPathPayload.TYPE, EnergyRayPathPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> EnergyRayClient.handlePath(payload));
        });

        reg.playToClient(DuctFilterSyncPayload.TYPE, DuctFilterSyncPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(
                    () -> {
                        Direction face =
                                Direction.values()[Mth.clamp(
                                        payload.faceOrdinal(), 0, Direction.values().length - 1)];
                        AbstractUniversalDuctScreen.applyClientFilterSync(
                                payload.pos(),
                                face,
                                payload.transportKindOrdinal(),
                                payload.filterBankOrdinal(),
                                payload.allow(),
                                payload.deny(),
                                payload.allowCaps(),
                                payload.allowCaps2(),
                                payload.denyOverridesAllow());
                    });
        });

        reg.playToClient(DuctGuiFeedbackPayload.TYPE, DuctGuiFeedbackPayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> AbstractUniversalDuctScreen.showSettingsCopierFeedback(payload.messageId()));
        });

        reg.playToClient(
                SettingsCopierStackSyncPayload.TYPE,
                SettingsCopierStackSyncPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        var player = ctx.player();
                        if (player.containerMenu instanceof DuctNodeMenu ductMenu) {
                            ductMenu.applyClientCopierStack(payload.stack());
                        } else if (player.containerMenu instanceof SettingsCopierMenu copierMenu) {
                            copierMenu.applyClientCopierStack(player, payload.stack());
                        }
                    });
                });

        reg.playToServer(
                SettingsCopierActionPayload.TYPE,
                SettingsCopierActionPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        if (!(player.containerMenu instanceof DuctNodeMenu menu)) {
                            return;
                        }
                        DuctBlockEntity linked = menu.linkedDuctBlockEntity();
                        if (linked == null || linked.isRemoved()) {
                            return;
                        }
                        if (!menu.getDuctBlockPos().equals(payload.pos())) {
                            return;
                        }
                        int fo = payload.faceOrdinal();
                        if (fo < 0 || fo >= Direction.values().length) {
                            return;
                        }
                        if (menu.getAccessFace() != Direction.values()[fo]) {
                            return;
                        }
                        if (player.level().getBlockEntity(payload.pos()) != linked) {
                            return;
                        }
                        if (!validateDuctGuiInteraction(player, payload.pos())) {
                            return;
                        }
                        menu.handleSettingsCopierAction(player, payload);
                        menu.broadcastChanges();
                    });
                });

    }

    /**
     * Matches vanilla container {@code stillValid} reach check ({@code canInteractWithBlock(pos, 4.0)}), so duct GUI
     * packets are accepted whenever vanilla would allow opening/using the block menu.
     */
    private static boolean validateDuctGuiInteraction(ServerPlayer player, BlockPos pos) {
        return player.canInteractWithBlock(pos, 4.0);
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
                        denyOverridesAllow));
    }

    public static void sendListLogicToggle(BlockPos pos, Direction face, int transportKindOrdinal, int filterBankOrdinal) {
        PacketDistributor.sendToServer(
                new DuctListLogicPayload(pos, face.ordinal(), transportKindOrdinal, filterBankOrdinal));
    }

    public static void sendSelfFeedSet(BlockPos pos, Direction face, boolean enabled) {
        PacketDistributor.sendToServer(new DuctSelfFeedPayload(pos, face.ordinal(), enabled));
    }

    public static void sendEnergyBufferLimits(
            BlockPos pos, Direction face, int extractLimitFe, int insertLimitFe) {
        PacketDistributor.sendToServer(
                new DuctEnergyBufferLimitsPayload(pos, face.ordinal(), extractLimitFe, insertLimitFe));
    }

    /** Toggles duct opaque rendering preference (player attachment); server authoritative. */
    public static void sendDuctOpaqueToggle() {
        PacketDistributor.sendToServer(DuctOpaqueTogglePayload.INSTANCE);
    }

    /**
     * Applies a duct node menu button on the server with the same validation as other duct GUI payloads, avoiding
     * vanilla {@code ServerboundContainerButtonClickPacket} which is dropped when {@code stillValid} is false.
     */
    public static void sendDuctMenuButton(UniversalDuctMenu menu, int buttonId) {
        PacketDistributor.sendToServer(
                new DuctMenuButtonPayload(menu.getDuctBlockPos(), menu.getAccessFace().ordinal(), buttonId));
    }

    public static void sendSettingsCopierAction(
            DuctNodeMenu menu,
            int action,
            int viewKind,
            int transportKindOrdinal,
            int filterBankOrdinal,
            int allowDeny) {
        PacketDistributor.sendToServer(
                new SettingsCopierActionPayload(
                        menu.getDuctBlockPos(),
                        menu.getAccessFace().ordinal(),
                        action,
                        viewKind,
                        transportKindOrdinal,
                        filterBankOrdinal,
                        allowDeny));
    }

    public static void sendDuctGuiFeedback(ServerPlayer player, int messageId) {
        PacketDistributor.sendToPlayer(player, new DuctGuiFeedbackPayload(messageId));
    }

    public static void sendSettingsCopierStackSync(ServerPlayer player, ItemStack stack) {
        PacketDistributor.sendToPlayer(player, new SettingsCopierStackSyncPayload(stack.copy()));
    }

    public static void sendSettingsCopierHubAction(int action) {
        PacketDistributor.sendToServer(new SettingsCopierHubActionPayload(action));
    }

    public static void sendSettingsCopierHubAction(int action, String renameText) {
        PacketDistributor.sendToServer(new SettingsCopierHubActionPayload(action, renameText));
    }

    public static void sendSettingsCopierReturnToHub() {
        PacketDistributor.sendToServer(new SettingsCopierReturnToHubPayload());
    }

    public static void sendFilterSyncForVirtual(ServerPlayer player, SettingsCopierVirtualSession session) {
        var server = player.getServer();
        if (server == null) {
            sendFilterSyncForVirtualNow(player, session);
            return;
        }
        server.execute(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            sendFilterSyncForVirtualNow(player, session);
        });
    }

    private static void sendFilterSyncForVirtualNow(ServerPlayer player, SettingsCopierVirtualSession session) {
        DuctFaceNode node = session.activeMenuFaceNode();
        int tk = session.menuActiveTransportKind().ordinal();
        Direction face = session.accessFace();
        for (DuctFaceNode.FilterBank bank : DuctFaceNode.FilterBank.values()) {
            java.util.List<Integer> caps2 =
                    bank == DuctFaceNode.FilterBank.FILTER
                            ? java.util.List.copyOf(node.filterBankKeepCaps())
                            : java.util.List.of();
            PacketDistributor.sendToPlayer(
                    player,
                    new DuctFilterSyncPayload(
                            BlockPos.ZERO,
                            face.ordinal(),
                            tk,
                            bank.ordinal(),
                            java.util.List.copyOf(node.bankAllowFilters(bank)),
                            java.util.List.copyOf(node.bankDenyFilters(bank)),
                            java.util.List.copyOf(node.bankAllowCaps(bank)),
                            caps2,
                            node.bankDenyOverridesAllow(bank)));
        }
    }

    public static void sendEnergyRayPath(
            ServerLevel level,
            List<BlockPos> ductPath,
            int argb,
            Vec3 mid,
            @org.jetbrains.annotations.Nullable net.minecraft.core.Direction sourceAttachFace,
            @org.jetbrains.annotations.Nullable net.minecraft.core.Direction destAttachFace) {
        if (level == null || ductPath == null || ductPath.isEmpty()) {
            return;
        }
        var payload = new EnergyRayPathPayload(ductPath, argb, sourceAttachFace, destAttachFace);
        double r2 = 64.0 * 64.0;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(mid) <= r2) {
                PacketDistributor.sendToPlayer(p, payload);
            }
        }
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
                            node.bankDenyOverridesAllow(bank)));
        }
    }

    private static @Nullable SettingsCopierVirtualSession virtualCopierSession(ServerPlayer player) {
        if (player.containerMenu instanceof SettingsCopierMenu menu && menu.isVirtualLayer()) {
            return menu.virtualSession();
        }
        return null;
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
