package net.unfamily.another_dynamics.duct.settings;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctFaceLanes;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.FilterConcatChannel;
import net.unfamily.another_dynamics.duct.DuctFeatureKeys;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFeaturePolicy;
import net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic;
import net.unfamily.another_dynamics.duct.DuctFluidTransportSpec;
import net.unfamily.another_dynamics.duct.DuctGasTransportSpec;
import net.unfamily.another_dynamics.duct.DuctItemTransportSpec;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.inventory.FilterSyncDebugLog;
import net.unfamily.another_dynamics.network.ModNetwork;

/**
 * In-memory universal duct face for settings copier virtual editor (no world block).
 */
public final class SettingsCopierVirtualSession {
    public static final String UNIVERSAL_LOGICAL_ID = "another_dynamics:universal_duct";
    public static final int UNLIMITED_FILTER_LINES = 512;
    public static final Direction VIRTUAL_FACE = Direction.NORTH;

    private final ServerPlayer player;
    private final InteractionHand hand;
    private final SettingsCopierStoreKind storeKind;
    private final DuctFaceLanes lanes;
    private final SimpleContainerData menuData;
    private final EnumSet<DuctTransportKind> enabledKinds;
    private final List<DuctTransportKind> orderedKinds;

    /**
     * WHOLE mode: editable face inside the multi-face root. Virtual editor edits one face; persist writes it back
     * into {@link #wholeRoot} without collapsing to FILTER/ALL.
     */
    private @org.jetbrains.annotations.Nullable CompoundTag wholeRoot;
    private Direction wholeEditFace = VIRTUAL_FACE;

    private int menuTransportKindIndex;
    private int menuUiLayer;

    /** Last filter list edited (for FILTER mode persist). */
    private DuctTransportKind lastFilterLane = DuctTransportKind.ITEM;
    private DuctFaceNode.FilterBank lastFilterBank = DuctFaceNode.FilterBank.EXTRACTOR;
    private boolean lastFilterAllowList = true;

    /** Portable FILTER copier: explicit list material kind (default {@link FilterListMaterialKind#NONE}). */
    private FilterListMaterialKind filterListMaterialKind = FilterListMaterialKind.NONE;

    public SettingsCopierVirtualSession(
            ServerPlayer player, InteractionHand hand, ItemStack copier, SimpleContainerData menuData) {
        this.menuData = menuData;
        this.player = player;
        this.hand = hand;
        this.storeKind = SettingsCopierStoreKind.getMode(copier);
        this.lanes = DuctFaceLanes.createDetached(VIRTUAL_FACE, this::refreshMenuData);
        this.enabledKinds =
                DuctDefinitionRegistry.getByLogicalId(UNIVERSAL_LOGICAL_ID)
                        .map(DuctDefinition::enabledTransportKinds)
                        .orElse(EnumSet.of(DuctTransportKind.ITEM));
        this.orderedKinds = DuctDefinition.orderedMenuTransportKinds(
                DuctDefinitionRegistry.getByLogicalId(UNIVERSAL_LOGICAL_ID));
        lanes.ensureTransportEnabledMask(enabledKinds);
        loadFromCopier(copier);
        prepareMenuOpenState();
        refreshMenuData();
        ModNetwork.sendFilterSyncForVirtual(player, this);
    }

    public InteractionHand hand() {
        return hand;
    }

    public SettingsCopierStoreKind storeKind() {
        return storeKind;
    }

    public DuctFaceLanes lanes() {
        return lanes;
    }

    public SimpleContainerData menuData() {
        return menuData;
    }

    public Direction accessFace() {
        return VIRTUAL_FACE;
    }

    public boolean isUnlimitedFilters() {
        return true;
    }

    public List<DuctTransportKind> orderedMenuTransportKinds() {
        return orderedKinds;
    }

    public DuctTransportKind menuActiveTransportKind() {
        if (orderedKinds.size() == 1) {
            return orderedKinds.getFirst();
        }
        return orderedKinds.get(Math.floorMod(menuTransportKindIndex, orderedKinds.size()));
    }

    public DuctFaceNode activeMenuFaceNode() {
        return faceNodeForTransportKind(menuActiveTransportKind());
    }

    public DuctFaceNode faceNodeForTransportKind(DuctTransportKind kind) {
        return switch (kind) {
            case FLUID -> lanes.fluid;
            case GAS -> lanes.gas;
            case ENERGY, HEAT, ITEM -> lanes.item;
        };
    }

    public boolean isMenuHubLayer() {
        return menuUiLayer == 0;
    }

  public boolean usesEnergyOrHeatPassThroughRouting() {
        DuctTransportKind k = menuActiveTransportKind();
        if (k != DuctTransportKind.ENERGY && k != DuctTransportKind.HEAT) {
            return false;
        }
        NodeMode nm = lanes.nodeMode;
        return nm == NodeMode.NONE || nm == NodeMode.FILTERING_INSERTION;
    }

    private Optional<DuctDefinition> definition() {
        return DuctDefinitionRegistry.getByLogicalId(UNIVERSAL_LOGICAL_ID);
    }

    private void prepareMenuOpenState() {
        menuTransportKindIndex = 0;
        // FILTER editor: land on detail + filter lists, not multi-transport hub.
        if (storeKind == SettingsCopierStoreKind.FILTER) {
            menuUiLayer = 1;
        } else {
            menuUiLayer = orderedKinds.size() > 1 ? 0 : 1;
        }
    }

