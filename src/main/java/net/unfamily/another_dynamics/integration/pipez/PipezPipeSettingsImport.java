package net.unfamily.another_dynamics.integration.pipez;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.SettingsCopierFeedback;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportPreview;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalFilterLineConverter;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalUpgradeFilterImportSource;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalUpgradeStackComponents;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.item.SettingsCopierItem;

/**
 * One-way Pipez pipe → Settings Copier {@link SettingsCopierStoreKind#WHOLE} import.
 * Never writes back to Pipez. Soft-deps via reflection + existing upgrade component readers.
 */
public final class PipezPipeSettingsImport {
    private static final String PIPEZ = "pipez";
    private static final String RETRIEVER_MOD = "pipezretriever";
    private static final String RETRIEVING_SIDES = "PipezRetriever_RetrievingSides";

    /**
     * Master switch for PipezRetriever sides. {@code false} on NeoForge 26.x until PipezRetriever ships for this
     * loader; import still works for Pipez extract upgrades, but never maps faces to {@code RETRIEVING}.
     */
    public static final boolean RETRIEVER_SUPPORT_ENABLED = false;

    private PipezPipeSettingsImport() {}

    public static boolean isPipezPipe(BlockState state) {
        if (!ModList.get().isLoaded(PIPEZ)) {
            return false;
        }
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && PIPEZ.equals(id.getNamespace()) && id.getPath().endsWith("_pipe");
    }

    /**
     * Shift+right-click with a Settings Copier on a Pipez pipe: copy whole-pipe settings into the copier.
     *
     * @return true if the interaction was handled (success or failure feedback)
     */
    public static boolean tryCopyWholePipe(
            Level level, BlockPos pos, Player player, ItemStack copierStack) {
        if (!(copierStack.getItem() instanceof SettingsCopierItem)) {
            return false;
        }
        if (!isPipezPipe(level.getBlockState(pos))) {
            return false;
        }
        if (level.isClientSide()) {
            return true;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            SettingsCopierFeedback.notifyPasteFailed(player);
            return true;
        }
        HolderLookup.Provider registries = level.registryAccess();
        CompoundTag whole = captureWholePipe(be, registries);
        if (whole == null) {
            SettingsCopierFeedback.notifyPasteFailed(player);
            return true;
        }
        DuctFaceSettingsSnapshot.writeToCopier(copierStack, whole);
        SettingsCopierFeedback.notifyPipezImported(player);
        return true;
    }

    @Nullable
    private static CompoundTag captureWholePipe(BlockEntity be, HolderLookup.Provider registries) {
        Set<Direction> extracting = readExtractingSides(be, registries);
        Set<Direction> retrieving = readRetrievingSides(be, registries);
        CompoundTag faces = new CompoundTag();
        EnumSet<DuctTransportKind> kinds =
                EnumSet.of(
                        DuctTransportKind.ITEM,
                        DuctTransportKind.FLUID,
                        DuctTransportKind.GAS,
                        DuctTransportKind.ENERGY);
        for (Direction face : Direction.values()) {
            DuctFaceLanes lanes = DuctFaceLanes.createDetached(face, () -> {});
            boolean extract = extracting.contains(face);
            boolean retrieve = extract && retrieving.contains(face);
            if (!extract) {
                lanes.nodeMode = NodeMode.NONE;
            } else if (retrieve) {
                lanes.nodeMode = NodeMode.RETRIEVING;
            } else {
                lanes.nodeMode = NodeMode.EXTRACTION;
            }
            ItemStack upgrade = readUpgradeItem(be, face);
            applyUpgradeToLanes(lanes, upgrade, retrieve, registries);
            CompoundTag faceSnap = DuctFaceSettingsSnapshot.captureFromLanes(lanes, registries, kinds);
            faces.put(Integer.toString(face.ordinal()), faceSnap);
        }
        return DuctFaceSettingsSnapshot.buildWholeRoot(faces);
    }

