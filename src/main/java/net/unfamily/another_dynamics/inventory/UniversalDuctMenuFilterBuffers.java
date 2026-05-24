package net.unfamily.another_dynamics.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;

/**
 * Client-side filter list mirrors shared by {@link DuctNodeMenu} and {@link SettingsCopierMenu}.
 */
final class UniversalDuctMenuFilterBuffers {
    private final List<String> clientAllowFiltersExtractor = new ArrayList<>();
    private final List<String> clientDenyFiltersExtractor = new ArrayList<>();
    private final List<String> clientAllowFiltersRetriever = new ArrayList<>();
    private final List<String> clientDenyFiltersRetriever = new ArrayList<>();
    private final List<String> clientAllowFiltersFilter = new ArrayList<>();
    private final List<String> clientDenyFiltersFilter = new ArrayList<>();
    private final List<Integer> clientAllowCapsExtractor = new ArrayList<>();
    private final List<Integer> clientAllowCapsRetriever = new ArrayList<>();
    private final List<Integer> clientAllowCapsFilter = new ArrayList<>();
    private final List<Integer> clientAllowCapsFilter2 = new ArrayList<>();
    private boolean clientDenyOverridesAllowExtractor;
    private boolean clientDenyOverridesAllowRetriever;
    private boolean clientDenyOverridesAllowFilter;