    private void loadFromCopier(ItemStack copier) {
        var registries = player.registryAccess();
        if (storeKind == SettingsCopierStoreKind.ALL) {
            DuctFaceSettingsSnapshot.readFromCopier(copier).ifPresent(tag -> {
                if (DuctFaceSettingsSnapshot.isAllPayload(tag)) {
                    int fmt = tag.getIntOr(DuctFaceSettingsSnapshot.KEY_FMT, 0);
                    if (fmt == DuctFaceSettingsSnapshot.FORMAT_VERSION) {
                        lanes.loadCopierSettings(registries, tag, enabledKinds);
                    } else {
                        DuctFaceSettingsSnapshot.applyLegacyToLanes(lanes, tag, registries, enabledKinds);
                    }
                }
            });
            lanes.ensureTransportEnabledMask(enabledKinds);
            return;
        }
        if (storeKind == SettingsCopierStoreKind.WHOLE) {
            DuctFaceSettingsSnapshot.readFromCopier(copier).ifPresent(tag -> {
                if (!DuctFaceSettingsSnapshot.isWholePayload(tag)) {
                    return;
                }
                wholeRoot = tag.copy();
                CompoundTag faces = wholeRoot.getCompoundOrEmpty(DuctFaceSettingsSnapshot.KEY_FACES);
                wholeEditFace = pickWholeEditFace(faces);
                CompoundTag faceData = faces.getCompoundOrEmpty(Integer.toString(wholeEditFace.ordinal()));
                if (!faceData.isEmpty()) {
                    if (!DuctFaceSettingsSnapshot.isAllPayload(faceData)) {
                        faceData = faceData.copy();
                        faceData.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.ALL.toTag());
                        if (!faceData.contains(DuctFaceSettingsSnapshot.KEY_FMT)) {
                            faceData.putInt(
                                    DuctFaceSettingsSnapshot.KEY_FMT, DuctFaceSettingsSnapshot.FORMAT_VERSION);
                        }
                    }
                    int fmt = faceData.getIntOr(DuctFaceSettingsSnapshot.KEY_FMT, 0);
                    if (fmt == DuctFaceSettingsSnapshot.FORMAT_VERSION) {
                        lanes.loadCopierSettings(registries, faceData, enabledKinds);
                    } else {
                        DuctFaceSettingsSnapshot.applyLegacyToLanes(lanes, faceData, registries, enabledKinds);
                    }
                }
            });
            lanes.ensureTransportEnabledMask(enabledKinds);
            return;
        }
        DuctFaceSettingsSnapshot.readFromCopier(copier).ifPresent(tag -> {
            if (DuctFilterListSnapshot.isFilterPayload(tag)) {
                filterListMaterialKind = DuctFilterListSnapshot.getMaterialKind(tag);
                DuctTransportKind lane =
                        filterListMaterialKind != FilterListMaterialKind.NONE
                                ? filterListMaterialKind.toTransportKind()
                                : DuctTransportKind.ITEM;
                DuctFilterListSnapshot.applyToList(
                        faceNodeForTransportKind(lane), DuctFaceNode.FilterBank.EXTRACTOR, true, tag);
                lastFilterLane = lane;
                lastFilterBank = DuctFaceNode.FilterBank.EXTRACTOR;
                lastFilterAllowList = true;
                if (filterListMaterialKind != FilterListMaterialKind.NONE) {
                    int idx = orderedKinds.indexOf(lane);
                    if (idx >= 0) {
                        menuTransportKindIndex = idx;
                    }
                }
            }
        });
        lanes.nodeMode = NodeMode.EXTRACTION;
        if (storeKind != SettingsCopierStoreKind.FILTER || filterListMaterialKind == FilterListMaterialKind.NONE) {
            menuTransportKindIndex = Math.max(0, orderedKinds.indexOf(DuctTransportKind.ITEM));
        }
        lanes.ensureTransportEnabledMask(enabledKinds);
    }

    /** Prefer a configured (non-{@link NodeMode#NONE}) face; otherwise {@link #VIRTUAL_FACE}. */
    private static Direction pickWholeEditFace(CompoundTag faces) {
        Direction fallback = VIRTUAL_FACE;
        for (Direction dir : Direction.values()) {
            String key = Integer.toString(dir.ordinal());
            if (!faces.contains(key)) {
                continue;
            }
            CompoundTag face = faces.getCompoundOrEmpty(key);
            if (face.contains("Shared")) {
                byte mode = face.getCompoundOrEmpty("Shared").getByteOr("NodeMode", (byte) 0);
                if (mode != (byte) NodeMode.NONE.ordinal()) {
                    return dir;
                }
            }
            if (dir == VIRTUAL_FACE) {
                fallback = dir;
            }
        }
        return fallback;
    }

    public void persistToCopier(ItemStack copier) {
        var registries = player.registryAccess();
        if (storeKind == SettingsCopierStoreKind.ALL) {
            DuctFaceSettingsSnapshot.writeToCopier(
                    copier, DuctFaceSettingsSnapshot.captureFromLanes(lanes, registries, enabledKinds));
            return;
        }
        if (storeKind == SettingsCopierStoreKind.WHOLE) {
            CompoundTag faceSnap = DuctFaceSettingsSnapshot.captureFromLanes(lanes, registries, enabledKinds);
            if (wholeRoot == null) {
                CompoundTag faces = new CompoundTag();
                faces.put(Integer.toString(wholeEditFace.ordinal()), faceSnap);
                DuctFaceSettingsSnapshot.writeToCopier(copier, DuctFaceSettingsSnapshot.buildWholeRoot(faces));
                return;
            }
            CompoundTag root = wholeRoot.copy();
            CompoundTag faces =
                    root.contains(DuctFaceSettingsSnapshot.KEY_FACES)
                            ? root.getCompoundOrEmpty(DuctFaceSettingsSnapshot.KEY_FACES).copy()
                            : new CompoundTag();
            faces.put(Integer.toString(wholeEditFace.ordinal()), faceSnap);
            root.put(DuctFaceSettingsSnapshot.KEY_FACES, faces);
            root.putInt(DuctFaceSettingsSnapshot.KEY_FMT, DuctFaceSettingsSnapshot.FORMAT_VERSION);
            root.putByte(SettingsCopierStoreKind.TAG, SettingsCopierStoreKind.WHOLE.toTag());
            DuctFaceSettingsSnapshot.writeToCopier(copier, root);
            wholeRoot = root;
            return;
        }
        FilterListMaterialKind kind = filterListMaterialKind;
        DuctFaceNode node =
                kind != FilterListMaterialKind.NONE
                        ? faceNodeForTransportKind(kind.toTransportKind())
                        : lanes.item;
        // FILTER copier stores a single portable allow list; load always targets EXTRACTOR allow.
        CompoundTag snap =
                DuctFilterListSnapshot.captureList(
                        node, DuctFaceNode.FilterBank.EXTRACTOR, true, kind);
        DuctFilterListSnapshot.trimTrailingEmptyLines(snap);
        DuctFaceSettingsSnapshot.writeToCopier(copier, snap);
    }

    public void refreshMenuData() {
        DuctFaceNode n = activeMenuFaceNode();
        DuctTransportKind menuKind = menuActiveTransportKind();
        menuData.set(DuctMenuSync.NODE_MODE, lanes.nodeMode.ordinal());
        if (menuKind == DuctTransportKind.ENERGY) {
            menuData.set(DuctMenuSync.ROUTING_MODE, lanes.energyRoutingMode.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, lanes.energyRoutingModeExtractor.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, lanes.energyRoutingModeRetriever.ordinal());
        } else if (menuKind == DuctTransportKind.HEAT) {
            menuData.set(DuctMenuSync.ROUTING_MODE, lanes.heatRoutingMode.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, lanes.heatRoutingModeExtractor.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, lanes.heatRoutingModeRetriever.ordinal());
        } else {
            menuData.set(DuctMenuSync.ROUTING_MODE, n.routingMode.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_EXTRACTOR, n.routingModeExtractor.ordinal());
            menuData.set(DuctMenuSync.ROUTING_MODE_RETRIEVER, n.routingModeRetriever.ordinal());
        }
        menuData.set(DuctMenuSync.PRIORITY, n.insertionPriority & 0xFFFF);
        menuData.set(DuctMenuSync.PRIORITY_HI, (n.insertionPriority >> 16) & 0xFFFF);
        menuData.set(DuctMenuSync.AMOUNT_FIELD, n.extractBatch);
        menuData.set(DuctMenuSync.EXTRACT_BATCH_CAP, Integer.MAX_VALUE / 2);
        menuData.set(DuctMenuSync.EXTRACT_SEQUENTIAL_STACK, n.extractSequentialStack);
        menuData.set(DuctMenuSync.EXTRACT_SEQUENTIAL_STACK_CAP, DuctItemTransportSpec.HARD_SEQUENTIAL_STACK_CAP);
        menuData.set(DuctMenuSync.CHANNEL, n.channelLetter);
        menuData.set(DuctMenuSync.REDSTONE_MODE, lanes.redstoneMode);
        menuData.set(DuctMenuSync.ELIGIBILITY_MODE, n.eligibilityMode.ordinal());
        int denyOverSync =
                switch (lanes.nodeMode) {
                    case FILTERING_INSERTION -> n.denyOverridesAllowFilter ? 1 : 0;
                    case EXTRACTION -> n.denyOverridesAllowExtractor ? 1 : 0;
                    case RETRIEVING -> n.denyOverridesAllowRetriever ? 1 : 0;
                    default -> n.denyOverridesAllow ? 1 : 0;
                };
        menuData.set(DuctMenuSync.DENY_OVERRIDES_ALLOW, denyOverSync);
        int flags = 0;
        if (lanes.nodeMode.usesRouting()
                || (menuKind == DuctTransportKind.ENERGY || menuKind == DuctTransportKind.HEAT)
                        && (lanes.nodeMode == NodeMode.NONE || lanes.nodeMode == NodeMode.FILTERING_INSERTION)) {
            flags |= DuctMenuSync.FLAG_ROUTING_ACTIVE;
        }
        if (lanes.nodeMode.usesItemFilterConfig()) {
            flags |= DuctMenuSync.FLAG_FILTERS_ACTIVE;
        }
        menuData.set(DuctMenuSync.FLAGS, flags);
        menuData.set(DuctMenuSync.ACCESS_FACE, VIRTUAL_FACE.ordinal());
        menuData.set(DuctMenuSync.ACTIVE_TRANSPORT_KIND, menuKind.ordinal());
        menuData.set(DuctMenuSync.TRANSPORT_KIND_COUNT, orderedKinds.size());
        menuData.set(DuctMenuSync.MENU_VIEW_LAYER, menuUiLayer);
        menuData.set(DuctMenuSync.TRANSPORT_ENABLED_MASK, lanes.transportEnabledMask);
        menuData.set(DuctMenuSync.ENERGY_BUF_LIMIT_EXTRACT, lanes.energyExtractBufferLimitFe);
        menuData.set(DuctMenuSync.ENERGY_BUF_LIMIT_INSERT, lanes.energyInsertBufferLimitFe);
        menuData.set(DuctMenuSync.ENERGY_BUF_INPUT_STORED, lanes.energyInputBufferFe);
        menuData.set(DuctMenuSync.ENERGY_BUF_OUTPUT_STORED, lanes.energyOutputBufferFe);
        int inCap = lanes.energyExtractBufferLimitFe > 0
                ? Math.max(1, lanes.energyExtractBufferLimitFe)
                : Math.max(1, (int) Math.min(energySpec().clampedExtract(), Integer.MAX_VALUE));
        int outCap = lanes.energyInsertBufferLimitFe > 0
                ? Math.max(1, lanes.energyInsertBufferLimitFe)
                : inCap;
        menuData.set(DuctMenuSync.ENERGY_BUF_INPUT_CAP, inCap);
        menuData.set(DuctMenuSync.ENERGY_BUF_OUTPUT_CAP, outCap);
        menuData.set(DuctMenuSync.SELF_FEED, n.selfFeed ? 1 : 0);
    }

    private DuctItemTransportSpec itemSpec() {
        return definition().map(DuctDefinition::itemTransportOrFallback).orElseGet(DuctItemTransportSpec::fallback);
    }

    private DuctFluidTransportSpec fluidSpec() {
        return definition().map(DuctDefinition::fluidTransportOrFallback).orElseGet(DuctFluidTransportSpec::fallback);
    }

    private DuctGasTransportSpec gasSpec() {
        return definition().map(DuctDefinition::gasTransportOrFallback).orElseGet(DuctGasTransportSpec::fallback);
    }

    private net.unfamily.another_dynamics.duct.DuctEnergyTransportSpec energySpec() {
        return definition().map(DuctDefinition::energyTransportOrFallback).orElseGet(
                net.unfamily.another_dynamics.duct.DuctEnergyTransportSpec::fallback);
    }

    public boolean handleMenuButton(int buttonId) {
        if (storeKind == SettingsCopierStoreKind.FILTER) {
            return false;
        }
        if (buttonId >= DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE
                && buttonId
                        < DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE + DuctTransportKind.values().length) {
            DuctTransportKind k =
                    DuctTransportKind.values()[buttonId - DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE];
            return setMenuTransportKindFromPicker(k);
        }
        if (buttonId == DuctBlockEntity.MENU_BUTTON_ENTER_DETAIL) {
            return enterMenuDetail();
        }
        if (buttonId == DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB) {
            return returnMenuToHub();
        }
        if (buttonId >= DuctBlockEntity.MENU_BUTTON_TRANSPORT_TOGGLE_BASE
                && buttonId
                        < DuctBlockEntity.MENU_BUTTON_TRANSPORT_TOGGLE_BASE + DuctTransportKind.values().length) {
            DuctTransportKind k =
                    DuctTransportKind.values()[buttonId - DuctBlockEntity.MENU_BUTTON_TRANSPORT_TOGGLE_BASE];
            if (!enabledKinds.contains(k)) {
                return false;
            }
            lanes.toggleTransportKind(k, enabledKinds);
            refreshMenuData();
            return true;
        }
        DuctFaceNode node = activeMenuFaceNode();
        boolean changed =
                switch (buttonId) {
                    case 0 -> cycleNodeMode(true);
                    case 10 -> cycleNodeMode(false);
                    case 1 -> {
                        DuctTransportKind mk1 = menuActiveTransportKind();
                        if (mk1 == DuctTransportKind.ENERGY || mk1 == DuctTransportKind.HEAT) {
                            yield stepRouting(node, 1);
                        }
                        if (usesEnergyOrHeatPassThroughRouting()) {
                            yield cycleEligibility(node, 1);
                        }
                        if (!lanes.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield stepRouting(node, 1);
                    }
                    case 2 -> {
                        lanes.redstoneMode = (lanes.redstoneMode + 1) % 4;
                        yield true;
                    }
                    case 11 -> {
                        DuctTransportKind mk11 = menuActiveTransportKind();
                        if (mk11 == DuctTransportKind.ENERGY || mk11 == DuctTransportKind.HEAT) {
                            yield stepRouting(node, -1);
                        }
                        if (usesEnergyOrHeatPassThroughRouting()) {
                            yield cycleEligibility(node, -1);
                        }
                        if (!lanes.nodeMode.usesRouting()) {
                            yield false;
                        }
                        yield stepRouting(node, -1);
                    }
                    case DuctBlockEntity.MENU_BUTTON_ROUTING_EXTRACTOR_FORWARD -> stepRoutingExtractor(node, 1);
                    case DuctBlockEntity.MENU_BUTTON_ROUTING_EXTRACTOR_BACK -> stepRoutingExtractor(node, -1);
                    case DuctBlockEntity.MENU_BUTTON_ROUTING_RETRIEVER_FORWARD -> stepRoutingRetriever(node, 1);
                    case DuctBlockEntity.MENU_BUTTON_ROUTING_RETRIEVER_BACK -> stepRoutingRetriever(node, -1);
                    case 12 -> {
                        lanes.redstoneMode = Math.floorMod(lanes.redstoneMode - 1, 4);
                        yield true;
                    }
                    case 4 -> {
                        if (!DuctFeaturePolicy.isUsable(definition().orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, false)) {
                            yield false;
                        }
                        node.channelLetter = node.channelLetter >= 26 ? 1 : node.channelLetter + 1;
                        yield true;
                    }
                    case 5 -> {
                        if (!DuctFeaturePolicy.isUsable(definition().orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, false)) {
                            yield false;
                        }
                        node.channelLetter = node.channelLetter <= 1 ? 26 : node.channelLetter - 1;
                        yield true;
                    }
                    case 13 -> {
                        if (!DuctFeaturePolicy.isUsable(definition().orElse(null), DuctFeatureKeys.SPECIAL_CHANNEL, false)) {
                            yield false;
                        }
                        node.channelLetter = 1;
                        yield true;
                    }
                    default -> false;
                };
        if (changed) {
            refreshMenuData();
            ModNetwork.sendFilterSyncForVirtual(player, this);
        }
        return changed;
    }

    private boolean setMenuTransportKindFromPicker(DuctTransportKind kind) {
        int idx = orderedKinds.indexOf(kind);
        if (idx < 0 || !lanes.isTransportKindEnabled(kind, enabledKinds)) {
            return false;
        }
        menuTransportKindIndex = idx;
        menuUiLayer = 1;
        refreshMenuData();
        ModNetwork.sendFilterSyncForVirtual(player, this);
        return true;
    }

    private boolean enterMenuDetail() {
        if ((storeKind != SettingsCopierStoreKind.ALL && storeKind != SettingsCopierStoreKind.WHOLE)
                || orderedKinds.size() <= 1
                || menuUiLayer != 0) {
            return false;
        }
        menuUiLayer = 1;
        refreshMenuData();
        ModNetwork.sendFilterSyncForVirtual(player, this);
        return true;
    }

    private boolean returnMenuToHub() {
        if (storeKind == SettingsCopierStoreKind.FILTER || orderedKinds.size() <= 1) {
            return false;
        }
        menuUiLayer = 0;
        refreshMenuData();
        return true;
    }

    private boolean cycleNodeMode(boolean forward) {
        NodeMode[] order = {
            NodeMode.NONE,
            NodeMode.EXTRACTION,
            NodeMode.FILTERING_INSERTION,
            NodeMode.RETRIEVING,
            NodeMode.EXTRACTION_FILTERING,
            NodeMode.RETRIEVING_EXTRACTION
        };
        Optional<DuctDefinition> def = definition();
        int idx = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == lanes.nodeMode) {
                idx = i;
                break;
            }
        }
        for (int off = 1; off <= order.length; off++) {
            int ni = forward ? (idx + off) % order.length : Math.floorMod(idx - off, order.length);
            NodeMode cand = order[ni];
            if (DuctFeaturePolicy.isModeUsable(def.orElse(null), cand, false)) {
                if (cand != lanes.nodeMode) {
                    lanes.nodeMode = cand;
                    refreshMenuData();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private static boolean cycleEligibility(DuctFaceNode node, int delta) {
        DuctFaceNode.EligibilityMode cur = node.eligibilityMode;
        DuctFaceNode.EligibilityMode nxt =
                switch (cur) {
                    case BOTH ->
                            delta > 0
                                    ? DuctFaceNode.EligibilityMode.INSERT_ONLY
                                    : DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;
                    case INSERT_ONLY ->
                            delta > 0
                                    ? DuctFaceNode.EligibilityMode.RETRIEVE_ONLY
                                    : DuctFaceNode.EligibilityMode.BOTH;
                    case RETRIEVE_ONLY ->
                            delta > 0
                                    ? DuctFaceNode.EligibilityMode.BOTH
                                    : DuctFaceNode.EligibilityMode.INSERT_ONLY;
                };
        if (nxt == cur) {
            return false;
        }
        node.eligibilityMode = nxt;
        return true;
    }

    private boolean stepRouting(DuctFaceNode node, int delta) {
        if (!lanes.nodeMode.usesRouting() && !usesEnergyOrHeatPassThroughRouting()) {
            return false;
        }
        DuctTransportKind menuKind = menuActiveTransportKind();
        Optional<DuctDefinition> def = definition();
        RoutingMode[] v = RoutingMode.values();
        if (menuKind == DuctTransportKind.ENERGY || menuKind == DuctTransportKind.HEAT) {
            return stepEnergyOrHeatRouting(delta, menuKind == DuctTransportKind.ENERGY);
        }
        return switch (lanes.nodeMode) {
            case EXTRACTION_FILTERING -> {
                RoutingMode cur = node.routingModeExtractor;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def);
                if (nxt != cur) {
                    node.routingModeExtractor = nxt;
                    yield true;
                }
                yield false;
            }
            case RETRIEVING_EXTRACTION -> false;
            case RETRIEVING -> {
                RoutingMode cur = node.routingModeRetriever;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def);
                if (nxt != cur) {
                    node.routingModeRetriever = nxt;
                    yield true;
                }
                yield false;
            }
            default -> {
                RoutingMode cur = node.routingMode;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def);
                if (nxt != cur) {
                    node.routingMode = nxt;
                    yield true;
                }
                yield false;
            }
        };
    }

    private boolean stepEnergyOrHeatRouting(int delta, boolean energy) {
        Optional<DuctDefinition> def = definition();
        RoutingMode[] v = RoutingMode.values();
        return switch (lanes.nodeMode) {
            case EXTRACTION_FILTERING -> {
                RoutingMode cur =
                        energy ? lanes.energyRoutingModeExtractor : lanes.heatRoutingModeExtractor;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def);
                if (nxt != cur) {
                    if (energy) {
                        lanes.energyRoutingModeExtractor = nxt;
                    } else {
                        lanes.heatRoutingModeExtractor = nxt;
                    }
                    yield true;
                }
                yield false;
            }
            case RETRIEVING_EXTRACTION -> false;
            case RETRIEVING -> {
                RoutingMode cur =
                        energy ? lanes.energyRoutingModeRetriever : lanes.heatRoutingModeRetriever;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def);
                if (nxt != cur) {
                    if (energy) {
                        lanes.energyRoutingModeRetriever = nxt;
                    } else {
                        lanes.heatRoutingModeRetriever = nxt;
                    }
                    yield true;
                }
                yield false;
            }
            default -> {
                RoutingMode cur = energy ? lanes.energyRoutingMode : lanes.heatRoutingMode;
                RoutingMode nxt = nextUsableRouting(cur, v, delta, def);
                if (nxt != cur) {
                    if (energy) {
                        lanes.energyRoutingMode = nxt;
                    } else {
                        lanes.heatRoutingMode = nxt;
                    }
                    yield true;
                }
                yield false;
            }
        };
    }

    private boolean stepRoutingExtractor(DuctFaceNode node, int delta) {
        RoutingMode[] v = RoutingMode.values();
        RoutingMode cur = node.routingModeExtractor;
        RoutingMode nxt = nextUsableRouting(cur, v, delta, definition());
        if (nxt != cur) {
            node.routingModeExtractor = nxt;
            return true;
        }
        return false;
    }

    private boolean stepRoutingRetriever(DuctFaceNode node, int delta) {
        RoutingMode[] v = RoutingMode.values();
        RoutingMode cur = node.routingModeRetriever;
        RoutingMode nxt = nextUsableRouting(cur, v, delta, definition());
        if (nxt != cur) {
            node.routingModeRetriever = nxt;
            return true;
        }
        return false;
    }

    private static RoutingMode nextUsableRouting(
            RoutingMode current, RoutingMode[] v, int delta, Optional<DuctDefinition> def) {
        int idx = current.ordinal();
        int dir = delta > 0 ? 1 : -1;
        for (int step = 1; step <= v.length; step++) {
            int ni = Math.floorMod(idx + dir * step, v.length);
            if (DuctFeaturePolicy.isRoutingUsable(def.orElse(null), v[ni], false)) {
                return v[ni];
            }
        }
        return current;
    }

    public void applyClientFieldUpdate(
            int transportKindOrdinal,
            int insertionPriority,
            int extractBatch,
            int extractSequentialStack,
            int eligibilityModeOrdinal) {
        DuctTransportKind[] vals = DuctTransportKind.values();
        DuctTransportKind kind = vals[Mth.clamp(transportKindOrdinal, 0, vals.length - 1)];
        DuctFaceNode node = faceNodeForTransportKind(kind);
        node.insertionPriority = insertionPriority;
        node.extractBatch = Math.max(0, extractBatch);
        node.extractSequentialStack = Math.max(0, extractSequentialStack);
        node.eligibilityMode = DuctFaceNode.EligibilityMode.fromOrdinal(eligibilityModeOrdinal);
        refreshMenuData();
    }

    public FilterListMaterialKind filterListMaterialKind() {
        return filterListMaterialKind;
    }

    /**
     * Cycle portable filter material kind; keeps allow lines, moves them to the active transport lane when kind changes.
     */
    public void setFilterListMaterialKind(FilterListMaterialKind kind) {
        if (storeKind != SettingsCopierStoreKind.FILTER) {
            return;
        }
        if (kind == FilterListMaterialKind.GAS && !FilterListMaterialKind.gasFiltersAvailable()) {
            return;
        }
        FilterListMaterialKind prev = filterListMaterialKind;
        if (prev == kind) {
            return;
        }
        filterListMaterialKind = kind;
        if (kind != FilterListMaterialKind.NONE) {
            DuctTransportKind tk = kind.toTransportKind();
            if (prev != FilterListMaterialKind.NONE && prev != kind) {
                copyFilterAllowListBetweenLanes(prev.toTransportKind(), tk);
            } else if (prev == FilterListMaterialKind.NONE && tk != DuctTransportKind.ITEM) {
                copyFilterAllowListBetweenLanes(DuctTransportKind.ITEM, tk);
            }
            int idx = orderedKinds.indexOf(tk);
            if (idx >= 0) {
                menuTransportKindIndex = idx;
            }
            lastFilterLane = tk;
        }
        refreshMenuData();
        DuctTransportKind syncLane =
                kind != FilterListMaterialKind.NONE ? kind.toTransportKind() : menuActiveTransportKind();
        ModNetwork.sendFilterSyncForVirtual(player, this, syncLane);
    }

    private void copyFilterAllowListBetweenLanes(DuctTransportKind from, DuctTransportKind to) {
        if (from == to) {
            return;
        }
        DuctFaceNode src = faceNodeForTransportKind(from);
        DuctFaceNode dst = faceNodeForTransportKind(to);
        DuctFaceNode.FilterBank bank = lastFilterBank;
        List<String> allow = new ArrayList<>(src.bankAllowFilters(bank));
        dst.bankAllowFilters(bank).clear();
        dst.bankAllowFilters(bank).addAll(allow);
        List<Integer> caps = new ArrayList<>(src.bankAllowCaps(bank));
        dst.bankAllowCaps(bank).clear();
        for (Integer v : caps) {
            dst.bankAllowCaps(bank).add(Math.max(0, v != null ? v : 0));
        }
        syncCapSize(dst.bankAllowCaps(bank), allow.size());
        if (bank == DuctFaceNode.FilterBank.FILTER) {
            List<Integer> keep = new ArrayList<>(src.filterBankKeepCaps());
            dst.filterBankKeepCaps().clear();
            for (Integer v : keep) {
                dst.filterBankKeepCaps().add(Math.max(0, v != null ? v : 0));
            }
            syncCapSize(dst.filterBankKeepCaps(), allow.size());
        } else if (bank == DuctFaceNode.FilterBank.EXTRACTOR) {
            List<Integer> lim = new ArrayList<>(src.extractorBankLimitCaps());
            dst.extractorBankLimitCaps().clear();
            for (Integer v : lim) {
                dst.extractorBankLimitCaps().add(Math.max(0, v != null ? v : 0));
            }
            syncCapSize(dst.extractorBankLimitCaps(), allow.size());
        }
    }

    public void applyFilterConfig(
            DuctTransportKind laneKind,
            DuctFaceNode.FilterBank bank,
            List<String> allowIn,
            List<String> denyIn,
            List<Integer> allowCapsIn,
            List<Integer> allowCaps2In,
            List<Integer> allowConcatIn,
            List<Integer> denyConcatIn,
            List<@org.jetbrains.annotations.Nullable DuctDirectionalEndpoint> allowRemoteIn,
            List<@org.jetbrains.annotations.Nullable DuctDirectionalEndpoint> denyRemoteIn,
            List<Boolean> allowRemoteIgnoreChannelIn,
            List<Boolean> denyRemoteIgnoreChannelIn,
            List<Boolean> allowRemoteAnyFaceIn,
            List<Boolean> denyRemoteAnyFaceIn,
            boolean denyOverridesAllow) {
        DuctTransportKind payloadLane = laneKind;
        if (storeKind == SettingsCopierStoreKind.FILTER
                && filterListMaterialKind != FilterListMaterialKind.NONE) {
            laneKind = filterListMaterialKind.toTransportKind();
        }
        if (storeKind == SettingsCopierStoreKind.FILTER && bank != DuctFaceNode.FilterBank.EXTRACTOR) {
            return;
        }
        lastFilterLane = laneKind;
        lastFilterBank = bank;
        lastFilterAllowList = true;
        if (laneKind == DuctTransportKind.ENERGY || laneKind == DuctTransportKind.HEAT) {
            return;
        }
        DuctFaceNode node = faceNodeForTransportKind(laneKind);
        if (!lanes.nodeMode.usesItemFilterConfig()) {
            return;
        }
        List<String> a = node.bankAllowFilters(bank);
        List<String> d = node.bankDenyFilters(bank);
        a.clear();
        d.clear();
        if (allowIn != null) {
            a.addAll(allowIn);
        }
        if (denyIn != null) {
            d.addAll(denyIn);
        }
        if (allowCapsIn != null) {
            List<Integer> caps = node.bankAllowCaps(bank);
            caps.clear();
            for (Integer v : allowCapsIn) {
                caps.add(Math.max(0, v != null ? v : 0));
            }
            syncCapSize(caps, a.size());
        }
        if (bank == DuctFaceNode.FilterBank.FILTER && allowCaps2In != null) {
            List<Integer> keep = node.filterBankKeepCaps();
            keep.clear();
            for (Integer v : allowCaps2In) {
                keep.add(Math.max(0, v != null ? v : 0));
            }
            syncCapSize(keep, a.size());
        } else if (bank == DuctFaceNode.FilterBank.EXTRACTOR && allowCaps2In != null) {
            List<Integer> lim = node.extractorBankLimitCaps();
            lim.clear();
            for (Integer v : allowCaps2In) {
                lim.add(Math.max(0, v != null ? v : 0));
            }
            syncCapSize(lim, a.size());
        }
        if (allowConcatIn != null) {
            List<Integer> allowCh = node.bankAllowConcatChannels(bank);
            allowCh.clear();
            for (Integer v : allowConcatIn) {
                allowCh.add(v != null ? Math.clamp(v, 0, FilterConcatChannel.MAX_LETTER) : 0);
            }
            FilterConcatChannel.syncToLineSize(allowCh, a.size());
        }
        if (denyConcatIn != null) {
            List<Integer> denyCh = node.bankDenyConcatChannels(bank);
            denyCh.clear();
            for (Integer v : denyConcatIn) {
                denyCh.add(v != null ? Math.clamp(v, 0, FilterConcatChannel.MAX_LETTER) : 0);
            }
            FilterConcatChannel.syncToLineSize(denyCh, d.size());
        }
        if (allowRemoteIn != null) {
            List<DuctDirectionalEndpoint> allowRemote = node.bankAllowRemoteNodes(bank);
            allowRemote.clear();
            allowRemote.addAll(allowRemoteIn);
            DuctFilterRemoteNodeLogic.syncToLineSize(allowRemote, a.size());
        }
        if (denyRemoteIn != null) {
            List<DuctDirectionalEndpoint> denyRemote = node.bankDenyRemoteNodes(bank);
            denyRemote.clear();
            denyRemote.addAll(denyRemoteIn);
            DuctFilterRemoteNodeLogic.syncToLineSize(denyRemote, d.size());
        }
        if (allowRemoteIgnoreChannelIn != null) {
            List<Boolean> allowIgnore = node.bankAllowRemoteIgnoreChannel(bank);
            allowIgnore.clear();
            for (int i = 0; i < allowRemoteIgnoreChannelIn.size(); i++) {
                Boolean v = allowRemoteIgnoreChannelIn.get(i);
                allowIgnore.add(v != null && v);
            }
            DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowIgnore, a.size());
        }
        if (denyRemoteIgnoreChannelIn != null) {
            List<Boolean> denyIgnore = node.bankDenyRemoteIgnoreChannel(bank);
            denyIgnore.clear();
            for (int i = 0; i < denyRemoteIgnoreChannelIn.size(); i++) {
                Boolean v = denyRemoteIgnoreChannelIn.get(i);
                denyIgnore.add(v != null && v);
            }
            DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyIgnore, d.size());
        }
        if (allowRemoteAnyFaceIn != null) {
            List<Boolean> allowAnyFace = node.bankAllowRemoteAnyFace(bank);
            allowAnyFace.clear();
            for (Boolean v : allowRemoteAnyFaceIn) {
                allowAnyFace.add(v != null && v);
            }
            DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowAnyFace, a.size());
        }
        if (denyRemoteAnyFaceIn != null) {
            List<Boolean> denyAnyFace = node.bankDenyRemoteAnyFace(bank);
            denyAnyFace.clear();
            for (Boolean v : denyRemoteAnyFaceIn) {
                denyAnyFace.add(v != null && v);
            }
            DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyAnyFace, d.size());
        }
        node.setBankDenyOverridesAllow(bank, denyOverridesAllow);
        refreshMenuData();
        FilterSyncDebugLog.serverApply(
                BlockPos.ZERO,
                accessFace(),
                laneKind.ordinal(),
                bank.ordinal(),
                allowIn,
                denyIn,
                a,
                d,
                "SettingsCopierVirtualSession.applyFilterConfig material="
                        + filterListMaterialKind
                        + " payloadTk="
                        + FilterSyncDebugLog.transportKindName(payloadLane.ordinal())
                        + " resolvedTk="
                        + FilterSyncDebugLog.transportKindName(laneKind.ordinal()));
        ModNetwork.sendFilterSyncForVirtual(player, this, laneKind);
    }

    private static void syncCapSize(List<Integer> caps, int size) {
        while (caps.size() < size) {
            caps.add(0);
        }
        while (caps.size() > size) {
            caps.remove(caps.size() - 1);
        }
    }

    public void toggleListLogic(DuctTransportKind laneKind, DuctFaceNode.FilterBank bank) {
        if (!lanes.nodeMode.usesItemFilterConfig()) {
            return;
        }
        DuctFaceNode node = faceNodeForTransportKind(laneKind);
        if (!DuctFeaturePolicy.isUsable(
                definition().orElse(null), DuctFeatureKeys.listPrecedenceKey(lanes.nodeMode, bank), false)) {
            return;
        }
        node.setBankDenyOverridesAllow(bank, !node.bankDenyOverridesAllow(bank));
        refreshMenuData();
        ModNetwork.sendFilterSyncForVirtual(player, this);
    }

    public void applyEnergyBufferLimits(int extractLimitFe, int insertLimitFe) {
        lanes.energyExtractBufferLimitFe = Math.max(0, extractLimitFe);
        lanes.energyInsertBufferLimitFe = Math.max(0, insertLimitFe);
        refreshMenuData();
    }

    public void setSelfFeed(boolean enabled) {
        NodeMode shared = lanes.nodeMode;
        if (shared != NodeMode.EXTRACTION_FILTERING && shared != NodeMode.RETRIEVING_EXTRACTION) {
            return;
        }
        activeMenuFaceNode().selfFeed = enabled;
        refreshMenuData();
    }

    public void noteFilterListContext(DuctTransportKind lane, DuctFaceNode.FilterBank bank, boolean allowList) {
        lastFilterLane = lane;
        lastFilterBank = bank;
        lastFilterAllowList = allowList;
    }

    public ItemStack getCopierStack() {
        return player.getItemInHand(hand);
    }

    public void applyRename(String name) {
        ItemStack stack = getCopierStack();
        if (stack.isEmpty()) {
            return;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_NAME);
        } else {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(trimmed));
        }
        player.setItemInHand(hand, stack);
    }
}
