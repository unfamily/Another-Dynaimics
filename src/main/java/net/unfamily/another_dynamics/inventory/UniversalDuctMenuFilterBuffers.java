package net.unfamily.another_dynamics.inventory;

import java.util.EnumMap;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFilterLineReorder;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctGasTransportSpec;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.module.DuctModuleHelper;
import net.unfamily.another_dynamics.duct.module.ModuleDefinition;
import net.unfamily.another_dynamics.duct.module.ModuleDefinitionRegistry;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;

import org.jetbrains.annotations.Nullable;

/**
 * Client-side filter list mirrors shared by {@link DuctNodeMenu} and {@link SettingsCopierMenu}.
 * One {@link ClientFilterLaneMirror} per {@link DuctTransportKind} prevents cross-lane contamination.
 */
final class UniversalDuctMenuFilterBuffers {
    private final ClientFilterMirrorStore mirrors = new ClientFilterMirrorStore();
    private final EnumMap<DuctTransportKind, ClientFilterPushState> pushByKind =
            new EnumMap<>(DuctTransportKind.class);

    private ClientFilterPushState pushState(DuctTransportKind kind) {
        return pushByKind.computeIfAbsent(kind, k -> new ClientFilterPushState());
    }

    private ClientFilterPushState pushState(int transportKindOrdinal) {
        return pushState(DuctTransportKind.values()[
                Mth.clamp(transportKindOrdinal, 0, DuctTransportKind.values().length - 1)]);
    }

    private ClientFilterLaneMirror mirrorForKind(int filterTransportKindOrdinal) {
        return mirrors.mirror(filterTransportKindOrdinal);
    }