    List<String> getClientAllowFilters(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientAllowFiltersExtractor;
            case RETRIEVER -> clientAllowFiltersRetriever;
            case FILTER -> clientAllowFiltersFilter;
        };
    }

    List<String> getClientDenyFilters(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientDenyFiltersExtractor;
            case RETRIEVER -> clientDenyFiltersRetriever;
            case FILTER -> clientDenyFiltersFilter;
        };
    }

    boolean getClientDenyOverridesAllow(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientDenyOverridesAllowExtractor;
            case RETRIEVER -> clientDenyOverridesAllowRetriever;
            case FILTER -> clientDenyOverridesAllowFilter;
        };
    }

    List<Integer> getClientAllowCaps(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> clientAllowCapsExtractor;
            case RETRIEVER -> clientAllowCapsRetriever;
            case FILTER -> clientAllowCapsFilter;
        };
    }

    List<Integer> getClientFilterKeepCaps() {
        return clientAllowCapsFilter2;
    }

    void receiveFilterSync(
            BlockPos expectedPos,
            Direction expectedFace,
            ContainerData syncData,
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            List<Integer> allowCaps,
            List<Integer> allowCaps2,
            boolean denyOverridesAllow) {
        if (!expectedPos.equals(pos) || expectedFace != face) {
            return;
        }
        if (transportKindOrdinal != syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND)) {
            return;
        }
        DuctFaceNode.FilterBank bank =
                DuctFaceNode.FilterBank.values()[
                        Mth.clamp(
                                filterBankOrdinal,
                                0,
                                DuctFaceNode.FilterBank.values().length - 1)];
        List<String> a = getClientAllowFilters(bank);
        List<String> d = getClientDenyFilters(bank);
        List<Integer> caps = getClientAllowCaps(bank);
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
        if (bank == DuctFaceNode.FilterBank.FILTER) {
            clientAllowCapsFilter2.clear();
            if (allowCaps2 != null) {
                for (Integer v : allowCaps2) {
                    clientAllowCapsFilter2.add(Math.max(0, v != null ? v : 0));
                }
            }
        }
        switch (bank) {
            case EXTRACTOR -> clientDenyOverridesAllowExtractor = denyOverridesAllow;
            case RETRIEVER -> clientDenyOverridesAllowRetriever = denyOverridesAllow;
            case FILTER -> clientDenyOverridesAllowFilter = denyOverridesAllow;
        }
    }

    void ensureClientFilterBufferSizes(
            ContainerData syncData,
            String logicalId,
            int moduleSlotCount,
            java.util.function.Function<Integer, ItemStack> moduleSlotStack,
            boolean unlimitedFilters,
            boolean hybridFilterContext) {
        int maxA = Math.max(0, filterAllowCap(syncData, logicalId, moduleSlotCount, moduleSlotStack, unlimitedFilters, hybridFilterContext));
        int maxD = Math.max(0, filterDenyCap(syncData, logicalId, moduleSlotCount, moduleSlotStack, unlimitedFilters, hybridFilterContext));
        clampClientList(clientAllowFiltersExtractor, maxA);
        clampClientList(clientDenyFiltersExtractor, maxD);
        clampClientList(clientAllowFiltersRetriever, maxA);
        clampClientList(clientDenyFiltersRetriever, maxD);
        clampClientList(clientAllowFiltersFilter, maxA);
        clampClientList(clientDenyFiltersFilter, maxD);
        clampClientIntList(clientAllowCapsExtractor, maxA);
        clampClientIntList(clientAllowCapsRetriever, maxA);
        clampClientIntList(clientAllowCapsFilter, maxA);
        clampClientIntList(clientAllowCapsFilter2, maxA);
    }

    static int filterAllowCap(
            ContainerData syncData,
            String logicalId,
            int moduleSlotCount,
            java.util.function.Function<Integer, ItemStack> moduleSlotStack,
            boolean unlimitedFilters,
            boolean hybridFilterContext) {
        if (unlimitedFilters) {
            return SettingsCopierVirtualSession.UNLIMITED_FILTER_LINES;
        }
        if (clientEditingEnergyOrHeatLane(syncData)) {
            return 0;
        }
        NodeMode nm = NodeMode.fromOrdinal(syncData.get(DuctMenuSync.NODE_MODE));
        DuctModuleEffects.FilterSlotBonuses fb =
                clientFilterSlotBonusesFromModuleColumn(moduleSlotCount, moduleSlotStack);
        if (clientEditingFluidLane(syncData)) {
            return DuctModuleEffects.effectiveFluidAllowBank(
                    fluidSpec(logicalId), nm, fb);
        }
        if (clientEditingGasLane(syncData)) {
            return DuctModuleEffects.effectiveGasAllowBank(gasSpec(logicalId), nm, fb);
        }
        return DuctModuleEffects.effectiveItemAllowBank(itemSpec(logicalId), nm, fb);
    }

    static int filterDenyCap(
            ContainerData syncData,
            String logicalId,
            int moduleSlotCount,
            java.util.function.Function<Integer, ItemStack> moduleSlotStack,
            boolean unlimitedFilters,
            boolean hybridFilterContext) {
        if (unlimitedFilters) {
            return SettingsCopierVirtualSession.UNLIMITED_FILTER_LINES;
        }
        if (clientEditingEnergyOrHeatLane(syncData)) {
            return 0;
        }
        NodeMode nm = NodeMode.fromOrdinal(syncData.get(DuctMenuSync.NODE_MODE));
        DuctModuleEffects.FilterSlotBonuses fb =
                clientFilterSlotBonusesFromModuleColumn(moduleSlotCount, moduleSlotStack);
        if (clientEditingFluidLane(syncData)) {
            return DuctModuleEffects.effectiveFluidDenyBank(fluidSpec(logicalId), nm, fb);
        }
        if (clientEditingGasLane(syncData)) {
            return DuctModuleEffects.effectiveGasDenyBank(gasSpec(logicalId), nm, fb);
        }
        return DuctModuleEffects.effectiveItemDenyBank(itemSpec(logicalId), nm, fb);
    }

    private static boolean clientEditingFluidLane(ContainerData syncData) {
        return syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.FLUID.ordinal();
    }

    private static boolean clientEditingGasLane(ContainerData syncData) {
        return syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.GAS.ordinal();
    }

    private static boolean clientEditingEnergyOrHeatLane(ContainerData syncData) {
        int o = syncData.get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
        return o == DuctTransportKind.ENERGY.ordinal() || o == DuctTransportKind.HEAT.ordinal();
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

    private static void clampClientList(List<String> list, int max) {
        while (list.size() < max) {
            list.add("");
        }
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }

    private static void clampClientIntList(List<Integer> list, int max) {
        while (list.size() < max) {
            list.add(0);
        }
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }
}
