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
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.inventory.FilterSyncDebugLog;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.inventory.UniversalDuctMenu;
import net.unfamily.another_dynamics.machine.sequential.SequentialBufferMenu;

import org.jetbrains.annotations.Nullable;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportResult;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportService;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.registry.ModAttachments;
import net.minecraft.world.InteractionHand;
import java.util.ArrayList;
import java.util.List;

public final class ModNetwork {
    public static final CustomPacketPayload.Type<DuctFieldPayload> DUCT_FIELD =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "duct_field"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DuctFieldPayload> DUCT_FIELD_STREAM =
            StreamCodec.of(
                    (buf, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                        ByteBufCodecs.VAR_INT.encode(buf, payload.faceOrdinal());
                        ByteBufCodecs.VAR_INT.encode(buf, payload.transportKindOrdinal());
                        ByteBufCodecs.INT.encode(buf, payload.insertionPriority());
                        ByteBufCodecs.INT.encode(buf, payload.extractBatch());
                        ByteBufCodecs.INT.encode(buf, payload.extractSequentialStack());
                        ByteBufCodecs.VAR_INT.encode(buf, payload.eligibilityModeOrdinal());
                    },
                    buf ->
                            new DuctFieldPayload(
                                    BlockPos.STREAM_CODEC.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf),
                                    ByteBufCodecs.INT.decode(buf),
                                    ByteBufCodecs.INT.decode(buf),
                                    ByteBufCodecs.INT.decode(buf),
                                    ByteBufCodecs.VAR_INT.decode(buf)));

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
                            payload.extractSequentialStack(),
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
                        payload.extractSequentialStack(),
                        payload.eligibilityModeOrdinal());
            });
        });

        reg.playToServer(DuctFilterUpdatePayload.TYPE, DuctFilterUpdatePayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                FilterSyncDebugLog.serverPacket(
                        "FILTER_UPDATE_RX",
                        "player="
                                + player.getGameProfile().getName()
                                + " pos="
                                + payload.pos()
                                + " face="
                                + payload.faceOrdinal()
                                + " tk="
                                + FilterSyncDebugLog.transportKindName(payload.transportKindOrdinal())
                                + " bank="
                                + FilterSyncDebugLog.bankName(payload.filterBankOrdinal())
                                + " allow="
                                + FilterSyncDebugLog.listPreview(payload.allow())
                                + " deny="
                                + FilterSyncDebugLog.listPreview(payload.deny()));
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
                            payload.allowConcat(),
                            payload.denyConcat(),
                            payload.allowRemote(),
                            payload.denyRemote(),
                            payload.allowRemoteIgnoreChannel(),
                            payload.denyRemoteIgnoreChannel(),
                            payload.allowRemoteAnyFace(),
                            payload.denyRemoteAnyFace(),
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
                        payload.allowConcat(),
                        payload.denyConcat(),
                        payload.allowRemote(),
                        payload.denyRemote(),
                        payload.allowRemoteIgnoreChannel(),
                        payload.denyRemoteIgnoreChannel(),
                        payload.allowRemoteAnyFace(),
                        payload.denyRemoteAnyFace(),
                        payload.denyOverridesAllow());
            });
        });

        reg.playToServer(
                DuctFilterSyncRequestPayload.TYPE,
                DuctFilterSyncRequestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        int fo = payload.faceOrdinal();
                        if (fo < 0 || fo >= Direction.values().length) {
                            return;
                        }
                        Direction face = Direction.values()[fo];
                        SettingsCopierVirtualSession session = virtualCopierSession(player);
                        if (session != null) {
                            if (!(player.containerMenu instanceof SettingsCopierMenu menu)) {
                                return;
                            }
                            if (!menu.getDuctBlockPos().equals(payload.pos()) || menu.getAccessFace() != face) {
                                return;
                            }
                            FilterSyncDebugLog.serverPacket(
                                    "FILTER_SYNC_REQUEST",
                                    "virtual player="
                                            + player.getGameProfile().getName()
                                            + " "
                                            + FilterSyncDebugLog.posFace(payload.pos(), face));
                            sendFilterSyncForVirtualNow(player, session);
                            return;
                        }
                        if (!(player.containerMenu instanceof DuctNodeMenu menu)) {
                            return;
                        }
                        if (!menu.getDuctBlockPos().equals(payload.pos()) || menu.getAccessFace() != face) {
                            return;
                        }
                        if (!validateDuctGuiInteraction(player, payload.pos())) {
                            return;
                        }
                        BlockEntity be = player.level().getBlockEntity(payload.pos());
                        if (!(be instanceof DuctBlockEntity duct) || duct.isRemoved()) {
                            return;
                        }
                        if (duct.isMenuHubLayer()) {
                            return;
                        }
                        FilterSyncDebugLog.serverPacket(
                                "FILTER_SYNC_REQUEST",
                                "duct player="
                                        + player.getGameProfile().getName()
                                        + " "
                                        + FilterSyncDebugLog.posFace(payload.pos(), face));
                        sendFilterSyncToPlayerNow(player, duct, face);
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

        reg.playToServer(DuctOpaqueTogglePayload.TYPE, DuctOpaqueTogglePayload.STREAM_CODEC, (payload, ctx) -> {
            ctx.enqueueWork(() -> {
                ServerPlayer player = (ServerPlayer) ctx.player();
                if (!(player.containerMenu instanceof UniversalDuctMenu menu)) {
                    return;
                }
                BlockPos anchor = payload.anchor();
                if (!anchor.equals(menu.getDuctBlockPos())) {
                    return;
                }
                if (player.containerMenu instanceof DuctNodeMenu ductMenu) {
                    DuctBlockEntity linked = ductMenu.linkedDuctBlockEntity();
                    if (linked != null && linked.isRemoved()) {
                        return;
                    }
                    if (!ductMenu.stillValid(player)) {
                        return;
                    }
                }
                if (payload.backwards()) {
                    net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation.retreatOpaqueCycle(player, anchor);
                } else {
                    net.unfamily.another_dynamics.duct.DuctNetworkOpaquePropagation.advanceOpaqueCycle(player, anchor);
                }
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
                        if (!(player.containerMenu instanceof SettingsCopierMenu copier)) {
                            return;
                        }
                        if (payload.action() == SettingsCopierHubActionPayload.ACTION_ENTER_IMPORT) {
                            if (copier.isHubLayer()) {
                                copier.enterImport(player);
                            }
                            return;
                        }
                        if (payload.action() == SettingsCopierHubActionPayload.ACTION_BACK_FROM_IMPORT) {
                            if (copier.isImportLayer()) {
                                copier.returnToHubFromImport(player);
                            }
                            return;
                        }
                        if (!copier.isHubLayer()) {
                            return;
                        }
                        InteractionHand hand = copier.getHand();
                        ItemStack stack = copier.copierStack(player);
                        if (stack.isEmpty() || !(stack.getItem() instanceof SettingsCopierItem)) {
                            return;
                        }
                        switch (payload.action()) {
                            case SettingsCopierHubActionPayload.ACTION_CONFIGURE -> {
                                if (SettingsCopierStoreKind.getMode(stack)
                                        == SettingsCopierStoreKind.SEQUENTIAL) {
                                    copier.enterSequentialVirtual(player);
                                } else {
                                    copier.enterVirtual(player);
                                }
                            }
                            case SettingsCopierHubActionPayload.ACTION_MODE_TOGGLE -> {
                                copier.discardVirtualEditors();
                                SettingsCopierStoreKind current = SettingsCopierStoreKind.getMode(stack);
                                SettingsCopierStoreKind next =
                                        switch (current) {
                                            case ALL -> SettingsCopierStoreKind.FILTER;
                                            case FILTER -> SettingsCopierStoreKind.SEQUENTIAL;
                                            case SEQUENTIAL, WHOLE -> SettingsCopierStoreKind.ALL;
                                        };
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
                FilterImportChannelPayload.TYPE,
                FilterImportChannelPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        if (!(player.containerMenu instanceof SettingsCopierMenu copier)
                                || !copier.isImportLayer()) {
                            return;
                        }
                        copier.setImportChannelOrdinal(payload.channelOrdinal());
                        copier.reconcileImportSecondSlot(player);
                    });
                });

        reg.playToServer(
                FilterImportExecutePayload.TYPE,
                FilterImportExecutePayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        if (!(player.containerMenu instanceof SettingsCopierMenu copier)
                                || !copier.isImportLayer()) {
                            return;
                        }
                        FilterImportChannel[] channels = FilterImportChannel.values();
                        int ord = payload.channelOrdinal();
                        if (ord < 0 || ord >= channels.length) {
                            return;
                        }
                        FilterImportResult result =
                                FilterImportService.execute(
                                        player,
                                        copier,
                                        copier.getHand(),
                                        channels[ord],
                                        payload.primaryName(),
                                        payload.secondaryName());
                        if (result == FilterImportResult.SUCCESS) {
                            copier.returnToHubFromImport(player);
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

        reg.playToServer(
                SettingsCopierFilterMaterialKindPayload.TYPE,
                SettingsCopierFilterMaterialKindPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        SettingsCopierVirtualSession session = virtualCopierSession(player);
                        if (session == null || session.storeKind() != SettingsCopierStoreKind.FILTER) {
                            return;
                        }
                        session.setFilterListMaterialKind(
                                FilterListMaterialKind.fromOrdinal(payload.materialKindOrdinal()));
                        if (player.containerMenu instanceof SettingsCopierMenu menu) {
                            menu.broadcastChanges();
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
                                payload.allowConcat(),
                                payload.denyConcat(),
                                payload.allowRemote(),
                                payload.denyRemote(),
                                payload.allowRemoteIgnoreChannel(),
                                payload.denyRemoteIgnoreChannel(),
                                payload.allowRemoteAnyFace(),
                                payload.denyRemoteAnyFace(),
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

        reg.playToServer(
                SequentialBufferActionPayload.TYPE,
                SequentialBufferActionPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = (ServerPlayer) ctx.player();
                        if (player.containerMenu instanceof SequentialBufferMenu menu) {
                            if (!menu.stillValid(player)) {
                                return;
                            }
                            menu.handleAction(player, payload);
                            menu.broadcastChanges();
                            return;
                        }
                        if (player.containerMenu instanceof SettingsCopierMenu copier
                                && copier.isSequentialVirtualLayer()) {
                            copier.handleSequentialAction(player, payload);
                            copier.broadcastChanges();
                        }
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
            int extractSequentialStack,
            int eligibilityModeOrdinal) {
        PacketDistributor.sendToServer(
                new DuctFieldPayload(
                        pos,
                        face.ordinal(),
                        transportKindOrdinal,
                        insertionPriority,
                        extractBatch,
                        extractSequentialStack,
                        eligibilityModeOrdinal));
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
            java.util.List<Integer> allowConcat,
            java.util.List<Integer> denyConcat,
            java.util.List<net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint> allowRemote,
            java.util.List<net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint> denyRemote,
            java.util.List<Boolean> allowRemoteIgnoreChannel,
            java.util.List<Boolean> denyRemoteIgnoreChannel,
            java.util.List<Boolean> allowRemoteAnyFace,
            java.util.List<Boolean> denyRemoteAnyFace,
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
                        allowConcat,
                        denyConcat,
                        allowRemote,
                        denyRemote,
                        allowRemoteIgnoreChannel,
                        denyRemoteIgnoreChannel,
                        allowRemoteAnyFace,
                        denyRemoteAnyFace,
                        denyOverridesAllow));
    }

    /** Client duct GUI is open; server sends filter snapshots for the active transport kind. */
    public static void sendFilterSyncRequest(BlockPos pos, Direction face) {
        PacketDistributor.sendToServer(new DuctFilterSyncRequestPayload(pos, face.ordinal()));
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

    /** Advances opaque cycle for duct at {@code anchor} (server authoritative). */
    public static void sendDuctOpaqueToggle(BlockPos anchor) {
        PacketDistributor.sendToServer(new DuctOpaqueTogglePayload(anchor, false));
    }

    /** Retreats opaque cycle for duct at {@code anchor} (server authoritative). */
    public static void sendDuctOpaqueToggleBackwards(BlockPos anchor) {
        PacketDistributor.sendToServer(new DuctOpaqueTogglePayload(anchor, true));
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

    public static void sendSequentialBufferAction(SequentialBufferActionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendSettingsCopierHubAction(int action, String renameText) {
        PacketDistributor.sendToServer(new SettingsCopierHubActionPayload(action, renameText));
    }

    public static void sendSettingsCopierReturnToHub() {
        PacketDistributor.sendToServer(new SettingsCopierReturnToHubPayload());
    }

    public static void sendSettingsCopierFilterMaterialKind(int materialKindOrdinal) {
        PacketDistributor.sendToServer(
                new SettingsCopierFilterMaterialKindPayload(materialKindOrdinal));
    }

    public static void sendFilterImportChannel(int channelOrdinal) {
        PacketDistributor.sendToServer(new FilterImportChannelPayload(channelOrdinal));
    }

    public static void sendFilterImportExecute(
            int channelOrdinal, String primaryName, String secondaryName) {
        PacketDistributor.sendToServer(
                new FilterImportExecutePayload(channelOrdinal, primaryName, secondaryName));
    }

    public static void sendFilterSyncForVirtual(ServerPlayer player, SettingsCopierVirtualSession session) {
        sendFilterSyncForVirtual(player, session, session.menuActiveTransportKind());
    }

    public static void sendFilterSyncForVirtual(
            ServerPlayer player, SettingsCopierVirtualSession session, DuctTransportKind lane) {
        var server = player.getServer();
        if (server == null) {
            sendFilterSyncForVirtualNow(player, session, lane);
            return;
        }
        server.execute(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            sendFilterSyncForVirtualNow(player, session, lane);
        });
    }

    private static void sendFilterSyncForVirtualNow(ServerPlayer player, SettingsCopierVirtualSession session) {
        sendFilterSyncForVirtualNow(player, session, session.menuActiveTransportKind());
    }

    private static void sendFilterSyncForVirtualNow(
            ServerPlayer player, SettingsCopierVirtualSession session, DuctTransportKind lane) {
        DuctFaceNode node = session.faceNodeForTransportKind(lane);
        int tk = lane.ordinal();
        Direction face = session.accessFace();
        for (DuctFaceNode.FilterBank bank : DuctFaceNode.FilterBank.values()) {
            java.util.List<Integer> caps2 =
                    node.bankAllowCaps2(bank) != null
                            ? copyFilterSyncList(node.bankAllowCaps2(bank))
                            : java.util.List.of();
            FilterSyncDebugLog.serverSyncSend(
                    "virtual",
                    BlockPos.ZERO,
                    face,
                    tk,
                    bank.ordinal(),
                    node.bankAllowFilters(bank),
                    node.bankDenyFilters(bank));
            PacketDistributor.sendToPlayer(
                    player,
                    new DuctFilterSyncPayload(
                            BlockPos.ZERO,
                            face.ordinal(),
                            tk,
                            bank.ordinal(),
                            copyFilterSyncList(node.bankAllowFilters(bank)),
                            copyFilterSyncList(node.bankDenyFilters(bank)),
                            copyFilterSyncList(node.bankAllowCaps(bank)),
                            caps2,
                            copyFilterSyncList(node.bankAllowConcatChannels(bank)),
                            copyFilterSyncList(node.bankDenyConcatChannels(bank)),
                            copyFilterSyncList(node.bankAllowRemoteNodes(bank)),
                            copyFilterSyncList(node.bankDenyRemoteNodes(bank)),
                            copyBoolSyncList(node.bankAllowRemoteIgnoreChannel(bank)),
                            copyBoolSyncList(node.bankDenyRemoteIgnoreChannel(bank)),
                            copyBoolSyncList(node.bankAllowRemoteAnyFace(bank)),
                            copyBoolSyncList(node.bankDenyRemoteAnyFace(bank)),
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
        sendFilterSyncToPlayer(player, duct, face, duct.menuActiveTransportKind());
    }

    public static void sendFilterSyncToPlayer(
            ServerPlayer player, DuctBlockEntity duct, Direction face, DuctTransportKind lane) {
        var server = player.getServer();
        if (server == null) {
            sendFilterSyncToPlayerNow(player, duct, face, lane);
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
                    sendFilterSyncToPlayerNow(player, duct, face, lane);
                });
    }

    private static void sendFilterSyncToPlayerNow(ServerPlayer player, DuctBlockEntity duct, Direction face) {
        sendFilterSyncToPlayerNow(player, duct, face, duct.menuActiveTransportKind());
    }

    private static void sendFilterSyncToPlayerNow(
            ServerPlayer player, DuctBlockEntity duct, Direction face, DuctTransportKind lane) {
        var node = duct.faceNodeForTransportKind(face, lane);
        int tk = lane.ordinal();
        for (DuctFaceNode.FilterBank bank : DuctFaceNode.FilterBank.values()) {
            java.util.List<Integer> caps2 =
                    node.bankAllowCaps2(bank) != null
                            ? copyFilterSyncList(node.bankAllowCaps2(bank))
                            : java.util.List.of();
            FilterSyncDebugLog.serverSyncSend(
                    "duct",
                    duct.getBlockPos(),
                    face,
                    tk,
                    bank.ordinal(),
                    node.bankAllowFilters(bank),
                    node.bankDenyFilters(bank));
            PacketDistributor.sendToPlayer(
                    player,
                    new DuctFilterSyncPayload(
                            duct.getBlockPos(),
                            face.ordinal(),
                            tk,
                            bank.ordinal(),
                            copyFilterSyncList(node.bankAllowFilters(bank)),
                            copyFilterSyncList(node.bankDenyFilters(bank)),
                            copyFilterSyncList(node.bankAllowCaps(bank)),
                            caps2,
                            copyFilterSyncList(node.bankAllowConcatChannels(bank)),
                            copyFilterSyncList(node.bankDenyConcatChannels(bank)),
                            copyFilterSyncList(node.bankAllowRemoteNodes(bank)),
                            copyFilterSyncList(node.bankDenyRemoteNodes(bank)),
                            copyBoolSyncList(node.bankAllowRemoteIgnoreChannel(bank)),
                            copyBoolSyncList(node.bankDenyRemoteIgnoreChannel(bank)),
                            copyBoolSyncList(node.bankAllowRemoteAnyFace(bank)),
                            copyBoolSyncList(node.bankDenyRemoteAnyFace(bank)),
                            node.bankDenyOverridesAllow(bank)));
        }
    }

    /**
     * Copies filter sync lists for packets. {@link List#copyOf} rejects null elements; remote-node slots use null
     * placeholders for empty bindings.
     */
    private static <T> List<T> copyFilterSyncList(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(source);
    }

    private static List<Boolean> copyBoolSyncList(List<Boolean> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Boolean> out = new ArrayList<>(source.size());
        for (Boolean v : source) {
            out.add(v != null && v);
        }
        return out;
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
            int extractSequentialStack,
            int eligibilityModeOrdinal)
            implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return DUCT_FIELD;
        }
    }
}