    List<String> getClientAllowFilters(int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).allowFilters(bank);
    }

    List<String> getClientDenyFilters(int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).denyFilters(bank);
    }

    boolean getClientDenyOverridesAllow(int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).denyOverridesAllow(bank);
    }

    List<Integer> getClientAllowCaps(int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).allowCaps(bank);
    }

    List<Integer> getClientFilterKeepCaps(int filterTransportKindOrdinal) {
        return mirrorForKind(filterTransportKindOrdinal).filterKeepCaps();
    }

    List<Integer> getClientAllowConcatChannels(int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).allowConcat(bank);
    }

    List<Integer> getClientDenyConcatChannels(int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).denyConcat(bank);
    }

    List<@Nullable DuctDirectionalEndpoint> getClientAllowRemoteNodes(
            int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).allowRemote(bank);
    }

    List<@Nullable DuctDirectionalEndpoint> getClientDenyRemoteNodes(
            int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).denyRemote(bank);
    }

    List<Boolean> getClientAllowRemoteIgnoreChannel(
            int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).allowRemoteIgnoreChannel(bank);
    }

    List<Boolean> getClientDenyRemoteIgnoreChannel(
            int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).denyRemoteIgnoreChannel(bank);
    }

    List<Boolean> getClientAllowRemoteAnyFace(
            int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).allowRemoteAnyFace(bank);
    }

    List<Boolean> getClientDenyRemoteAnyFace(
            int filterTransportKindOrdinal, DuctFaceNode.FilterBank bank) {
        return mirrorForKind(filterTransportKindOrdinal).denyRemoteAnyFace(bank);
    }

    ClientFilterLaneMirror mirrorForTransport(int transportKindOrdinal) {
        return mirrors.mirror(transportKindOrdinal);
    }

    void reorderFilterBank(
            int transportKindOrdinal,
            DuctFaceNode.FilterBank bank,
            net.minecraft.core.RegistryAccess registryAccess) {
        ClientFilterLaneMirror mirror = mirrors.mirror(transportKindOrdinal);
        List<Integer> keepCaps = bank == DuctFaceNode.FilterBank.FILTER ? mirror.filterKeepCaps() : null;
        DuctFilterLineReorder.sortAllowDenyRows(
                mirror.allowFilters(bank),
                mirror.denyFilters(bank),
                mirror.allowCaps(bank),
                null,
                registryAccess,
                keepCaps,
                mirror.allowConcat(bank),
                mirror.denyConcat(bank),
                mirror.allowRemote(bank),
                mirror.denyRemote(bank),
                mirror.allowRemoteIgnoreChannel(bank),
                mirror.denyRemoteIgnoreChannel(bank),
                mirror.allowRemoteAnyFace(bank),
                mirror.denyRemoteAnyFace(bank));
    }

    void receiveFilterSync(
            BlockPos expectedPos,
            Direction expectedFace,
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<@Nullable DuctDirectionalEndpoint> allowRemote,
            List<@Nullable DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowRemoteIgnoreChannel,
            List<Boolean> denyRemoteIgnoreChannel,
            List<Boolean> allowRemoteAnyFace,
            List<Boolean> denyRemoteAnyFace,
            boolean denyOverridesAllow) {
        if (!expectedPos.equals(pos) || expectedFace != face) {
            FilterSyncDebugLog.log(
                    "CLIENT",
                    "RECEIVE_SYNC_REJECT",
                    "pos/face mismatch expected="
                            + FilterSyncDebugLog.posFace(expectedPos, expectedFace)
                            + " got="
                            + FilterSyncDebugLog.posFace(pos, face));
            return;
        }
        if (pushState(transportKindOrdinal).dirty()) {
            FilterSyncDebugLog.clientReceiveSync(
                    pos, face, transportKindOrdinal, filterBankOrdinal, allow, deny, true);
            return;
        }
        FilterSyncDebugLog.clientReceiveSync(
                pos, face, transportKindOrdinal, filterBankOrdinal, allow, deny, false);
        DuctFaceNode.FilterBank bank =
                DuctFaceNode.FilterBank.values()[
                        Mth.clamp(
                                filterBankOrdinal,
                                0,
                                DuctFaceNode.FilterBank.values().length - 1)];
        ClientFilterLaneMirror lane = mirrors.mirror(transportKindOrdinal);
        List<String> a = lane.allowFilters(bank);
        List<String> d = lane.denyFilters(bank);
        List<Integer> caps = lane.allowCaps(bank);
        List<Integer> allowCh = lane.allowConcat(bank);
        List<Integer> denyCh = lane.denyConcat(bank);
        List<@Nullable DuctDirectionalEndpoint> allowRemoteNodes = lane.allowRemote(bank);
        List<@Nullable DuctDirectionalEndpoint> denyRemoteNodes = lane.denyRemote(bank);
        List<Boolean> allowIgnore = lane.allowRemoteIgnoreChannel(bank);
        List<Boolean> denyIgnore = lane.denyRemoteIgnoreChannel(bank);
        List<Boolean> allowAnyFace = lane.allowRemoteAnyFace(bank);
        List<Boolean> denyAnyFace = lane.denyRemoteAnyFace(bank);
        a.clear();
        a.addAll(allow);
        d.clear();
        d.addAll(deny);
        caps.clear();
        if (allowCaps != null) {
            for (Integer v : allowCaps) {
                caps.add(Math.max(0, v != null ? v : 0));
            }
        }
        allowCh.clear();
        if (allowConcat != null) {
            for (Integer v : allowConcat) {
                allowCh.add(v != null ? Math.clamp(v, 0, 26) : 0);
            }
        }
        denyCh.clear();
        if (denyConcat != null) {
            for (Integer v : denyConcat) {
                denyCh.add(v != null ? Math.clamp(v, 0, 26) : 0);
            }
        }
        allowRemoteNodes.clear();
        if (allowRemote != null) {
            allowRemoteNodes.addAll(allowRemote);
        }
        denyRemoteNodes.clear();
        if (denyRemote != null) {
            denyRemoteNodes.addAll(denyRemote);
        }
        allowIgnore.clear();
        if (allowRemoteIgnoreChannel != null) {
            for (Boolean v : allowRemoteIgnoreChannel) {
                allowIgnore.add(v != null && v);
            }
        }
        denyIgnore.clear();
        if (denyRemoteIgnoreChannel != null) {
            for (Boolean v : denyRemoteIgnoreChannel) {
                denyIgnore.add(v != null && v);
            }
        }
        allowAnyFace.clear();
        if (allowRemoteAnyFace != null) {
            for (Boolean v : allowRemoteAnyFace) {
                allowAnyFace.add(v != null && v);
            }
        }
        denyAnyFace.clear();
        if (denyRemoteAnyFace != null) {
            for (Boolean v : denyRemoteAnyFace) {
                denyAnyFace.add(v != null && v);
            }
        }
        if (bank == DuctFaceNode.FilterBank.FILTER) {
            List<Integer> keep = lane.filterKeepCaps();
            keep.clear();
            if (allowCaps2 != null) {
                for (Integer v : allowCaps2) {
                    keep.add(Math.max(0, v != null ? v : 0));
                }
            }
        }
        lane.setDenyOverridesAllow(bank, denyOverridesAllow);
        ClientFilterPushState state = pushState(transportKindOrdinal);
        state.onServerFilterSync(transportKindOrdinal, filterBankOrdinal);
        FilterSyncDebugLog.clientPushState(
                "mirror",
                transportKindOrdinal,
                state.hydrated(),
                state.dirty(),
                state.syncBankMask(),
                "after_receive_sync");
    }

    boolean clientFiltersHydrated(int filterTransportKindOrdinal) {
        return pushState(filterTransportKindOrdinal).hydrated();
    }

    boolean clientFiltersDirty(int filterTransportKindOrdinal) {
        return pushState(filterTransportKindOrdinal).dirty();
    }

    void markClientFiltersDirty(int filterTransportKindOrdinal) {
        pushState(filterTransportKindOrdinal).markDirty(filterTransportKindOrdinal);
    }

    void logPushState(int filterTransportKindOrdinal, String reason) {
        ClientFilterPushState state = pushState(filterTransportKindOrdinal);
        FilterSyncDebugLog.clientPushState(
                "buffers",
                filterTransportKindOrdinal,
                state.hydrated(),
                state.dirty(),
                state.syncBankMask(),
                reason);
    }

    boolean shouldPushOnClose(int filterTransportKindOrdinal) {
        return pushState(filterTransportKindOrdinal).shouldPushOnClose();
    }

    void ensureClientFilterBufferSizes(
            int filterTransportKindOrdinal,
            ContainerData syncData,
            String logicalId,
            int moduleSlotCount,
            java.util.function.Function<Integer, ItemStack> moduleSlotStack,
            boolean unlimitedFilters,
            boolean hybridFilterContext) {
        if (!unlimitedFilters && clientEditingEnergyOrHeatLane(filterTransportKindOrdinal)) {
            return;
        }
        int maxA = Math.max(
                0,
                filterAllowCap(
                        filterTransportKindOrdinal,
                        syncData,
                        logicalId,
                        moduleSlotCount,
                        moduleSlotStack,
                        unlimitedFilters,
                        hybridFilterContext));
        int maxD = Math.max(
                0,
                filterDenyCap(
                        filterTransportKindOrdinal,
                        syncData,
                        logicalId,
                        moduleSlotCount,
                        moduleSlotStack,
                        unlimitedFilters,
                        hybridFilterContext));
        mirrorForKind(filterTransportKindOrdinal).clampSizes(maxA, maxD);
    }

    static int filterAllowCap(
            int filterTransportKindOrdinal,
            ContainerData syncData,
            String logicalId,
            int moduleSlotCount,
            java.util.function.Function<Integer, ItemStack> moduleSlotStack,
            boolean unlimitedFilters,
            boolean hybridFilterContext) {
        if (unlimitedFilters) {
            return SettingsCopierVirtualSession.UNLIMITED_FILTER_LINES;
        }
        if (clientEditingEnergyOrHeatLane(filterTransportKindOrdinal)) {
            return 0;
        }
        NodeMode nm = NodeMode.fromOrdinal(syncData.get(DuctMenuSync.NODE_MODE));
        DuctModuleEffects.FilterSlotBonuses fb =
                clientFilterSlotBonusesFromModuleColumn(moduleSlotCount, moduleSlotStack);
        if (clientEditingFluidLane(filterTransportKindOrdinal)) {
            return DuctModuleEffects.effectiveFluidAllowBank(fluidSpec(logicalId), nm, fb);
        }
        if (clientEditingGasLane(filterTransportKindOrdinal)) {
            return DuctModuleEffects.effectiveGasAllowBank(gasSpec(logicalId), nm, fb);
        }
        return DuctModuleEffects.effectiveItemAllowBank(itemSpec(logicalId), nm, fb);
    }

    static int filterDenyCap(
            int filterTransportKindOrdinal,
            ContainerData syncData,
            String logicalId,
            int moduleSlotCount,
            java.util.function.Function<Integer, ItemStack> moduleSlotStack,
            boolean unlimitedFilters,
            boolean hybridFilterContext) {
        if (unlimitedFilters) {
            return SettingsCopierVirtualSession.UNLIMITED_FILTER_LINES;
        }
        if (clientEditingEnergyOrHeatLane(filterTransportKindOrdinal)) {
            return 0;
        }
        NodeMode nm = NodeMode.fromOrdinal(syncData.get(DuctMenuSync.NODE_MODE));
        DuctModuleEffects.FilterSlotBonuses fb =
                clientFilterSlotBonusesFromModuleColumn(moduleSlotCount, moduleSlotStack);
        if (clientEditingFluidLane(filterTransportKindOrdinal)) {
            return DuctModuleEffects.effectiveFluidDenyBank(fluidSpec(logicalId), nm, fb);
        }
        if (clientEditingGasLane(filterTransportKindOrdinal)) {
            return DuctModuleEffects.effectiveGasDenyBank(gasSpec(logicalId), nm, fb);
        }
        return DuctModuleEffects.effectiveItemDenyBank(itemSpec(logicalId), nm, fb);
    }

    private static boolean clientEditingFluidLane(int filterTransportKindOrdinal) {
        return filterTransportKindOrdinal == DuctTransportKind.FLUID.ordinal();
    }

    private static boolean clientEditingGasLane(int filterTransportKindOrdinal) {
        return filterTransportKindOrdinal == DuctTransportKind.GAS.ordinal();
    }

    private static boolean clientEditingEnergyOrHeatLane(int filterTransportKindOrdinal) {
        return filterTransportKindOrdinal == DuctTransportKind.ENERGY.ordinal()
                || filterTransportKindOrdinal == DuctTransportKind.HEAT.ordinal();
    }

    private static DuctItemTransportSpec itemSpec(String logicalId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .map(DuctDefinition::itemTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::itemDuctTransportSpec);
    }

    private static DuctFluidTransportSpec fluidSpec(String logicalId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .map(DuctDefinition::fluidTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::fluidDuctTransportSpec);
    }

    private static DuctGasTransportSpec gasSpec(String logicalId) {
        return DuctDefinitionRegistry.getByLogicalId(logicalId)
                .map(DuctDefinition::gasTransportOrFallback)
                .orElseGet(DuctGasTransportSpec::fallback);
    }

    private static DuctModuleEffects.FilterSlotBonuses clientFilterSlotBonusesFromModuleColumn(
            int moduleSlotCount, java.util.function.Function<Integer, ItemStack> moduleSlotStack) {
        int ia = 0, idn = 0, iah = 0, idnh = 0;
        int fa = 0, fd = 0, fah = 0, fdh = 0;
        int ga = 0, gd = 0, gah = 0, gdh = 0;
        for (int i = 0; i < moduleSlotCount; i++) {
            ItemStack s = moduleSlotStack.apply(i);
            if (s.isEmpty()) {
                continue;
            }
            var modId = DuctModuleHelper.resolvedDeclarationId(s);
            if (modId.isEmpty()) {
                continue;
            }
            ModuleDefinition def = ModuleDefinitionRegistry.get(modId.get()).orElse(null);
            if (def == null) {
                continue;
            }
            ModuleDefinition.FilterSlotModifiers fi = def.filterSlotsItem();
            ia += fi.allowSlotAdd();
            idn += fi.denySlotAdd();
            iah += fi.allowHybridSlotAdd();
            idnh += fi.denyHybridSlotAdd();
            ModuleDefinition.FilterSlotModifiers ff = def.filterSlotsFluid();
            fa += ff.allowSlotAdd();
            fd += ff.denySlotAdd();
            fah += ff.allowHybridSlotAdd();
            fdh += ff.denyHybridSlotAdd();
            ModuleDefinition.FilterSlotModifiers fg = def.filterSlotsGas();
            ga += fg.allowSlotAdd();
            gd += fg.denySlotAdd();
            gah += fg.allowHybridSlotAdd();
            gdh += fg.denyHybridSlotAdd();
        }
        return new DuctModuleEffects.FilterSlotBonuses(
                new DuctModuleEffects.FilterSlotBonuses.PerKind(ia, idn, iah, idnh),
                new DuctModuleEffects.FilterSlotBonuses.PerKind(fa, fd, fah, fdh),
                new DuctModuleEffects.FilterSlotBonuses.PerKind(ga, gd, gah, gdh));
    }
}