    private static void applyUpgradeToLanes(
            DuctFaceLanes lanes, ItemStack upgrade, boolean retrieving, HolderLookup.Provider registries) {
        if (upgrade.isEmpty()
                || !ExternalUpgradeFilterImportSource.isCompanionModLoaded()
                || !ExternalUpgradeStackComponents.isUpgradeItem(upgrade)) {
            return;
        }
        boolean sharedMetaApplied = false;
        for (FilterImportChannel channel : FilterImportChannel.values()) {
            CompoundTag data =
                    ExternalUpgradeStackComponents.readChannelData(upgrade, channel, registries);
            if (data == null) {
                continue;
            }
            if (!sharedMetaApplied) {
                lanes.redstoneMode = readRedstoneMode(data);
                RoutingMode routing = mapDistribution(data);
                if (retrieving) {
                    lanes.item.routingModeRetriever = routing;
                    lanes.fluid.routingModeRetriever = routing;
                    lanes.gas.routingModeRetriever = routing;
                    lanes.energyRoutingModeRetriever = routing;
                } else {
                    lanes.item.routingMode = routing;
                    lanes.fluid.routingMode = routing;
                    lanes.gas.routingMode = routing;
                    lanes.energyRoutingMode = routing;
                    lanes.item.routingModeExtractor = routing;
                    lanes.fluid.routingModeExtractor = routing;
                    lanes.gas.routingModeExtractor = routing;
                    lanes.energyRoutingModeExtractor = routing;
                }
                sharedMetaApplied = true;
            }
            DuctFaceNode node =
                    switch (channel) {
                        case ITEM -> lanes.item;
                        case FLUID -> lanes.fluid;
                        case GAS -> lanes.gas;
                    };
            DuctFaceNode.FilterBank bank =
                    retrieving ? DuctFaceNode.FilterBank.RETRIEVER : DuctFaceNode.FilterBank.EXTRACTOR;
            applyFiltersToBank(node, bank, data);
            migrateStrayFilterBank(node, bank);
        }
        CompoundTag energyData = readNamedChannel(upgrade, "energy", registries);
        if (energyData != null) {
            if (!sharedMetaApplied) {
                lanes.redstoneMode = readRedstoneMode(energyData);
                sharedMetaApplied = true;
            }
            RoutingMode routing = mapDistribution(energyData);
            if (retrieving) {
                lanes.energyRoutingModeRetriever = routing;
            } else {
                lanes.energyRoutingMode = routing;
                lanes.energyRoutingModeExtractor = routing;
            }
        }
    }

    @Nullable
    private static CompoundTag readNamedChannel(
            ItemStack stack, String suffix, HolderLookup.Provider registries) {
        // Reuse ITEM reader path by temporarily using a synthetic suffix via components encode.
        var encoded =
                net.minecraft.world.item.ItemStack.CODEC
                        .encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack)
                        .result()
                        .orElse(null);
        if (!(encoded instanceof CompoundTag root) || !root.contains("components")) {
            return null;
        }
        CompoundTag components = root.getCompoundOrEmpty("components");
        String key = PIPEZ + ":" + suffix;
        return components.contains(key) ? components.getCompoundOrEmpty(key) : null;
    }

    private static void applyFiltersToBank(
            DuctFaceNode node, DuctFaceNode.FilterBank bank, CompoundTag channelData) {
        FilterImportPreview preview = ExternalFilterLineConverter.convert(channelData);
        boolean whitelist = isWhitelist(channelData);
        List<String> allow =
                new ArrayList<>(whitelist ? preview.mainLines() : preview.invertedLines());
        List<String> deny =
                new ArrayList<>(whitelist ? preview.invertedLines() : preview.mainLines());
        List<Integer> allowConcat =
                new ArrayList<>(
                        whitelist ? preview.mainConcatChannels() : preview.invertedConcatChannels());
        List<Integer> denyConcat =
                new ArrayList<>(
                        whitelist ? preview.invertedConcatChannels() : preview.mainConcatChannels());
        List<DuctDirectionalEndpoint> allowRemote =
                new ArrayList<>(
                        whitelist ? preview.mainRemoteNodes() : preview.invertedRemoteNodes());
        List<DuctDirectionalEndpoint> denyRemote =
                new ArrayList<>(
                        whitelist ? preview.invertedRemoteNodes() : preview.mainRemoteNodes());
        FilterConcatChannel.syncToLineSize(allowConcat, allow.size());
        FilterConcatChannel.syncToLineSize(denyConcat, deny.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemote, allow.size());
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemote, deny.size());

        List<String> allowTarget = node.bankAllowFilters(bank);
        List<String> denyTarget = node.bankDenyFilters(bank);
        allowTarget.clear();
        allowTarget.addAll(allow);
        denyTarget.clear();
        denyTarget.addAll(deny);

        List<Integer> allowCaps = node.bankAllowCaps(bank);
        allowCaps.clear();
        for (int i = 0; i < allow.size(); i++) {
            allowCaps.add(0);
        }
        List<Integer> allowConcatTarget = node.bankAllowConcatChannels(bank);
        allowConcatTarget.clear();
        allowConcatTarget.addAll(allowConcat);
        List<Integer> denyConcatTarget = node.bankDenyConcatChannels(bank);
        denyConcatTarget.clear();
        denyConcatTarget.addAll(denyConcat);

        List<DuctDirectionalEndpoint> allowRemoteTarget = node.bankAllowRemoteNodes(bank);
        allowRemoteTarget.clear();
        allowRemoteTarget.addAll(allowRemote);
        List<DuctDirectionalEndpoint> denyRemoteTarget = node.bankDenyRemoteNodes(bank);
        denyRemoteTarget.clear();
        denyRemoteTarget.addAll(denyRemote);

        node.setBankDenyOverridesAllow(bank, true);
    }

    /**
     * If lines landed on FILTER while the face is extract/retrieve, move them to the operational bank so
     * paste/GUI see them. Always clear FILTER afterward (Pipez has no filtering-insertion role).
     */
    private static void migrateStrayFilterBank(DuctFaceNode node, DuctFaceNode.FilterBank bank) {
        if (bank == DuctFaceNode.FilterBank.FILTER) {
            return;
        }
        List<String> filterAllow = node.bankAllowFilters(DuctFaceNode.FilterBank.FILTER);
        List<String> filterDeny = node.bankDenyFilters(DuctFaceNode.FilterBank.FILTER);
        boolean filterHas =
                filterAllow.stream().anyMatch(s -> s != null && !s.isBlank())
                        || filterDeny.stream().anyMatch(s -> s != null && !s.isBlank());
        List<String> targetAllow = node.bankAllowFilters(bank);
        List<String> targetDeny = node.bankDenyFilters(bank);
        boolean targetEmpty =
                targetAllow.stream().allMatch(s -> s == null || s.isBlank())
                        && targetDeny.stream().allMatch(s -> s == null || s.isBlank());
        if (filterHas && targetEmpty) {
            targetAllow.clear();
            targetAllow.addAll(filterAllow);
            targetDeny.clear();
            targetDeny.addAll(filterDeny);
            List<Integer> targetCaps = node.bankAllowCaps(bank);
            targetCaps.clear();
            targetCaps.addAll(node.bankAllowCaps(DuctFaceNode.FilterBank.FILTER));
            List<Integer> targetAllowConcat = node.bankAllowConcatChannels(bank);
            targetAllowConcat.clear();
            targetAllowConcat.addAll(node.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER));
            List<Integer> targetDenyConcat = node.bankDenyConcatChannels(bank);
            targetDenyConcat.clear();
            targetDenyConcat.addAll(node.bankDenyConcatChannels(DuctFaceNode.FilterBank.FILTER));
            List<DuctDirectionalEndpoint> targetAllowRemote = node.bankAllowRemoteNodes(bank);
            targetAllowRemote.clear();
            targetAllowRemote.addAll(node.bankAllowRemoteNodes(DuctFaceNode.FilterBank.FILTER));
            List<DuctDirectionalEndpoint> targetDenyRemote = node.bankDenyRemoteNodes(bank);
            targetDenyRemote.clear();
            targetDenyRemote.addAll(node.bankDenyRemoteNodes(DuctFaceNode.FilterBank.FILTER));
            node.setBankDenyOverridesAllow(
                    bank, node.bankDenyOverridesAllow(DuctFaceNode.FilterBank.FILTER));
        }
        clearFilterBank(node);
    }

    private static void clearFilterBank(DuctFaceNode node) {
        node.bankAllowFilters(DuctFaceNode.FilterBank.FILTER).clear();
        node.bankDenyFilters(DuctFaceNode.FilterBank.FILTER).clear();
        node.bankAllowCaps(DuctFaceNode.FilterBank.FILTER).clear();
        List<Integer> keep = node.filterBankKeepCaps();
        if (keep != null) {
            keep.clear();
        }
        node.bankAllowConcatChannels(DuctFaceNode.FilterBank.FILTER).clear();
        node.bankDenyConcatChannels(DuctFaceNode.FilterBank.FILTER).clear();
        node.bankAllowRemoteNodes(DuctFaceNode.FilterBank.FILTER).clear();
        node.bankDenyRemoteNodes(DuctFaceNode.FilterBank.FILTER).clear();
        node.setBankDenyOverridesAllow(DuctFaceNode.FilterBank.FILTER, true);
    }

    private static boolean isWhitelist(CompoundTag channelData) {
        if (channelData.contains("filter_mode")) {
            return channelData.getByteOr("filter_mode", (byte) 0) == 0;
        }
        if (channelData.contains("filter_mode")) {
            return channelData.getIntOr("filter_mode", 0) == 0;
        }
        return true;
    }

    private static int readRedstoneMode(CompoundTag channelData) {
        int mode = 0;
        if (channelData.contains("redstone_mode")) {
            mode = channelData.getByteOr("redstone_mode", (byte) 0) & 0xFF;
        } else if (channelData.contains("redstone_mode")) {
            mode = channelData.getIntOr("redstone_mode", 0);
        }
        return Math.max(0, Math.min(3, mode));
    }

    private static RoutingMode mapDistribution(CompoundTag channelData) {
        // Pipez UpgradeTileEntity.Distribution: NEAREST, FURTHEST, ROUND_ROBIN, RANDOM
        int dist = 0;
        if (channelData.contains("distribution")) {
            dist = channelData.getByteOr("distribution", (byte) 0) & 0xFF;
        } else if (channelData.contains("distribution")) {
            dist = channelData.getIntOr("distribution", 0);
        }
        return switch (dist) {
            case 1 -> RoutingMode.FARTHEST_FIRST;
            case 2 -> RoutingMode.ROUND_ROBIN;
            case 3 -> RoutingMode.RANDOM;
            default -> RoutingMode.NEAREST_FIRST;
        };
    }

    private static Set<Direction> readExtractingSides(BlockEntity be, HolderLookup.Provider registries) {
        Set<Direction> out = new HashSet<>();
        try {
            var method = findMethod(be.getClass(), "isExtracting", Direction.class);
            if (method != null) {
                for (Direction d : Direction.values()) {
                    Object r = method.invoke(be, d);
                    if (r instanceof Boolean b && b) {
                        out.add(d);
                    }
                }
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        CompoundTag tag = saveBeTag(be, registries);
        if (tag == null) {
            return Set.of();
        }
        return sidesFromByteList(tag.getListOrEmpty("ExtractingSides"));
    }

    private static Set<Direction> readRetrievingSides(BlockEntity be, HolderLookup.Provider registries) {
        if (!RETRIEVER_SUPPORT_ENABLED || !ModList.get().isLoaded(RETRIEVER_MOD)) {
            return Set.of();
        }
        try {
            var method = findMethod(be.getClass(), "isRetrieving", Direction.class);
            if (method != null) {
                Set<Direction> out = new HashSet<>();
                for (Direction d : Direction.values()) {
                    Object r = method.invoke(be, d);
                    if (r instanceof Boolean b && b) {
                        out.add(d);
                    }
                }
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        CompoundTag tag = saveBeTag(be, registries);
        if (tag == null || !tag.contains(RETRIEVING_SIDES)) {
            return Set.of();
        }
        return sidesFromByteList(tag.getListOrEmpty(RETRIEVING_SIDES));
    }

    private static Set<Direction> sidesFromByteList(ListTag list) {
        Set<Direction> out = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Tag entry = list.get(i);
            int v;
            if (entry instanceof net.minecraft.nbt.ByteTag byteTag) {
                v = byteTag.value() & 0xFF;
            } else if (entry instanceof net.minecraft.nbt.IntTag intTag) {
                v = intTag.value() & 0xFF;
            } else {
                continue;
            }
            if (v >= 0 && v < Direction.values().length) {
                out.add(Direction.from3DDataValue(v));
            }
        }
        return out;
    }

    private static ItemStack readUpgradeItem(BlockEntity be, Direction face) {
        try {
            var method = findMethod(be.getClass(), "getUpgradeItem", Direction.class);
            if (method != null) {
                Object r = method.invoke(be, face);
                if (r instanceof ItemStack stack) {
                    return stack;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static CompoundTag saveBeTag(BlockEntity be, HolderLookup.Provider registries) {
        try {
            var m = findMethod(BlockEntity.class, "saveWithFullMetadata", HolderLookup.Provider.class);
            if (m == null) {
                m = findMethod(be.getClass(), "saveWithFullMetadata", HolderLookup.Provider.class);
            }
            if (m != null) {
                Object r = m.invoke(be, registries);
                if (r instanceof CompoundTag tag) {
                    return tag;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @Nullable
    private static java.lang.reflect.Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                var m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
