package net.unfamily.another_dynamics.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.network.DuctGuiFeedbackPayload;
import net.unfamily.another_dynamics.network.SettingsCopierActionPayload;
import net.unfamily.another_dynamics.duct.DestinationToolEndpointReader;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFeatureKeys;
import net.unfamily.another_dynamics.duct.DuctFeaturePolicy;
import net.unfamily.another_dynamics.duct.DuctFilterLineReorder;
import net.unfamily.another_dynamics.duct.DuctFilterSpecialKeys;
import net.unfamily.another_dynamics.duct.FilterLineTextUtil;
import net.unfamily.another_dynamics.duct.DuctGuiLayout;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.client.SettingsCopierClient;
import net.unfamily.another_dynamics.duct.DuctRoutingUiSync;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.DuctFilterListSnapshot;
import net.unfamily.another_dynamics.duct.settings.FilterListMaterialKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierVirtualSession;
import net.unfamily.another_dynamics.integration.jei.ghost.IAnDynamicsGhostTarget;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.inventory.FilterSyncDebugLog;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.inventory.UniversalDuctMenu;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModAttachments;
import net.unfamily.another_dynamics.registry.ModDataComponents;
import net.unfamily.another_dynamics.registry.ModItems;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Universal duct face GUI: modules, center controls, filter sub-screens, right column, inventory.
 * World ducts use {@link DuctNodeScreen}; settings copier uses {@link SettingsCopierScreen}.
 */
public abstract class AbstractUniversalDuctScreen<M extends AbstractContainerMenu & UniversalDuctMenu>
    extends AbstractContainerScreen<M>
    implements IAnDynamicsGhostTarget
{
    /** World duct node shows copy/paste column; virtual copier editor does not. */
    protected boolean showsSettingsCopierColumn() {
        return true;
    }

    /**
     * Channel letter widget (left column). World ducts: same visibility rules as copy/paste when that column is shown.
     * {@link SettingsCopierScreen} overrides so channel appears on virtual detail sub-GUIs without the copier column.
     */
    protected boolean showsChannelLetterControl(
            boolean hubLayer, boolean filterList, boolean advancedFiltering, boolean bufferLimits) {
        return showsSettingsCopierColumn()
                && (!hubLayer || filterList || advancedFiltering || bufferLimits);
    }

    /** Universal multi-transport hub main: channel slot visible but empty (concat None style), not editable. */
    private boolean isHubChannelLocked() {
        return menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1
                && menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0
                && subView == SubView.MAIN;
    }

    /** Virtual editor X/ESC returns to settings copier hub instead of closing. */
    protected boolean useSettingsCopierHubNavigation() {
        return false;
    }

    /** When non-null, replaces duct node title on virtual copier {@link SubView#MAIN} only. */
    @org.jetbrains.annotations.Nullable
    protected Component settingsCopierVirtualMainTitle() {
        return null;
    }

    /** Hub layer: widgets only, no player inventory slots. */
    protected boolean deliverMouseToWidgets(double mouseX, double mouseY, int button) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child.isMouseOver(mouseX, mouseY)
                    && child.mouseClicked(
                            new MouseButtonEvent(
                                    mouseX, mouseY, new MouseButtonInfo(button, 0)),
                            false)) {
                return true;
            }
        }
        return false;
    }

    /** FILTER-mode virtual editor: allow-list only (not universal duct node MAIN). */
    protected boolean isSettingsCopierFilterListEditor() {
        if (!menu.isSettingsCopierVirtualEditor() || minecraft == null || minecraft.player == null) {
            return false;
        }
        if (menu instanceof SettingsCopierMenu copier) {
            return copier.isVirtualLayer()
                    && SettingsCopierStoreKind.getMode(copier.copierStack(minecraft.player))
                            == SettingsCopierStoreKind.FILTER;
        }
        return false;
    }

    /** Virtual ALL editor on the multi-transport hub (layer 0). */
    private boolean isSettingsCopierAllVirtualHub() {
        if (!menu.isSettingsCopierVirtualEditor() || minecraft == null || minecraft.player == null) {
            return false;
        }
        if (!(menu instanceof SettingsCopierMenu copier) || !copier.isVirtualLayer()) {
            return false;
        }
        if (copier.storeKind(minecraft.player) != SettingsCopierStoreKind.ALL) {
            return false;
        }
        return menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1
                && menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0;
    }

    /** Virtual ALL with universal transport hub (layer 0 = root screen). */
    private boolean isSettingsCopierAllVirtualMultiTransport() {
        if (!useSettingsCopierHubNavigation() || minecraft == null || minecraft.player == null) {
            return false;
        }
        if (!(menu instanceof SettingsCopierMenu copier) || !copier.isVirtualLayer()) {
            return false;
        }
        return copier.storeKind(minecraft.player) == SettingsCopierStoreKind.ALL
                && menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1;
    }

    /** Detail layer (1) on virtual ALL multi-transport editor. */
    protected boolean isSettingsCopierVirtualDetailLayer() {
        return isSettingsCopierAllVirtualMultiTransport()
                && menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) != 0;
    }

    /** Sub-menus → universal virtual transport hub (not detail {@link SubView#MAIN}). */
    protected void returnToVirtualTransportHub() {
        exitEditMode(false);
        subView = SubView.MAIN;
        hybridPanel = HybridPanel.NONE;
        flushPendingFilterEditsBeforeClose();
        if (shouldPushFiltersOnClose()) {
            reorderAndPushAllFilterBanks();
        }
        handleMenuButton(DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB);
        applySubViewVisibility();
        layoutMainChromeRowsForHubOrDetail();
        layoutHubTransportGrid();
        layoutHubBackButton();
    }

    private void returnToSettingsCopierHubFromVirtual() {
        flushPendingFilterEditsBeforeClose();
        if (shouldPushFiltersOnClose()) {
            reorderAndPushAllFilterBanks();
        }
        playClickSound();
        ModNetwork.sendSettingsCopierReturnToHub();
    }

    /** Virtual editor X/Esc: one UI level up (Back may skip further to transport hub). */
    private void popSettingsCopierVirtualOneLevel() {
        if (subView == SubView.ADVANCED_FILTERING) {
            if (popAdvancedFilterSubLevel()) {
                return;
            }
            closeAdvancedFiltering();
            return;
        }
        if (subView == SubView.BUFFER_LIMITS) {
            closeEnergyBufferSubview();
            return;
        }
        if (subView == SubView.HOW_TO_USE) {
            closeFilterSubview();
            return;
        }
        if (inEditMode()) {
            exitEditMode(true);
            return;
        }
        if (subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS) {
            if (isSettingsCopierFilterListEditor()) {
                returnToSettingsCopierHubFromVirtual();
            } else {
                closeFilterSubview();
            }
            return;
        }
        if (hybridPanel != HybridPanel.NONE) {
            hybridPanel = HybridPanel.NONE;
            applySubViewVisibility();
            return;
        }
        if (isSettingsCopierVirtualDetailLayer()) {
            returnToVirtualTransportHub();
        } else if (useSettingsCopierHubNavigation()) {
            returnToSettingsCopierHubFromVirtual();
        } else {
            onClose();
        }
    }

    /** FILTER mode virtual editor: open allow-list on first init. */
    protected void bootstrapSettingsCopierInitialSubview() {
        if (!isSettingsCopierFilterListEditor()) {
            return;
        }
        if (subView != SubView.MAIN) {
            return;
        }
        activeFilterBank = DuctFaceNode.FilterBank.EXTRACTOR;
        openFilterSubview(SubView.ALLOW_FILTERS);
    }

    private enum HybridPanel {
        NONE,
        EXTRACTOR,
        FILTERING,
        RETRIEVER,
    }

    private enum SubView {
        MAIN,
        DENY_FILTERS,
        ALLOW_FILTERS,
        ADVANCED_FILTERING,
        HOW_TO_USE,
        BUFFER_LIMITS,
    }

    private static final Identifier TEXTURE =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/background/node.png"
        );
    private static final Identifier VALID_KEYS_TEXTURE =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/background/valid_keys.png"
        );
    private static final Identifier REDSTONE_GUI =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/redstone_gui.png"
        );
    private static final Identifier SINGLE_SLOT =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/single_slot.png"
        );
    private static final Identifier SINGLE_SLOT_REMOTE_NODE =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/single_slot_remote_node.png"
        );
    private static final Identifier MODULE_SLOT =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/single_slot_module.png"
        );
    private static final Identifier ENTRY_ROW_TEXTURE =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/entry_duct.png"
        );
    private static final Identifier SCROLLBAR_TEXTURE =
        Identifier.fromNamespaceAndPath(
            AnotherDynamicsMod.MOD_ID,
            "textures/gui/scrollbar.png"
        );

    private static final int TEXTURE_WIDTH = DuctGuiLayout.NODE_TEXTURE_WIDTH;
    private static final int TEXTURE_HEIGHT = DuctGuiLayout.NODE_TEXTURE_HEIGHT;

    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_Y = 5;
    private static final int CLOSE_BUTTON_X =
        TEXTURE_WIDTH - CLOSE_BUTTON_SIZE - 5;

    private static final int REDSTONE_BUTTON_SIZE = 16;
    private static final int ADVANCED_REMOTE_NODE_SLOT_SIZE = 18;
    /** Vertical gap between the centered slot and coordinate line below. */
    private static final int ADVANCED_REMOTE_NODE_COORD_GAP_Y = 2;
    /**
     * GUI-local Y of the centered remote-node slot in advanced filtering. Kept above
     * {@link #ADVANCED_CAP_NUMERIC_ROW_GUI_Y} cap labels (Limit / Keep in Storage).
     */
    private static final int ADVANCED_REMOTE_NODE_ROW_GUI_Y = 20;
    private static final int ADVANCED_REMOTE_NODE_COORD_MAX_WIDTH = 220;
    /** Gap between the coordinate line (or coord editor) and the Respect/Ignore Channel button. */
    private static final int ADVANCED_REMOTE_NODE_LINE_GAP_Y = 4;
    /** Minimum clearance between the channel button bottom and the cap label row. */
    private static final int ADVANCED_REMOTE_IGNORE_CHANNEL_CAP_LABEL_GAP = 2;
    private static final int ADVANCED_REMOTE_NODE_SLOT_GUI_X =
            (TEXTURE_WIDTH - ADVANCED_REMOTE_NODE_SLOT_SIZE) / 2;
    private static final int REMOTE_NODE_COORD_EDIT_W = 44;
    private static final int REMOTE_NODE_AXIS_LABEL_GAP = 1;

    private record AdvancedRemoteNodeRowLayout(
            int slotScreenX,
            int slotScreenY,
            int coordTextScreenY) {}

    private static final int CENTER_X = 38;

    private static final int ROW1_Y = 32;
    private static final int ROW2_Y = 50;
    private static final int ROW3_Y = 70;
    private static final int BTN_H = 14;

    private static final int ROW_BTN_W = 76;
    private static final int ROW_GAP = 4;
    /** Middle column of the 3-button chrome row (hub: empty slot / copier Back; detail: opaque). */
    private static final int CHROME_COL_MID_X = CENTER_X + ROW_BTN_W + ROW_GAP;
    /** Universal hub: enabled/disabled row above each transport-kind picker. */
    private static final int HUB_TRANSPORT_TOGGLE_ROW_Y = ROW2_Y;
    private static final int HUB_TRANSPORT_PICKER_ROW_Y = HUB_TRANSPORT_TOGGLE_ROW_Y + BTN_H + ROW_GAP;
    private static final int HUB_BACK_ROW_Y = HUB_TRANSPORT_PICKER_ROW_Y + BTN_H + ROW_GAP;
    private static final int ADJACENT_BTN_GAP = 2;

    /**
     * Priority / quantity block: centered, two rows (numeric then 0/A/[M]/Close-without-saving). Slightly above player inventory.
     */
    /** +/- steps: priority uses 1 / Ctrl+Alt 10 / Shift 100. Quantity and allow-cap Limit/Keep: 1 / Ctrl+Alt 8 / Shift 64. */
    private static final int PRIORITY_STEP_PLAIN = 1;
    private static final int PRIORITY_STEP_CTRL_OR_ALT = 10;
    private static final int PRIORITY_STEP_SHIFT = 100;
    private static final int BATCH_STEP_PLAIN = 1;
    private static final int BATCH_STEP_CTRL_OR_ALT = 8;
    private static final int BATCH_STEP_SHIFT = 64;
    private static final int AMOUNT_STEPPER_W = 14;
    private static final int AMOUNT_INNER_GAP = 2;
    private static final int AMOUNT_EDIT_W = 56;
    private static final int AMOUNT_ACTION_BTN = 12;
    private static final int AMOUNT_BTN_GAP = 2;
    private static final int AMOUNT_ROWS_GAP = 4;
    private static final int AMOUNT_ROW_Y = 118;
    private static final int AMOUNT_LABEL_ABOVE_GAP = 3;
    /** Energy buffer subview: title row, fill %, then limit editor (replaces priority/quantity block). */
    private static final int ENERGY_BUF_NAME_Y = 88;
    private static final int ENERGY_BUF_FILL_Y = 98;
    private static final int ENERGY_BUF_LIMIT_ROW_Y = 108;
    /**
     * GUI-local Y of the Limit/Keep numeric row in {@link SubView#ADVANCED_FILTERING}. Lowered so the cap block sits above
     * the filter entry editor (same anchor as non-advanced: {@link #FIRST_FILTER_ROW_Y} + visible rows) without overlapping it.
     */
    private static final int ADVANCED_CAP_NUMERIC_ROW_GUI_Y = 88;
    /** ARGB; filter entry {@link EditBox} uses the same white in allow edit and advanced (always readable on field). */
    private static final int FILTER_ENTRY_EDIT_TEXT_COLOR = 0xFFFFFFFF;
    /** Width of "Advanced filtering" button beside the filter edit line (allow list edit mode). */
    private static final int ADVANCED_FILTER_BUTTON_WIDTH = 64;

    /** Gui-local X of {@link #routingPriorityBox} left edge (for label centering). Set in {@link #init}. */
    private int amountEditBoxGuiLeft;
    /** Gui-local X of {@link #advCapEditBox} left edge (for Limit/Keep label centering). Set in {@link #layoutAdvancedCapBlock}. */
    private int advCapEditBoxGuiLeft;
    /** Gui-local X of {@link #advCap2EditBox} left edge (for Keep label centering in FILTER retrieve-only/both). */
    private int advCap2EditBoxGuiLeft;
    private int energyBufExtractEditGuiLeft;
    private int energyBufInsertEditGuiLeft;

    private static final int CHANNEL_WIDGET_W = 18;
    private static final int CHANNEL_WIDGET_H = 18;
    private static final int COPIER_ACTION_BTN_H = DuctNodeMenu.COPIER_ACTION_BUTTON_H;

    /** Multi-transport hub: wrap kind pickers so at most this many fit per row within {@link #TEXTURE_WIDTH}. */
    private static final int HUB_TRANSPORT_KIND_COLUMNS = 3;

    /** Visible filter rows; scroll when there are more slots. */
    private static final int VISIBLE_FILTER_ENTRIES = 4;

    /** Filter row background width; must match {@code entry_duct.png} (220px wide). */
    private static final int ENTRY_WIDTH = 220;
    private static final int ENTRY_HEIGHT = 24;
    private static final int ENTRY_X = CENTER_X;
    /** Horizontal inset for edit text field inside the entry column (narrows EditBox slightly). */
    private static final int EDIT_MODE_TEXT_INSET_X = 10;
    /** Filter entries start directly under the title row (Back / Valid keys are below the list). */
    private static final int FIRST_FILTER_ROW_Y = ROW1_Y;

    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_HEIGHT = 34;
    private static final int HANDLE_SIZE = 8;
    /** Gap between last entry row and Back / Valid keys row (tight vs {@link DuctNodeMenu#PLAYER_SLOTS_Y}). */
    private static final int FILTER_NAV_GAP = 4;
    /**
     * Gap between last filter row (full {@link #ENTRY_HEIGHT}) and edit-mode block.
     * Keep {@code FIRST_FILTER_ROW_Y + VISIBLE_FILTER_ENTRIES * ENTRY_HEIGHT + gap + edit block height} below {@link DuctNodeMenu#PLAYER_SLOTS_Y}.
     */
    private static final int EDIT_MODE_GAP_BELOW_LIST = 4;

    private static final int SCROLLBAR_X_REL = ENTRY_X + ENTRY_WIDTH + 4;
    private static final int BUTTON_UP_Y_REL = FIRST_FILTER_ROW_Y;
    private static final int SCROLLBAR_Y_REL = BUTTON_UP_Y_REL + HANDLE_SIZE;
    private static final int BUTTON_DOWN_Y_REL =
        SCROLLBAR_Y_REL + SCROLLBAR_HEIGHT;

    /** Left inset for Valid keys body text (inside panel border). */
    private static final int HELP_TEXT_X = 14;
    /** "How to use" macro chained examples: first line fits two, the next up to three (then repeat that cap). */
    private static final int MACRO_HELP_FIRST_LINE_EXAMPLES = 2;
    private static final int MACRO_HELP_NEXT_LINE_EXAMPLES = 3;
    private static final int HELP_BACK_BUTTON_X = 8;
    private static final int HELP_BACK_BUTTON_Y = TEXTURE_HEIGHT - 25;

    private ItemIconButton redstoneModeButton;
    private int redstoneModeStub;

    protected Button closeButton;
    private Button nodeModeButton;
    private Button routingModeButton;
    private Button routingMinusButton;
    private Button routingPlusButton;
    private Button amountClearButton;
    private Button amountApplyButton;
    private Button amountMaxButton;
    private Button amountDiscardButton;
    private ChannelLetterButton channelButton;
    private Button settingsCopierSaveButton;
    private Button settingsCopierLoadButton;
    private final List<Button> transportKindPickerButtons = new ArrayList<>();
    private final List<Button> transportToggleButtons = new ArrayList<>();
    private Button hubBackButton;
    /** Settings copier ALL: transport hub → virtual node detail (fills column 3 beside mode). */
    private Button copierVirtualNodeBackButton;
    private EditBox routingPriorityBox;

    private Button denyNavButton;
    private Button listLogicButton;
    private Button allowNavButton;
    private Button selfFeedStub;
    private Button opaqueRenderingButton;
    private Button backButton;
    private Button validKeysButton;
    private Button filterReorderButton;
    private Button oppositeFilterListButton;
    private Button copierFilterTypeButton;

    private SubView subView = SubView.MAIN;
    private SubView filterListBeforeHelp = SubView.DENY_FILTERS;
    private DuctFaceNode.FilterBank activeFilterBank =
        DuctFaceNode.FilterBank.FILTER;
    private HybridPanel hybridPanel = HybridPanel.NONE;
    private int filterScrollOffset;
    /** Tracks {@link DuctMenuSync#MENU_VIEW_LAYER} for copier virtual hub → detail MAIN transition. */
    private int lastSyncedMenuViewLayer = -1;
    /** Tracks {@link DuctMenuSync#ACTIVE_TRANSPORT_KIND} for multi-lane filter mirror flush/sync. */
    private int lastActiveTransportKind = -1;
    private boolean isDraggingHandle;
    private int dragStartY;
    private int dragStartScrollOffset;

    /** Main GUI priority/batch: unsent local edits until Apply (or Enter); discard reverts draft (tooltip Cancel). */
    private boolean amountFieldsDirty;
    private boolean syncingAmountBoxFromServer;
    /** Packed {@link #amountBlockLayoutKey}: relayout amount row when mode or hybrid sub-panel changes (M button / steps). */
    private int amountBlockLayoutCache = -1;
    /** Relayout advanced cap block when eligibility/bank changes (Both vs Only affects geometry). */
    private int advCapLayoutCache = -1;
    /** Client-only: previous {@link #syncedExtractBatchCap()} to detect cap drops after module changes. */
    private int lastTrackedExtractBatchCap = -1;

    private static final class ExampleData {

        final String example;
        final int x;
        final int y;
        final int width;

        ExampleData(String example, int x, int y, int width) {
            this.example = example;
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    private final List<ExampleData> exampleDataList = new ArrayList<>();

    private final List<Button> filterEditButtons = new ArrayList<>();
    private final List<Button> filterDeleteButtons = new ArrayList<>();
    private final List<FilterConcatChannelButton> filterConcatButtons = new ArrayList<>();

    // Edit mode (ported from DeepDrawerExtractorScreen)
    private int editModeFilterIndex = -1;
    private String originalFilterValue = "";
    private EditBox editModeTextBox;
    private Button leftArrowButton;
    private Button rightArrowButton;
    private Button editModeClearButton;
    private Button editModeApplyButton;
    private Button editModeCloseButton;
    /** Inserts {@link net.unfamily.another_dynamics.duct.DuctFilterSpecialKeys#ANYTHING_ELSE} into the entry EditBox. */
    private Button editModeAnythingElseButton;
    private Button advancedFilteringOpenButton;
    /** Allow vs deny list that opened {@link SubView#ADVANCED_FILTERING}. */
    private SubView advancedFilterParentSubview = SubView.ALLOW_FILTERS;
    private @Nullable DuctDirectionalEndpoint editModeRemoteNodeDraft;
    private @Nullable DuctDirectionalEndpoint originalRemoteNodeValue;
    private boolean editModeRemoteIgnoreChannelDraft;
    private boolean originalRemoteIgnoreChannelValue;
    private boolean editModeRemoteAnyFaceDraft;
    private boolean originalRemoteAnyFaceValue;
    private int editModeAllowCapValue;
    private int originalAllowCapValue;
    private int editModeAllowCap2Value;
    private int originalAllowCap2Value;
    private Button advCapMinusButton;
    private Button advCapPlusButton;
    private EditBox advCapEditBox;
    private Button advCapInfinityButton;
    private Button advCapApplyButton;
    private Button advCapUndoButton;
    private Button advCap2MinusButton;
    private Button advCap2PlusButton;
    private EditBox advCap2EditBox;
    private Button advCap2InfinityButton;
    private Button advCap2ApplyButton;
    private Button advCap2UndoButton;
    private Button remoteIgnoreChannelButton;
    private RemoteNodeFaceButton remoteNodeFaceButton;
    private Button remoteNodeCoordEditButton;
    private boolean remoteNodeCoordEditOpen;
    private int remoteNodeCoordSnapshotX;
    private int remoteNodeCoordSnapshotY;
    private int remoteNodeCoordSnapshotZ;
    private EditBox remoteNodeCoordXEditBox;
    private EditBox remoteNodeCoordYEditBox;
    private EditBox remoteNodeCoordZEditBox;
    private Button remoteNodeCoordApplyButton;
    private Button remoteNodeCoordUndoButton;
    private int remoteNodeCoordAxisLabelScreenX;
    private boolean advCapEditHadFocus;

    private int energyBufExtractDraft;
    private int energyBufInsertDraft;
    private int energyBufExtractCommitted;
    private int energyBufInsertCommitted;
    private boolean energyBufDirty;
    private boolean syncingEnergyBufFromServer;
    private int energyBufLayoutCache = -1;
    private Button energyBufExtractMinus;
    private Button energyBufExtractPlus;
    private EditBox energyBufExtractBox;
    private Button energyBufExtractAutoButton;
    private Button energyBufExtractApplyButton;
    private Button energyBufExtractUndoButton;
    private Button energyBufInsertMinus;
    private Button energyBufInsertPlus;
    private EditBox energyBufInsertBox;
    private Button energyBufInsertAutoButton;
    private Button energyBufInsertApplyButton;
    private Button energyBufInsertUndoButton;
    private ItemStack ghostSlotItem = ItemStack.EMPTY;
    /** When editing fluid filters, preview still sprite from {@link FluidUtil#getFluidContained} on the ghost item. */
    private FluidStack ghostSlotFluid = FluidStack.EMPTY;

    /** When editing gas (Mekanism chemical) filters: sample from cursor item capability, or {@code null} when empty. */
    @Nullable
    private Object ghostSlotGas = null;

    private List<String> filterVariants = new ArrayList<>();
    private int currentFilterVariantIndex = 0;

    private int editGhostSlotScreenX;
    private int editGhostSlotScreenY;
    private int lastMouseX;
    private int lastMouseY;

    private static final long TRANSIENT_FEEDBACK_MS = 2000L;

    @Nullable
    private Component transientFeedback;
    private long transientFeedbackHideAt;
    private int transientFeedbackColor = 0xFFFFFFFF;

    /**
     * Ghost ingredient consumer for JEI drag-and-drop into the filter calibration slot.
     */
    @Nullable
    private IAnDynamicsGhostTarget.IGhostIngredientConsumer ghostIngredientConsumer;

    protected AbstractUniversalDuctScreen(
        M menu,
        Inventory playerInventory,
        Component title
    ) {
        super(menu, playerInventory, title, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        this.inventoryLabelY = 10_000;
    }

    /**
     * Client: apply duct filter lists from {@link net.unfamily.another_dynamics.network.DuctFilterSyncPayload}. Updates
     * {@link DuctNodeMenu} cache and rebuilds filter entry buttons when this duct GUI is the active screen.
     */
    public static void applyClientFilterSync(
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
        boolean denyOverridesAllow
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!(mc.player.containerMenu instanceof UniversalDuctMenu menu)) {
            return;
        }
        FilterSyncDebugLog.clientPacket(
                "FILTER_SYNC_RX",
                FilterSyncDebugLog.posFace(pos, face)
                        + " tk="
                        + FilterSyncDebugLog.transportKindName(transportKindOrdinal)
                        + " bank="
                        + FilterSyncDebugLog.bankName(filterBankOrdinal)
                        + " allow="
                        + FilterSyncDebugLog.listPreview(allow)
                        + " deny="
                        + FilterSyncDebugLog.listPreview(deny)
                        + " dirty="
                        + menu.clientFiltersDirty(transportKindOrdinal)
                        + " hydrated="
                        + menu.clientFiltersHydrated(transportKindOrdinal));
        menu.receiveFilterSync(
            pos,
            face,
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
            denyOverridesAllow
        );
        if (
            mc.screen instanceof AbstractUniversalDuctScreen<?> screen &&
            screen.getMenu() == menu
        ) {
            if (!menu.clientFiltersDirty(transportKindOrdinal)) {
                screen.menu.ensureClientFilterBufferSizes(
                    screen.useHybridFilterCaps()
                );
            }
            screen.rebuildFilterEntryWidgets();
        }
    }

    @Override
    protected void init() {
        super.init();

        redstoneModeButton =
                new ItemIconButton(
                        this.leftPos + DuctNodeMenu.REDSTONE_GUI_X,
                        this.topPos + DuctNodeMenu.REDSTONE_GUI_Y,
                        REDSTONE_BUTTON_SIZE,
                        b -> handleMenuButton(2),
                        this::redstoneButtonIconStack,
                        () -> redstoneModeStub == 2 ? REDSTONE_GUI : null,
                        () -> handleMenuButton(12),
                        Component.empty());
        addRenderableWidget(redstoneModeButton);

        closeButton = Button.builder(Component.literal("\u2715"), b -> {
            playClickSound();
            handleCloseOrBack();
        })
            .bounds(
                this.leftPos + CLOSE_BUTTON_X,
                this.topPos + CLOSE_BUTTON_Y,
                CLOSE_BUTTON_SIZE,
                CLOSE_BUTTON_SIZE
            )
            .build();
        addRenderableWidget(closeButton);

        denyNavButton = Button.builder(
            Component.translatable("gui.another_dynamics.duct_node.deny_list"),
            b -> {
                playClickSound();
                NodeMode nm = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE)
                );
                if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                    hybridPanel = (nm == NodeMode.EXTRACTION_FILTERING)
                        ? HybridPanel.EXTRACTOR
                        : HybridPanel.RETRIEVER;
                    activeFilterBank = (hybridPanel == HybridPanel.EXTRACTOR)
                        ? DuctFaceNode.FilterBank.EXTRACTOR
                        : DuctFaceNode.FilterBank.RETRIEVER;
                    applySubViewVisibility();
                    return;
                }
                if (transportFilterListsLocked()) {
                    return;
                }
                openFilterSubview(SubView.DENY_FILTERS);
            }
        )
            .bounds(
                this.leftPos + CENTER_X,
                this.topPos + ROW1_Y,
                ROW_BTN_W,
                BTN_H
            )
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.deny_list.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(denyNavButton);

        listLogicButton = Button.builder(
                listLogicButtonMessage(menu.getClientDenyOverridesAllow(activeFilterBank)),
                b -> {
            playClickSound();
            if (subView == SubView.BUFFER_LIMITS) {
                closeEnergyBufferSubview();
                return;
            }
            if (isEnergyOrHeatTransport()
                    && menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND)
                            == DuctTransportKind.ENERGY.ordinal()) {
                openEnergyBufferSubview();
                return;
            }
            NodeMode nm = NodeMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.NODE_MODE)
            );
            if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                boolean on = (menu.getSyncData().get(DuctMenuSync.SELF_FEED) !=
                    0);
                ModNetwork.sendSelfFeedSet(
                    menuSyncedPos(),
                    menuSyncedFace(),
                    !on
                );
                return;
            }
            ModNetwork.sendListLogicToggle(
                menuSyncedPos(),
                menuSyncedFace(),
                menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                activeFilterBank.ordinal()
            );
        })
            .bounds(
                this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                this.topPos + ROW1_Y,
                ROW_BTN_W,
                BTN_H
            )
            .build();
        addRenderableWidget(listLogicButton);

        allowNavButton = Button.builder(
            Component.translatable("gui.another_dynamics.duct_node.allow_list"),
            b -> {
                playClickSound();
                NodeMode nm = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE)
                );
                if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                    hybridPanel = (nm == NodeMode.EXTRACTION_FILTERING)
                        ? HybridPanel.FILTERING
                        : HybridPanel.EXTRACTOR;
                    activeFilterBank = (hybridPanel == HybridPanel.FILTERING)
                        ? DuctFaceNode.FilterBank.FILTER
                        : DuctFaceNode.FilterBank.EXTRACTOR;
                    applySubViewVisibility();
                    return;
                }
                if (transportFilterListsLocked()) {
                    return;
                }
                openFilterSubview(SubView.ALLOW_FILTERS);
            }
        )
            .bounds(
                this.leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                this.topPos + ROW1_Y,
                ROW_BTN_W,
                BTN_H
            )
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_list.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(allowNavButton);

        int r2x = CENTER_X + 2 * (ROW_BTN_W + ROW_GAP);
        routingModeButton = Button.builder(Component.empty(), b ->
            handleMenuButton(1)
        )
            .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, ROW_BTN_W, BTN_H)
            .build();
        addRenderableWidget(routingModeButton);

        routingMinusButton = Button.builder(Component.literal("-"), b ->
            adjustAmountField(-1)
        )
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.minus.tooltip.priority"
                    )
                )
            )
            .build();
        addRenderableWidget(routingMinusButton);
        routingPriorityBox = new EditBox(
            this.font,
            0,
            0,
            AMOUNT_EDIT_W,
            BTN_H,
            Component.empty()
        );
        routingPriorityBox.setMaxLength(11);
        routingPriorityBox.setResponder(s -> {
            if (!syncingAmountBoxFromServer) {
                amountFieldsDirty = true;
            }
        });
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue("0");
        syncingAmountBoxFromServer = false;
        addRenderableWidget(routingPriorityBox);
        routingPlusButton = Button.builder(Component.literal("+"), b ->
            adjustAmountField(1)
        )
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.plus.tooltip.priority"
                    )
                )
            )
            .build();
        addRenderableWidget(routingPlusButton);

        amountClearButton = Button.builder(Component.literal("1"), b ->
            amountQuickSetField()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.set_to_one.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(amountClearButton);
        amountApplyButton = Button.builder(Component.literal("A"), b ->
            amountApplyField()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.apply"
                    )
                )
            )
            .build();
        addRenderableWidget(amountApplyButton);
        amountMaxButton = Button.builder(Component.literal("M"), b ->
            amountMaxField()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.set_to_max.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(amountMaxButton);
        amountDiscardButton = Button.builder(Component.literal("\u2715"), b ->
            amountDiscardDraft()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.undo.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(amountDiscardButton);

        layoutAmountBlock();

        advCapMinusButton = Button.builder(Component.literal("-"), b ->
            adjustAdvCap(-1)
        )
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_cap.minus.limit"
                    )
                )
            )
            .build();
        addRenderableWidget(advCapMinusButton);
        advCapEditBox = new EditBox(
            this.font,
            0,
            0,
            AMOUNT_EDIT_W,
            BTN_H,
            Component.literal("Limit/Keep")
        );
        advCapEditBox.setMaxLength(10);
        advCapEditBox.setResponder(v -> {
            String t = v.trim();
            if (
                t.isEmpty() || t.equals("\u221e") || t.equalsIgnoreCase("inf")
            ) {
                editModeAllowCapValue = 0;
                return;
            }
            try {
                editModeAllowCapValue = (int) Mth.clamp(
                    Long.parseLong(t),
                    0L,
                    Integer.MAX_VALUE
                );
            } catch (NumberFormatException ignored) {}
        });
        addRenderableWidget(advCapEditBox);
        advCapPlusButton = Button.builder(Component.literal("+"), b ->
            adjustAdvCap(1)
        )
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_cap.plus.limit"
                    )
                )
            )
            .build();
        addRenderableWidget(advCapPlusButton);
        advCapInfinityButton = Button.builder(Component.literal("0"), b -> {
            playClickSound();
            editModeAllowCapValue = 0;
            syncAllowCapEditBoxDisplay();
        })
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_cap.set_to_zero"
                    )
                )
            )
            .build();
        addRenderableWidget(advCapInfinityButton);
        advCapApplyButton = Button.builder(Component.literal("A"), b ->
            applyAllowCapField()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.apply"
                    )
                )
            )
            .build();
        addRenderableWidget(advCapApplyButton);
        advCapUndoButton = Button.builder(Component.literal("\u2715"), b ->
            undoAllowCapDraft()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.undo.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(advCapUndoButton);

        advCap2MinusButton = Button.builder(Component.literal("-"), b ->
            adjustAdvCap2(-1)
        )
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_cap.minus.keep"
                    )
                )
            )
            .build();
        addRenderableWidget(advCap2MinusButton);
        advCap2EditBox = new EditBox(
            this.font,
            0,
            0,
            AMOUNT_EDIT_W,
            BTN_H,
            Component.literal("Keep")
        );
        advCap2EditBox.setMaxLength(10);
        advCap2EditBox.setResponder(v -> {
            String t = v.trim();
            if (
                t.isEmpty() || t.equals("\u221e") || t.equalsIgnoreCase("inf")
            ) {
                editModeAllowCap2Value = 0;
                return;
            }
            try {
                editModeAllowCap2Value = (int) Mth.clamp(
                    Long.parseLong(t),
                    0L,
                    Integer.MAX_VALUE
                );
            } catch (NumberFormatException ignored) {}
        });
        addRenderableWidget(advCap2EditBox);
        advCap2PlusButton = Button.builder(Component.literal("+"), b ->
            adjustAdvCap2(1)
        )
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_cap.plus.keep"
                    )
                )
            )
            .build();
        addRenderableWidget(advCap2PlusButton);
        advCap2InfinityButton = Button.builder(Component.literal("0"), b -> {
            playClickSound();
            editModeAllowCap2Value = 0;
            syncAllowCap2EditBoxDisplay();
        })
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.allow_cap.set_to_zero"
                    )
                )
            )
            .build();
        addRenderableWidget(advCap2InfinityButton);
        advCap2ApplyButton = Button.builder(Component.literal("A"), b ->
            applyAllowCap2Field()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.apply"
                    )
                )
            )
            .build();
        addRenderableWidget(advCap2ApplyButton);
        advCap2UndoButton = Button.builder(Component.literal("\u2715"), b ->
            undoAllowCap2Draft()
        )
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.amount.undo.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(advCap2UndoButton);

        remoteIgnoreChannelButton = Button.builder(
                Component.translatable(
                        "gui.another_dynamics.duct_node.remote_node.ignore_channel.respect"),
                b -> handleAdvancedRemoteIgnoreChannelToggle())
            .bounds(0, 0, remoteIgnoreChannelButtonWidth(), BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.remote_node.ignore_channel.tooltip")))
            .build();
        addRenderableWidget(remoteIgnoreChannelButton);

        remoteNodeFaceButton =
                new RemoteNodeFaceButton(
                        0,
                        0,
                        AMOUNT_ACTION_BTN,
                        AMOUNT_ACTION_BTN,
                        this::handleRemoteNodeFaceChanged);
        addRenderableWidget(remoteNodeFaceButton);

        remoteNodeCoordEditButton =
                Button.builder(Component.literal("\u270E"), b -> openRemoteNodeCoordEdit())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, AMOUNT_ACTION_BTN)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.remote_node.coords_edit.tooltip")))
                        .build();
        addRenderableWidget(remoteNodeCoordEditButton);

        remoteNodeCoordXEditBox =
                new EditBox(this.font, 0, 0, REMOTE_NODE_COORD_EDIT_W, BTN_H, Component.empty());
        remoteNodeCoordXEditBox.setMaxLength(6);
        remoteNodeCoordXEditBox.setTextColor(FILTER_ENTRY_EDIT_TEXT_COLOR);
        addRenderableWidget(remoteNodeCoordXEditBox);
        remoteNodeCoordYEditBox =
                new EditBox(this.font, 0, 0, REMOTE_NODE_COORD_EDIT_W, BTN_H, Component.empty());
        remoteNodeCoordYEditBox.setMaxLength(6);
        remoteNodeCoordYEditBox.setTextColor(FILTER_ENTRY_EDIT_TEXT_COLOR);
        addRenderableWidget(remoteNodeCoordYEditBox);
        remoteNodeCoordZEditBox =
                new EditBox(this.font, 0, 0, REMOTE_NODE_COORD_EDIT_W, BTN_H, Component.empty());
        remoteNodeCoordZEditBox.setMaxLength(6);
        remoteNodeCoordZEditBox.setTextColor(FILTER_ENTRY_EDIT_TEXT_COLOR);
        addRenderableWidget(remoteNodeCoordZEditBox);
        remoteNodeCoordApplyButton =
                Button.builder(Component.literal("A"), b -> applyRemoteNodeCoordEdit())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.filters.apply")))
                        .build();
        addRenderableWidget(remoteNodeCoordApplyButton);
        remoteNodeCoordUndoButton =
                Button.builder(Component.literal("\u2715"), b -> undoRemoteNodeCoordEdit())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.amount.undo.tooltip")))
                        .build();
        addRenderableWidget(remoteNodeCoordUndoButton);

        energyBufExtractMinus = Button.builder(Component.literal("-"), b -> adjustEnergyBufExtract(-1))
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .build();
        addRenderableWidget(energyBufExtractMinus);
        energyBufExtractBox = new EditBox(this.font, 0, 0, AMOUNT_EDIT_W, BTN_H, Component.empty());
        energyBufExtractBox.setMaxLength(10);
        energyBufExtractBox.setResponder(v -> parseEnergyBufExtractBox(v));
        addRenderableWidget(energyBufExtractBox);
        energyBufExtractPlus = Button.builder(Component.literal("+"), b -> adjustEnergyBufExtract(1))
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .build();
        addRenderableWidget(energyBufExtractPlus);
        energyBufExtractAutoButton = Button.builder(Component.literal("~"), b -> {
            playClickSound();
            energyBufExtractDraft = 0;
            syncEnergyBufExtractBoxDisplay();
        })
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .build();
        addRenderableWidget(energyBufExtractAutoButton);
        energyBufExtractApplyButton = Button.builder(Component.literal("A"), b -> applyEnergyBufferLimits())
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .build();
        addRenderableWidget(energyBufExtractApplyButton);
        energyBufExtractUndoButton = Button.builder(Component.literal("\u2715"), b -> cancelEnergyBufferDraft())
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .build();
        addRenderableWidget(energyBufExtractUndoButton);

        energyBufInsertMinus = Button.builder(Component.literal("-"), b -> adjustEnergyBufInsert(-1))
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .build();
        addRenderableWidget(energyBufInsertMinus);
        energyBufInsertBox = new EditBox(this.font, 0, 0, AMOUNT_EDIT_W, BTN_H, Component.empty());
        energyBufInsertBox.setMaxLength(10);
        energyBufInsertBox.setResponder(v -> parseEnergyBufInsertBox(v));
        addRenderableWidget(energyBufInsertBox);
        energyBufInsertPlus = Button.builder(Component.literal("+"), b -> adjustEnergyBufInsert(1))
            .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
            .build();
        addRenderableWidget(energyBufInsertPlus);
        energyBufInsertAutoButton = Button.builder(Component.literal("~"), b -> {
            playClickSound();
            energyBufInsertDraft = 0;
            syncEnergyBufInsertBoxDisplay();
        })
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .build();
        addRenderableWidget(energyBufInsertAutoButton);
        energyBufInsertApplyButton = Button.builder(Component.literal("A"), b -> applyEnergyBufferLimits())
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .build();
        addRenderableWidget(energyBufInsertApplyButton);
        energyBufInsertUndoButton = Button.builder(Component.literal("\u2715"), b -> cancelEnergyBufferDraft())
            .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
            .build();
        addRenderableWidget(energyBufInsertUndoButton);

        layoutAdvancedCapBlock();
        layoutAdvancedRemoteNodeBlock();
        layoutEnergyBufferBlock();

        nodeModeButton = Button.builder(Component.empty(), b -> {
            playClickSound();
            NodeMode nm = NodeMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.NODE_MODE)
            );
            if (nm.isHybrid() && hybridPanel != HybridPanel.NONE) {
                hybridPanel = HybridPanel.NONE;
                applySubViewVisibility();
                return;
            }
            handleMenuButton(0);
        })
            .bounds(
                this.leftPos + CENTER_X,
                this.topPos + ROW2_Y,
                ROW_BTN_W,
                BTN_H
            )
            .build();
        addRenderableWidget(nodeModeButton);
        selfFeedStub = Button.builder(
            Component.translatable("gui.another_dynamics.duct_node.self_feed"),
            b -> playClickSound()
        )
            .bounds(
                this.leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                this.topPos + ROW3_Y,
                ROW_BTN_W,
                BTN_H
            )
            .build();
        addRenderableWidget(selfFeedStub);
        opaqueRenderingButton = Button.builder(Component.empty(), b -> {
            if (menu.isDuctAlwaysOpaqueLocked()) {
                return;
            }
            playClickSound();
            ModNetwork.sendDuctOpaqueToggle(menu.getDuctBlockPos());
        })
            .bounds(
                this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                this.topPos + ROW2_Y,
                ROW_BTN_W,
                BTN_H
            )
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.opaque_rendering.tooltip.off"
                    )
                )
            )
            .build();
        addRenderableWidget(opaqueRenderingButton);

        hubBackButton = Button.builder(
            Component.translatable("gui.another_dynamics.duct_node.hub_back"),
            b -> {
                playClickSound();
                handleMenuButton(DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB);
            }
        )
            .bounds(0, 0, ROW_BTN_W, BTN_H)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.hub_back.tooltip"
                    )
                )
            )
            .build();
        addRenderableWidget(hubBackButton);

        copierVirtualNodeBackButton = Button.builder(
            Component.translatable("gui.another_dynamics.duct_node.filters.back"),
            b -> returnToSettingsCopierHubFromVirtual()
        )
            .bounds(
                this.leftPos + CHROME_COL_MID_X,
                this.topPos + ROW1_Y,
                ROW_BTN_W,
                BTN_H
            )
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.settings_copier.back_to_hub"
                    )
                )
            )
            .build();
        addRenderableWidget(copierVirtualNodeBackButton);

        backButton = Button.builder(
            Component.translatable("gui.another_dynamics.duct_node.filters.back"),
            b -> {
                if (isSettingsCopierFilterListEditor()) {
                    playClickSound();
                    returnToSettingsCopierHubFromVirtual();
                } else if (isSettingsCopierAllVirtualMultiTransport()) {
                    playClickSound();
                    returnToVirtualTransportHub();
                } else if (subView == SubView.ADVANCED_FILTERING) {
                    playClickSound();
                    closeAdvancedFiltering();
                } else {
                    playClickSound();
                    closeFilterSubview();
                }
            }
        )
            .bounds(
                this.leftPos + CENTER_X,
                this.topPos + filterNavRowScreenY(),
                ROW_BTN_W,
                BTN_H
            )
            .build();
        addRenderableWidget(backButton);

        validKeysButton = Button.builder(
            Component.translatable(
                "gui.another_dynamics.duct_node.filters.how_to_use"
            ),
            b -> {
                playClickSound();
                unfocusAllTextFields();
                filterListBeforeHelp = subView;
                subView = SubView.HOW_TO_USE;
                applySubViewVisibility();
                rebuildFilterEntryWidgets();
            }
        )
            .bounds(
                this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                this.topPos + filterNavRowScreenY(),
                ROW_BTN_W,
                BTN_H
            )
            .build();
        addRenderableWidget(validKeysButton);

        filterReorderButton =
                Button.builder(Component.literal("R"), b -> {
                            playClickSound();
                            reorderActiveFilterLines();
                        })
                        .bounds(0, 0, 12, 12)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.filters.reorder.tooltip")))
                        .build();
        filterReorderButton.visible = false;
        addRenderableWidget(filterReorderButton);

        oppositeFilterListButton =
                Button.builder(Component.empty(), b -> {
                            playClickSound();
                            openOppositeFilterList();
                        })
                        .bounds(0, 0, 80, BTN_H)
                        .build();
        oppositeFilterListButton.visible = false;
        addRenderableWidget(oppositeFilterListButton);

        copierFilterTypeButton =
                Button.builder(Component.empty(), b -> {
                            playClickSound();
                            cycleCopierFilterMaterialKind();
                        })
                        .bounds(0, 0, filterNavActionSpanWidth(), BTN_H)
                        .build();
        copierFilterTypeButton.visible = false;
        addRenderableWidget(copierFilterTypeButton);

        settingsCopierSaveButton =
                Button.builder(
                                Component.translatable(
                                        "gui.another_dynamics.duct_node.settings_copier.copy"),
                                b -> sendSettingsCopierAction(SettingsCopierActionPayload.ACTION_COPY))
                        .tooltip(
                                Tooltip.create(
                                        SettingsCopierItem.grayTooltipLine(
                                                "gui.another_dynamics.duct_node.settings_copier.copy.tooltip")))
                        .bounds(0, 0, 18, COPIER_ACTION_BTN_H)
                        .build();
        addRenderableWidget(settingsCopierSaveButton);
        settingsCopierLoadButton =
                Button.builder(
                                Component.translatable(
                                        "gui.another_dynamics.duct_node.settings_copier.paste"),
                                b -> sendSettingsCopierAction(SettingsCopierActionPayload.ACTION_PASTE))
                        .tooltip(
                                Tooltip.create(
                                        SettingsCopierItem.grayTooltipLine(
                                                "gui.another_dynamics.duct_node.settings_copier.paste.tooltip")))
                        .bounds(0, 0, 18, COPIER_ACTION_BTN_H)
                        .build();
        addRenderableWidget(settingsCopierLoadButton);

        channelButton = new ChannelLetterButton(
            this.leftPos + DuctNodeMenu.CHANNEL_BACKGROUND_X,
            this.topPos + DuctNodeMenu.CHANNEL_BACKGROUND_Y,
            CHANNEL_WIDGET_W,
            CHANNEL_WIDGET_H,
            dir -> {
                int id = dir == 0 ? 13 : (dir > 0 ? 4 : 5);
                ModNetwork.sendDuctMenuButton(menu, id);
            }
        );
        MutableComponent channelTip = Component.empty();
        appendChannelLetterClickHints(channelTip, true);
        channelButton.setTooltip(Tooltip.create(channelTip));
        addRenderableWidget(channelButton);
        layoutCopierColumn();

        transportKindPickerButtons.clear();
        for (DuctTransportKind k : DuctTransportKind.values()) {
            final int kindOrdinal = k.ordinal();
            Button b = Button.builder(Component.empty(), btn ->
                handleMenuButton(
                    DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE +
                        kindOrdinal
                )
            )
                .bounds(0, 0, ROW_BTN_W, BTN_H)
                .build();
            transportKindPickerButtons.add(b);
            addRenderableWidget(b);
        }

        transportToggleButtons.clear();
        for (DuctTransportKind k : DuctTransportKind.values()) {
            final int kindOrdinal = k.ordinal();
            Button b = Button.builder(Component.empty(), btn ->
                handleMenuButton(
                    DuctBlockEntity.MENU_BUTTON_TRANSPORT_TOGGLE_BASE + kindOrdinal
                )
            )
                .bounds(0, 0, ROW_BTN_W, BTN_H)
                .build();
            transportToggleButtons.add(b);
            addRenderableWidget(b);
        }

        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        rebuildFilterEntryWidgets();
        layoutMainChromeRowsForHubOrDetail();
        layoutHubTransportGrid();
        layoutHubBackButton();
        layoutCopierColumn();
        applySubViewVisibility();
        bootstrapSettingsCopierInitialSubview();
        lastActiveTransportKind = menu.filterTransportKindOrdinal();
        if (menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) != 0) {
            ModNetwork.sendFilterSyncRequest(menu.getDuctBlockPos(), menu.getAccessFace());
        }

        // JEI (and some UI transitions) can cause a screen re-init that clears widgets.
        // If we are mid edit-mode, restore the edit widgets without grabbing keyboard focus.
        if (inEditMode()) {
            createEditModeUI();
            applySubViewVisibility();
            reloadFilterEntryTextBoxFromList(false);
        }
    }

    /** Bottom of the right column stack (channel, optional copier + Save/Load) for positioning transport pickers. */
    private int rightColumnStackBottomGuiY() {
        if (showsSettingsCopierColumn() && menu.copySettingsSlotIndex() >= 0) {
            return DuctNodeMenu.COPIER_LOAD_BUTTON_Y
                    + COPIER_ACTION_BTN_H
                    + DuctNodeMenu.COPY_COLUMN_GAP;
        }
        if (channelButton != null && channelButton.visible) {
            return DuctNodeMenu.CHANNEL_BACKGROUND_Y + CHANNEL_WIDGET_H + DuctNodeMenu.COPY_COLUMN_GAP;
        }
        return DuctNodeMenu.REDSTONE_GUI_Y + REDSTONE_BUTTON_SIZE;
    }

    private void layoutCopierColumn() {
        if (settingsCopierSaveButton == null || settingsCopierLoadButton == null || channelButton == null) {
            return;
        }
        int colX = this.leftPos + DuctNodeMenu.SLOT_COPY_BACKGROUND_X;
        channelButton.setX(this.leftPos + DuctNodeMenu.CHANNEL_BACKGROUND_X);
        channelButton.setY(this.topPos + DuctNodeMenu.CHANNEL_BACKGROUND_Y);
        channelButton.setWidth(CHANNEL_WIDGET_W);
        channelButton.setHeight(CHANNEL_WIDGET_H);
        settingsCopierSaveButton.setX(colX);
        settingsCopierSaveButton.setY(this.topPos + DuctNodeMenu.COPIER_SAVE_BUTTON_Y);
        settingsCopierSaveButton.setWidth(18);
        settingsCopierSaveButton.setHeight(COPIER_ACTION_BTN_H);
        settingsCopierLoadButton.setX(colX);
        settingsCopierLoadButton.setY(this.topPos + DuctNodeMenu.COPIER_LOAD_BUTTON_Y);
        settingsCopierLoadButton.setWidth(18);
        settingsCopierLoadButton.setHeight(COPIER_ACTION_BTN_H);
        refreshCopierPasteUi();
    }

    private void refreshCopierPasteUi() {
        if (settingsCopierLoadButton == null) {
            return;
        }
        List<Component> tipLines = new ArrayList<>();
        tipLines.add(
                SettingsCopierItem.grayTooltipLine(
                        "gui.another_dynamics.duct_node.settings_copier.paste.tooltip"));
        boolean allowPaste = true;
        if (isAllowOrDenyFilterListContext()) {
            int idx = menu.copySettingsSlotIndex();
            if (idx >= 0) {
                ItemStack copier = menu.getSlot(idx).getItem();
                if (!copier.isEmpty()
                        && SettingsCopierStoreKind.getMode(copier) == SettingsCopierStoreKind.FILTER) {
                    var snapshot = DuctFaceSettingsSnapshot.readFromCopier(copier);
                    if (snapshot.isPresent()) {
                        FilterListMaterialKind kind =
                                DuctFilterListSnapshot.getMaterialKind(snapshot.get());
                        tipLines.add(
                                Component.translatable(
                                                "gui.another_dynamics.duct_node.settings_copier.paste.copier_kind",
                                                kind.displayName())
                                        .withStyle(ChatFormatting.AQUA));
                        int mask = menu.getSyncData().get(DuctMenuSync.TRANSPORT_ENABLED_MASK);
                        if (kind == FilterListMaterialKind.NONE) {
                            tipLines.add(
                                    Component.translatable(
                                                    "gui.another_dynamics.duct_node.settings_copier.paste.kind_not_set")
                                            .withStyle(ChatFormatting.RED));
                            allowPaste = false;
                        } else if (!kind.isEnabledInTransportMask(mask)) {
                            tipLines.add(
                                    Component.translatable(
                                                    "gui.another_dynamics.duct_node.settings_copier.paste.kind_mismatch",
                                                    kind.displayName())
                                            .withStyle(ChatFormatting.RED));
                            allowPaste = false;
                        }
                    } else {
                        allowPaste = false;
                    }
                }
            }
        }
        MutableComponent combined = Component.empty();
        for (int i = 0; i < tipLines.size(); i++) {
            if (i > 0) {
                combined.append("\n");
            }
            combined.append(tipLines.get(i));
        }
        settingsCopierLoadButton.setTooltip(Tooltip.create(combined));
        settingsCopierLoadButton.active = allowPaste;
    }

    private void sendSettingsCopierAction(int action) {
        playClickSound();
        boolean filterList =
                subView == SubView.ALLOW_FILTERS || subView == SubView.DENY_FILTERS;
        if (filterList) {
            flushPendingFilterEditsBeforeClose();
            pushFiltersToServer();
            int allowDeny =
                    subView == SubView.ALLOW_FILTERS
                            ? SettingsCopierActionPayload.LIST_ALLOW
                            : SettingsCopierActionPayload.LIST_DENY;
            if (menu instanceof DuctNodeMenu ductMenu) {
                ModNetwork.sendSettingsCopierAction(
                        ductMenu,
                        action,
                        SettingsCopierActionPayload.VIEW_FILTER_LIST,
                        menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                        activeFilterBank.ordinal(),
                        allowDeny);
            }
        } else if (menu instanceof DuctNodeMenu ductMenu) {
            ModNetwork.sendSettingsCopierAction(
                    ductMenu,
                    action,
                    SettingsCopierActionPayload.VIEW_MAIN,
                    menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                    0,
                    0);
        }
    }

    private static Component listLogicButtonMessage(boolean denyOverridesAllow) {
        return Component.translatable(
                denyOverridesAllow
                        ? "gui.another_dynamics.duct_node.list_logic.label.deny_wins"
                        : "gui.another_dynamics.duct_node.list_logic.label.allow_bypass");
    }

    private boolean shouldReturnToTransportHubInsteadOfClosing() {
        if (isSettingsCopierFilterListEditor()) {
            return false;
        }
        // Settings copier virtual editor: hub Back / X return to configurator; detail uses hubBackButton.
        if (useSettingsCopierHubNavigation()) {
            return false;
        }
        return (
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) != 0
        );
    }

    /** Node mode + opaque: row 1 on transport hub, row 2 on detail (filters use row 1 in detail). */
    private void layoutMainChromeRowsForHubOrDetail() {
        if (nodeModeButton == null || opaqueRenderingButton == null) {
            return;
        }
        boolean hubMain =
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0 &&
            subView == SubView.MAIN;
        int y = this.topPos + (hubMain ? ROW1_Y : ROW2_Y);
        nodeModeButton.setX(this.leftPos + CENTER_X);
        nodeModeButton.setY(y);
        nodeModeButton.setWidth(ROW_BTN_W);
        nodeModeButton.setHeight(BTN_H);
        // Hub: mode | (empty / copier Back) | opaque. Detail: mode | opaque | routing.
        int opaqueCol = hubMain ? 2 : 1;
        opaqueRenderingButton.setX(
            this.leftPos + CENTER_X + opaqueCol * (ROW_BTN_W + ROW_GAP)
        );
        opaqueRenderingButton.setY(y);
        opaqueRenderingButton.setWidth(ROW_BTN_W);
        opaqueRenderingButton.setHeight(BTN_H);
        if (copierVirtualNodeBackButton != null) {
            if (hubMain && isSettingsCopierAllVirtualHub()) {
                copierVirtualNodeBackButton.setX(this.leftPos + CHROME_COL_MID_X);
                copierVirtualNodeBackButton.setY(this.topPos + ROW1_Y);
                copierVirtualNodeBackButton.setWidth(ROW_BTN_W);
                copierVirtualNodeBackButton.setHeight(BTN_H);
            }
        }
    }

    /**
     * Universal duct hub: each column stacks enabled/disabled toggle (top) and transport-kind entry (bottom).
     * Detail view keeps a single horizontal picker row without toggles.
     */
    private void layoutHubTransportGrid() {
        Optional<DuctDefinition> defOpt =
            DuctDefinitionRegistry.getByLogicalId(menu.getClientDuctLogicalId());
        EnumSet<DuctTransportKind> ductKinds =
            defOpt.map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        EnumSet<DuctTransportKind> suppressedMek =
            defOpt.map(DuctDefinition::suppressedMekKindsSet).orElse(EnumSet.noneOf(DuctTransportKind.class));
        boolean hubMain =
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0 &&
            subView == SubView.MAIN;
        int startX = this.leftPos + CENTER_X;
        if (hubMain) {
            int cellStride = BTN_H + ROW_GAP;
            int gridRowStride = 2 * cellStride;
            int idx = 0;
            for (DuctTransportKind k : DuctTransportKind.values()) {
                boolean show = ductKinds.contains(k) || suppressedMek.contains(k);
                if (!show) {
                    if (k.ordinal() < transportToggleButtons.size()) {
                        transportToggleButtons.get(k.ordinal()).setWidth(0);
                        transportToggleButtons.get(k.ordinal()).setHeight(0);
                    }
                    if (k.ordinal() < transportKindPickerButtons.size()) {
                        transportKindPickerButtons.get(k.ordinal()).setWidth(0);
                        transportKindPickerButtons.get(k.ordinal()).setHeight(0);
                    }
                    continue;
                }
                int col = idx % HUB_TRANSPORT_KIND_COLUMNS;
                int gridRow = idx / HUB_TRANSPORT_KIND_COLUMNS;
                int cellX = startX + col * (ROW_BTN_W + ROW_GAP);
                int toggleY = this.topPos + HUB_TRANSPORT_TOGGLE_ROW_Y + gridRow * gridRowStride;
                int pickerY = this.topPos + HUB_TRANSPORT_PICKER_ROW_Y + gridRow * gridRowStride;
                if (k.ordinal() < transportToggleButtons.size()) {
                    Button toggle = transportToggleButtons.get(k.ordinal());
                    toggle.setX(cellX);
                    toggle.setY(toggleY);
                    toggle.setWidth(ROW_BTN_W);
                    toggle.setHeight(BTN_H);
                }
                if (k.ordinal() < transportKindPickerButtons.size()) {
                    Button picker = transportKindPickerButtons.get(k.ordinal());
                    picker.setX(cellX);
                    picker.setY(pickerY);
                    picker.setWidth(ROW_BTN_W);
                    picker.setHeight(BTN_H);
                }
                idx++;
            }
        } else {
            int y = this.topPos + rightColumnStackBottomGuiY() + 4;
            int rowStride = BTN_H + ROW_GAP;
            int idx = 0;
            for (DuctTransportKind k : DuctTransportKind.values()) {
                if (k.ordinal() >= transportKindPickerButtons.size()) {
                    break;
                }
                Button b = transportKindPickerButtons.get(k.ordinal());
                boolean show = ductKinds.contains(k) || suppressedMek.contains(k);
                if (!show) {
                    b.setWidth(0);
                    b.setHeight(0);
                    continue;
                }
                int col = idx % HUB_TRANSPORT_KIND_COLUMNS;
                int row = idx / HUB_TRANSPORT_KIND_COLUMNS;
                b.setX(startX + col * (ROW_BTN_W + ROW_GAP));
                b.setY(y + row * rowStride);
                b.setWidth(ROW_BTN_W);
                b.setHeight(BTN_H);
                idx++;
            }
            for (Button toggle : transportToggleButtons) {
                toggle.setWidth(0);
                toggle.setHeight(0);
            }
        }
    }

    private boolean isUniversalDetailMain() {
        return (
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) != 0 &&
            subView == SubView.MAIN
        );
    }

    private static int amountActionRowWidth(boolean includeMax) {
        int w = AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        if (includeMax) {
            w += AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        }
        return w + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
    }

    private void positionAmountActionButton(Button button, int screenX, int screenY) {
        button.setPosition(screenX, screenY);
        button.setWidth(AMOUNT_ACTION_BTN);
        button.setHeight(BTN_H);
    }

    private boolean isTransportKindEnabledInMask(DuctTransportKind kind) {
        int mask = menu.getSyncData().get(DuctMenuSync.TRANSPORT_ENABLED_MASK);
        if (mask < 0) {
            return true;
        }
        return (mask & (1 << kind.ordinal())) != 0;
    }

    /** Accent per transport column in the universal hub (toggle + picker share this hue). */
    private static ChatFormatting hubTransportColumnColor(DuctTransportKind kind) {
        return switch (kind) {
            case ITEM -> ChatFormatting.YELLOW;
            case FLUID -> ChatFormatting.AQUA;
            case GAS -> ChatFormatting.LIGHT_PURPLE;
            case ENERGY -> ChatFormatting.RED;
            case HEAT -> ChatFormatting.GOLD;
        };
    }

    private static Component hubToggleLabel(boolean laneOn) {
        return Component.translatable(
                        laneOn
                                ? "gui.another_dynamics.duct_node.transport_toggle.enabled"
                                : "gui.another_dynamics.duct_node.transport_toggle.disabled")
                .withStyle(laneOn ? ChatFormatting.GREEN : ChatFormatting.RED)
                .withStyle(Style.EMPTY.withBold(true));
    }

    private static Component hubPickerLabel(DuctTransportKind kind, boolean laneOn) {
        return Component.translatable(
                        "gui.another_dynamics.duct_node.transport." + kind.name().toLowerCase())
                .withStyle(laneOn ? hubTransportColumnColor(kind) : ChatFormatting.DARK_GRAY);
    }

    private void layoutHubBackButton() {
        if (hubBackButton == null) {
            return;
        }
        boolean hubMain =
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0;
        int ox = CENTER_X + ROW_BTN_W + ROW_GAP;
        int oy = hubMain ? HUB_BACK_ROW_Y : ROW3_Y;
        hubBackButton.setX(this.leftPos + ox);
        hubBackButton.setY(this.topPos + oy);
        hubBackButton.setWidth(ROW_BTN_W);
        hubBackButton.setHeight(BTN_H);
    }

    private void openFilterSubview(SubView v) {
        if (isSettingsCopierFilterListEditor() && v != SubView.ALLOW_FILTERS) {
            return;
        }
        exitEditMode(false);
        subView = v;
        filterScrollOffset = 0;
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        rebuildFilterEntryWidgets();
        applySubViewVisibility();
    }

    private void closeFilterSubview() {
        if (subView == SubView.HOW_TO_USE) {
            subView = filterListBeforeHelp;
            applySubViewVisibility();
            rebuildFilterEntryWidgets();
            return;
        }
        if (isSettingsCopierFilterListEditor()) {
            returnToSettingsCopierHubFromVirtual();
            return;
        }
        flushPendingFilterEditsBeforeClose();
        if (shouldPushFiltersOnClose()) {
            reorderAndPushAllFilterBanks();
        }
        exitEditMode(false);
        subView = SubView.MAIN;
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    /** When node mode is {@link NodeMode#NONE}, filter subviews must not stay open (controls are inactive). */
    private void forceExitFilterUiToMain() {
        if (isSettingsCopierFilterListEditor()) {
            returnToSettingsCopierHubFromVirtual();
            return;
        }
        exitEditMode(false);
        subView = SubView.MAIN;
        rebuildFilterEntryWidgets();
        applySubViewVisibility();
    }

    protected boolean isMainChromeSubView() {
        return subView == SubView.MAIN && hybridPanel == HybridPanel.NONE;
    }

    protected void handleCloseOrBack() {
        if (useSettingsCopierHubNavigation()) {
            playClickSound();
            popSettingsCopierVirtualOneLevel();
            return;
        }
        if (subView == SubView.MAIN) {
            if (isSettingsCopierFilterListEditor()) {
                returnToSettingsCopierHubFromVirtual();
                return;
            }
            if (hybridPanel != HybridPanel.NONE) {
                hybridPanel = HybridPanel.NONE;
                applySubViewVisibility();
                return;
            }
            if (shouldReturnToTransportHubInsteadOfClosing()) {
                playClickSound();
                handleMenuButton(DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB);
                return;
            }
            onClose();
        } else {
            if (isSettingsCopierFilterListEditor()) {
                returnToSettingsCopierHubFromVirtual();
                return;
            }
            if (subView == SubView.ADVANCED_FILTERING) {
                playClickSound();
                closeAdvancedFiltering();
                return;
            }
            if (subView == SubView.BUFFER_LIMITS) {
                playClickSound();
                closeEnergyBufferSubview();
                return;
            }
            playClickSound();
            closeFilterSubview();
        }
    }

    /** Screen Y of the row with Back and Valid keys (below the filter entry list). */
    private int filterNavRowScreenY() {
        return (
            FIRST_FILTER_ROW_Y +
            VISIBLE_FILTER_ENTRIES * ENTRY_HEIGHT +
            FILTER_NAV_GAP
        );
    }

    /** X/Y placement for the "Advanced" button slot in filter entry edit mode. */
    private int editModeAdvancedButtonScreenX() {
        int buttonSize = 12;
        int buttonSpacing = 2;
        int slotSize = 18;
        int rowLeft = this.leftPos + ENTRY_X;
        int slotX = rowLeft + buttonSize + buttonSpacing;
        int rightArrowX = slotX + slotSize + buttonSpacing;
        return rightArrowX + buttonSize + buttonSpacing;
    }

    private int editModeAdvancedButtonScreenY() {
        int slotSize = 18;
        int slotY = editModeRowAnchorScreenY();
        return slotY + (slotSize - BTN_H) / 2;
    }

    /** Shared X for Go To / copier filter-type row (aligned with Back). */
    private int filterNavActionSpanX() {
        return editModeAdvancedButtonScreenX();
    }

    /** Shared Y for Go To / copier filter-type row (EditBox row below ghost slot). */
    private int filterNavActionSpanY() {
        return editModeRowAnchorScreenY() + 18 + 2;
    }

    /** Width from Back through Valid keys. */
    private int filterNavActionSpanWidth() {
        return ADVANCED_FILTER_BUTTON_WIDTH * 2 + ADJACENT_BTN_GAP;
    }

    private void layoutFilterSortButton() {
        if (filterReorderButton == null) {
            return;
        }
        int buttonSize = 12;
        int buttonSpacing = 2;
        int slotSize = 18;
        boolean show =
                isAllowOrDenyFilterListContext() && !inEditMode() && subView != SubView.HOW_TO_USE;
        if (!show) {
            filterReorderButton.visible = false;
            return;
        }
        int slotY = editModeRowAnchorScreenY();
        int rowLeft = this.leftPos + ENTRY_X;
        int rowRight = this.leftPos + ENTRY_X + ENTRY_WIDTH;
        int closeButtonX = rowRight - buttonSize;
        int applyButtonX = closeButtonX - buttonSize - buttonSpacing;
        int clearButtonX = applyButtonX - buttonSize - buttonSpacing;
        int buttonRowY = slotY + (slotSize - buttonSize) / 2;
        filterReorderButton.setX(clearButtonX);
        filterReorderButton.setY(buttonRowY);
        filterReorderButton.setWidth(buttonSize);
        filterReorderButton.setHeight(buttonSize);
        filterReorderButton.visible = true;
    }

    private void layoutFilterNavAndHelpButtons() {
        boolean howto = subView == SubView.HOW_TO_USE;
        boolean filterList =
            subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS;
        if (howto) {
            backButton.setX(this.leftPos + HELP_BACK_BUTTON_X);
            backButton.setY(this.topPos + HELP_BACK_BUTTON_Y);
            backButton.setWidth(ADVANCED_FILTER_BUTTON_WIDTH);
            backButton.setHeight(BTN_H);
        } else if (subView == SubView.ADVANCED_FILTERING) {
            // Back is placed with {@link #layoutEditModeWidgets()} (same slot as the old Advanced button).
        } else if (filterList && !inEditMode()) {
            int bx = editModeAdvancedButtonScreenX();
            int by = editModeAdvancedButtonScreenY();
            backButton.setX(bx);
            backButton.setY(by);
            backButton.setWidth(ADVANCED_FILTER_BUTTON_WIDTH);
            backButton.setHeight(BTN_H);
            validKeysButton.setX(bx + backButton.getWidth() + ADJACENT_BTN_GAP);
            validKeysButton.setY(by);
            validKeysButton.setWidth(ADVANCED_FILTER_BUTTON_WIDTH);
            validKeysButton.setHeight(BTN_H);
        }
        layoutFilterSortButton();
        layoutOppositeFilterListButton();
        layoutCopierFilterTypeButton();
    }

    private void layoutOppositeFilterListButton() {
        if (oppositeFilterListButton == null) {
            return;
        }
        boolean show =
                isAllowOrDenyFilterListContext()
                        && !inEditMode()
                        && !isSettingsCopierFilterListEditor()
                        && subView != SubView.HOW_TO_USE;
        if (!show) {
            oppositeFilterListButton.visible = false;
            return;
        }
        oppositeFilterListButton.setX(filterNavActionSpanX());
        oppositeFilterListButton.setY(filterNavActionSpanY());
        oppositeFilterListButton.setWidth(filterNavActionSpanWidth());
        oppositeFilterListButton.setHeight(BTN_H);
        if (subView == SubView.ALLOW_FILTERS) {
            oppositeFilterListButton.setMessage(
                    Component.translatable(
                            "gui.another_dynamics.duct_node.filters.goto_deny_list"));
        } else {
            oppositeFilterListButton.setMessage(
                    Component.translatable(
                            "gui.another_dynamics.duct_node.filters.goto_allow_list"));
        }
        int menuFlags = menu.getSyncData().get(DuctMenuSync.FLAGS);
        boolean filtersActive = (menuFlags & DuctMenuSync.FLAG_FILTERS_ACTIVE) != 0;
        oppositeFilterListButton.active = filtersActive && !transportFilterListsLocked();
        oppositeFilterListButton.visible = true;
    }

    private void layoutCopierFilterTypeButton() {
        if (copierFilterTypeButton == null) {
            return;
        }
        boolean show = isSettingsCopierFilterListEditor() && !inEditMode() && subView != SubView.HOW_TO_USE;
        if (!show) {
            copierFilterTypeButton.visible = false;
            return;
        }
        copierFilterTypeButton.setX(filterNavActionSpanX());
        copierFilterTypeButton.setY(filterNavActionSpanY());
        copierFilterTypeButton.setWidth(filterNavActionSpanWidth());
        copierFilterTypeButton.setHeight(BTN_H);
        copierFilterTypeButton.setMessage(copierFilterTypeButtonLabel(copierFilterMaterialKind()));
        copierFilterTypeButton.setTooltip(
                Tooltip.create(
                        Component.translatable(FilterListMaterialKind.filterTypeButtonTooltipKey())));
        copierFilterTypeButton.visible = true;
    }

    private static Component copierFilterTypeButtonLabel(FilterListMaterialKind kind) {
        return switch (kind) {
            case NONE ->
                    Component.translatable("gui.another_dynamics.settings_copier.filter_type.none");
            case ITEM ->
                    Component.translatable("gui.another_dynamics.settings_copier.filter_type.item");
            case FLUID ->
                    Component.translatable("gui.another_dynamics.settings_copier.filter_type.fluid");
            case GAS ->
                    Component.translatable("gui.another_dynamics.settings_copier.filter_type.gas");
        };
    }

    private FilterListMaterialKind copierFilterMaterialKind() {
        if (!(menu instanceof SettingsCopierMenu copier) || minecraft == null || minecraft.player == null) {
            return FilterListMaterialKind.NONE;
        }
        return copier.clientFilterListMaterialKind(minecraft.player);
    }

    private boolean canOpenCopierFilterEntry() {
        return !isSettingsCopierFilterListEditor() || copierFilterMaterialKind() != FilterListMaterialKind.NONE;
    }

    private void cycleCopierFilterMaterialKind() {
        int prevLane = menu.filterTransportKindOrdinal();
        reorderAndPushAllFilterBanksForTransport(prevLane);
        FilterListMaterialKind next = copierFilterMaterialKind().next();
        if (menu instanceof SettingsCopierMenu copier) {
            copier.setClientFilterListMaterialKind(next);
        }
        ModNetwork.sendSettingsCopierFilterMaterialKind(next.ordinal());
        ModNetwork.sendFilterSyncRequest(menu.getDuctBlockPos(), menu.getAccessFace());
        lastActiveTransportKind = next != FilterListMaterialKind.NONE
                ? next.toTransportKind().ordinal()
                : menu.filterTransportKindOrdinal();
        layoutCopierFilterTypeButton();
        rebuildFilterEntryWidgets();
    }

    private void openOppositeFilterList() {
        if (transportFilterListsLocked()) {
            return;
        }
        SubView target =
                subView == SubView.ALLOW_FILTERS
                        ? SubView.DENY_FILTERS
                        : SubView.ALLOW_FILTERS;
        if (menu instanceof SettingsCopierMenu sc) {
            SettingsCopierVirtualSession session = sc.virtualSession();
            if (session != null) {
                session.noteFilterListContext(
                        menu.filterTransportKind(), activeFilterBank, target == SubView.ALLOW_FILTERS);
            }
        }
        openFilterSubview(target);
    }

    private boolean inEditMode() {
        return editModeFilterIndex >= 0;
    }

    private boolean isAdvancedFilterCapSubview() {
        return subView == SubView.ADVANCED_FILTERING;
    }

    private boolean showsAdvancedCapEditors() {
        return isAdvancedFilterCapSubview()
                && advancedFilterParentSubview == SubView.ALLOW_FILTERS;
    }

    private boolean showsAdvancedRemoteNodeBlock() {
        return isAdvancedFilterCapSubview()
                && filterRemoteNodeUiEnabled()
                && inEditMode();
    }

    private AdvancedRemoteNodeRowLayout advancedRemoteNodeRowLayout() {
        int slotScreenX = this.leftPos + ADVANCED_REMOTE_NODE_SLOT_GUI_X;
        int slotScreenY = this.topPos + ADVANCED_REMOTE_NODE_ROW_GUI_Y;
        int coordTextScreenY =
                slotScreenY + ADVANCED_REMOTE_NODE_SLOT_SIZE + ADVANCED_REMOTE_NODE_COORD_GAP_Y;
        return new AdvancedRemoteNodeRowLayout(slotScreenX, slotScreenY, coordTextScreenY);
    }

    private int remoteIgnoreChannelButtonWidth() {
        int respectWidth =
                this.font.width(
                        Component.translatable(
                                        "gui.another_dynamics.duct_node.remote_node.ignore_channel.respect")
                                .getString());
        int ignoreWidth =
                this.font.width(
                        Component.translatable(
                                        "gui.another_dynamics.duct_node.remote_node.ignore_channel.ignore")
                                .getString());
        return Math.max(respectWidth, ignoreWidth) + 8;
    }

    private void layoutAdvancedRemoteNodeBlock() {
        if (remoteIgnoreChannelButton == null) {
            return;
        }
        AdvancedRemoteNodeRowLayout row = advancedRemoteNodeRowLayout();
        int slotX = row.slotScreenX();
        int slotY = row.slotScreenY();
        int flankY = slotY + (ADVANCED_REMOTE_NODE_SLOT_SIZE - AMOUNT_ACTION_BTN) / 2;

        if (remoteNodeFaceButton != null) {
            remoteNodeFaceButton.setPosition(
                    slotX - AMOUNT_ACTION_BTN - ADJACENT_BTN_GAP, flankY);
            remoteNodeFaceButton.setWidth(AMOUNT_ACTION_BTN);
            remoteNodeFaceButton.setHeight(AMOUNT_ACTION_BTN);
        }
        if (remoteNodeCoordEditButton != null) {
            remoteNodeCoordEditButton.setPosition(
                    slotX + ADVANCED_REMOTE_NODE_SLOT_SIZE + ADJACENT_BTN_GAP, flankY);
            remoteNodeCoordEditButton.setWidth(AMOUNT_ACTION_BTN);
            remoteNodeCoordEditButton.setHeight(AMOUNT_ACTION_BTN);
        }

        layoutRemoteNodeCoordEditor(row);

        int buttonWidth = remoteIgnoreChannelButtonWidth();
        int ignoreButtonScreenY = remoteNodeIgnoreChannelButtonScreenY(row);
        int buttonScreenX = this.leftPos + (TEXTURE_WIDTH - buttonWidth) / 2;
        remoteIgnoreChannelButton.setPosition(buttonScreenX, ignoreButtonScreenY);
        remoteIgnoreChannelButton.setWidth(buttonWidth);
        remoteIgnoreChannelButton.setHeight(BTN_H);
    }

    /** Fixed bottom of the coord row for Respect/Ignore Channel placement (always {@link #BTN_H}, same as the editor). */
    private int remoteNodeCoordRowBottomScreenY(AdvancedRemoteNodeRowLayout row) {
        return row.coordTextScreenY() + BTN_H;
    }

    private int advancedCapLabelTopScreenY() {
        return this.topPos
                + ADVANCED_CAP_NUMERIC_ROW_GUI_Y
                - this.font.lineHeight
                - AMOUNT_LABEL_ABOVE_GAP;
    }

    private int remoteNodeIgnoreChannelButtonScreenY(AdvancedRemoteNodeRowLayout row) {
        int y = remoteNodeCoordRowBottomScreenY(row) + ADVANCED_REMOTE_NODE_LINE_GAP_Y;
        if (showsAdvancedCapEditors()) {
            int maxTop =
                    advancedCapLabelTopScreenY()
                            - BTN_H
                            - ADVANCED_REMOTE_IGNORE_CHANNEL_CAP_LABEL_GAP;
            y = Math.min(y, maxTop);
        }
        return y;
    }

    private void layoutRemoteNodeCoordEditor(AdvancedRemoteNodeRowLayout row) {
        if (remoteNodeCoordXEditBox == null) {
            return;
        }
        String labelX =
                Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.axis.x")
                        .getString();
        String labelY =
                Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.axis.y")
                        .getString();
        String labelZ =
                Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.axis.z")
                        .getString();
        int labelW =
                Math.max(
                        this.font.width(labelX),
                        Math.max(this.font.width(labelY), this.font.width(labelZ)));
        int groupW = labelW + REMOTE_NODE_AXIS_LABEL_GAP + REMOTE_NODE_COORD_EDIT_W;
        int actionW = AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        int totalW = groupW * 3 + AMOUNT_INNER_GAP * 2 + AMOUNT_INNER_GAP + actionW;
        int rowY = row.coordTextScreenY();
        int startX = this.leftPos + (TEXTURE_WIDTH - totalW) / 2;
        remoteNodeCoordAxisLabelScreenX = startX;

        int cursor = startX;
        remoteNodeCoordXEditBox.setPosition(cursor + labelW + REMOTE_NODE_AXIS_LABEL_GAP, rowY);
        remoteNodeCoordXEditBox.setWidth(REMOTE_NODE_COORD_EDIT_W);
        remoteNodeCoordXEditBox.setHeight(BTN_H);
        cursor += groupW + AMOUNT_INNER_GAP;

        remoteNodeCoordYEditBox.setPosition(cursor + labelW + REMOTE_NODE_AXIS_LABEL_GAP, rowY);
        remoteNodeCoordYEditBox.setWidth(REMOTE_NODE_COORD_EDIT_W);
        remoteNodeCoordYEditBox.setHeight(BTN_H);
        cursor += groupW + AMOUNT_INNER_GAP;

        remoteNodeCoordZEditBox.setPosition(cursor + labelW + REMOTE_NODE_AXIS_LABEL_GAP, rowY);
        remoteNodeCoordZEditBox.setWidth(REMOTE_NODE_COORD_EDIT_W);
        remoteNodeCoordZEditBox.setHeight(BTN_H);

        int actionX = cursor + groupW + AMOUNT_INNER_GAP;
        remoteNodeCoordApplyButton.setPosition(actionX, rowY);
        remoteNodeCoordApplyButton.setWidth(AMOUNT_ACTION_BTN);
        remoteNodeCoordApplyButton.setHeight(BTN_H);
        remoteNodeCoordUndoButton.setPosition(actionX + AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP, rowY);
        remoteNodeCoordUndoButton.setWidth(AMOUNT_ACTION_BTN);
        remoteNodeCoordUndoButton.setHeight(BTN_H);
    }

    private void syncRemoteNodeFaceButtonDisplay() {
        if (remoteNodeFaceButton == null) {
            return;
        }
        boolean remoteNodeBound = editModeRemoteNodeDraft != null;
        remoteNodeFaceButton.active = remoteNodeBound;
        remoteNodeFaceButton.setSelection(
                remoteNodeBound,
                remoteNodeBound ? editModeRemoteNodeDraft.face() : null,
                remoteNodeBound && editModeRemoteAnyFaceDraft);
    }

    private void syncRemoteNodeCoordEditButtonDisplay() {
        if (remoteNodeCoordEditButton == null) {
            return;
        }
        boolean remoteNodeBound = editModeRemoteNodeDraft != null;
        remoteNodeCoordEditButton.visible = showsAdvancedRemoteNodeBlock();
        remoteNodeCoordEditButton.active = remoteNodeBound && !remoteNodeCoordEditOpen;
    }

    private void syncRemoteNodeCoordEditBoxes() {
        if (remoteNodeCoordXEditBox == null || editModeRemoteNodeDraft == null) {
            return;
        }
        BlockPos p = editModeRemoteNodeDraft.pos();
        remoteNodeCoordXEditBox.setValue(Integer.toString(p.getX()));
        remoteNodeCoordYEditBox.setValue(Integer.toString(p.getY()));
        remoteNodeCoordZEditBox.setValue(Integer.toString(p.getZ()));
    }

    private void syncRemoteIgnoreChannelButtonDisplay() {
        if (remoteIgnoreChannelButton == null) {
            return;
        }
        boolean remoteNodeBound = editModeRemoteNodeDraft != null;
        remoteIgnoreChannelButton.active = remoteNodeBound;
        remoteIgnoreChannelButton.setMessage(
                Component.translatable(
                        remoteNodeBound && editModeRemoteIgnoreChannelDraft
                                ? "gui.another_dynamics.duct_node.remote_node.ignore_channel.ignore"
                                : "gui.another_dynamics.duct_node.remote_node.ignore_channel.respect"));
        remoteIgnoreChannelButton.setWidth(remoteIgnoreChannelButtonWidth());
        layoutAdvancedRemoteNodeBlock();
    }

    private void handleRemoteNodeFaceChanged(RemoteNodeFaceButton.FaceSelection selection) {
        if (editModeRemoteNodeDraft == null || editModeFilterIndex < 0) {
            return;
        }
        editModeRemoteAnyFaceDraft = selection.anyFace();
        if (!selection.anyFace() && selection.face() != null) {
            editModeRemoteNodeDraft =
                    new DuctDirectionalEndpoint(editModeRemoteNodeDraft.pos(), selection.face());
        }
        commitRemoteNodeDraftToLine();
        syncRemoteNodeFaceButtonDisplay();
        pushFiltersToServer();
        playClickSound();
    }

    private void openRemoteNodeCoordEdit() {
        if (editModeRemoteNodeDraft == null || remoteNodeCoordEditOpen) {
            return;
        }
        playClickSound();
        BlockPos p = editModeRemoteNodeDraft.pos();
        remoteNodeCoordSnapshotX = p.getX();
        remoteNodeCoordSnapshotY = p.getY();
        remoteNodeCoordSnapshotZ = p.getZ();
        remoteNodeCoordEditOpen = true;
        syncRemoteNodeCoordEditBoxes();
        layoutAdvancedRemoteNodeBlock();
        applySubViewVisibility();
    }

    private void closeRemoteNodeCoordEdit(boolean restoreSnapshot) {
        if (!remoteNodeCoordEditOpen) {
            return;
        }
        if (restoreSnapshot && editModeRemoteNodeDraft != null) {
            editModeRemoteNodeDraft =
                    new DuctDirectionalEndpoint(
                            new BlockPos(
                                    remoteNodeCoordSnapshotX,
                                    remoteNodeCoordSnapshotY,
                                    remoteNodeCoordSnapshotZ),
                            editModeRemoteNodeDraft.face());
            syncRemoteNodeCoordEditBoxes();
        }
        remoteNodeCoordEditOpen = false;
        if (remoteNodeCoordXEditBox != null) {
            remoteNodeCoordXEditBox.setFocused(false);
            remoteNodeCoordYEditBox.setFocused(false);
            remoteNodeCoordZEditBox.setFocused(false);
        }
        layoutAdvancedRemoteNodeBlock();
        applySubViewVisibility();
    }

    private void undoRemoteNodeCoordEdit() {
        if (!remoteNodeCoordEditOpen) {
            return;
        }
        playClickSound();
        closeRemoteNodeCoordEdit(true);
    }

    private boolean popAdvancedFilterSubLevel() {
        if (remoteNodeCoordEditOpen) {
            undoRemoteNodeCoordEdit();
            return true;
        }
        return false;
    }

    private void applyRemoteNodeCoordEdit() {
        if (!remoteNodeCoordEditOpen || editModeRemoteNodeDraft == null || editModeFilterIndex < 0) {
            return;
        }
        Integer x = parseCoordEditBox(remoteNodeCoordXEditBox);
        Integer y = parseCoordEditBox(remoteNodeCoordYEditBox);
        Integer z = parseCoordEditBox(remoteNodeCoordZEditBox);
        if (x == null || y == null || z == null) {
            return;
        }
        playClickSound();
        editModeRemoteNodeDraft =
                new DuctDirectionalEndpoint(
                        new BlockPos(x, y, z), editModeRemoteNodeDraft.face());
        remoteNodeCoordSnapshotX = x;
        remoteNodeCoordSnapshotY = y;
        remoteNodeCoordSnapshotZ = z;
        commitRemoteNodeDraftToLine();
        remoteNodeCoordEditOpen = false;
        if (remoteNodeCoordXEditBox != null) {
            remoteNodeCoordXEditBox.setFocused(false);
            remoteNodeCoordYEditBox.setFocused(false);
            remoteNodeCoordZEditBox.setFocused(false);
        }
        layoutAdvancedRemoteNodeBlock();
        applySubViewVisibility();
        pushFiltersToServer();
    }

    private static @Nullable Integer parseCoordEditBox(@Nullable EditBox box) {
        if (box == null) {
            return null;
        }
        String t = box.getValue().trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return (int) Mth.clamp(Long.parseLong(t), Integer.MIN_VALUE, Integer.MAX_VALUE);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int centeredPanelTextScreenX(String text) {
        return this.leftPos + (TEXTURE_WIDTH - this.font.width(text)) / 2;
    }

    private String truncateRemoteNodeCoordText(String plain) {
        return truncateRemoteNodeCoordText(plain, ADVANCED_REMOTE_NODE_COORD_MAX_WIDTH);
    }

    private String truncateRemoteNodeCoordText(String plain, int maxWidth) {
        if (this.font.width(plain) <= maxWidth) {
            return plain;
        }
        return this.font.plainSubstrByWidth(plain, maxWidth - this.font.width("...")) + "...";
    }

    /** Subview that owns the filter line list / allow vs deny semantics (before help overlay). */
    private SubView effectiveFilterLineSubview() {
        if (subView == SubView.HOW_TO_USE) {
            return filterListBeforeHelp;
        }
        if (subView == SubView.ADVANCED_FILTERING) {
            return advancedFilterParentSubview;
        }
        return subView;
    }

    private boolean isEditingAllowFilterList() {
        return effectiveFilterLineSubview() == SubView.ALLOW_FILTERS;
    }

    private boolean isAllowOrDenyFilterListContext() {
        return (
            subView == SubView.ALLOW_FILTERS || subView == SubView.DENY_FILTERS
        );
    }

    /** Ghost calibration slot: allow/deny line edit or {@link SubView#ADVANCED_FILTERING} entry editor. */
    private boolean isGhostSlotJeiContext() {
        return inEditMode()
                && (isAllowOrDenyFilterListContext() || subView == SubView.ADVANCED_FILTERING);
    }

    private int amountBlockLayoutKey(NodeMode nm, HybridPanel hybrid) {
        int transport = menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
        int mode = amountFieldEditsPriority() ? 1 : 0;
        return (nm.ordinal() << 10) | (hybrid.ordinal() << 6) | (transport << 1) | mode;
    }

    /**
     * Whether the main numeric field edits insertion priority ({@link DuctMenuSync#PRIORITY}) vs extract/retrieve batch
     * ({@link DuctMenuSync#AMOUNT_FIELD}). In {@link NodeMode#EXTRACTION_FILTERING}, the filtering sub-panel edits
     * priority; the extractor sub-panel edits batch.
     */
    private boolean amountFieldEditsPriority() {
        if (isEnergyOrHeatTransport()) {
            return true;
        }
        NodeMode nm = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        if (
            nm == NodeMode.EXTRACTION_FILTERING &&
            hybridPanel == HybridPanel.FILTERING
        ) {
            return true;
        }
        return nm.usesInsertionPriorityField();
    }

    /** Repositions priority/quantity widgets when {@link NodeMode} or hybrid panel toggles batch vs priority (M button slot). */
    private void layoutAmountBlock() {
        NodeMode nm = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        boolean priorityField = amountFieldEditsPriority();
        boolean showMax = nm.usesExtractBatchField() && !priorityField;

        int numericRowW =
            AMOUNT_STEPPER_W +
            AMOUNT_INNER_GAP +
            AMOUNT_EDIT_W +
            AMOUNT_INNER_GAP +
            AMOUNT_STEPPER_W;
        int actionRowW = amountActionRowWidth(showMax);

        int blockW = Math.max(numericRowW, actionRowW);
        int blockGuiX = (TEXTURE_WIDTH - blockW) / 2;
        int numericGuiX = blockGuiX + (blockW - numericRowW) / 2;

        int amX = this.leftPos + numericGuiX;
        int amY = this.topPos + AMOUNT_ROW_Y;
        routingMinusButton.setPosition(amX, amY);
        routingMinusButton.setWidth(AMOUNT_STEPPER_W);
        routingMinusButton.setHeight(BTN_H);

        int boxX = amX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        amountEditBoxGuiLeft =
            numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        routingPriorityBox.setPosition(boxX, amY);
        routingPriorityBox.setWidth(AMOUNT_EDIT_W);
        routingPriorityBox.setHeight(BTN_H);

        int plusX = boxX + AMOUNT_EDIT_W + AMOUNT_INNER_GAP;
        routingPlusButton.setPosition(plusX, amY);
        routingPlusButton.setWidth(AMOUNT_STEPPER_W);
        routingPlusButton.setHeight(BTN_H);

        // Center 0 / A / (M) / ✕ under the edit field (same rule as advanced cap editors).
        int actY = amY + BTN_H + AMOUNT_ROWS_GAP;
        int ax = boxX + (AMOUNT_EDIT_W - actionRowW) / 2;
        positionAmountActionButton(amountClearButton, ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        positionAmountActionButton(amountApplyButton, ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        if (showMax) {
            positionAmountActionButton(amountMaxButton, ax, actY);
            ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        }
        positionAmountActionButton(amountDiscardButton, ax, actY);
    }

    /** Centered Limit/Keep editor (same geometry as {@link #layoutAmountBlock}). */
    private void layoutAdvancedCapBlock() {
        boolean advanced = isAdvancedFilterCapSubview();
        DuctFaceNode.EligibilityMode em =
            DuctFaceNode.EligibilityMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
            );
        boolean filter =
            advanced && activeFilterBank == DuctFaceNode.FilterBank.FILTER;
        boolean filterBoth = filter && em == DuctFaceNode.EligibilityMode.BOTH;
        boolean filterRetrieveOnly =
            filter && em == DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;

        int editW = AMOUNT_EDIT_W;
        int numericRowW1 =
            AMOUNT_STEPPER_W +
            AMOUNT_INNER_GAP +
            editW +
            AMOUNT_INNER_GAP +
            AMOUNT_STEPPER_W;
        int actionRowW1 =
            AMOUNT_ACTION_BTN +
            AMOUNT_BTN_GAP +
            AMOUNT_ACTION_BTN +
            AMOUNT_BTN_GAP +
            AMOUNT_ACTION_BTN;
        int pairGap = filterBoth ? 18 : 0;
        int numericRowW = filterBoth
            ? (numericRowW1 * 2 + pairGap)
            : numericRowW1;
        int blockW = numericRowW;
        int blockGuiX = (TEXTURE_WIDTH - blockW) / 2;
        int numericGuiX = blockGuiX + (blockW - numericRowW) / 2;

        int capRowGuiY = isAdvancedFilterCapSubview()
            ? ADVANCED_CAP_NUMERIC_ROW_GUI_Y
            : AMOUNT_ROW_Y;
        int amX = this.leftPos + numericGuiX;
        int amY = this.topPos + capRowGuiY;
        advCapMinusButton.setPosition(amX, amY);

        int boxX = amX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        advCapEditBoxGuiLeft =
            numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        advCapEditBox.setPosition(boxX, amY);
        advCapEditBox.setWidth(editW);

        int plusX = boxX + editW + AMOUNT_INNER_GAP;
        advCapPlusButton.setPosition(plusX, amY);

        int actY = amY + BTN_H + AMOUNT_ROWS_GAP;
        int editCenterX = boxX + editW / 2;
        int ax = editCenterX - actionRowW1 / 2;
        advCapInfinityButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        advCapApplyButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        advCapUndoButton.setPosition(ax, actY);

        if (filterBoth) {
            int offsetX = numericRowW1 + pairGap;
            int amX2 = amX + offsetX;
            int boxX2 = amX2 + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
            advCap2EditBoxGuiLeft =
                numericGuiX + offsetX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
            int plusX2 = boxX2 + editW + AMOUNT_INNER_GAP;

            advCap2MinusButton.setPosition(amX2, amY);
            advCap2EditBox.setPosition(boxX2, amY);
            advCap2EditBox.setWidth(editW);
            advCap2PlusButton.setPosition(plusX2, amY);

            int editCenterX2 = boxX2 + editW / 2;
            int ax2 = editCenterX2 - actionRowW1 / 2;
            advCap2InfinityButton.setPosition(ax2, actY);
            ax2 += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
            advCap2ApplyButton.setPosition(ax2, actY);
            ax2 += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
            advCap2UndoButton.setPosition(ax2, actY);
        } else if (filterRetrieveOnly) {
            // In retrieve-only, the Keep configurator is the primary one and must be centered.
            advCap2MinusButton.setPosition(amX, amY);
            advCap2EditBoxGuiLeft =
                numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
            advCap2EditBox.setPosition(boxX, amY);
            advCap2EditBox.setWidth(editW);
            advCap2PlusButton.setPosition(plusX, amY);

            int ax2 = editCenterX - actionRowW1 / 2;
            advCap2InfinityButton.setPosition(ax2, actY);
            ax2 += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
            advCap2ApplyButton.setPosition(ax2, actY);
            ax2 += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
            advCap2UndoButton.setPosition(ax2, actY);
        } else {
            // For non-both cases, keep widgets are hidden by visibility logic; still reset widths.
            advCap2EditBox.setWidth(AMOUNT_EDIT_W);
        }
    }

    private boolean isEnergyBufferLimitsSubview() {
        return subView == SubView.BUFFER_LIMITS;
    }

    private boolean isExtractOnlyEnergyFace() {
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        if (nm == NodeMode.EXTRACTION) {
            return true;
        }
        return nm == NodeMode.EXTRACTION_FILTERING && hybridPanel == HybridPanel.EXTRACTOR;
    }

    private void openEnergyBufferSubview() {
        exitEditMode(false);
        subView = SubView.BUFFER_LIMITS;
        syncEnergyBufFromServer();
        energyBufDirty = false;
        applySubViewVisibility();
    }

    private void closeEnergyBufferSubview() {
        exitEditMode(false);
        subView = SubView.MAIN;
        applySubViewVisibility();
    }

    private void syncEnergyBufFromServer() {
        syncingEnergyBufFromServer = true;
        energyBufExtractCommitted = menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_LIMIT_EXTRACT);
        energyBufInsertCommitted = menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_LIMIT_INSERT);
        energyBufExtractDraft = energyBufExtractCommitted;
        energyBufInsertDraft = energyBufInsertCommitted;
        syncEnergyBufExtractBoxDisplay();
        syncEnergyBufInsertBoxDisplay();
        syncingEnergyBufFromServer = false;
    }

    private void syncEnergyBufExtractBoxDisplay() {
        if (energyBufExtractBox == null) {
            return;
        }
        if (energyBufExtractBox.isFocused()) {
            return;
        }
        energyBufExtractBox.setValue(
                energyBufExtractDraft <= 0 ? "AUTO" : Integer.toString(energyBufExtractDraft));
    }

    private void syncEnergyBufInsertBoxDisplay() {
        if (energyBufInsertBox == null) {
            return;
        }
        if (energyBufInsertBox.isFocused()) {
            return;
        }
        energyBufInsertBox.setValue(
                energyBufInsertDraft <= 0 ? "AUTO" : Integer.toString(energyBufInsertDraft));
    }

    private void parseEnergyBufExtractBox(String v) {
        if (syncingEnergyBufFromServer) {
            return;
        }
        energyBufDirty = true;
        String t = v.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("auto")) {
            energyBufExtractDraft = 0;
            return;
        }
        try {
            energyBufExtractDraft =
                    (int) Mth.clamp(Long.parseLong(t), 0L, Integer.MAX_VALUE);
        } catch (NumberFormatException ignored) {}
    }

    private void parseEnergyBufInsertBox(String v) {
        if (syncingEnergyBufFromServer) {
            return;
        }
        energyBufDirty = true;
        String t = v.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("auto")) {
            energyBufInsertDraft = 0;
            return;
        }
        try {
            energyBufInsertDraft =
                    (int) Mth.clamp(Long.parseLong(t), 0L, Integer.MAX_VALUE);
        } catch (NumberFormatException ignored) {}
    }

    private void adjustEnergyBufExtract(int delta) {
        playClickSound();
        energyBufDirty = true;
        long next = (long) energyBufExtractDraft + delta;
        energyBufExtractDraft = (int) Mth.clamp(next, 0L, Integer.MAX_VALUE);
        syncEnergyBufExtractBoxDisplay();
    }

    private void adjustEnergyBufInsert(int delta) {
        if (isExtractOnlyEnergyFace()) {
            return;
        }
        playClickSound();
        energyBufDirty = true;
        long next = (long) energyBufInsertDraft + delta;
        energyBufInsertDraft = (int) Mth.clamp(next, 0L, Integer.MAX_VALUE);
        syncEnergyBufInsertBoxDisplay();
    }

    private void applyEnergyBufferLimits() {
        playClickSound();
        if (energyBufExtractBox != null) {
            parseEnergyBufExtractBox(energyBufExtractBox.getValue());
        }
        if (energyBufInsertBox != null && !isExtractOnlyEnergyFace()) {
            parseEnergyBufInsertBox(energyBufInsertBox.getValue());
        }
        ModNetwork.sendEnergyBufferLimits(
                menuSyncedPos(),
                menuSyncedFace(),
                energyBufExtractDraft,
                isExtractOnlyEnergyFace() ? energyBufInsertCommitted : energyBufInsertDraft);
        energyBufExtractCommitted = energyBufExtractDraft;
        energyBufInsertCommitted = isExtractOnlyEnergyFace() ? energyBufInsertCommitted : energyBufInsertDraft;
        energyBufDirty = false;
    }

    private void cancelEnergyBufferDraft() {
        playClickSound();
        energyBufExtractDraft = energyBufExtractCommitted;
        energyBufInsertDraft = energyBufInsertCommitted;
        energyBufDirty = false;
        syncEnergyBufExtractBoxDisplay();
        syncEnergyBufInsertBoxDisplay();
    }

    private void layoutEnergyBufferBlock() {
        int editW = AMOUNT_EDIT_W;
        int numericRowW1 =
                AMOUNT_STEPPER_W + AMOUNT_INNER_GAP + editW + AMOUNT_INNER_GAP + AMOUNT_STEPPER_W;
        int actionRowW1 =
                AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        int pairGap = 18;
        int numericRowW = numericRowW1 * 2 + pairGap;
        int blockW = numericRowW;
        int blockGuiX = (TEXTURE_WIDTH - blockW) / 2;
        int numericGuiX = blockGuiX + (blockW - numericRowW) / 2;
        int amY = this.topPos + ENERGY_BUF_LIMIT_ROW_Y;
        int amX = this.leftPos + numericGuiX;
        int boxX = amX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        int plusX = boxX + editW + AMOUNT_INNER_GAP;

        energyBufExtractEditGuiLeft = numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        energyBufExtractMinus.setPosition(amX, amY);
        energyBufExtractBox.setPosition(boxX, amY);
        energyBufExtractBox.setWidth(editW);
        energyBufExtractPlus.setPosition(plusX, amY);

        int actY = amY + BTN_H + AMOUNT_ROWS_GAP;
        int editCenterX = boxX + editW / 2;
        int ax = editCenterX - actionRowW1 / 2;
        energyBufExtractAutoButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        energyBufExtractApplyButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        energyBufExtractUndoButton.setPosition(ax, actY);

        int offsetX = numericRowW1 + pairGap;
        int amX2 = amX + offsetX;
        int boxX2 = amX2 + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        int plusX2 = boxX2 + editW + AMOUNT_INNER_GAP;
        energyBufInsertEditGuiLeft =
                numericGuiX + numericRowW1 + pairGap + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        energyBufInsertMinus.setPosition(amX2, amY);
        energyBufInsertBox.setPosition(boxX2, amY);
        energyBufInsertBox.setWidth(editW);
        energyBufInsertPlus.setPosition(plusX2, amY);

        int editCenterX2 = boxX2 + editW / 2;
        int ax2 = editCenterX2 - actionRowW1 / 2;
        energyBufInsertAutoButton.setPosition(ax2, actY);
        ax2 += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        energyBufInsertApplyButton.setPosition(ax2, actY);
        ax2 += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        energyBufInsertUndoButton.setPosition(ax2, actY);
    }

    private static int energyBufferFillPercent(int stored, int cap) {
        if (cap <= 0) {
            return stored > 0 ? 100 : 0;
        }
        return Mth.clamp((int) ((stored * 100L) / cap), 0, 100);
    }

    private void renderEnergyBufferColumnLabels(GuiGraphicsExtractor graphics) {
        int editW = AMOUNT_EDIT_W;
        int storedIn = Math.max(0, menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_INPUT_STORED));
        int capIn = Math.max(0, menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_INPUT_CAP));
        int storedOut = Math.max(0, menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_OUTPUT_STORED));
        int capOut = Math.max(0, menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_OUTPUT_CAP));
        int pctIn = energyBufferFillPercent(storedIn, capIn);
        int pctOut = energyBufferFillPercent(storedOut, capOut);

        drawCenteredEnergyBufferLabel(
                graphics,
                Component.translatable("gui.another_dynamics.duct_node.energy_buffer.extract"),
                energyBufExtractEditGuiLeft,
                editW,
                ENERGY_BUF_NAME_Y);
        drawCenteredEnergyBufferLabel(
                graphics,
                Component.translatable(
                        "gui.another_dynamics.duct_node.energy_buffer.fill_percent", pctIn),
                energyBufExtractEditGuiLeft,
                editW,
                ENERGY_BUF_FILL_Y);

        int insertColor = isExtractOnlyEnergyFace() ? 0xFF808080 : 0xFF404040;
        drawCenteredEnergyBufferLabel(
                graphics,
                Component.translatable("gui.another_dynamics.duct_node.energy_buffer.insert"),
                energyBufInsertEditGuiLeft,
                editW,
                ENERGY_BUF_NAME_Y,
                insertColor);
        drawCenteredEnergyBufferLabel(
                graphics,
                Component.translatable(
                        "gui.another_dynamics.duct_node.energy_buffer.fill_percent", pctOut),
                energyBufInsertEditGuiLeft,
                editW,
                ENERGY_BUF_FILL_Y,
                insertColor);
    }

    private void drawCenteredEnergyBufferLabel(
            GuiGraphicsExtractor graphics,
            Component text,
            int editGuiLeft,
            int editW,
            int guiY,
            int color) {
        int lw = this.font.width(text);
        int x = editGuiLeft + (editW - lw) / 2;
        graphics.text(this.font, text, x, guiY, color, false);
    }

    private void drawCenteredEnergyBufferLabel(
            GuiGraphicsExtractor graphics, Component text, int editGuiLeft, int editW, int guiY) {
        drawCenteredEnergyBufferLabel(graphics, text, editGuiLeft, editW, guiY, 0xFF404040);
    }

    private void applyFilterEntryEditBoxTextStyle() {
        if (editModeTextBox == null) {
            return;
        }
        editModeTextBox.setTextColor(FILTER_ENTRY_EDIT_TEXT_COLOR);
    }

    /**
     * Pushes the current list line into the filter {@link EditBox} and {@link #originalFilterValue}. Use when opening
     * advanced or after edit widgets are laid out so text renders without an extra click. Optionally focuses the field
     * (non-advanced edit) so the client draws the value immediately.
     */
    private void reloadFilterEntryTextBoxFromList(boolean grabFilterFocus) {
        if (editModeTextBox == null || editModeFilterIndex < 0) {
            return;
        }
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        List<String> list = getEditingList();
        while (list.size() <= editModeFilterIndex) {
            list.add("");
        }
        String line =
            list.get(editModeFilterIndex) != null
                ? list.get(editModeFilterIndex)
                : "";
        originalFilterValue = line;
        editModeTextBox.setValue(line);
        editModeTextBox.setCursorPosition(0);
        editModeTextBox.setHighlightPos(0);
        applyFilterEntryEditBoxTextStyle();
        if (grabFilterFocus) {
            editModeTextBox.setFocused(true);
        }
    }

    private void openAdvancedFiltering() {
        SubView parent = effectiveFilterLineSubview();
        if (editModeFilterIndex < 0) {
            return;
        }
        if (parent == SubView.ALLOW_FILTERS) {
            // Limit / Keep advanced editor.
        } else if (parent == SubView.DENY_FILTERS && filterRemoteNodeUiEnabled()) {
            // Deny advanced: remote node binding only.
        } else {
            return;
        }
        closeRemoteNodeCoordEdit(false);
        advancedFilterParentSubview = parent;
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        if (editModeTextBox != null) {
            String value = editModeTextBox.getValue();
            List<String> list = getEditingList();
            while (list.size() <= editModeFilterIndex) {
                list.add("");
            }
            list.set(editModeFilterIndex, value);
            originalFilterValue = value;
        }
        if (parent == SubView.ALLOW_FILTERS) {
            List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
            while (caps.size() <= editModeFilterIndex) {
                caps.add(0);
            }
            editModeAllowCapValue = caps.get(editModeFilterIndex);
            originalAllowCapValue = editModeAllowCapValue;
            if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
                List<Integer> caps2 = menu.getClientFilterKeepCaps();
                while (caps2.size() <= editModeFilterIndex) {
                    caps2.add(0);
                }
                editModeAllowCap2Value = caps2.get(editModeFilterIndex);
                originalAllowCap2Value = editModeAllowCap2Value;
            }
        }
        if (filterRemoteNodeUiEnabled()) {
            editModeRemoteNodeDraft = remoteNodeAt(editModeFilterIndex);
            originalRemoteNodeValue = editModeRemoteNodeDraft;
            editModeRemoteIgnoreChannelDraft = remoteIgnoreChannelAt(editModeFilterIndex);
            originalRemoteIgnoreChannelValue = editModeRemoteIgnoreChannelDraft;
            editModeRemoteAnyFaceDraft = remoteAnyFaceAt(editModeFilterIndex);
            originalRemoteAnyFaceValue = editModeRemoteAnyFaceDraft;
            if (editModeRemoteNodeDraft == null) {
                editModeRemoteIgnoreChannelDraft = false;
                originalRemoteIgnoreChannelValue = false;
                editModeRemoteAnyFaceDraft = false;
                originalRemoteAnyFaceValue = false;
                setRemoteIgnoreChannelAt(editModeFilterIndex, false);
                setRemoteAnyFaceAt(editModeFilterIndex, false);
            }
        } else {
            editModeRemoteNodeDraft = null;
            originalRemoteNodeValue = null;
            editModeRemoteIgnoreChannelDraft = false;
            originalRemoteIgnoreChannelValue = false;
            editModeRemoteAnyFaceDraft = false;
            originalRemoteAnyFaceValue = false;
        }
        subView = SubView.ADVANCED_FILTERING;
        reloadFilterEntryTextBoxFromList(false);
        layoutAdvancedCapBlock();
        layoutEditModeWidgets();
        layoutFilterNavAndHelpButtons();
        syncAllowCapEditBoxDisplay();
        syncAllowCap2EditBoxDisplay();
        if (advCapEditBox != null) {
            advCapEditHadFocus = advCapEditBox.isFocused();
        }
        layoutAdvancedRemoteNodeBlock();
        syncRemoteIgnoreChannelButtonDisplay();
        syncRemoteNodeFaceButtonDisplay();
        syncRemoteNodeCoordEditButtonDisplay();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    private void closeAdvancedFiltering() {
        if (subView != SubView.ADVANCED_FILTERING) {
            return;
        }
        closeRemoteNodeCoordEdit(false);
        if (advCapEditBox != null) {
            advCapEditBox.setFocused(false);
        }
        persistFilterEditDraftToClientBuffers(true);
        subView = advancedFilterParentSubview;
        layoutAdvancedCapBlock();
        layoutAdvancedRemoteNodeBlock();
        layoutEditModeWidgets();
        layoutFilterNavAndHelpButtons();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    private void applySubViewVisibility() {
        boolean main = subView == SubView.MAIN;
        boolean advancedFiltering = subView == SubView.ADVANCED_FILTERING;
        boolean bufferLimits = isEnergyBufferLimitsSubview();
        boolean filterList =
            subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS;
        boolean howto = subView == SubView.HOW_TO_USE;
        boolean edit = inEditMode();
        boolean filterListOnlyEditor = isSettingsCopierFilterListEditor();
        NodeMode nm = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        boolean inHybridSelector =
            nm.isHybrid() && hybridPanel == HybridPanel.NONE;
        boolean showMainStyleChrome = main || advancedFiltering || bufferLimits;
        boolean multiTransport =
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1;
        boolean hubLayer =
            multiTransport &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0;
        boolean detailMain = (main || bufferLimits) && !hubLayer;

        boolean showHubTransportToggles = hubLayer && main;
        for (Button b : transportToggleButtons) {
            b.visible = showHubTransportToggles;
        }

        denyNavButton.visible = detailMain && !filterListOnlyEditor;
        listLogicButton.visible = detailMain && !filterListOnlyEditor;
        allowNavButton.visible = detailMain && !filterListOnlyEditor;
        routingModeButton.visible =
            showMainStyleChrome && !howto && !advancedFiltering && !hubLayer;

        boolean showAmountBlock = (detailMain && !howto && !inHybridSelector && !bufferLimits);
        boolean showEnergyBufBlock = bufferLimits;
        boolean extractOnlyEnergy = showEnergyBufBlock && isExtractOnlyEnergyFace();
        energyBufExtractMinus.visible = showEnergyBufBlock;
        energyBufExtractPlus.visible = showEnergyBufBlock;
        energyBufExtractBox.visible = showEnergyBufBlock;
        energyBufExtractAutoButton.visible = showEnergyBufBlock;
        energyBufExtractApplyButton.visible = showEnergyBufBlock;
        energyBufExtractUndoButton.visible = showEnergyBufBlock;
        energyBufInsertMinus.visible = showEnergyBufBlock && !extractOnlyEnergy;
        energyBufInsertPlus.visible = showEnergyBufBlock && !extractOnlyEnergy;
        energyBufInsertBox.visible = showEnergyBufBlock;
        energyBufInsertAutoButton.visible = showEnergyBufBlock && !extractOnlyEnergy;
        energyBufInsertApplyButton.visible = showEnergyBufBlock && !extractOnlyEnergy;
        energyBufInsertUndoButton.visible = showEnergyBufBlock && !extractOnlyEnergy;
        if (energyBufInsertBox != null) {
            energyBufInsertBox.setEditable(!extractOnlyEnergy);
            energyBufInsertBox.setTextColor(extractOnlyEnergy ? 0xFF808080 : FILTER_ENTRY_EDIT_TEXT_COLOR);
        }
        routingMinusButton.visible = showAmountBlock;
        routingPlusButton.visible = showAmountBlock;
        routingPriorityBox.visible = showAmountBlock;
        amountClearButton.visible = showAmountBlock;
        amountApplyButton.visible = showAmountBlock;
        NodeMode amountNodeMode = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        amountMaxButton.visible =
            showAmountBlock &&
            amountNodeMode.usesExtractBatchField() &&
            !amountFieldEditsPriority();
        amountDiscardButton.visible = showAmountBlock;

        boolean showAdvCapBlock = showsAdvancedCapEditors();
        advCapMinusButton.visible = showAdvCapBlock;
        advCapPlusButton.visible = showAdvCapBlock;
        advCapEditBox.visible = showAdvCapBlock;
        advCapInfinityButton.visible = showAdvCapBlock;
        advCapApplyButton.visible = showAdvCapBlock;
        advCapUndoButton.visible = showAdvCapBlock;
        boolean filterBoth =
            showAdvCapBlock &&
            activeFilterBank == DuctFaceNode.FilterBank.FILTER &&
            DuctFaceNode.EligibilityMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
            ) ==
            DuctFaceNode.EligibilityMode.BOTH;
        boolean filterInsertOnly =
            showAdvCapBlock &&
            activeFilterBank == DuctFaceNode.FilterBank.FILTER &&
            DuctFaceNode.EligibilityMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
            ) ==
            DuctFaceNode.EligibilityMode.INSERT_ONLY;
        boolean filterRetrieveOnly =
            showAdvCapBlock &&
            activeFilterBank == DuctFaceNode.FilterBank.FILTER &&
            DuctFaceNode.EligibilityMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
            ) ==
            DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;

        // Primary cap editor acts as Limit for FILTER, otherwise as Keep/Limit depending on bank.
        if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
            advCapMinusButton.visible = filterBoth || filterInsertOnly;
            advCapPlusButton.visible = filterBoth || filterInsertOnly;
            advCapEditBox.visible = filterBoth || filterInsertOnly;
            advCapInfinityButton.visible = filterBoth || filterInsertOnly;
            advCapApplyButton.visible = filterBoth || filterInsertOnly;
            advCapUndoButton.visible = filterBoth || filterInsertOnly;
        }

        advCap2MinusButton.visible = filterBoth || filterRetrieveOnly;
        advCap2PlusButton.visible = filterBoth || filterRetrieveOnly;
        advCap2EditBox.visible = filterBoth || filterRetrieveOnly;
        advCap2InfinityButton.visible = filterBoth || filterRetrieveOnly;
        advCap2ApplyButton.visible = filterBoth || filterRetrieveOnly;
        advCap2UndoButton.visible = filterBoth || filterRetrieveOnly;

        if (remoteIgnoreChannelButton != null) {
            boolean showRemoteBlock = showsAdvancedRemoteNodeBlock();
            remoteIgnoreChannelButton.visible = showRemoteBlock;
            if (showRemoteBlock) {
                syncRemoteIgnoreChannelButtonDisplay();
            }
        }
        if (remoteNodeFaceButton != null) {
            boolean showRemoteBlock = showsAdvancedRemoteNodeBlock();
            remoteNodeFaceButton.visible = showRemoteBlock;
            if (showRemoteBlock) {
                syncRemoteNodeFaceButtonDisplay();
            }
        }
        syncRemoteNodeCoordEditButtonDisplay();
        boolean showCoordEditor =
                showsAdvancedRemoteNodeBlock()
                        && remoteNodeCoordEditOpen
                        && editModeRemoteNodeDraft != null;
        if (remoteNodeCoordXEditBox != null) {
            remoteNodeCoordXEditBox.visible = showCoordEditor;
            remoteNodeCoordYEditBox.visible = showCoordEditor;
            remoteNodeCoordZEditBox.visible = showCoordEditor;
            remoteNodeCoordApplyButton.visible = showCoordEditor;
            remoteNodeCoordUndoButton.visible = showCoordEditor;
        }

        // Hub layer: same widget positions as detail; only filters/routing/channel stay hidden (see detailMain).
        nodeModeButton.visible =
            showMainStyleChrome && !howto && !advancedFiltering;
        selfFeedStub.visible = false;
        opaqueRenderingButton.visible =
            (main || bufferLimits) && !howto && !advancedFiltering;

        closeButton.visible = true;
        boolean showCopierColumn = !howto && showsSettingsCopierColumn();
        boolean showChannel =
            !howto
                    && showsSettingsCopierColumn()
                    && (showsChannelLetterControl(
                                    hubLayer, filterList, advancedFiltering, bufferLimits)
                            || (hubLayer && main && multiTransport));
        if (settingsCopierSaveButton != null) {
            settingsCopierSaveButton.visible = showCopierColumn;
        }
        if (settingsCopierLoadButton != null) {
            settingsCopierLoadButton.visible = showCopierColumn;
        }
        if (channelButton != null) {
            channelButton.visible = showChannel;
            boolean hubChannelLocked = isHubChannelLocked();
            channelButton.active = showChannel && !hubChannelLocked;
            channelButton.setHubPlaceholder(hubChannelLocked);
        }
        if (redstoneModeButton != null) {
            redstoneModeButton.visible = !filterListOnlyEditor;
        }
        boolean showTransportPickers = main && hubLayer && !howto;
        for (Button b : transportKindPickerButtons) {
            b.visible = showTransportPickers;
        }
        if (hubBackButton != null) {
            hubBackButton.visible =
                detailMain && multiTransport && !howto && !advancedFiltering;
        }
        if (copierVirtualNodeBackButton != null) {
            copierVirtualNodeBackButton.visible =
                isSettingsCopierAllVirtualHub() && main && !howto && !advancedFiltering;
        }

        backButton.visible =
            howto || advancedFiltering || (filterList && !edit);
        // Valid keys should always be consultable while browsing filters (allow/deny) and in advanced filtering.
        validKeysButton.visible =
            (filterList || advancedFiltering) && !howto && !hubLayer;

        boolean showFilterEntryList = filterList;
        for (Button b : filterEditButtons) {
            b.visible = showFilterEntryList;
        }
        for (Button b : filterDeleteButtons) {
            b.visible = showFilterEntryList;
        }
        for (FilterConcatChannelButton b : filterConcatButtons) {
            b.visible = showFilterEntryList;
        }

        if (editModeTextBox != null) {
            boolean showFilterEditChrome =
                edit &&
                (subView == SubView.DENY_FILTERS ||
                    subView == SubView.ALLOW_FILTERS ||
                    subView == SubView.ADVANCED_FILTERING);
            editModeTextBox.visible = showFilterEditChrome;
            if (leftArrowButton != null) {
                leftArrowButton.visible = showFilterEditChrome;
            }
            if (rightArrowButton != null) {
                rightArrowButton.visible = showFilterEditChrome;
            }
            if (editModeClearButton != null) {
                editModeClearButton.visible = showFilterEditChrome;
            }
            if (editModeAnythingElseButton != null) {
                editModeAnythingElseButton.visible = showFilterEditChrome;
            }
            if (editModeApplyButton != null) {
                editModeApplyButton.visible = showFilterEditChrome;
            }
            if (editModeCloseButton != null) {
                editModeCloseButton.visible = showFilterEditChrome;
            }
            if (advancedFilteringOpenButton != null) {
                SubView effLine = effectiveFilterLineSubview();
                advancedFilteringOpenButton.visible =
                    showFilterEditChrome
                            && !isAdvancedFilterCapSubview()
                            && (effLine == SubView.ALLOW_FILTERS
                                    || (effLine == SubView.DENY_FILTERS
                                            && filterRemoteNodeUiEnabled()));
                advancedFilteringOpenButton.active =
                    effLine == SubView.ALLOW_FILTERS
                            || (effLine == SubView.DENY_FILTERS
                                    && filterRemoteNodeUiEnabled());
                if (advancedFilteringOpenButton.active) {
                    String tooltipKey =
                            effLine == SubView.DENY_FILTERS
                                    ? "gui.another_dynamics.duct_node.advanced_filtering.button.tooltip.deny_remote"
                                    : "gui.another_dynamics.duct_node.advanced_filtering.button.tooltip";
                    advancedFilteringOpenButton.setTooltip(
                            Tooltip.create(Component.translatable(tooltipKey)));
                }
            }
            if (editModeApplyButton != null && editModeCloseButton != null) {
                if (isAdvancedFilterCapSubview()) {
                    editModeApplyButton.setTooltip(
                        Tooltip.create(
                            Component.translatable(
                                "gui.another_dynamics.duct_node.filters.apply_keep_open.tooltip"
                            )
                        )
                    );
                    editModeCloseButton.setMessage(Component.literal("\u2715"));
                    editModeCloseButton.setTooltip(
                        Tooltip.create(
                            Component.translatable(
                                "gui.another_dynamics.duct_node.amount.undo.tooltip"
                            )
                        )
                    );
                } else {
                    editModeApplyButton.setTooltip(
                        Tooltip.create(
                            Component.translatable(
                                "gui.another_dynamics.duct_node.filters.apply"
                            )
                        )
                    );
                    editModeCloseButton.setMessage(Component.literal("\u2715"));
                    editModeCloseButton.setTooltip(
                        Tooltip.create(
                            Component.translatable(
                                "gui.another_dynamics.duct_node.filters.close_without_saving"
                            )
                        )
                    );
                }
            }
            applyFilterEntryEditBoxTextStyle();
        }

        layoutFilterNavAndHelpButtons();
        layoutOppositeFilterListButton();
        layoutMainChromeRowsForHubOrDetail();
        layoutHubTransportGrid();
        layoutHubBackButton();
        layoutCopierColumn();
        if (
            (subView == SubView.ADVANCED_FILTERING ||
                subView == SubView.ALLOW_FILTERS ||
                subView == SubView.DENY_FILTERS) &&
            editModeTextBox != null &&
            inEditMode()
        ) {
            layoutEditModeWidgets();
        }
    }

    private void rebuildFilterEntryWidgets() {
        for (Button b : filterEditButtons) {
            removeWidget(b);
        }
        filterEditButtons.clear();
        for (Button b : filterDeleteButtons) {
            removeWidget(b);
        }
        filterDeleteButtons.clear();
        for (FilterConcatChannelButton b : filterConcatButtons) {
            removeWidget(b);
        }
        filterConcatButtons.clear();

        if (!isAllowOrDenyFilterListContext()) {
            return;
        }

        int maxSlots = currentFilterMaxSlots();
        int vis = visibleFilterEntries();
        for (int i = 0; i < vis; i++) {
            int filterIndex = filterScrollOffset + i;
            if (filterIndex >= maxSlots) {
                break;
            }
            int entryX = this.leftPos + ENTRY_X;
            int entryY = this.topPos + FIRST_FILTER_ROW_Y + i * ENTRY_HEIGHT;
            int buttonSize = 12;
            int buttonMargin = 4;
            int editX = entryX + ENTRY_WIDTH - buttonMargin - buttonSize;
            int deleteX = editX - buttonSize - 2;
            int concatX = deleteX - buttonSize - 2;
            int buttonY = entryY + (ENTRY_HEIGHT - buttonSize) / 2;

            final int idx = filterIndex;
            List<Integer> concatList = getEditingConcatChannels();
            while (concatList.size() <= idx) {
                concatList.add(0);
            }
            FilterConcatChannelButton[] concatBtnRef = new FilterConcatChannelButton[1];
            concatBtnRef[0] =
                    new FilterConcatChannelButton(concatX, buttonY, buttonSize, buttonSize, v -> {
                        playClickSound();
                        concatList.set(idx, v);
                        concatBtnRef[0].setTooltip(concatChannelTooltip());
                        pushFiltersToServer();
                    });
            concatBtnRef[0].setChannelOrdinal(concatList.get(idx));
            concatBtnRef[0].setTooltip(concatChannelTooltip());
            FilterConcatChannelButton concatBtn = concatBtnRef[0];
            filterConcatButtons.add(concatBtn);
            addRenderableWidget(concatBtn);

            Button del = Button.builder(Component.literal("C"), b -> {
                playClickSound();
                getEditingList().set(idx, "");
                concatList.set(idx, 0);
                clearRemoteNodeAt(idx);
                if (effectiveFilterLineSubview() == SubView.ALLOW_FILTERS) {
                    List<Integer> caps = menu.getClientAllowCaps(
                        activeFilterBank
                    );
                    while (caps.size() <= idx) {
                        caps.add(0);
                    }
                    caps.set(idx, 0);
                }
                pushFiltersToServer();
                rebuildFilterEntryWidgets();
            })
                .bounds(deleteX, buttonY, buttonSize, buttonSize)
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.filters.clear"
                        )
                    )
                )
                .build();
            filterDeleteButtons.add(del);
            addRenderableWidget(del);

            Button ed = Button.builder(Component.literal("\u270e"), b -> {
                playClickSound();
                enterEditMode(idx);
            })
                .bounds(editX, buttonY, buttonSize, buttonSize)
                .build();
            boolean canEdit = canOpenCopierFilterEntry();
            ed.active = canEdit;
            if (!canEdit) {
                ed.setTooltip(
                        Tooltip.create(
                                Component.translatable(
                                        FilterListMaterialKind.pickBeforeEditTooltipKey())));
            }
            filterEditButtons.add(ed);
            addRenderableWidget(ed);
        }
        applySubViewVisibility();
    }

    private int currentFilterMaxSlots() {
        boolean hyb = useHybridFilterCaps();
        int raw =
            isEditingAllowFilterList()
            ? menu.filterAllowCap(hyb)
            : menu.filterDenyCap(hyb);
        return Math.max(0, raw);
    }

    /** Hybrid selector uses no filter caps; sub-panels use {@code filter.*_hybrid} from the duct datapack. */
    private boolean useHybridFilterCaps() {
        NodeMode nm = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        return nm.isHybrid() && hybridPanel != HybridPanel.NONE;
    }

    private List<String> getEditingList() {
        return isEditingAllowFilterList()
                ? menu.getClientAllowFilters(activeFilterBank)
                : menu.getClientDenyFilters(activeFilterBank);
    }

    private List<Integer> getEditingConcatChannels() {
        return isEditingAllowFilterList()
                ? menu.getClientAllowConcatChannels(activeFilterBank)
                : menu.getClientDenyConcatChannels(activeFilterBank);
    }

    private List<@Nullable DuctDirectionalEndpoint> getEditingRemoteNodes() {
        return isEditingAllowFilterList()
                ? menu.getClientAllowRemoteNodes(activeFilterBank)
                : menu.getClientDenyRemoteNodes(activeFilterBank);
    }

    private List<Boolean> getEditingRemoteIgnoreChannel() {
        return isEditingAllowFilterList()
                ? menu.getClientAllowRemoteIgnoreChannel(activeFilterBank)
                : menu.getClientDenyRemoteIgnoreChannel(activeFilterBank);
    }

    private boolean remoteIgnoreChannelAt(int index) {
        List<Boolean> flags = getEditingRemoteIgnoreChannel();
        return index >= 0 && index < flags.size() && Boolean.TRUE.equals(flags.get(index));
    }

    private void setRemoteIgnoreChannelAt(int index, boolean value) {
        List<Boolean> flags = getEditingRemoteIgnoreChannel();
        while (flags.size() <= index) {
            flags.add(false);
        }
        flags.set(index, value);
    }

    private List<Boolean> getEditingRemoteAnyFace() {
        return isEditingAllowFilterList()
                ? menu.getClientAllowRemoteAnyFace(activeFilterBank)
                : menu.getClientDenyRemoteAnyFace(activeFilterBank);
    }

    private boolean remoteAnyFaceAt(int index) {
        List<Boolean> flags = getEditingRemoteAnyFace();
        return index >= 0 && index < flags.size() && Boolean.TRUE.equals(flags.get(index));
    }

    private void setRemoteAnyFaceAt(int index, boolean value) {
        List<Boolean> flags = getEditingRemoteAnyFace();
        while (flags.size() <= index) {
            flags.add(false);
        }
        flags.set(index, value);
    }

    private boolean filterRemoteNodeUiEnabled() {
        return !DuctFeaturePolicy.isDisabled(
                DuctDefinitionRegistry.getByLogicalId(menu.getClientDuctLogicalId()).orElse(null),
                DuctFeatureKeys.FILTER_REMOTE_NODE);
    }

    private void clearRemoteNodeAt(int index) {
        List<@Nullable DuctDirectionalEndpoint> remoteNodes = getEditingRemoteNodes();
        while (remoteNodes.size() <= index) {
            remoteNodes.add(null);
        }
        remoteNodes.set(index, null);
        setRemoteIgnoreChannelAt(index, false);
        setRemoteAnyFaceAt(index, false);
    }

    private void setRemoteNodeAt(int index, @Nullable DuctDirectionalEndpoint endpoint) {
        List<@Nullable DuctDirectionalEndpoint> remoteNodes = getEditingRemoteNodes();
        while (remoteNodes.size() <= index) {
            remoteNodes.add(null);
        }
        remoteNodes.set(index, endpoint);
        if (endpoint == null) {
            setRemoteIgnoreChannelAt(index, false);
            setRemoteAnyFaceAt(index, false);
        }
    }

    @Nullable
    private DuctDirectionalEndpoint remoteNodeAt(int index) {
        List<@Nullable DuctDirectionalEndpoint> remoteNodes = getEditingRemoteNodes();
        return index >= 0 && index < remoteNodes.size() ? remoteNodes.get(index) : null;
    }

    private Component remoteNodeTooltip(@Nullable DuctDirectionalEndpoint bound) {
        if (bound == null) {
            return Component.translatable("gui.another_dynamics.duct_node.remote_node.unbound");
        }
        BlockPos p = bound.pos();
        return Component.translatable(
                "gui.another_dynamics.duct_node.remote_node.bound",
                p.getX(),
                p.getY(),
                p.getZ(),
                bound.face().getSerializedName());
    }

    private void renderAdvancedRemoteNodeBlock(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!showsAdvancedRemoteNodeBlock()) {
            return;
        }
        AdvancedRemoteNodeRowLayout row = advancedRemoteNodeRowLayout();
        int slotX = row.slotScreenX();
        int slotY = row.slotScreenY();
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            SINGLE_SLOT_REMOTE_NODE,
            slotX,
            slotY,
            0.0F,
            0.0F,
            ADVANCED_REMOTE_NODE_SLOT_SIZE,
            ADVANCED_REMOTE_NODE_SLOT_SIZE,
            ADVANCED_REMOTE_NODE_SLOT_SIZE,
            ADVANCED_REMOTE_NODE_SLOT_SIZE);
        if (editModeRemoteNodeDraft != null) {
            ItemStack icon = new ItemStack(ModItems.REMOTE_NODE_SELECTOR.get());
            icon.set(ModDataComponents.REMOTE_NODE_ENDPOINT.get(), editModeRemoteNodeDraft);
            graphics.item(icon, slotX + 1, slotY + 1);
        }
        if (!remoteNodeCoordEditOpen) {
            if (editModeRemoteNodeDraft != null) {
                String coordLine =
                        truncateRemoteNodeCoordText(
                                remoteNodeCoordLabel(editModeRemoteNodeDraft).getString());
                graphics.text(
                        this.font,
                        coordLine,
                        centeredPanelTextScreenX(coordLine),
                        row.coordTextScreenY(),
                        0xFF404040,
                        false);
            } else {
                String coordLine =
                        truncateRemoteNodeCoordText(
                                remoteNodeCoordLabel(null).getString());
                graphics.text(
                        this.font,
                        coordLine,
                        centeredPanelTextScreenX(coordLine),
                        row.coordTextScreenY(),
                        0xFF707070,
                        false);
            }
        } else {
            renderRemoteNodeCoordAxisLabels(graphics, row);
        }

        if (mouseX >= slotX
                && mouseX < slotX + ADVANCED_REMOTE_NODE_SLOT_SIZE
                && mouseY >= slotY
                && mouseY < slotY + ADVANCED_REMOTE_NODE_SLOT_SIZE) {
            MutableComponent tip = remoteNodeTooltip(editModeRemoteNodeDraft).copy();
            tip.append("\n");
            tip.append(
                    Component.translatable(
                                    "gui.another_dynamics.duct_node.remote_node.tooltip.apply")
                            .withStyle(ChatFormatting.GRAY));
            if (editModeRemoteNodeDraft != null) {
                tip.append("\n");
                tip.append(
                        Component.translatable(
                                        "gui.another_dynamics.duct_node.remote_node.tooltip.clear_slot")
                                .withStyle(ChatFormatting.GRAY));
            }
            graphics.setTooltipForNextFrame(
                    this.font,
                    List.of(tip),
                    java.util.Optional.empty(),
                    ItemStack.EMPTY,
                    mouseX,
                    mouseY,
                    null);
        }
    }

    private void renderRemoteNodeCoordAxisLabels(
            GuiGraphicsExtractor graphics, AdvancedRemoteNodeRowLayout row) {
        String labelX =
                Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.axis.x")
                        .getString();
        String labelY =
                Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.axis.y")
                        .getString();
        String labelZ =
                Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.axis.z")
                        .getString();
        int labelW =
                Math.max(
                        this.font.width(labelX),
                        Math.max(this.font.width(labelY), this.font.width(labelZ)));
        int groupW = labelW + REMOTE_NODE_AXIS_LABEL_GAP + REMOTE_NODE_COORD_EDIT_W;
        int textY = row.coordTextScreenY() + (BTN_H - this.font.lineHeight) / 2;
        int x = remoteNodeCoordAxisLabelScreenX;
        graphics.text(this.font, labelX, x, textY, 0xFF404040, false);
        x += groupW + AMOUNT_INNER_GAP;
        graphics.text(this.font, labelY, x, textY, 0xFF404040, false);
        x += groupW + AMOUNT_INNER_GAP;
        graphics.text(this.font, labelZ, x, textY, 0xFF404040, false);
    }

    private Component remoteNodeCoordLabel(@Nullable DuctDirectionalEndpoint bound) {
        if (bound == null) {
            return Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.any");
        }
        BlockPos p = bound.pos();
        return Component.translatable(
                "gui.another_dynamics.duct_node.remote_node.coords",
                p.getX(),
                p.getY(),
                p.getZ(),
                bound.face().getSerializedName());
    }

    private String remoteNodeSummaryCoordText(
            @Nullable DuctDirectionalEndpoint bound, boolean ignoreChannel) {
        if (bound == null) {
            return Component.translatable("gui.another_dynamics.duct_node.remote_node.coords.any")
                    .getString();
        }
        String base = remoteNodeCoordLabel(bound).getString();
        if (!ignoreChannel) {
            return base;
        }
        return base
                + " "
                + Component.translatable(
                                "gui.another_dynamics.duct_node.remote_node.ignore_channel.summary_suffix")
                        .getString();
    }

    private void handleAdvancedRemoteIgnoreChannelToggle() {
        if (editModeRemoteNodeDraft == null || editModeFilterIndex < 0) {
            return;
        }
        editModeRemoteIgnoreChannelDraft = !editModeRemoteIgnoreChannelDraft;
        commitRemoteNodeDraftToLine();
        syncRemoteIgnoreChannelButtonDisplay();
        pushFiltersToServer();
        playClickSound();
    }

    private void handleAdvancedRemoteNodeSlotClick() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemStack carried = menu.getCarried();
        DuctDirectionalEndpoint endpoint = null;
        if (!carried.isEmpty()) {
            if (!DestinationToolEndpointReader.isDestinationTool(carried)) {
                return;
            }
            endpoint =
                    DestinationToolEndpointReader.readFromItemStack(
                            carried, minecraft.level.registryAccess());
        }
        editModeRemoteNodeDraft = endpoint;
        if (endpoint == null) {
            editModeRemoteIgnoreChannelDraft = false;
        }
        closeRemoteNodeCoordEdit(false);
        commitRemoteNodeDraftToLine();
        syncRemoteIgnoreChannelButtonDisplay();
        syncRemoteNodeFaceButtonDisplay();
        syncRemoteNodeCoordEditButtonDisplay();
        applySubViewVisibility();
        pushFiltersToServer();
        playClickSound();
    }

    /** Same click lines as {@link #channelButton} (filter concat has no Shift reset). */
    private static void appendChannelLetterClickHints(
            MutableComponent tip, boolean includeShiftReset) {
        tip.append(
                Component.translatable(
                        "gui.another_dynamics.duct_node.channel_letter.tooltip.click_forward"));
        tip.append("\n");
        tip.append(
                Component.translatable(
                        "gui.another_dynamics.duct_node.channel_letter.tooltip.click_back"));
        if (includeShiftReset) {
            tip.append("\n");
            tip.append(
                    Component.translatable(
                            "gui.another_dynamics.duct_node.channel_letter.tooltip.shift_a"));
        }
    }

    private Tooltip concatChannelTooltip() {
        MutableComponent tip = Component.empty();
        tip.append(
                Component.translatable(
                        "gui.another_dynamics.duct_node.filters.concat.tooltip.concat"));
        tip.append("\n");
        tip.append(
                Component.translatable("gui.another_dynamics.duct_node.filters.concat.tooltip.and"));
        tip.append("\n");
        appendChannelLetterClickHints(tip, false);
        return Tooltip.create(tip);
    }

    private int visibleFilterEntries() {
        return VISIBLE_FILTER_ENTRIES;
    }

    private int maxFilterScroll() {
        return Math.max(0, currentFilterMaxSlots() - visibleFilterEntries());
    }

    private void setFilterScrollOffset(int offset) {
        int max = maxFilterScroll();
        this.filterScrollOffset = Mth.clamp(offset, 0, max);
        if (editModeFilterIndex >= 0) {
            int visibleIndex = editModeFilterIndex - filterScrollOffset;
            if (visibleIndex < 0 || visibleIndex >= visibleFilterEntries()) {
                exitEditMode(true);
            }
        }
        rebuildFilterEntryWidgets();
    }

    private void scrollUp() {
        if (scrollUpSilent()) {
            playClickSound();
        }
    }

    private void scrollDown() {
        if (scrollDownSilent()) {
            playClickSound();
        }
    }

    private boolean scrollUpSilent() {
        if (
            currentFilterMaxSlots() > visibleFilterEntries() &&
            filterScrollOffset > 0
        ) {
            setFilterScrollOffset(filterScrollOffset - 1);
            return true;
        }
        return false;
    }

    private boolean scrollDownSilent() {
        int maxScroll = maxFilterScroll();
        if (
            currentFilterMaxSlots() > visibleFilterEntries() &&
            filterScrollOffset < maxScroll
        ) {
            setFilterScrollOffset(filterScrollOffset + 1);
            return true;
        }
        return false;
    }

    private boolean handleFilterScrollButtonClick(
        double mouseX,
        double mouseY
    ) {
        if (currentFilterMaxSlots() <= visibleFilterEntries()) {
            return false;
        }
        int gx = this.leftPos;
        int gy = this.topPos;
        if (
            mouseX >= gx + SCROLLBAR_X_REL &&
            mouseX < gx + SCROLLBAR_X_REL + SCROLLBAR_WIDTH &&
            mouseY >= gy + BUTTON_UP_Y_REL &&
            mouseY < gy + BUTTON_UP_Y_REL + HANDLE_SIZE
        ) {
            scrollUp();
            return true;
        }
        if (
            mouseX >= gx + SCROLLBAR_X_REL &&
            mouseX < gx + SCROLLBAR_X_REL + SCROLLBAR_WIDTH &&
            mouseY >= gy + BUTTON_DOWN_Y_REL &&
            mouseY < gy + BUTTON_DOWN_Y_REL + HANDLE_SIZE
        ) {
            scrollDown();
            return true;
        }
        return false;
    }

    private boolean handleFilterHandleClick(double mouseX, double mouseY) {
        if (currentFilterMaxSlots() <= visibleFilterEntries()) {
            return false;
        }
        int maxScroll = maxFilterScroll();
        if (maxScroll <= 0) {
            return false;
        }
        int gx = this.leftPos;
        int gy = this.topPos;
        double scrollRatio = (double) filterScrollOffset / maxScroll;
        int handleY =
            gy +
            SCROLLBAR_Y_REL +
            (int) (scrollRatio * (SCROLLBAR_HEIGHT - HANDLE_SIZE));
        if (
            mouseX >= gx + SCROLLBAR_X_REL &&
            mouseX < gx + SCROLLBAR_X_REL + HANDLE_SIZE &&
            mouseY >= handleY &&
            mouseY < handleY + HANDLE_SIZE
        ) {
            isDraggingHandle = true;
            dragStartY = (int) mouseY;
            dragStartScrollOffset = filterScrollOffset;
            playClickSound();
            return true;
        }
        return false;
    }

    private boolean handleFilterScrollbarTrackClick(
        double mouseX,
        double mouseY
    ) {
        if (currentFilterMaxSlots() <= visibleFilterEntries()) {
            return false;
        }
        int gx = this.leftPos;
        int gy = this.topPos;
        if (
            mouseX >= gx + SCROLLBAR_X_REL &&
            mouseX < gx + SCROLLBAR_X_REL + SCROLLBAR_WIDTH &&
            mouseY >= gy + SCROLLBAR_Y_REL &&
            mouseY < gy + SCROLLBAR_Y_REL + SCROLLBAR_HEIGHT
        ) {
            float clickRatio =
                (float) (mouseY - (gy + SCROLLBAR_Y_REL)) / SCROLLBAR_HEIGHT;
            clickRatio = Mth.clamp(clickRatio, 0.0f, 1.0f);
            int maxScroll = maxFilterScroll();
            int newOffset = (int) (clickRatio * maxScroll);
            if (newOffset != filterScrollOffset) {
                setFilterScrollOffset(newOffset);
                playClickSound();
            }
            return true;
        }
        return false;
    }

    private int editModeSlotX() {
        return editGhostSlotScreenX;
    }

    private int editModeSlotY() {
        return editGhostSlotScreenY;
    }

    /**
     * Screen Y for the top of the ghost-slot row: same below the visible filter list in allow/deny edit and in advanced
     * filtering (list rows are not drawn in advanced, but the entry editor stays aligned with the non-advanced layout).
     */
    private int editModeRowAnchorScreenY() {
        return (
            this.topPos +
            FIRST_FILTER_ROW_Y +
            VISIBLE_FILTER_ENTRIES * ENTRY_HEIGHT +
            EDIT_MODE_GAP_BELOW_LIST
        );
    }

    /** Repositions filter-entry edit widgets when {@link #leftPos}/{@link #topPos} or {@link #subView} changes. */
    private void layoutEditModeWidgets() {
        if (editModeTextBox == null) {
            return;
        }
        int slotSize = 18;
        int buttonSize = 12;
        int buttonSpacing = 2;
        int slotY = editModeRowAnchorScreenY();
        int rowLeft = this.leftPos + ENTRY_X;
        int rowRight = this.leftPos + ENTRY_X + ENTRY_WIDTH;

        int leftArrowX = rowLeft;
        int slotX = rowLeft + buttonSize + buttonSpacing;
        int rightArrowX = slotX + slotSize + buttonSpacing;
        int closeButtonX = rowRight - buttonSize;
        int applyButtonX = closeButtonX - buttonSize - buttonSpacing;
        int clearButtonX = applyButtonX - buttonSize - buttonSpacing;
        int buttonRowY = slotY + (slotSize - buttonSize) / 2;

        editGhostSlotScreenX = slotX;
        editGhostSlotScreenY = slotY;

        leftArrowButton.setPosition(leftArrowX, buttonRowY);
        rightArrowButton.setPosition(rightArrowX, buttonRowY);

        if (advancedFilteringOpenButton != null && !isAdvancedFilterCapSubview()) {
            int advBtnW = ADVANCED_FILTER_BUTTON_WIDTH;
            int advBtnX = rightArrowX + buttonSize + buttonSpacing;
            int advBtnY = slotY + (slotSize - BTN_H) / 2;
            advancedFilteringOpenButton.setPosition(advBtnX, advBtnY);
            advancedFilteringOpenButton.setWidth(advBtnW);
            advancedFilteringOpenButton.setHeight(BTN_H);

            if (validKeysButton != null && validKeysButton.visible) {
                validKeysButton.setX(advBtnX + advBtnW + ADJACENT_BTN_GAP);
                validKeysButton.setY(advBtnY);
                validKeysButton.setWidth(ADVANCED_FILTER_BUTTON_WIDTH);
                validKeysButton.setHeight(BTN_H);
            }
        }

        int textBoxY = slotY + slotSize + 2;
        int textBoxHeight = 15;
        int anyBtn = buttonSize;
        int anyGap = buttonSpacing;
        int textBoxX = this.leftPos + ENTRY_X + EDIT_MODE_TEXT_INSET_X;
        int entryContentRight =
            this.leftPos + ENTRY_X + ENTRY_WIDTH - EDIT_MODE_TEXT_INSET_X;
        // Reserve space for the anything-else button flush against the EditBox (right side).
        int textBoxWidth = entryContentRight - textBoxX;
        if (editModeAnythingElseButton != null) {
            textBoxWidth = Math.max(40, textBoxWidth - anyBtn - anyGap);
        }
        editModeTextBox.setPosition(textBoxX, textBoxY);
        editModeTextBox.setWidth(textBoxWidth);
        editModeTextBox.setHeight(textBoxHeight);

        if (editModeAnythingElseButton != null) {
            int anyX = textBoxX + textBoxWidth + anyGap;
            int anyY = textBoxY + (textBoxHeight - anyBtn) / 2;
            editModeAnythingElseButton.setPosition(anyX, anyY);
            editModeAnythingElseButton.setWidth(anyBtn);
            editModeAnythingElseButton.setHeight(anyBtn);
        }

        editModeClearButton.setPosition(clearButtonX, buttonRowY);
        editModeApplyButton.setPosition(applyButtonX, buttonRowY);
        editModeCloseButton.setPosition(closeButtonX, buttonRowY);

        layoutFilterSortButton();

        if (isAdvancedFilterCapSubview()) {
            int advBtnW = ADVANCED_FILTER_BUTTON_WIDTH;
            int advBtnX = rightArrowX + buttonSize + buttonSpacing;
            int advBtnY = slotY + (slotSize - BTN_H) / 2;
            backButton.setX(advBtnX);
            backButton.setY(advBtnY);
            backButton.setWidth(advBtnW);
            backButton.setHeight(BTN_H);
            if (validKeysButton != null && validKeysButton.visible) {
                validKeysButton.setX(advBtnX + advBtnW + ADJACENT_BTN_GAP);
                validKeysButton.setY(advBtnY);
                validKeysButton.setWidth(ADVANCED_FILTER_BUTTON_WIDTH);
                validKeysButton.setHeight(BTN_H);
            }
        }
    }

    private void enterEditMode(int index) {
        if (!canOpenCopierFilterEntry()) {
            transientFeedback =
                    Component.translatable(FilterListMaterialKind.pickBeforeEditTooltipKey());
            transientFeedbackColor = 0xFFFFAA55;
            transientFeedbackHideAt = Util.getMillis() + TRANSIENT_FEEDBACK_MS;
            return;
        }
        if (editModeFilterIndex == index) {
            exitEditMode(true);
            return;
        }
        if (editModeFilterIndex >= 0) {
            persistFilterEditDraftToClientBuffers(false);
        }
        exitEditMode(false);
        editModeFilterIndex = index;
        List<String> list = getEditingList();
        while (list.size() <= index) {
            list.add("");
        }
        originalFilterValue = list.get(index) != null ? list.get(index) : "";
        if (effectiveFilterLineSubview() == SubView.ALLOW_FILTERS) {
            menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
            List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
            while (caps.size() <= index) {
                caps.add(0);
            }
            editModeAllowCapValue = caps.get(index);
            originalAllowCapValue = editModeAllowCapValue;
        } else {
            editModeAllowCapValue = 0;
            originalAllowCapValue = 0;
        }
        if (filterRemoteNodeUiEnabled()) {
            menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
            editModeRemoteNodeDraft = remoteNodeAt(index);
            originalRemoteNodeValue = editModeRemoteNodeDraft;
            editModeRemoteIgnoreChannelDraft = remoteIgnoreChannelAt(index);
            originalRemoteIgnoreChannelValue = editModeRemoteIgnoreChannelDraft;
            editModeRemoteAnyFaceDraft = remoteAnyFaceAt(index);
            originalRemoteAnyFaceValue = editModeRemoteAnyFaceDraft;
            if (editModeRemoteNodeDraft == null) {
                editModeRemoteIgnoreChannelDraft = false;
                originalRemoteIgnoreChannelValue = false;
                editModeRemoteAnyFaceDraft = false;
                originalRemoteAnyFaceValue = false;
            }
        } else {
            editModeRemoteNodeDraft = null;
            originalRemoteNodeValue = null;
            editModeRemoteIgnoreChannelDraft = false;
            originalRemoteIgnoreChannelValue = false;
            editModeRemoteAnyFaceDraft = false;
            originalRemoteAnyFaceValue = false;
        }
        createEditModeUI();
        filterScrollOffset = Mth.clamp(
            filterScrollOffset,
            0,
            maxFilterScroll()
        );
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
        layoutEditModeWidgets();
        reloadFilterEntryTextBoxFromList(true);
    }

    private void exitEditMode(boolean restoreValue) {
        if (editModeFilterIndex >= 0 && restoreValue) {
            List<String> list = getEditingList();
            while (list.size() <= editModeFilterIndex) {
                list.add("");
            }
            list.set(editModeFilterIndex, originalFilterValue);
            if (isEditingAllowFilterList()) {
                List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
                while (caps.size() <= editModeFilterIndex) {
                    caps.add(0);
                }
                caps.set(editModeFilterIndex, originalAllowCapValue);
            }
            if (filterRemoteNodeUiEnabled()) {
                setRemoteNodeAt(editModeFilterIndex, originalRemoteNodeValue);
                setRemoteIgnoreChannelAt(
                        editModeFilterIndex, originalRemoteIgnoreChannelValue);
                editModeRemoteNodeDraft = originalRemoteNodeValue;
            }
        }
        editModeFilterIndex = -1;
        originalFilterValue = "";
        originalAllowCapValue = 0;
        originalRemoteNodeValue = null;
        originalRemoteIgnoreChannelValue = false;
        editModeRemoteNodeDraft = null;
        closeRemoteNodeCoordEdit(false);
        removeEditModeUI();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
        filterScrollOffset = Mth.clamp(
            filterScrollOffset,
            0,
            maxFilterScroll()
        );
    }

    private void createEditModeUI() {
        removeEditModeUI();

        int buttonSize = 12;

        leftArrowButton = Button.builder(Component.literal("\u2190"), b -> {
            playClickSound();
            cycleFilterVariant(-1);
        })
            .bounds(0, 0, buttonSize, buttonSize)
            .build();
        addRenderableWidget(leftArrowButton);

        rightArrowButton = Button.builder(Component.literal("\u2192"), b -> {
            playClickSound();
            cycleFilterVariant(1);
        })
            .bounds(0, 0, buttonSize, buttonSize)
            .build();
        addRenderableWidget(rightArrowButton);

        leftArrowButton.setTooltip(null);
        rightArrowButton.setTooltip(null);

        advancedFilteringOpenButton = null;
        if (
            effectiveFilterLineSubview() == SubView.ALLOW_FILTERS ||
            effectiveFilterLineSubview() == SubView.DENY_FILTERS
        ) {
            advancedFilteringOpenButton = Button.builder(
                Component.translatable(
                    "gui.another_dynamics.duct_node.advanced_filtering.button"
                ),
                b -> {
                    playClickSound();
                    openAdvancedFiltering();
                }
            )
                .bounds(0, 0, ADVANCED_FILTER_BUTTON_WIDTH, BTN_H)
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.advanced_filtering.button.tooltip"
                        )
                    )
                )
                .build();
            addRenderableWidget(advancedFilteringOpenButton);
            SubView effLine = effectiveFilterLineSubview();
            advancedFilteringOpenButton.active =
                    effLine == SubView.ALLOW_FILTERS
                            || (effLine == SubView.DENY_FILTERS
                                    && filterRemoteNodeUiEnabled());
        }

        editModeTextBox = new EditBox(
            this.font,
            0,
            0,
            1,
            15,
            Component.empty()
        );
        editModeTextBox.setMaxLength(16_384);
        editModeTextBox.setValue(originalFilterValue);
        editModeTextBox.setResponder(v -> {});
        addRenderableWidget(editModeTextBox);

        editModeAnythingElseButton =
                Button.builder(Component.literal("\u03A3"), b -> {
                            playClickSound();
                            if (editModeTextBox != null) {
                                String token = DuctFilterSpecialKeys.ANYTHING_ELSE;
                                editModeTextBox.setValue(token);
                                editModeTextBox.setCursorPosition(token.length());
                                editModeTextBox.setHighlightPos(token.length());
                            }
                        })
                        .bounds(0, 0, buttonSize, buttonSize)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.filters.anything_else.button.tooltip")))
                        .build();
        addRenderableWidget(editModeAnythingElseButton);

        editModeClearButton = Button.builder(Component.literal("C"), b -> {
            playClickSound();
            if (editModeTextBox != null) {
                editModeTextBox.setValue("");
                editModeTextBox.setCursorPosition(0);
                editModeTextBox.setHighlightPos(0);
            }
        })
            .bounds(0, 0, buttonSize, buttonSize)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.clear"
                    )
                )
            )
            .build();
        addRenderableWidget(editModeClearButton);

        editModeApplyButton = Button.builder(Component.literal("A"), b -> {
            if (isAdvancedFilterCapSubview()) {
                applyFilterEditDraft();
            } else {
                playClickSound();
                applyEditModeAndClose();
            }
        })
            .bounds(0, 0, buttonSize, buttonSize)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.apply"
                    )
                )
            )
            .build();
        addRenderableWidget(editModeApplyButton);

        editModeCloseButton = Button.builder(Component.literal("\u2715"), b -> {
            if (isAdvancedFilterCapSubview()) {
                if (popAdvancedFilterSubLevel()) {
                    return;
                }
                undoFilterEditDraft();
            } else {
                playClickSound();
                exitEditMode(true);
            }
        })
            .bounds(0, 0, buttonSize, buttonSize)
            .tooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.close_without_saving"
                    )
                )
            )
            .build();
        addRenderableWidget(editModeCloseButton);

        layoutEditModeWidgets();
        applyFilterEntryEditBoxTextStyle();

        clearGhostCalibration(false);
    }

    /**
     * Saves filter text (and allow caps for this line) while staying in {@link SubView#ADVANCED_FILTERING} and keeping
     * edit widgets open.
     */
    private void applyFilterEditDraft() {
        if (
            editModeTextBox == null ||
            editModeFilterIndex < 0 ||
            !isAdvancedFilterCapSubview()
        ) {
            return;
        }
        playClickSound();
        persistFilterEditDraftToClientBuffers(true);
    }

    /** Writes the open filter line editor into client buffers; optionally pushes to the server. */
    private void persistFilterEditDraftToClientBuffers(boolean pushToServer) {
        if (editModeTextBox == null || editModeFilterIndex < 0) {
            return;
        }
        menu.markClientFiltersDirty();
        String value = sanitizeFilterLineForCommit(editModeTextBox.getValue());
        editModeTextBox.setValue(value);
        editModeTextBox.setCursorPosition(value.length());
        editModeTextBox.setHighlightPos(value.length());
        List<String> list = getEditingList();
        while (list.size() <= editModeFilterIndex) {
            list.add("");
        }
        list.set(editModeFilterIndex, value);
        if (isEditingAllowFilterList()) {
            List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
            while (caps.size() <= editModeFilterIndex) {
                caps.add(0);
            }
            caps.set(editModeFilterIndex, editModeAllowCapValue);
            originalAllowCapValue = editModeAllowCapValue;
        }
        originalFilterValue = value;
        commitRemoteNodeDraftToLine();
        if (pushToServer) {
            pushFiltersToServer();
        }
    }

    private void commitRemoteNodeDraftToLine() {
        if (!filterRemoteNodeUiEnabled() || editModeFilterIndex < 0) {
            return;
        }
        // Remote binding is advanced-only; plain filter-line apply must leave client buffers unchanged.
        if (!isAdvancedFilterCapSubview()) {
            return;
        }
        setRemoteNodeAt(editModeFilterIndex, editModeRemoteNodeDraft);
        originalRemoteNodeValue = editModeRemoteNodeDraft;
        if (editModeRemoteNodeDraft == null) {
            editModeRemoteIgnoreChannelDraft = false;
            editModeRemoteAnyFaceDraft = false;
        }
        setRemoteIgnoreChannelAt(editModeFilterIndex, editModeRemoteIgnoreChannelDraft);
        originalRemoteIgnoreChannelValue = editModeRemoteIgnoreChannelDraft;
        setRemoteAnyFaceAt(editModeFilterIndex, editModeRemoteAnyFaceDraft);
        originalRemoteAnyFaceValue = editModeRemoteAnyFaceDraft;
    }

    /** Reverts filter text and Limit/Keep draft to last applied values (advanced filtering only). */
    private void undoFilterEditDraft() {
        if (
            editModeTextBox == null ||
            editModeFilterIndex < 0 ||
            !isAdvancedFilterCapSubview()
        ) {
            return;
        }
        playClickSound();
        editModeTextBox.setValue(originalFilterValue);
        editModeTextBox.setCursorPosition(0);
        editModeTextBox.setHighlightPos(0);
        editModeAllowCapValue = originalAllowCapValue;
        if (showsAdvancedCapEditors()) {
            syncAllowCapEditBoxDisplay();
        }
        editModeRemoteNodeDraft = originalRemoteNodeValue;
        editModeRemoteIgnoreChannelDraft = originalRemoteIgnoreChannelValue;
        editModeRemoteAnyFaceDraft = originalRemoteAnyFaceValue;
        closeRemoteNodeCoordEdit(false);
        syncRemoteIgnoreChannelButtonDisplay();
        syncRemoteNodeFaceButtonDisplay();
        syncRemoteNodeCoordEditButtonDisplay();
        applySubViewVisibility();
    }

    private static String sanitizeFilterLineForCommit(String raw) {
        if (raw == null) {
            return "";
        }
        return FilterLineTextUtil.stripWrappingQuotes(raw.trim());
    }

    private void applyEditModeAndClose() {
        if (editModeTextBox != null && editModeFilterIndex >= 0) {
            menu.markClientFiltersDirty();
            String value = sanitizeFilterLineForCommit(editModeTextBox.getValue());
            List<String> list = getEditingList();
            while (list.size() <= editModeFilterIndex) {
                list.add("");
            }
            list.set(editModeFilterIndex, value);
            if (isEditingAllowFilterList()) {
                List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
                while (caps.size() <= editModeFilterIndex) {
                    caps.add(0);
                }
                caps.set(editModeFilterIndex, editModeAllowCapValue);
                originalAllowCapValue = editModeAllowCapValue;
            }
            pushFiltersToServer();
        }
        editModeFilterIndex = -1;
        originalFilterValue = "";
        originalAllowCapValue = 0;
        removeEditModeUI();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
        filterScrollOffset = Mth.clamp(
            filterScrollOffset,
            0,
            maxFilterScroll()
        );
    }

    private void removeEditModeUI() {
        if (editModeTextBox != null) {
            removeWidget(editModeTextBox);
            editModeTextBox = null;
        }
        if (leftArrowButton != null) {
            removeWidget(leftArrowButton);
            leftArrowButton = null;
        }
        if (rightArrowButton != null) {
            removeWidget(rightArrowButton);
            rightArrowButton = null;
        }
        if (editModeClearButton != null) {
            removeWidget(editModeClearButton);
            editModeClearButton = null;
        }
        if (editModeAnythingElseButton != null) {
            removeWidget(editModeAnythingElseButton);
            editModeAnythingElseButton = null;
        }
        if (editModeApplyButton != null) {
            removeWidget(editModeApplyButton);
            editModeApplyButton = null;
        }
        if (editModeCloseButton != null) {
            removeWidget(editModeCloseButton);
            editModeCloseButton = null;
        }
        if (advancedFilteringOpenButton != null) {
            removeWidget(advancedFilteringOpenButton);
            advancedFilteringOpenButton = null;
        }
        editGhostSlotScreenX = 0;
        editGhostSlotScreenY = 0;
        clearGhostCalibration(false);
    }

    private boolean isFluidFilterTransport() {
        return (
            menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) ==
            DuctTransportKind.FLUID.ordinal()
        );
    }

    private boolean isGasFilterTransport() {
        return (
            menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) ==
            DuctTransportKind.GAS.ordinal()
        );
    }

    private boolean isEnergyOrHeatTransport() {
        int k = menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
        return (
            k == DuctTransportKind.ENERGY.ordinal() ||
            k == DuctTransportKind.HEAT.ordinal()
        );
    }

    private boolean isEnergyTransportTab() {
        return menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND)
                == DuctTransportKind.ENERGY.ordinal();
    }

    /** Allow/deny filter lists are not used on energy/heat tabs (no item/fluid/gas filters). */
    private boolean transportFilterListsLocked() {
        return isEnergyOrHeatTransport();
    }

    /** Energy/heat: blank deny/allow nav except on the hybrid lane selector ({@link HybridPanel#NONE}). */
    private boolean shouldBlankFilterNavButtons(NodeMode nm) {
        if (!transportFilterListsLocked()) {
            return false;
        }
        return !(nm.isHybrid() && hybridPanel == HybridPanel.NONE);
    }

    private String filterHelpTextPrefix() {
        int k = menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
        if (k == DuctTransportKind.FLUID.ordinal()) {
            return "gui.another_dynamics.fluid_filter_text.";
        }
        if (k == DuctTransportKind.GAS.ordinal()) {
            return "gui.another_dynamics.gas_filter_text.";
        }
        return "gui.another_dynamics.general_filter_text.";
    }

    private String allowCapLimitTooltipKey() {
        if (isFluidFilterTransport()) {
            return "gui.another_dynamics.duct_node.allow_cap.field.limit_mb";
        }
        if (isGasFilterTransport()) {
            return "gui.another_dynamics.duct_node.allow_cap.field.limit_gas";
        }
        return "gui.another_dynamics.duct_node.allow_cap.field.limit";
    }

    private String allowCapKeepTooltipKey() {
        if (isFluidFilterTransport()) {
            return "gui.another_dynamics.duct_node.allow_cap.field.keep_mb";
        }
        if (isGasFilterTransport()) {
            return "gui.another_dynamics.duct_node.allow_cap.field.keep_gas";
        }
        return "gui.another_dynamics.duct_node.allow_cap.field.keep";
    }

    private void renderEditModeSlot(GuiGraphicsExtractor guiGraphics) {
        int slotSize = 18;
        int slotX = editModeSlotX();
        int slotY = editModeSlotY();
        guiGraphics.blit(
            RenderPipelines.GUI_TEXTURED,
            SINGLE_SLOT,
            slotX,
            slotY,
            0.0F,
            0.0F,
            slotSize,
            slotSize,
            slotSize,
            slotSize);
        if (
            ghostSlotGas != null &&
            !MekanismChemicalCompat.isEmptyStack(ghostSlotGas)
        ) {
            GuiChemicalStillBlit.blit16(
                guiGraphics,
                ghostSlotGas,
                slotX + 1,
                slotY + 1
            );
        } else if (!ghostSlotFluid.isEmpty()) {
            GuiFluidStillBlit.blit16(
                guiGraphics,
                ghostSlotFluid,
                slotX + 1,
                slotY + 1
            );
        } else if (!ghostSlotItem.isEmpty()) {
            guiGraphics.item(ghostSlotItem, slotX + 1, slotY + 1);
            guiGraphics.itemDecorations(
                this.font,
                ghostSlotItem,
                slotX + 1,
                slotY + 1
            );
        }
    }

    private boolean acceptsFluidGhostCalibration() {
        return isFluidFilterTransport();
    }

    private void clearGhostCalibration(boolean updateEditBox) {
        ghostSlotItem = ItemStack.EMPTY;
        ghostSlotFluid = FluidStack.EMPTY;
        ghostSlotGas = null;
        filterVariants.clear();
        currentFilterVariantIndex = 0;
        if (updateEditBox && editModeTextBox != null) {
            editModeTextBox.setValue("");
            editModeTextBox.setCursorPosition(0);
            editModeTextBox.setHighlightPos(0);
        }
    }

    private void pushActiveVariantToEditBox() {
        if (editModeTextBox == null) {
            return;
        }
        String v =
                filterVariants.isEmpty() ? "" : filterVariants.get(currentFilterVariantIndex);
        editModeTextBox.setValue(v);
        editModeTextBox.setCursorPosition(0);
        editModeTextBox.setHighlightPos(0);
    }

    private void syncVariantIndexFromEditBoxText() {
        if (editModeTextBox == null || filterVariants.isEmpty()) {
            currentFilterVariantIndex = 0;
            return;
        }
        String line = editModeTextBox.getValue();
        if (line == null) {
            currentFilterVariantIndex = 0;
            pushActiveVariantToEditBox();
            return;
        }
        String trimmed = line.trim();
        for (int i = 0; i < filterVariants.size(); i++) {
            if (filterVariants.get(i).equals(trimmed)) {
                currentFilterVariantIndex = i;
                return;
            }
        }
        currentFilterVariantIndex = 0;
        pushActiveVariantToEditBox();
    }

    private void applyGhostFromFluidStack(FluidStack fluidStack, boolean updateEditBox) {
        ghostSlotGas = null;
        if (!fluidStack.isEmpty()) {
            ghostSlotItem = ItemStack.EMPTY;
            ghostSlotFluid = fluidStack.copy();
            filterVariants = generateFluidFilterVariants(ghostSlotFluid);
        } else {
            ghostSlotFluid = FluidStack.EMPTY;
            ghostSlotItem = ItemStack.EMPTY;
            filterVariants.clear();
        }
        currentFilterVariantIndex = 0;
        if (updateEditBox) {
            pushActiveVariantToEditBox();
        }
    }

    private void applyGhostFromGasSample(Object sample, boolean updateEditBox) {
        ghostSlotFluid = FluidStack.EMPTY;
        ghostSlotItem = ItemStack.EMPTY;
        if (!MekanismChemicalCompat.isEmptyStack(sample)) {
            ghostSlotGas = sample;
            filterVariants = generateGasFilterVariants(sample);
        } else {
            ghostSlotGas = null;
            filterVariants.clear();
        }
        currentFilterVariantIndex = 0;
        if (updateEditBox) {
            pushActiveVariantToEditBox();
        }
    }

    private void applyGhostFromItemStack(ItemStack stack, boolean updateEditBox) {
        if (stack.isEmpty()) {
            clearGhostCalibration(updateEditBox);
            return;
        }
        if (isFluidFilterTransport()) {
            ghostSlotGas = null;
            Optional<FluidStack> contained = FluidUtil.getFluidContained(stack);
            if (contained.isPresent() && !contained.get().isEmpty()) {
                applyGhostFromFluidStack(contained.get(), updateEditBox);
            } else {
                ghostSlotFluid = FluidStack.EMPTY;
                ghostSlotItem = ItemStack.EMPTY;
                filterVariants.clear();
                currentFilterVariantIndex = 0;
                if (updateEditBox && editModeTextBox != null) {
                    editModeTextBox.setValue("");
                    editModeTextBox.setCursorPosition(0);
                    editModeTextBox.setHighlightPos(0);
                }
            }
            return;
        }
        if (isGasFilterTransport() && MekanismChemicalCompat.isLoaded()) {
            applyGhostFromGasSample(MekanismChemicalCompat.sampleFromItemStack(stack), updateEditBox);
            return;
        }
        ghostSlotGas = null;
        ghostSlotFluid = FluidStack.EMPTY;
        ghostSlotItem = stack.copy();
        filterVariants = generateAllFilterVariants(stack);
        currentFilterVariantIndex = 0;
        if (updateEditBox) {
            pushActiveVariantToEditBox();
        }
    }

    private void handleGhostSlotClick() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack cursorItem = this.menu.getCarried();
        if (cursorItem.isEmpty()) {
            clearGhostCalibration(true);
        } else {
            applyGhostFromItemStack(cursorItem, true);
        }
        playClickSound();
    }

    /**
     * Handle dropping an ingredient from JEI onto the ghost filter slot.
     * Respects active transport (item ducts never promote fluid from containers).
     */
    private void handleGhostIngredientDrop(Object ingredient) {
        if (ingredient == null) {
            clearGhostCalibration(true);
            playClickSound();
            applyFilterEditDraft();
            return;
        }

        if (ingredient instanceof FluidStack fluidStack) {
            if (!isFluidFilterTransport()) {
                return;
            }
            applyGhostFromFluidStack(fluidStack, true);
            playClickSound();
            applyFilterEditDraft();
            return;
        }

        if (ingredient instanceof ItemStack itemStack) {
            applyGhostFromItemStack(itemStack, true);
            playClickSound();
            applyFilterEditDraft();
            return;
        }

        if (MekanismChemicalCompat.isLoaded()
                && !MekanismChemicalCompat.isEmptyStack(ingredient)
                && isGasFilterTransport()) {
            applyGhostFromGasSample(ingredient, true);
            playClickSound();
            applyFilterEditDraft();
        }
    }

    private void cycleFilterVariant(int direction) {
        if (filterVariants.isEmpty()) {
            return;
        }
        currentFilterVariantIndex += direction;
        if (currentFilterVariantIndex < 0) {
            currentFilterVariantIndex = filterVariants.size() - 1;
        } else if (currentFilterVariantIndex >= filterVariants.size()) {
            currentFilterVariantIndex = 0;
        }
        pushActiveVariantToEditBox();
    }

    /**
     * Filter presets from a sample item (ghost slot): ID, mod, macros, tags, then {@code ?} + full stack SNBT (last), for
     * {@link net.unfamily.another_dynamics.duct.DuctFilterMatcher} {@code ?} substring matching without commands.
     */
    private List<String> generateAllFilterVariants(ItemStack stack) {
        List<String> variants = new ArrayList<>();
        if (stack.isEmpty()) {
            return variants;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(
            stack.getItem()
        );
        if (itemId == null) {
            return variants;
        }
        variants.add("-" + itemId);
        String namespace = itemId.getNamespace();
        variants.add("@" + namespace);
        Item item = stack.getItem();
        var holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        List<String> itemTags = BuiltInRegistries.ITEM.getTags()
            .filter(named -> named.contains(holder))
            .map(named -> named.key().location().toString())
            .sorted()
            .toList();
        for (String tagId : itemTags) {
            variants.add("#" + tagId);
        }
        boolean enchantedMacro =
            stack.isEnchanted() || stack.is(Items.ENCHANTED_BOOK);
        if (enchantedMacro) {
            variants.add("&enchanted");
        }
        if (stack.isDamageableItem()) {
            if (stack.isDamaged()) {
                variants.add("&damaged");
            }
            variants.add("&damaged>0");
            if (stack.isDamaged()) {
                variants.add("&damaged=" + stack.getDamageValue());
            }
        }
        if (minecraft != null && minecraft.level != null) {
            try {
                var ops =
                        minecraft.level.registryAccess()
                                .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                net.minecraft.nbt.Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
                String snbt = encoded.toString();
                if (!snbt.isEmpty()) {
                    variants.add("?" + snbt);
                }
            } catch (Exception ignored) {}
        }
        return variants;
    }

    /**
     * Filter presets from a contained fluid (ghost slot): same prefix rules as {@link net.unfamily.another_dynamics.duct.DuctFluidFilterMatcher}.
     */
    private List<String> generateFluidFilterVariants(FluidStack stack) {
        List<String> variants = new ArrayList<>();
        if (stack.isEmpty() || minecraft == null || minecraft.level == null) {
            return variants;
        }
        var registries = minecraft.level.registryAccess();
        Fluid fluid = stack.getFluid();
        if (fluid == Fluids.EMPTY) {
            return variants;
        }
        Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null) {
            return variants;
        }
        variants.add("-" + fluidId);
        String namespace = fluidId.getNamespace();
        variants.add("@" + namespace);
        var holder = BuiltInRegistries.FLUID.wrapAsHolder(fluid);
        List<String> fluidTags = BuiltInRegistries.FLUID.getTags()
            .filter(named -> named.contains(holder))
            .map(named -> named.key().location().toString())
            .sorted()
            .toList();
        for (String tagId : fluidTags) {
            variants.add("#" + tagId);
        }
        // Fluid predefined filters: always offer macros with the current fluid's values (fallback to 0 if unknown).
        var ft = fluid.getFluidType();
        int temperature = 0;
        int light = 0;
        int density = 0;
        int viscosity = 0;
        try {
            temperature = ft.getTemperature(stack);
            light = ft.getLightLevel(stack);
            density = ft.getDensity(stack);
            viscosity = ft.getViscosity(stack);
        } catch (Exception ignored) {}
        variants.add("&temperature=" + temperature);
        variants.add("&light=" + light);
        variants.add("&density=" + density);
        variants.add("&viscosity=" + viscosity);
        try {
            CompoundTag saved = net.unfamily.another_dynamics.duct.DuctNbtCodecs.saveFluidStack(registries, stack);
            if (!saved.isEmpty()) {
                String snbt = saved.toString();
                if (!snbt.isEmpty()) {
                    variants.add("?" + snbt);
                }
            }
        } catch (Exception ignored) {}
        return variants;
    }

    /** Filter presets from a Mekanism chemical sample (ghost slot), mirroring {@link #generateFluidFilterVariants}. */
    private List<String> generateGasFilterVariants(Object chemicalStack) {
        List<String> variants = new ArrayList<>();
        if (
            chemicalStack == null ||
            !MekanismChemicalCompat.isLoaded() ||
            MekanismChemicalCompat.isEmptyStack(chemicalStack) ||
            minecraft == null ||
            minecraft.level == null
        ) {
            return variants;
        }
        String idStr = MekanismChemicalCompat.getTypeRegistryName(
            chemicalStack
        );
        if (idStr == null || idStr.isEmpty()) {
            return variants;
        }
        variants.add("-" + idStr);
        try {
            Identifier id = Identifier.parse(idStr);
            variants.add("@" + id.getNamespace());
        } catch (Exception ignored) {}
        if (MekanismChemicalCompat.isRadioactive(chemicalStack)) {
            variants.add("&radioactive");
        }
        variants.add("&tint=" + MekanismChemicalCompat.getTint(chemicalStack));
        variants.add(
            "&radioactivity=" +
                MekanismChemicalCompat.getRadioactivityPerUnit(chemicalStack)
        );
        return variants;
    }

    private static ItemStack getDisplayItemForFilter(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return ItemStack.EMPTY;
        }
        filter = filter.trim();
        if (filter.startsWith("-")) {
            String idFilter = filter.substring(1);
            try {
                Identifier id = Identifier.parse(idFilter);
                Item item = BuiltInRegistries.ITEM.get(id).map(h -> h.value()).orElse(Items.AIR);
                return new ItemStack(item);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        }
        if (filter.startsWith("#")) {
            return getItemForTag(filter.substring(1));
        }
        if (filter.startsWith("@")) {
            return getItemForMod(filter.substring(1));
        }
        if (filter.startsWith("?")) {
            return new ItemStack(Items.KNOWLEDGE_BOOK);
        }
        if (filter.startsWith("&")) {
            String macro = filter.substring(1).toLowerCase();
            if (DuctFilterSpecialKeys.isAnythingElseMacroBody(macro)) {
                return new ItemStack(Items.KNOWLEDGE_BOOK);
            }
            return switch (macro) {
                case "enchanted" -> {
                    String snbt =
                        "{components:{\"minecraft:enchantments\":{levels:{\"minecraft:aqua_affinity\":1}},\"minecraft:repair_cost\":1},count:1,id:\"minecraft:iron_pickaxe\"}";
                    ItemStack st = parseItemStackFromSNBT(snbt);
                    yield st.isEmpty()
                        ? new ItemStack(Items.DIAMOND_PICKAXE)
                        : st;
                }
                case "damaged" -> {
                    ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
                    st.setDamageValue(st.getMaxDamage() / 2);
                    yield st;
                }
                default -> ItemStack.EMPTY;
            };
        }
        // Support command-style bracket filters for display only (e.g. minecraft:enchanted_book[...]).
        if (filter.startsWith("minecraft:enchanted_book[")) {
            return new ItemStack(Items.ENCHANTED_BOOK);
        }
        try {
            Identifier id = Identifier.parse(filter);
            return new ItemStack(BuiltInRegistries.ITEM.get(id).map(h -> h.value()).orElse(Items.AIR));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Sample fluid for filter row / still icon when the duct is in fluid filter mode. */
    private static FluidStack getDisplayFluidForFilter(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return FluidStack.EMPTY;
        }
        String f = filter.trim();
        if (f.startsWith("?") || f.startsWith("&")) {
            return FluidStack.EMPTY;
        }
        if (f.startsWith("-")) {
            return fluidStackFromId(f.substring(1));
        }
        if (f.startsWith("#")) {
            return firstFluidInFluidTag(f.substring(1));
        }
        if (f.startsWith("@")) {
            return firstFluidInMod(f.substring(1));
        }
        try {
            Identifier id = Identifier.parse(f);
            Fluid fluid = BuiltInRegistries.FLUID.get(id).map(h -> h.value()).orElse(Fluids.EMPTY);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(fluid, 1000);
        } catch (Exception e) {
            return FluidStack.EMPTY;
        }
    }

    private static FluidStack fluidStackFromId(String idStr) {
        try {
            Identifier id = Identifier.parse(idStr);
            Fluid fluid = BuiltInRegistries.FLUID.get(id).map(h -> h.value()).orElse(Fluids.EMPTY);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(fluid, 1000);
        } catch (Exception e) {
            return FluidStack.EMPTY;
        }
    }

    private static FluidStack firstFluidInFluidTag(String tagId) {
        try {
            Identifier loc = Identifier.parse(tagId);
            TagKey<Fluid> tagKey = TagKey.create(Registries.FLUID, loc);
            for (var holder : BuiltInRegistries.FLUID.getTagOrEmpty(tagKey)) {
                return new FluidStack(holder.value(), 1000);
            }
            return FluidStack.EMPTY;
        } catch (Exception e) {
            return FluidStack.EMPTY;
        }
    }

    private static FluidStack firstFluidInMod(String modId) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            if (
                id != null &&
                id.getNamespace().startsWith(modId) &&
                fluid != Fluids.EMPTY
            ) {
                return new FluidStack(fluid, 1000);
            }
        }
        return FluidStack.EMPTY;
    }

    /** Sample chemical for filter row icon when the duct is in gas (Mekanism) filter mode. */
    private Object getDisplayGasForFilter(String filter) {
        if (
            filter == null ||
            filter.trim().isEmpty() ||
            !MekanismChemicalCompat.isLoaded() ||
            minecraft == null ||
            minecraft.level == null
        ) {
            return MekanismChemicalCompat.emptyStack();
        }
        String f = filter.trim();
        if (f.startsWith("?") || f.startsWith("&")) {
            return MekanismChemicalCompat.emptyStack();
        }
        var reg = minecraft.level.registryAccess();
        if (f.startsWith("-")) {
            return MekanismChemicalCompat.chemicalStackFromIdForDisplay(
                f.substring(1),
                1024L,
                reg
            );
        }
        if (f.startsWith("#")) {
            return MekanismChemicalCompat.firstChemicalInTagForDisplay(
                f.substring(1),
                1024L,
                reg
            );
        }
        if (f.startsWith("@")) {
            return MekanismChemicalCompat.firstChemicalInModForDisplay(
                f.substring(1),
                1024L,
                reg
            );
        }
        return MekanismChemicalCompat.chemicalStackFromIdForDisplay(
            f,
            1024L,
            reg
        );
    }

    private void renderFilterRowSlotIcon(GuiGraphicsExtractor graphics, String filter, int slotX, int slotY) {
        if (filter == null || filter.isBlank() || minecraft == null || minecraft.level == null) {
            return;
        }
        if (isFluidFilterTransport()) {
            FluidStack displayFluid = getDisplayFluidForFilter(filter);
            if (!displayFluid.isEmpty()) {
                GuiFluidStillBlit.blit16(graphics, displayFluid, slotX + 1, slotY + 1);
                return;
            }
            ItemStack displayItem = getDisplayItemForFilter(filter);
            if (!displayItem.isEmpty()) {
                graphics.item(displayItem, slotX + 1, slotY + 1);
                graphics.itemDecorations(this.font, displayItem, slotX + 1, slotY + 1);
            }
            return;
        }
        if (isGasFilterTransport() && MekanismChemicalCompat.isLoaded()) {
            Object displayGas = getDisplayGasForFilter(filter);
            if (!MekanismChemicalCompat.isEmptyStack(displayGas)) {
                GuiChemicalStillBlit.blit16(graphics, displayGas, slotX + 1, slotY + 1);
                return;
            }
            ItemStack displayItem = getDisplayItemForFilter(filter);
            if (!displayItem.isEmpty()) {
                graphics.item(displayItem, slotX + 1, slotY + 1);
                graphics.itemDecorations(this.font, displayItem, slotX + 1, slotY + 1);
            }
            return;
        }
        ItemStack displayItem = getDisplayItemForFilter(filter);
        if (!displayItem.isEmpty()) {
            graphics.item(displayItem, slotX + 1, slotY + 1);
            graphics.itemDecorations(this.font, displayItem, slotX + 1, slotY + 1);
        }
    }

    private static ItemStack parseItemStackFromSNBT(String snbtString) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbtString);
            if (Minecraft.getInstance().level == null) {
                return ItemStack.EMPTY;
            }
            var registries = Minecraft.getInstance().level.registryAccess();
            var ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            return ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (CommandSyntaxException e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack getItemForTag(String tagId) {
        try {
            Identifier loc = Identifier.parse(tagId);
            TagKey<Item> itemTag = ItemTags.create(loc);
            var contents = BuiltInRegistries.ITEM.getTagOrEmpty(itemTag);
            java.util.ArrayList<Item> items = new java.util.ArrayList<>();
            for (var holder : contents) {
                items.add(holder.value());
            }
            if (!items.isEmpty()) {
                int index = (int) ((System.currentTimeMillis() / 2000) % items.size());
                return new ItemStack(items.get(index));
            }
        } catch (Exception e) {
            // ignore
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack getItemForMod(String modId) {
        List<Item> modItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && id.getNamespace().startsWith(modId)) {
                modItems.add(item);
            }
        }
        if (!modItems.isEmpty()) {
            int index = (int) ((System.currentTimeMillis() / 2000) %
                modItems.size());
            return new ItemStack(modItems.get(index));
        }
        return ItemStack.EMPTY;
    }

    private void reorderFilterBank(DuctFaceNode.FilterBank bank) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        List<Integer> keepCaps =
                bank == DuctFaceNode.FilterBank.FILTER ? menu.getClientFilterKeepCaps() : null;
        DuctFilterLineReorder.sortAllowDenyRows(
                menu.getClientAllowFilters(bank),
                menu.getClientDenyFilters(bank),
                menu.getClientAllowCaps(bank),
                null,
                minecraft.level.registryAccess(),
                keepCaps,
                menu.getClientAllowConcatChannels(bank),
                menu.getClientDenyConcatChannels(bank),
                menu.getClientAllowRemoteNodes(bank),
                menu.getClientDenyRemoteNodes(bank),
                menu.getClientAllowRemoteIgnoreChannel(bank),
                menu.getClientDenyRemoteIgnoreChannel(bank),
                menu.getClientAllowRemoteAnyFace(bank),
                menu.getClientDenyRemoteAnyFace(bank));
    }

    private void reorderActiveFilterLines() {
        menu.markClientFiltersDirty();
        reorderFilterBank(activeFilterBank);
        filterScrollOffset = 0;
        rebuildFilterEntryWidgets();
        pushFiltersToServer();
    }

    private boolean shouldPushFiltersOnClose() {
        if (!menu.shouldPushClientFiltersOnClose()) {
            return false;
        }
        int tk = menu.filterTransportKindOrdinal();
        return tk != DuctTransportKind.ENERGY.ordinal() && tk != DuctTransportKind.HEAT.ordinal();
    }

    private void reorderAndPushAllFilterBanks() {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        if (!menu.clientFiltersDirty()) {
            FilterSyncDebugLog.clientScreenAction(
                    "CLOSE_PUSH_SKIP",
                    menu.filterTransportKindOrdinal(),
                    menu.clientFiltersHydrated(),
                    false,
                    "not dirty");
            return;
        }
        int tk = menu.filterTransportKindOrdinal();
        if (tk == DuctTransportKind.ENERGY.ordinal() || tk == DuctTransportKind.HEAT.ordinal()) {
            return;
        }
        boolean pushAllBanks = menu.clientFiltersHydrated();
        FilterSyncDebugLog.clientScreenAction(
                "CLOSE_PUSH_ALL_BANKS",
                tk,
                pushAllBanks,
                true,
                "pushAllBanks=" + pushAllBanks + " activeBank=" + activeFilterBank);
        for (DuctFaceNode.FilterBank bank : DuctFaceNode.FilterBank.values()) {
            if (isSettingsCopierFilterListEditor() && bank != DuctFaceNode.FilterBank.EXTRACTOR) {
                continue;
            }
            if (!pushAllBanks && bank != activeFilterBank) {
                continue;
            }
            reorderFilterBank(bank);
            List<Integer> caps2 =
                    bank == DuctFaceNode.FilterBank.FILTER
                            ? new ArrayList<>(menu.getClientFilterKeepCaps())
                            : List.of();
            boolean allowListCtx =
                    bank == activeFilterBank ? subView == SubView.ALLOW_FILTERS : true;
            menu.pushFilterConfigToServer(
                    bank,
                    new ArrayList<>(menu.getClientAllowFilters(bank)),
                    new ArrayList<>(menu.getClientDenyFilters(bank)),
                    new ArrayList<>(menu.getClientAllowCaps(bank)),
                    caps2,
                    new ArrayList<>(menu.getClientAllowConcatChannels(bank)),
                    new ArrayList<>(menu.getClientDenyConcatChannels(bank)),
                    new ArrayList<>(menu.getClientAllowRemoteNodes(bank)),
                    new ArrayList<>(menu.getClientDenyRemoteNodes(bank)),
                    new ArrayList<>(menu.getClientAllowRemoteIgnoreChannel(bank)),
                    new ArrayList<>(menu.getClientDenyRemoteIgnoreChannel(bank)),
                    new ArrayList<>(menu.getClientAllowRemoteAnyFace(bank)),
                    new ArrayList<>(menu.getClientDenyRemoteAnyFace(bank)),
                    menu.getClientDenyOverridesAllow(bank),
                    allowListCtx);
        }
    }

    private void reorderAndPushAllFilterBanksForTransport(int transportKindOrdinal) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        if (!menu.shouldPushFiltersForTransport(transportKindOrdinal)) {
            return;
        }
        if (transportKindOrdinal == DuctTransportKind.ENERGY.ordinal()
                || transportKindOrdinal == DuctTransportKind.HEAT.ordinal()) {
            return;
        }
        var registryAccess = minecraft.level.registryAccess();
        for (DuctFaceNode.FilterBank bank : DuctFaceNode.FilterBank.values()) {
            menu.reorderClientFilterBankForTransport(transportKindOrdinal, bank, registryAccess);
        }
        menu.pushAllFilterBanksForTransport(transportKindOrdinal);
    }

    private boolean shouldFlushFiltersBeforeMenuButton(int id) {
        if (id == DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB) {
            return true;
        }
        if (id >= DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE
                && id < DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE + DuctTransportKind.values().length) {
            return true;
        }
        return id >= DuctBlockEntity.MENU_BUTTON_TRANSPORT_TOGGLE_BASE
                && id < DuctBlockEntity.MENU_BUTTON_TRANSPORT_TOGGLE_BASE + DuctTransportKind.values().length;
    }

    private void flushPendingFilterEditsBeforeClose() {
        if (editModeFilterIndex >= 0) {
            applyEditModeAndClose();
            return;
        }
        if (advCapEditBox != null && advCapEditBox.isFocused()) {
            applyAllowCapField();
        }
        if (advCap2EditBox != null && advCap2EditBox.isFocused()) {
            applyAllowCap2Field();
        }
    }

    @Override
    public void onClose() {
        flushPendingFilterEditsBeforeClose();
        FilterSyncDebugLog.clientScreenAction(
                "ON_CLOSE",
                menu.filterTransportKindOrdinal(),
                menu.clientFiltersHydrated(),
                menu.clientFiltersDirty(),
                "shouldPush=" + shouldPushFiltersOnClose());
        if (shouldPushFiltersOnClose()) {
            reorderAndPushAllFilterBanks();
        }
        super.onClose();
    }

    private void pushFiltersToServer() {
        FilterSyncDebugLog.clientScreenAction(
                "LIVE_PUSH",
                menu.filterTransportKindOrdinal(),
                menu.clientFiltersHydrated(),
                menu.clientFiltersDirty(),
                "bank=" + activeFilterBank
                        + " allow="
                        + FilterSyncDebugLog.listPreview(menu.getClientAllowFilters(activeFilterBank))
                        + " deny="
                        + FilterSyncDebugLog.listPreview(menu.getClientDenyFilters(activeFilterBank)));
        menu.markClientFiltersDirty();
        List<Integer> caps2 =
            activeFilterBank == DuctFaceNode.FilterBank.FILTER
                ? new ArrayList<>(menu.getClientFilterKeepCaps())
                : List.of();
        menu.pushFilterConfigToServer(
            activeFilterBank,
            new ArrayList<>(menu.getClientAllowFilters(activeFilterBank)),
            new ArrayList<>(menu.getClientDenyFilters(activeFilterBank)),
            new ArrayList<>(menu.getClientAllowCaps(activeFilterBank)),
            caps2,
            new ArrayList<>(menu.getClientAllowConcatChannels(activeFilterBank)),
            new ArrayList<>(menu.getClientDenyConcatChannels(activeFilterBank)),
            new ArrayList<>(menu.getClientAllowRemoteNodes(activeFilterBank)),
            new ArrayList<>(menu.getClientDenyRemoteNodes(activeFilterBank)),
            new ArrayList<>(menu.getClientAllowRemoteIgnoreChannel(activeFilterBank)),
            new ArrayList<>(menu.getClientDenyRemoteIgnoreChannel(activeFilterBank)),
            new ArrayList<>(menu.getClientAllowRemoteAnyFace(activeFilterBank)),
            new ArrayList<>(menu.getClientDenyRemoteAnyFace(activeFilterBank)),
            menu.getClientDenyOverridesAllow(activeFilterBank),
            isEditingAllowFilterList());
    }

    private void syncAllowCapEditBoxDisplay() {
        if (advCapEditBox == null || !isAdvancedFilterCapSubview()) {
            return;
        }
        if (advCapEditBox.isFocused()) {
            return;
        }
        boolean limitCtx;
        if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
            int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
            DuctFaceNode.EligibilityMode em =
                DuctFaceNode.EligibilityMode.fromOrdinal(ord);
            limitCtx = em.isInsertable();
        } else {
            limitCtx = activeFilterBank == DuctFaceNode.FilterBank.RETRIEVER;
        }
        if (editModeAllowCapValue <= 0) {
            advCapEditBox.setValue(limitCtx ? "\u221e" : "0");
        } else {
            advCapEditBox.setValue(Integer.toString(editModeAllowCapValue));
        }
    }

    private void syncAllowCap2EditBoxDisplay() {
        if (advCap2EditBox == null || !isAdvancedFilterCapSubview()) {
            return;
        }
        if (advCap2EditBox.isFocused()) {
            return;
        }
        if (editModeAllowCap2Value <= 0) {
            advCap2EditBox.setValue("0");
        } else {
            advCap2EditBox.setValue(Integer.toString(editModeAllowCap2Value));
        }
    }

    private static int parseAllowCapFromEditBox(String raw) {
        if (raw == null) {
            return 0;
        }
        String t = raw.trim();
        if (t.isEmpty() || t.equals("\u221e") || t.equalsIgnoreCase("inf")) {
            return 0;
        }
        try {
            return (int) Mth.clamp(Long.parseLong(t), 0L, Integer.MAX_VALUE);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void adjustAdvCap(int sign) {
        playClickSound();
        int step = stepForBatchAdjust();
        if (sign < 0) {
            if (editModeAllowCapValue <= 0) {
                return;
            }
            editModeAllowCapValue = Math.max(0, editModeAllowCapValue - step);
        } else {
            if (editModeAllowCapValue <= 0) {
                editModeAllowCapValue = step;
            } else {
                editModeAllowCapValue = (int) Mth.clamp(
                    (long) editModeAllowCapValue + (long) step,
                    1L,
                    Integer.MAX_VALUE
                );
            }
        }
        syncAllowCapEditBoxDisplay();
    }

    private void adjustAdvCapWithStep(int sign, int step) {
        playClickSound();
        step = Math.max(1, step);
        if (sign < 0) {
            if (editModeAllowCapValue <= 0) {
                return;
            }
            editModeAllowCapValue = Math.max(0, editModeAllowCapValue - step);
        } else {
            if (editModeAllowCapValue <= 0) {
                editModeAllowCapValue = step;
            } else {
                editModeAllowCapValue = (int) Mth.clamp(
                    (long) editModeAllowCapValue + (long) step,
                    1L,
                    Integer.MAX_VALUE
                );
            }
        }
        syncAllowCapEditBoxDisplay();
    }

    private void adjustAdvCap2(int sign) {
        playClickSound();
        int step = stepForBatchAdjust();
        if (sign < 0) {
            if (editModeAllowCap2Value <= 0) {
                editModeAllowCap2Value = 0;
            } else {
                editModeAllowCap2Value = Math.max(
                    0,
                    editModeAllowCap2Value - step
                );
            }
        } else {
            if (editModeAllowCap2Value <= 0) {
                editModeAllowCap2Value = step;
            } else {
                editModeAllowCap2Value = (int) Mth.clamp(
                    (long) editModeAllowCap2Value + (long) step,
                    1L,
                    Integer.MAX_VALUE
                );
            }
        }
        syncAllowCap2EditBoxDisplay();
    }

    private void adjustAdvCap2WithStep(int sign, int step) {
        playClickSound();
        step = Math.max(1, step);
        if (sign < 0) {
            if (editModeAllowCap2Value <= 0) {
                editModeAllowCap2Value = 0;
            } else {
                editModeAllowCap2Value = Math.max(
                    0,
                    editModeAllowCap2Value - step
                );
            }
        } else {
            if (editModeAllowCap2Value <= 0) {
                editModeAllowCap2Value = step;
            } else {
                editModeAllowCap2Value = (int) Mth.clamp(
                    (long) editModeAllowCap2Value + (long) step,
                    1L,
                    Integer.MAX_VALUE
                );
            }
        }
        syncAllowCap2EditBoxDisplay();
    }

    /** Commits allow-line Limit/Keep to the client menu cache and server. */
    private void applyAllowCapField() {
        if (
            !isAdvancedFilterCapSubview() ||
            editModeFilterIndex < 0 ||
            advCapEditBox == null
        ) {
            return;
        }
        playClickSound();
        editModeAllowCapValue = parseAllowCapFromEditBox(
            advCapEditBox.getValue()
        );
        List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
        while (caps.size() <= editModeFilterIndex) {
            caps.add(0);
        }
        caps.set(editModeFilterIndex, editModeAllowCapValue);
        originalAllowCapValue = editModeAllowCapValue;
        pushFiltersToServer();
    }

    private void applyAllowCap2Field() {
        if (
            !isAdvancedFilterCapSubview() ||
            activeFilterBank != DuctFaceNode.FilterBank.FILTER ||
            editModeFilterIndex < 0 ||
            advCap2EditBox == null
        ) {
            return;
        }
        playClickSound();
        editModeAllowCap2Value = parseAllowCapFromEditBox(
            advCap2EditBox.getValue()
        );
        List<Integer> caps2 = menu.getClientFilterKeepCaps();
        while (caps2.size() <= editModeFilterIndex) {
            caps2.add(0);
        }
        caps2.set(editModeFilterIndex, editModeAllowCap2Value);
        originalAllowCap2Value = editModeAllowCap2Value;
        pushFiltersToServer();
    }

    /** Reverts Limit/Keep draft to the last applied value. */
    private void undoAllowCapDraft() {
        if (advCapEditBox == null) {
            return;
        }
        playClickSound();
        editModeAllowCapValue = originalAllowCapValue;
        advCapEditBox.setFocused(false);
        syncAllowCapEditBoxDisplay();
    }

    private void undoAllowCap2Draft() {
        if (advCap2EditBox == null) {
            return;
        }
        playClickSound();
        editModeAllowCap2Value = originalAllowCap2Value;
        advCap2EditBox.setFocused(false);
        syncAllowCap2EditBoxDisplay();
    }

    private int resolveHybridRoutingButtonId(boolean forward) {
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        if (nm == NodeMode.EXTRACTION_FILTERING && hybridPanel == HybridPanel.EXTRACTOR) {
            return forward
                    ? DuctBlockEntity.MENU_BUTTON_ROUTING_EXTRACTOR_FORWARD
                    : DuctBlockEntity.MENU_BUTTON_ROUTING_EXTRACTOR_BACK;
        }
        if (nm == NodeMode.RETRIEVING_EXTRACTION) {
            if (hybridPanel == HybridPanel.RETRIEVER) {
                return forward
                        ? DuctBlockEntity.MENU_BUTTON_ROUTING_RETRIEVER_FORWARD
                        : DuctBlockEntity.MENU_BUTTON_ROUTING_RETRIEVER_BACK;
            }
            if (hybridPanel == HybridPanel.EXTRACTOR) {
                return forward
                        ? DuctBlockEntity.MENU_BUTTON_ROUTING_EXTRACTOR_FORWARD
                        : DuctBlockEntity.MENU_BUTTON_ROUTING_EXTRACTOR_BACK;
            }
        }
        return forward ? 1 : 11;
    }

    private ItemStack redstoneButtonIconStack() {
        return switch (redstoneModeStub) {
            case 0 -> new ItemStack(Items.GUNPOWDER);
            case 1 -> new ItemStack(Items.REDSTONE);
            case 3 -> new ItemStack(Items.BARRIER);
            default -> ItemStack.EMPTY;
        };
    }

    private boolean routingButtonUsesEligibilityUi(NodeMode nm) {
        if (isEnergyOrHeatTransport()) {
            return false;
        }
        return nm == NodeMode.NONE
                || nm == NodeMode.FILTERING_INSERTION
                || (nm == NodeMode.EXTRACTION_FILTERING && hybridPanel == HybridPanel.FILTERING);
    }

    private int syncedRoutingModeOrdinal(NodeMode nm) {
        DuctRoutingUiSync.RoutingSlot slot =
                DuctRoutingUiSync.activeRoutingSlot(
                        nm,
                        hybridPanel == HybridPanel.EXTRACTOR,
                        hybridPanel == HybridPanel.RETRIEVER);
        return DuctRoutingUiSync.ordinalFromSlots(
                slot,
                menu.getSyncData().get(DuctMenuSync.ROUTING_MODE),
                menu.getSyncData().get(DuctMenuSync.ROUTING_MODE_EXTRACTOR),
                menu.getSyncData().get(DuctMenuSync.ROUTING_MODE_RETRIEVER));
    }

    private void handleMenuButton(int id) {
        if (shouldFlushFiltersBeforeMenuButton(id)) {
            flushPendingFilterEditsBeforeClose();
            reorderAndPushAllFilterBanks();
        }
        if (id == 1 || id == 11) {
            NodeMode nm = NodeMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.NODE_MODE)
            );
            if (routingButtonUsesEligibilityUi(nm)) {
                playClickSound();
                int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
                var cur = DuctFaceNode.EligibilityMode.fromOrdinal(ord);
                var next = switch (cur) {
                    case BOTH -> DuctFaceNode.EligibilityMode.INSERT_ONLY;
                    case INSERT_ONLY -> DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;
                    case RETRIEVE_ONLY -> DuctFaceNode.EligibilityMode.BOTH;
                };
                pushAmountFields(
                    syncedInsertionPriority(),
                    menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD),
                    next.ordinal()
                );
                return;
            }
            id = resolveHybridRoutingButtonId(id == 1);
        }
        playClickSound();
        ModNetwork.sendDuctMenuButton(menu, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        menu.updateClientDenyOverridesFromSync();
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        if (isEnergyBufferLimitsSubview() && !energyBufDirty) {
            int ex = menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_LIMIT_EXTRACT);
            int ins = menu.getSyncData().get(DuctMenuSync.ENERGY_BUF_LIMIT_INSERT);
            if (ex != energyBufExtractCommitted || ins != energyBufInsertCommitted) {
                syncEnergyBufFromServer();
            }
        }
        if (isEnergyBufferLimitsSubview()) {
            layoutEnergyBufferBlock();
        }
        if (isAdvancedFilterCapSubview() && advCapEditBox != null) {
            boolean f = advCapEditBox.isFocused();
            if (f != advCapEditHadFocus) {
                advCapEditHadFocus = f;
                if (!f) {
                    syncAllowCapEditBoxDisplay();
                } else if (editModeAllowCapValue == 0) {
                    advCapEditBox.setValue("");
                }
            }
            if (
                advCap2EditBox != null &&
                !advCap2EditBox.isFocused() &&
                editModeAllowCap2Value == 0
            ) {
                // Keep editor displays 0 when unfocused.
                syncAllowCap2EditBoxDisplay();
            }
            boolean limitCtx;
            if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
                // FILTER bank cap is interpreted as Limit when the face is insertable, and as Keep when it is retriever-only.
                int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
                DuctFaceNode.EligibilityMode em =
                    DuctFaceNode.EligibilityMode.fromOrdinal(ord);
                limitCtx = em.isInsertable();
            } else {
                limitCtx =
                    activeFilterBank == DuctFaceNode.FilterBank.RETRIEVER;
            }
            advCapMinusButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        limitCtx
                            ? "gui.another_dynamics.duct_node.allow_cap.minus.limit"
                            : "gui.another_dynamics.duct_node.allow_cap.minus.keep"
                    )
                )
            );
            advCapPlusButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        limitCtx
                            ? "gui.another_dynamics.duct_node.allow_cap.plus.limit"
                            : "gui.another_dynamics.duct_node.allow_cap.plus.keep"
                    )
                )
            );
            advCapEditBox.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        limitCtx
                            ? allowCapLimitTooltipKey()
                            : allowCapKeepTooltipKey()
                    )
                )
            );
            // Infinity/zero convenience button is contextual: Limit uses ∞, Keep uses 0.
            advCapInfinityButton.setMessage(
                Component.literal(limitCtx ? "\u221e" : "0")
            );
            advCapInfinityButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        limitCtx
                            ? "gui.another_dynamics.duct_node.allow_cap.set_unlimited"
                            : "gui.another_dynamics.duct_node.allow_cap.set_to_zero"
                    )
                )
            );

            if (advCap2MinusButton != null) {
                advCap2MinusButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.allow_cap.minus.keep"
                        )
                    )
                );
                advCap2PlusButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.allow_cap.plus.keep"
                        )
                    )
                );
                advCap2EditBox.setTooltip(
                    Tooltip.create(
                        Component.translatable(allowCapKeepTooltipKey())
                    )
                );
            }
        }
        NodeMode nm = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        syncActiveFilterBankToNodeMode(nm);
        boolean inHybridPanel =
            nm.isHybrid() && hybridPanel != HybridPanel.NONE;
        if (inHybridPanel) {
            nodeModeButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.hybrid.back"
                )
            );
            nodeModeButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.hybrid.back.tooltip"
                    )
                )
            );
        } else {
            nodeModeButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.mode." +
                        nm.name().toLowerCase()
                )
            );
            nodeModeButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.mode.tooltip." +
                            nm.name().toLowerCase()
                    )
                )
            );
        }
        int menuFlags = menu.getSyncData().get(DuctMenuSync.FLAGS);
        boolean filtersActive =
            (menuFlags & DuctMenuSync.FLAG_FILTERS_ACTIVE) != 0;
        if (!filtersActive
                && subView != SubView.MAIN
                && subView != SubView.BUFFER_LIMITS) {
            forceExitFilterUiToMain();
        }
        boolean routingUsable = nm.usesRouting();
        boolean hybridAllowsRoutingUi =
            !nm.isHybrid() ||
            (nm == NodeMode.EXTRACTION_FILTERING &&
                hybridPanel == HybridPanel.EXTRACTOR) ||
            (nm == NodeMode.RETRIEVING_EXTRACTION &&
                (hybridPanel == HybridPanel.EXTRACTOR ||
                    hybridPanel == HybridPanel.RETRIEVER));
        boolean routingMovedUi = nm.isHybrid() && hybridPanel == HybridPanel.NONE;
        boolean routingActive = routingUsable && hybridAllowsRoutingUi;

        boolean eligibilityCtx = routingButtonUsesEligibilityUi(nm);

        if (routingMovedUi) {
            routingModeButton.active = false;
            routingModeButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.routing_moved"
                )
            );
            routingModeButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.routing.tooltip.moved"
                    )
                )
            );
        } else if (eligibilityCtx) {
            int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
            DuctFaceNode.EligibilityMode em =
                DuctFaceNode.EligibilityMode.fromOrdinal(ord);
            routingModeButton.active = true;
            routingModeButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.eligibility." +
                        em.name().toLowerCase()
                )
            );
            routingModeButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.eligibility.tooltip." +
                            em.name().toLowerCase()
                    )
                )
            );
        } else if (!routingActive) {
            routingModeButton.active = false;
            routingModeButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.routing_unroutable"
                )
            );
            routingModeButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.routing.tooltip.unroutable"
                    )
                )
            );
        } else {
            routingModeButton.active = true;
            int rmOrd = syncedRoutingModeOrdinal(nm);
            RoutingMode rm = RoutingMode.fromOrdinal(rmOrd);
            routingModeButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.routing." +
                        rm.name().toLowerCase()
                )
            );
            routingModeButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.routing.tooltip." +
                            rm.name().toLowerCase()
                    )
                )
            );
        }
        if (
            minecraft != null &&
            minecraft.player != null &&
            opaqueRenderingButton != null
        ) {
            boolean locked = menu.isDuctAlwaysOpaqueLocked();
            if (menu instanceof DuctNodeMenu ductMenu) {
                ductMenu.refreshClientComponentNetworkOpaque(minecraft.level);
            }
            net.unfamily.another_dynamics.duct.DuctOpaqueDisplayState display =
                locked
                    ? net.unfamily.another_dynamics.duct.DuctOpaqueDisplayState.ALL
                    : net.unfamily.another_dynamics.duct.DuctOpaqueDisplayState.forContext(
                        minecraft.player,
                        minecraft.level,
                        menu.getDuctBlockPos());
            opaqueRenderingButton.setMessage(Component.translatable(display.labelKey()));
            opaqueRenderingButton.setTooltip(
                Tooltip.create(Component.translatable(display.tooltipKey())));
            opaqueRenderingButton.active = !locked;
        }
        if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
            if (nm == NodeMode.EXTRACTION_FILTERING) {
                denyNavButton.setMessage(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.hybrid.selector.extractor"
                    )
                );
                allowNavButton.setMessage(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.hybrid.selector.filtering"
                    )
                );
            } else {
                denyNavButton.setMessage(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.hybrid.selector.retrieving"
                    )
                );
                allowNavButton.setMessage(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.hybrid.selector.extractor"
                    )
                );
            }
            denyNavButton.active = true;
            allowNavButton.active = true;
            if (nm == NodeMode.EXTRACTION_FILTERING) {
                boolean on = (menu.getSyncData().get(DuctMenuSync.SELF_FEED) != 0);
                listLogicButton.setMessage(
                    Component.translatable(
                        on
                            ? "gui.another_dynamics.duct_node.hybrid.self_feed.on"
                            : "gui.another_dynamics.duct_node.hybrid.self_feed.off"
                    )
                );
                listLogicButton.active = true;
                listLogicButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.hybrid.self_feed.tooltip"
                        )
                    )
                );
                denyNavButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.hybrid.selector.extractor.tooltip"
                        )
                    )
                );
                allowNavButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.hybrid.selector.filtering.tooltip"
                        )
                    )
                );
            } else {
                // Self-feed applies only to Extr/Filt; Retr/Extr leaves the center slot blank.
                listLogicButton.setMessage(Component.empty());
                listLogicButton.active = false;
                listLogicButton.setTooltip(null);
                denyNavButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.hybrid.selector.retrieving.tooltip"
                        )
                    )
                );
                allowNavButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.hybrid.selector.extractor.tooltip"
                        )
                    )
                );
            }
        } else if (shouldBlankFilterNavButtons(nm)) {
            denyNavButton.setMessage(Component.empty());
            allowNavButton.setMessage(Component.empty());
            denyNavButton.active = false;
            allowNavButton.active = false;
            denyNavButton.setTooltip(null);
            allowNavButton.setTooltip(null);
            if (isEnergyTransportTab()) {
                if (isEnergyBufferLimitsSubview()) {
                    listLogicButton.setMessage(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.modify_buffers.back"));
                } else {
                    listLogicButton.setMessage(
                        Component.translatable("gui.another_dynamics.duct_node.modify_buffers"));
                }
                listLogicButton.active = true;
                listLogicButton.setTooltip(null);
            } else {
                listLogicButton.setMessage(Component.empty());
                listLogicButton.active = false;
                listLogicButton.setTooltip(null);
            }
        } else if (nm.isHybrid()) {
            denyNavButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.deny_list"
                )
            );
            allowNavButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.allow_list"
                )
            );
            denyNavButton.active = filtersActive;
            allowNavButton.active = filtersActive;
            listLogicButton.active = filtersActive;
            boolean denyOver = menu.getClientDenyOverridesAllow(
                activeFilterBank
            );
            listLogicButton.setMessage(listLogicButtonMessage(denyOver));
        } else {
            denyNavButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.deny_list"
                )
            );
            allowNavButton.setMessage(
                Component.translatable(
                    "gui.another_dynamics.duct_node.allow_list"
                )
            );
            denyNavButton.active = filtersActive;
            listLogicButton.active = filtersActive;
            allowNavButton.active = filtersActive;
            boolean denyOver =
                menu.getSyncData().get(DuctMenuSync.DENY_OVERRIDES_ALLOW) != 0;
            listLogicButton.setMessage(listLogicButtonMessage(denyOver));
            if (filtersActive) {
                denyNavButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.deny_list.tooltip"
                        )
                    )
                );
                allowNavButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.another_dynamics.duct_node.allow_list.tooltip"
                        )
                    )
                );
                listLogicButton.setTooltip(
                    Tooltip.create(
                        Component.translatable(
                            denyOver
                                ? "gui.another_dynamics.duct_node.list_logic.tooltip.deny_wins"
                                : "gui.another_dynamics.duct_node.list_logic.tooltip.allow_bypass"
                        )
                    )
                );
            } else if (!filtersActive) {
                var inactive = Tooltip.create(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.filters.tooltip.inactive"
                    )
                );
                denyNavButton.setTooltip(inactive);
                allowNavButton.setTooltip(inactive);
                listLogicButton.setTooltip(inactive);
            }
        }

        boolean amountIsPriority = amountFieldEditsPriority();
        routingPriorityBox.setTooltip(
            Tooltip.create(
                Component.translatable(
                    amountIsPriority
                        ? "gui.another_dynamics.duct_node.amount.field.tooltip.priority"
                        : "gui.another_dynamics.duct_node.amount.field.tooltip.batch"
                )
            )
        );
        routingMinusButton.setTooltip(
            Tooltip.create(
                Component.translatable(
                    amountIsPriority
                        ? "gui.another_dynamics.duct_node.amount.minus.tooltip.priority"
                        : "gui.another_dynamics.duct_node.amount.minus.tooltip.batch"
                )
            )
        );
        routingPlusButton.setTooltip(
            Tooltip.create(
                Component.translatable(
                    amountIsPriority
                        ? "gui.another_dynamics.duct_node.amount.plus.tooltip.priority"
                        : "gui.another_dynamics.duct_node.amount.plus.tooltip.batch"
                )
            )
        );
        if (amountClearButton != null) {
            amountClearButton.setMessage(
                Component.literal(amountIsPriority ? "0" : "1"));
            amountClearButton.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        amountIsPriority
                            ? "gui.another_dynamics.duct_node.amount.set_to_zero.tooltip.priority"
                            : "gui.another_dynamics.duct_node.amount.set_to_one.tooltip")));
        }

        redstoneModeStub = menu.getSyncData().get(DuctMenuSync.REDSTONE_MODE);
        if (redstoneModeButton != null) {
            redstoneModeButton.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "gui.another_dynamics.duct_node.redstone_mode."
                                            + redstoneModeStub)));
        }
        if (isHubChannelLocked()) {
            channelButton.setHubPlaceholder(true);
            channelButton.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "gui.another_dynamics.duct_node.channel_letter.hub_locked.tooltip")));
        } else {
            channelButton.setHubPlaceholder(false);
            channelButton.setLetterValue(
                    menu.getSyncData().get(DuctMenuSync.CHANNEL));
            MutableComponent channelTip = Component.empty();
            appendChannelLetterClickHints(channelTip, true);
            channelButton.setTooltip(Tooltip.create(channelTip));
        }

        Optional<DuctDefinition> syncDefOpt =
            DuctDefinitionRegistry.getByLogicalId(menu.getClientDuctLogicalId());
        EnumSet<DuctTransportKind> enabledKinds =
            syncDefOpt.map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        EnumSet<DuctTransportKind> suppressedMekKinds =
            syncDefOpt.map(DuctDefinition::suppressedMekKindsSet).orElse(EnumSet.noneOf(DuctTransportKind.class));
        boolean hubMain =
            menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
            menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0 &&
            subView == SubView.MAIN;
        for (DuctTransportKind k : DuctTransportKind.values()) {
            if (k.ordinal() >= transportKindPickerButtons.size()) {
                break;
            }
            Button picker = transportKindPickerButtons.get(k.ordinal());
            boolean suppressed = suppressedMekKinds.contains(k);
            boolean inDuct = enabledKinds.contains(k);
            boolean laneOn = inDuct && isTransportKindEnabledInMask(k);
            if (hubMain && inDuct) {
                picker.setMessage(hubPickerLabel(k, laneOn));
            } else {
                picker.setMessage(
                    Component.translatable(
                        "gui.another_dynamics.duct_node.transport." + k.name().toLowerCase()
                    )
                );
            }
            picker.active = inDuct && !suppressed && (!hubMain || laneOn);
            if (k.ordinal() < transportToggleButtons.size()) {
                Button toggle = transportToggleButtons.get(k.ordinal());
                if (suppressed) {
                    toggle.active = false;
                    if (hubMain) {
                        toggle.setMessage(hubToggleLabel(false));
                        ChatFormatting accent = hubTransportColumnColor(k);
                        toggle.setTooltip(
                            Tooltip.create(
                                Component.translatable(
                                                "gui.another_dynamics.duct_node.transport_toggle.tooltip.disabled")
                                        .append(
                                                Component.literal(" — ")
                                                        .withStyle(ChatFormatting.GRAY))
                                        .append(
                                                Component.translatable(
                                                                "gui.another_dynamics.duct_node.transport."
                                                                        + k.name().toLowerCase())
                                                        .withStyle(accent))));
                    }
                    continue;
                }
                if (!inDuct) {
                    continue;
                }
                toggle.active = true;
                if (hubMain) {
                    toggle.setMessage(hubToggleLabel(laneOn));
                    ChatFormatting accent = hubTransportColumnColor(k);
                    toggle.setTooltip(
                        Tooltip.create(
                            Component.translatable(
                                            laneOn
                                                    ? "gui.another_dynamics.duct_node.transport_toggle.tooltip.enabled"
                                                    : "gui.another_dynamics.duct_node.transport_toggle.tooltip.disabled")
                                    .append(
                                            Component.literal(" — ")
                                                    .withStyle(ChatFormatting.GRAY))
                                    .append(
                                            Component.translatable(
                                                            "gui.another_dynamics.duct_node.transport."
                                                                    + k.name().toLowerCase())
                                                    .withStyle(accent))));
                } else {
                    toggle.setMessage(
                        Component.translatable(
                            laneOn
                                ? "gui.another_dynamics.duct_node.transport_toggle.enabled"
                                : "gui.another_dynamics.duct_node.transport_toggle.disabled"
                        )
                    );
                }
            }
        }
        layoutHubTransportGrid();

        int layoutKey = amountBlockLayoutKey(nm, hybridPanel);
        if (amountBlockLayoutCache != layoutKey) {
            amountBlockLayoutCache = layoutKey;
            amountFieldsDirty = false;
            layoutAmountBlock();
            if (isAdvancedFilterCapSubview()) {
                layoutAdvancedCapBlock();
                layoutAdvancedRemoteNodeBlock();
                layoutEditModeWidgets();
            }
            applySubViewVisibility();
        }
        if (showsAdvancedCapEditors()) {
            int eligOrd = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
            int capLayoutKey =
                (activeFilterBank.ordinal() << 8) + (eligOrd & 0xFF);
            if (advCapLayoutCache != capLayoutKey) {
                advCapLayoutCache = capLayoutKey;
                layoutAdvancedCapBlock();
                layoutAdvancedRemoteNodeBlock();
                layoutEditModeWidgets();
            }
        }
        if (!amountIsPriority && nm.usesExtractBatchField()) {
            int capNow = syncedExtractBatchCap();
            if (
                lastTrackedExtractBatchCap >= 0 &&
                capNow < lastTrackedExtractBatchCap &&
                routingPriorityBox != null
            ) {
                int parsed = parsePriorityOr(
                    routingPriorityBox.getValue(),
                    syncedExtractBatch()
                );
                if (parsed > capNow) {
                    int clamped = Mth.clamp(parsed, 0, capNow);
                    syncingAmountBoxFromServer = true;
                    routingPriorityBox.setValue(Integer.toString(clamped));
                    syncingAmountBoxFromServer = false;
                    if (clamped == syncedExtractBatch()) {
                        amountFieldsDirty = false;
                    }
                }
            }
            lastTrackedExtractBatchCap = capNow;
        } else {
            lastTrackedExtractBatchCap = -1;
        }
        if (!routingPriorityBox.isFocused() && !amountFieldsDirty) {
            int v = amountIsPriority
                ? syncedInsertionPriority()
                : menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD);
            syncingAmountBoxFromServer = true;
            routingPriorityBox.setValue(Integer.toString(v));
            syncingAmountBoxFromServer = false;
        }

        filterScrollOffset = Mth.clamp(
            filterScrollOffset,
            0,
            maxFilterScroll()
        );

        // Server can change MENU_VIEW_LAYER / ACTIVE_TRANSPORT_KIND without node-mode layout key changing; keep hub vs detail visibility and positions in sync.
        int menuLayer = menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER);
        int transportKind = menu.filterTransportKindOrdinal();
        if (lastActiveTransportKind < 0) {
            lastActiveTransportKind = transportKind;
        } else if (transportKind != lastActiveTransportKind) {
            FilterSyncDebugLog.clientScreenAction(
                    "LANE_CHANGE_FLUSH",
                    lastActiveTransportKind,
                    menu.clientFiltersHydrated(lastActiveTransportKind),
                    menu.clientFiltersDirty(lastActiveTransportKind),
                    "newTk=" + FilterSyncDebugLog.transportKindName(transportKind));
            reorderAndPushAllFilterBanksForTransport(lastActiveTransportKind);
            FilterSyncDebugLog.clientPacket(
                    "FILTER_SYNC_REQUEST",
                    FilterSyncDebugLog.posFace(menu.getDuctBlockPos(), menu.getAccessFace())
                            + " reason=lane_change");
            ModNetwork.sendFilterSyncRequest(menu.getDuctBlockPos(), menu.getAccessFace());
            lastActiveTransportKind = transportKind;
            rebuildFilterEntryWidgets();
        }
        if (lastSyncedMenuViewLayer < 0) {
            lastSyncedMenuViewLayer = menuLayer;
        } else if (lastSyncedMenuViewLayer == 0 && menuLayer != 0) {
            ModNetwork.sendFilterSyncRequest(menu.getDuctBlockPos(), menu.getAccessFace());
            lastSyncedMenuViewLayer = menuLayer;
        } else if (isSettingsCopierAllVirtualMultiTransport() && lastSyncedMenuViewLayer != menuLayer) {
            exitEditMode(false);
            subView = SubView.MAIN;
            hybridPanel = HybridPanel.NONE;
            lastSyncedMenuViewLayer = menuLayer;
        }
        applySubViewVisibility();
        layoutMainChromeRowsForHubOrDetail();
        layoutHubTransportGrid();
        layoutHubBackButton();
    }

    /**
     * List-logic toggle and filter editing must target the bank that matches the current mode / hybrid panel;
     * otherwise the server toggles the wrong flag while the UI reads another (>>>> would appear stuck).
     */
    private void syncActiveFilterBankToNodeMode(NodeMode nm) {
        if (nm.isHybrid()) {
            if (hybridPanel == HybridPanel.NONE) {
                return;
            }
            activeFilterBank = switch (hybridPanel) {
                case EXTRACTOR -> DuctFaceNode.FilterBank.EXTRACTOR;
                case FILTERING -> DuctFaceNode.FilterBank.FILTER;
                case RETRIEVER -> DuctFaceNode.FilterBank.RETRIEVER;
                case NONE -> activeFilterBank;
            };
            return;
        }
        activeFilterBank = switch (nm) {
            case EXTRACTION -> DuctFaceNode.FilterBank.EXTRACTOR;
            case RETRIEVING -> DuctFaceNode.FilterBank.RETRIEVER;
            case FILTERING_INSERTION -> DuctFaceNode.FilterBank.FILTER;
            default -> activeFilterBank;
        };
    }

    private BlockPos menuSyncedPos() {
        return menu.getDuctBlockPos();
    }

    private Direction menuSyncedFace() {
        return menu.getAccessFace();
    }

    private int syncedInsertionPriority() {
        int lo = menu.getSyncData().get(DuctMenuSync.PRIORITY) & 0xFFFF;
        int hi = menu.getSyncData().get(DuctMenuSync.PRIORITY_HI) & 0xFFFF;
        return (hi << 16) | lo;
    }

    private int syncedExtractBatch() {
        return menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD);
    }

    /**
     * Server-authoritative max configurable extract batch; before {@link DuctMenuSync#EXTRACT_BATCH_CAP} syncs, derives
     * the same cap from registry + local module slots (see {@link net.unfamily.another_dynamics.duct.module.DuctModuleEffects}).
     */
    private int syncedExtractBatchCap() {
        int c = menu.getSyncData().get(DuctMenuSync.EXTRACT_BATCH_CAP);
        if (c > 0) {
            return c;
        }
        int bonus = 0;
        for (int i = 0; i < menu.moduleSlotCount(); i++) {
            Slot s = menu.getSlot(i);
            if (s != null && s.hasItem()) {
                // Future: parse module item stats client-side (keep in sync with DuctBlockEntity batch bonus).
            }
        }
        if (
            menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) ==
            DuctTransportKind.FLUID.ordinal()
        ) {
            return DuctDefinitionRegistry.getByLogicalId(
                menu.getClientDuctLogicalId()
            )
                .map(DuctDefinition::fluidTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::fluidDuctTransportSpec)
                .extractBatchSettingCapMb(bonus);
        }
        return DuctDefinitionRegistry.getByLogicalId(
            menu.getClientDuctLogicalId()
        )
            .map(DuctDefinition::itemTransportOrFallback)
            .orElseGet(DuctDefinitionRegistry::itemDuctTransportSpec)
            .extractBatchSettingCap(bonus);
    }

    private void pushAmountFields(int insertionPriority, int extractBatch) {
        pushAmountFields(
            insertionPriority,
            extractBatch,
            menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
        );
    }

    private void pushAmountFields(
        int insertionPriority,
        int extractBatch,
        int eligibilityModeOrdinal
    ) {
        ModNetwork.sendFieldUpdate(
            menuSyncedPos(),
            menuSyncedFace(),
            menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
            insertionPriority,
            extractBatch,
            eligibilityModeOrdinal
        );
    }

    private int stepForPriorityAdjust() {
        if (minecraft != null && minecraft.hasShiftDown()) {
            return PRIORITY_STEP_SHIFT;
        }
        if (minecraft != null && minecraft.hasControlDown() || minecraft != null && minecraft.hasAltDown()) {
            return PRIORITY_STEP_CTRL_OR_ALT;
        }
        return PRIORITY_STEP_PLAIN;
    }

    private int stepForBatchAdjust() {
        if (minecraft != null && minecraft.hasShiftDown()) {
            return BATCH_STEP_SHIFT;
        }
        if (minecraft != null && minecraft.hasControlDown() || minecraft != null && minecraft.hasAltDown()) {
            return BATCH_STEP_CTRL_OR_ALT;
        }
        return BATCH_STEP_PLAIN;
    }

    private int stackStepForCapAdjust() {
        int kind = menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND);
        return kind == DuctTransportKind.ITEM.ordinal() ? 64 : 1000;
    }

    private void adjustAmountField(int sign) {
        playClickSound();
        int priSynced = syncedInsertionPriority();
        int batchSynced = syncedExtractBatch();
        if (amountFieldEditsPriority()) {
            int base = parsePriorityOr(
                routingPriorityBox.getValue(),
                priSynced
            );
            int step = stepForPriorityAdjust();
            int pri = (int) Mth.clamp(
                (long) base + (long) sign * step,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE
            );
            syncingAmountBoxFromServer = true;
            routingPriorityBox.setValue(Integer.toString(pri));
            syncingAmountBoxFromServer = false;
        } else {
            int base = parsePriorityOr(
                routingPriorityBox.getValue(),
                batchSynced
            );
            int cap = syncedExtractBatchCap();
            int step = stepForBatchAdjust();
            int batch = Mth.clamp(base + sign * step, 0, cap);
            syncingAmountBoxFromServer = true;
            routingPriorityBox.setValue(Integer.toString(batch));
            syncingAmountBoxFromServer = false;
        }
        amountFieldsDirty = true;
    }

    /** Pushes priority + batch from the amount EditBox and server-paired field; clears dirty. */
    private void commitFieldFromEditBox() {
        int typed = parsePriority(routingPriorityBox.getValue());
        int pri = syncedInsertionPriority();
        int batch = syncedExtractBatch();
        if (amountFieldEditsPriority()) {
            pri = typed;
        } else {
            batch = Mth.clamp(typed, 0, syncedExtractBatchCap());
        }
        pushAmountFields(pri, batch);
        amountFieldsDirty = false;
    }

    /** Quick-set under amount field: priority → 0, extract/retrieve batch → 1. */
    private void amountQuickSetField() {
        playClickSound();
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue(amountFieldEditsPriority() ? "0" : "1");
        syncingAmountBoxFromServer = false;
        amountFieldsDirty = true;
    }

    private void amountMaxField() {
        if (amountFieldEditsPriority()) {
            return;
        }
        NodeMode nm = NodeMode.fromOrdinal(
            menu.getSyncData().get(DuctMenuSync.NODE_MODE)
        );
        if (!nm.usesExtractBatchField()) {
            return;
        }
        playClickSound();
        int cap = syncedExtractBatchCap();
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue(Integer.toString(cap));
        syncingAmountBoxFromServer = false;
        pushAmountFields(syncedInsertionPriority(), cap);
        amountFieldsDirty = false;
    }

    private void amountApplyField() {
        playClickSound();
        commitFieldFromEditBox();
    }

    private void revertAmountDraft(boolean defocus) {
        int v = amountFieldEditsPriority()
            ? syncedInsertionPriority()
            : syncedExtractBatch();
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue(Integer.toString(v));
        syncingAmountBoxFromServer = false;
        amountFieldsDirty = false;
        if (defocus) {
            routingPriorityBox.setFocused(false);
        }
    }

    /** Revert unsent amount/priority edits and defocus; button label ✕. */
    private void amountDiscardDraft() {
        playClickSound();
        revertAmountDraft(true);
    }

    private static int parsePriority(String s) {
        return parsePriorityOr(s, 0);
    }

    private static int parsePriorityOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void playClickSound() {
        if (minecraft != null) {
            minecraft
                .getSoundManager()
                .play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F)
                );
        }
    }


    private boolean routeHowToUseInputToWidgetsOnly(
        HowToUseInput op,
        double mouseX,
        double mouseY,
        int button,
        double dragX,
        double dragY,
        int keyCode,
        int scanCode,
        int modifiers,
        char codePoint
    ) {
        for (GuiEventListener child : children()) {
            boolean handled = switch (op) {
                case MOUSE_CLICK -> child.mouseClicked(
                    new MouseButtonEvent(
                        mouseX, mouseY, new MouseButtonInfo(button, modifiers)),
                    false);
                case MOUSE_RELEASE -> child.mouseReleased(
                    new MouseButtonEvent(
                        mouseX, mouseY, new MouseButtonInfo(button, modifiers)));
                case MOUSE_DRAG -> child.mouseDragged(
                    new MouseButtonEvent(
                        mouseX, mouseY, new MouseButtonInfo(button, modifiers)),
                    dragX,
                    dragY);
                case KEY -> child.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
                case CHAR -> child.charTyped(new CharacterEvent(codePoint));
            };
            if (handled) {
                return true;
            }
        }
        return false;
    }

    private enum HowToUseInput {
        MOUSE_CLICK,
        MOUSE_RELEASE,
        MOUSE_DRAG,
        KEY,
        CHAR,
    }

    @Override
    public void extractBackground(
        GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        if (subView == SubView.HOW_TO_USE) {
            if (minecraft != null && minecraft.level != null) {
                extractTransparentBackground(graphics);
                extractBlurredBackground(graphics);
            } else {
                super.extractBackground(graphics, mouseX, mouseY, partialTick);
            }
        } else {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        }

        if (subView == SubView.HOW_TO_USE) {
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                VALID_KEYS_TEXTURE,
                this.leftPos,
                this.topPos,
                0.0F,
                0.0F,
                this.imageWidth,
                this.imageHeight,
                TEXTURE_WIDTH,
                TEXTURE_HEIGHT);
            return;
        }

        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            TEXTURE,
            this.leftPos,
            this.topPos,
            0.0F,
            0.0F,
            this.imageWidth,
            this.imageHeight,
            TEXTURE_WIDTH,
            TEXTURE_HEIGHT);

        blitMachineSlotBackgrounds(graphics);

        if (
            subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS
        ) {
            renderFilterPanel(graphics, mouseX, mouseY);
            if (
                inEditMode() &&
                (subView == SubView.ALLOW_FILTERS ||
                    subView == SubView.DENY_FILTERS)
            ) {
                renderEditModeSlot(graphics);
            }
        } else if (subView == SubView.ADVANCED_FILTERING && inEditMode()) {
            renderEditModeSlot(graphics);
            renderAdvancedRemoteNodeBlock(graphics, mouseX, mouseY);
        }

        if (isSettingsCopierFilterListEditor()) {
            return;
        }
    }

    private void renderFilterPanel(
        GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY
    ) {
        int maxSlots = currentFilterMaxSlots();
        int vis = visibleFilterEntries();
        List<String> list = getEditingList();
        for (int i = 0; i < vis; i++) {
            int idx = filterScrollOffset + i;
            if (idx >= maxSlots) {
                break;
            }
            int entryX = this.leftPos + ENTRY_X;
            int entryY = this.topPos + FIRST_FILTER_ROW_Y + i * ENTRY_HEIGHT;
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ENTRY_ROW_TEXTURE,
                entryX,
                entryY,
                0.0F,
                0.0F,
                ENTRY_WIDTH,
                ENTRY_HEIGHT,
                ENTRY_WIDTH,
                ENTRY_HEIGHT);

            // Match DeepDrawerExtractorScreen: icon slot inset 3px from entry top-left (entry row is 24px tall).
            int slotX = entryX + 3;
            int slotY = entryY + 3;
            graphics.blit(RenderPipelines.GUI_TEXTURED, SINGLE_SLOT, slotX, slotY, 0.0F, 0.0F, 18, 18, 18, 18);
            String filter =
                idx < list.size() && list.get(idx) != null ? list.get(idx) : "";
            renderFilterRowSlotIcon(graphics, filter, slotX, slotY);

            int textX = slotX + 18 + 6;
            int buttonSize = 12;
            int buttonMargin = 4;
            int buttonSpacing = 2;
            int editButtonX = entryX + ENTRY_WIDTH - buttonMargin - buttonSize;
            int deleteButtonX = editButtonX - buttonSize - buttonSpacing;
            int concatButtonX = deleteButtonX - buttonSize - buttonSpacing;
            int maxTextWidth = concatButtonX - textX - 5;
            String displayText = filter;
            if (font.width(displayText) > maxTextWidth && !displayText.isEmpty()) {
                displayText =
                        font.plainSubstrByWidth(displayText, maxTextWidth - font.width("...")) + "...";
            }
            @Nullable DuctDirectionalEndpoint boundRemote =
                    filterRemoteNodeUiEnabled() ? remoteNodeAt(idx) : null;
            if (boundRemote != null) {
                int filterTextY = entryY + 4;
                graphics.text(font, displayText, textX, filterTextY, 0xFF404040, false);
                String coordLine =
                        truncateRemoteNodeCoordText(
                                remoteNodeSummaryCoordText(
                                        boundRemote, remoteIgnoreChannelAt(idx)),
                                maxTextWidth);
                int coordY = filterTextY + font.lineHeight + 1;
                graphics.text(font, coordLine, textX, coordY, 0xFF707070, false);
            } else {
                int textY = entryY + (ENTRY_HEIGHT - font.lineHeight) / 2;
                graphics.text(font, displayText, textX, textY, 0xFF404040, false);
            }
        }

        // After entry rows so the handle draws above the list edge (DeepDrawerExtractorScreen order).
        if (maxSlots > vis) {
            int scrollbarX = this.leftPos + SCROLLBAR_X_REL;
            int buttonUpY = this.topPos + BUTTON_UP_Y_REL;
            int scrollbarY = this.topPos + SCROLLBAR_Y_REL;
            int buttonDownY = this.topPos + BUTTON_DOWN_Y_REL;
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                SCROLLBAR_TEXTURE,
                scrollbarX,
                scrollbarY,
                0.0F,
                0.0F,
                SCROLLBAR_WIDTH,
                SCROLLBAR_HEIGHT,
                32,
                34);
            int upV =
                mouseX >= scrollbarX &&
                mouseX < scrollbarX + SCROLLBAR_WIDTH &&
                mouseY >= buttonUpY &&
                mouseY < buttonUpY + HANDLE_SIZE
                    ? HANDLE_SIZE
                    : 0;
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                SCROLLBAR_TEXTURE,
                scrollbarX,
                buttonUpY,
                (float) (SCROLLBAR_WIDTH * 2),
                (float) (upV),
                HANDLE_SIZE,
                HANDLE_SIZE,
                32,
                34);
            int downV =
                mouseX >= scrollbarX &&
                mouseX < scrollbarX + SCROLLBAR_WIDTH &&
                mouseY >= buttonDownY &&
                mouseY < buttonDownY + HANDLE_SIZE
                    ? HANDLE_SIZE
                    : 0;
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                SCROLLBAR_TEXTURE,
                scrollbarX,
                buttonDownY,
                (float) (SCROLLBAR_WIDTH * 3),
                (float) (downV),
                HANDLE_SIZE,
                HANDLE_SIZE,
                32,
                34);
            int maxScroll = maxFilterScroll();
            if (maxScroll > 0) {
                double ratio = (double) filterScrollOffset / maxScroll;
                int handleY =
                    scrollbarY +
                    (int) (ratio * (SCROLLBAR_HEIGHT - HANDLE_SIZE));
                int hV =
                    mouseX >= scrollbarX &&
                    mouseX < scrollbarX + HANDLE_SIZE &&
                    mouseY >= handleY &&
                    mouseY < handleY + HANDLE_SIZE
                        ? HANDLE_SIZE
                        : 0;
                graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    SCROLLBAR_TEXTURE,
                    scrollbarX,
                    handleY,
                    (float) (SCROLLBAR_WIDTH),
                    (float) (hV),
                    HANDLE_SIZE,
                    HANDLE_SIZE,
                    32,
                    34);
            }
        }
    }

    /** Redraw copy column above filter panels; frame then item (frame must not cover the icon). */
    private void renderCopySettingsSlotOnTop(GuiGraphicsExtractor graphics) {
        if (subView == SubView.HOW_TO_USE || !showsSettingsCopierColumn()) {
            return;
        }
        int copierSlot = menu.copySettingsSlotIndex();
        if (copierSlot < 0) {
            return;
        }
        int frameX = this.leftPos + DuctNodeMenu.SLOT_COPY_BACKGROUND_X;
        int frameY = this.topPos + DuctNodeMenu.SLOT_COPY_BACKGROUND_Y;
        int iconX = this.leftPos + DuctNodeMenu.SLOT_COPY_X;
        int iconY = this.topPos + DuctNodeMenu.SLOT_COPY_Y;
        ItemStack copier = menu.getSlot(copierSlot).getItem();

        graphics.pose().pushMatrix();
        graphics.nextStratum();
        SettingsCopierClient.blitSlotFrame(graphics, frameX, frameY);
        graphics.pose().popMatrix();

        if (!copier.isEmpty()) {
            graphics.pose().pushMatrix();
            graphics.nextStratum();
            graphics.item(copier, iconX, iconY);
            graphics.itemDecorations(this.font, copier, iconX, iconY);
            graphics.pose().popMatrix();
        }
    }

    private void blitMachineSlotBackgrounds(GuiGraphicsExtractor graphics) {
        int sw = 18;
        int sh = 18;
        for (int i = 0; i < menu.moduleSlotCount(); i++) {
            int y = DuctNodeMenu.SLOT_MODULE_BACKGROUND_Y0 + i * 18;
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                MODULE_SLOT,
                this.leftPos + DuctNodeMenu.SLOT_MODULE_BACKGROUND_X,
                this.topPos + y,
                0.0F,
                0.0F,
                sw,
                sh,
                sw,
                sh);
        }
        if (channelButton != null && channelButton.visible) {
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                SINGLE_SLOT,
                this.leftPos + DuctNodeMenu.CHANNEL_BACKGROUND_X,
                this.topPos + DuctNodeMenu.CHANNEL_BACKGROUND_Y,
                0.0F,
                0.0F,
                sw,
                sh,
                sw,
                sh);
        }
        if (showsSettingsCopierColumn() && menu.copySettingsSlotIndex() >= 0) {
            SettingsCopierClient.blitSlotFrame(
                    graphics,
                    this.leftPos + DuctNodeMenu.SLOT_COPY_BACKGROUND_X,
                    this.topPos + DuctNodeMenu.SLOT_COPY_BACKGROUND_Y);
        }
    }


    @Override
    protected void extractSlot(
        @NotNull GuiGraphicsExtractor graphics,
        @NotNull Slot slot,
        int mouseX,
        int mouseY
    ) {
        if (subView == SubView.HOW_TO_USE) {
            return;
        }
        // AbstractContainerScreen has already translated the pose by (leftPos, topPos); slot x/y are GUI-local.
        int copierSlot = menu.copySettingsSlotIndex();
        if (copierSlot >= 0 && slot.index == copierSlot) {
            return;
        }
        if (copierSlot > 0 && slot.index >= 0 && slot.index < copierSlot) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                graphics.item(stack, slot.x, slot.y);
                graphics.itemDecorations(this.font, stack, slot.x, slot.y);
            }
            return;
        }
        super.extractSlot(graphics, slot, mouseX, mouseY);
    }

    public static void showSettingsCopierFeedback(int messageId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractUniversalDuctScreen<?> screen) {
            screen.applyTransientFeedback(messageId);
        }
    }

    private void applyTransientFeedback(int messageId) {
        transientFeedback =
                switch (messageId) {
                    case DuctGuiFeedbackPayload.COPIED ->
                            Component.translatable("message.another_dynamics.settings_copier.copied")
                                    .withStyle(ChatFormatting.GREEN);
                    case DuctGuiFeedbackPayload.PASTED ->
                            Component.translatable("message.another_dynamics.settings_copier.pasted")
                                    .withStyle(ChatFormatting.GREEN);
                    case DuctGuiFeedbackPayload.PASTE_FAILED ->
                            Component.translatable("message.another_dynamics.settings_copier.paste_failed")
                                    .withStyle(ChatFormatting.RED);
                    case DuctGuiFeedbackPayload.WRONG_MODE ->
                            Component.translatable("message.another_dynamics.settings_copier.wrong_mode")
                                    .withStyle(ChatFormatting.RED);
                    default -> null;
                };
        if (transientFeedback != null) {
            transientFeedbackColor =
                    messageId == DuctGuiFeedbackPayload.PASTE_FAILED
                                    || messageId == DuctGuiFeedbackPayload.WRONG_MODE
                            ? 0xFFFF5555
                            : 0xFF55FF55;
            transientFeedbackHideAt = Util.getMillis() + TRANSIENT_FEEDBACK_MS;
        }
    }

    private void renderTransientFeedback(GuiGraphicsExtractor graphics) {
        if (transientFeedback == null) {
            return;
        }
        if (Util.getMillis() >= transientFeedbackHideAt) {
            transientFeedback = null;
            return;
        }
        int cx = leftPos + imageWidth / 2;
        int cy = topPos + DuctNodeMenu.PLAYER_SLOTS_Y - 12;
        graphics.text(this.font, transientFeedback, cx - this.font.width(transientFeedback) / 2, cy, transientFeedbackColor, false);
    }

    private boolean isMouseOverAnyVisibleTextField(
        double mouseX,
        double mouseY
    ) {
        if (
            routingPriorityBox != null &&
            routingPriorityBox.visible &&
            routingPriorityBox.isMouseOver(mouseX, mouseY)
        ) {
            return true;
        }
        if (
            editModeTextBox != null &&
            editModeTextBox.visible &&
            editModeTextBox.isMouseOver(mouseX, mouseY)
        ) {
            return true;
        }
        if (
            advCapEditBox != null &&
            advCapEditBox.visible &&
            advCapEditBox.isMouseOver(mouseX, mouseY)
        ) {
            return true;
        }
        if (
            advCap2EditBox != null &&
            advCap2EditBox.visible &&
            advCap2EditBox.isMouseOver(mouseX, mouseY)
        ) {
            return true;
        }
        return false;
    }

    /** Clears text focus so JEI search / other overlays receive keyboard input. */
    private void unfocusAllTextFields() {
        if (routingPriorityBox != null) {
            routingPriorityBox.setFocused(false);
        }
        if (editModeTextBox != null) {
            editModeTextBox.setFocused(false);
        }
        if (advCapEditBox != null) {
            advCapEditBox.setFocused(false);
            syncAllowCapEditBoxDisplay();
        }
        if (advCap2EditBox != null) {
            advCap2EditBox.setFocused(false);
            syncAllowCap2EditBoxDisplay();
        }
    }

    private boolean isMouseInsideOurGui() {
        return (
            lastMouseX >= this.leftPos &&
            lastMouseX < this.leftPos + this.imageWidth &&
            lastMouseY >= this.topPos &&
            lastMouseY < this.topPos + this.imageHeight
        );
    }

    private static boolean jeiIsHandlingKeyboard() {
        try {
            Class<?> c = Class.forName(
                "net.unfamily.another_dynamics.integration.jei.JeiRuntimeState"
            );
            return (boolean) c
                .getMethod("jeiHasKeyboardFocusOrRecipesGuiOpen")
                .invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (
            button == 0 &&
            subView != SubView.HOW_TO_USE &&
            !isMouseOverAnyVisibleTextField(mouseX, mouseY)
        ) {
            unfocusAllTextFields();
        }
        // Right-click on Mode cycles backward.
        if (button == 1 && nodeModeButton != null && nodeModeButton.visible) {
            if (
                mouseX >= nodeModeButton.getX() &&
                mouseX < nodeModeButton.getX() + nodeModeButton.getWidth() &&
                mouseY >= nodeModeButton.getY() &&
                mouseY < nodeModeButton.getY() + nodeModeButton.getHeight()
            ) {
                NodeMode nm = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE)
                );
                boolean inHybridPanel =
                    nm.isHybrid() && hybridPanel != HybridPanel.NONE;
                // In hybrid sub-panels, right-click behaves like Back.
                handleMenuButton(inHybridPanel ? 0 : 10);
                return true;
            }
        }
        if (
            button == 1 &&
            routingModeButton != null &&
            routingModeButton.visible &&
            routingModeButton.active
        ) {
            if (
                mouseX >= routingModeButton.getX() &&
                mouseX <
                routingModeButton.getX() + routingModeButton.getWidth() &&
                mouseY >= routingModeButton.getY() &&
                mouseY <
                routingModeButton.getY() + routingModeButton.getHeight()
            ) {
                handleMenuButton(11);
                return true;
            }
        }
        // Right-click on Opaque cycles backward: All -> Network, Network -> Off.
        if (button == 1 && opaqueRenderingButton != null && opaqueRenderingButton.visible) {
            if (
                mouseX >= opaqueRenderingButton.getX() &&
                mouseX < opaqueRenderingButton.getX() + opaqueRenderingButton.getWidth() &&
                mouseY >= opaqueRenderingButton.getY() &&
                mouseY < opaqueRenderingButton.getY() + opaqueRenderingButton.getHeight()
            ) {
                playClickSound();
                ModNetwork.sendDuctOpaqueToggleBackwards(menu.getDuctBlockPos());
                return true;
            }
        }
        if (subView == SubView.HOW_TO_USE && button == 0) {
            for (ExampleData exampleData : exampleDataList) {
                int sx = this.leftPos + exampleData.x;
                int sy = this.topPos + exampleData.y;
                if (
                    mouseX >= sx &&
                    mouseX <= sx + exampleData.width &&
                    mouseY >= sy &&
                    mouseY <= sy + this.font.lineHeight
                ) {
                    if (
                        minecraft != null && minecraft.keyboardHandler != null
                    ) {
                        minecraft.keyboardHandler.setClipboard(
                            exampleData.example
                        );
                        playClickSound();
                    }
                    return true;
                }
            }
        }
        if (subView == SubView.HOW_TO_USE) {
            routeHowToUseInputToWidgetsOnly(
                HowToUseInput.MOUSE_CLICK,
                mouseX,
                mouseY,
                button,
                0.0,
                0.0,
                0,
                0,
                0,
                '\0'
            );
            return true;
        }
        if (inEditMode() && button == 0) {
            if (showsAdvancedRemoteNodeBlock()) {
                AdvancedRemoteNodeRowLayout row = advancedRemoteNodeRowLayout();
                int remoteSlotX = row.slotScreenX();
                int remoteSlotY = row.slotScreenY();
                if (mouseX >= remoteSlotX
                        && mouseX < remoteSlotX + ADVANCED_REMOTE_NODE_SLOT_SIZE
                        && mouseY >= remoteSlotY
                        && mouseY < remoteSlotY + ADVANCED_REMOTE_NODE_SLOT_SIZE) {
                    handleAdvancedRemoteNodeSlotClick();
                    return true;
                }
            }
            int slotX = editModeSlotX();
            int slotY = editModeSlotY();
            if (
                mouseX >= slotX &&
                mouseX < slotX + 18 &&
                mouseY >= slotY &&
                mouseY < slotY + 18
            ) {
                handleGhostSlotClick();
                return true;
            }
        }
        if (isAllowOrDenyFilterListContext()) {
            if (handleFilterScrollButtonClick(mouseX, mouseY)) {
                return true;
            }
            if (handleFilterHandleClick(mouseX, mouseY)) {
                return true;
            }
            if (handleFilterScrollbarTrackClick(mouseX, mouseY)) {
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(
        double mouseX,
        double mouseY,
        double deltaX,
        double deltaY
    ) {
        if (isAllowOrDenyFilterListContext()) {
            if (deltaY > 0) {
                if (scrollUpSilent()) {
                    return true;
                }
            } else if (deltaY < 0) {
                if (scrollDownSilent()) {
                    return true;
                }
            }
        }
        if (subView == SubView.HOW_TO_USE) {
            return false;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseDragged(
        MouseButtonEvent event,
        double dragX,
        double dragY
    ) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (subView == SubView.HOW_TO_USE) {
            routeHowToUseInputToWidgetsOnly(
                HowToUseInput.MOUSE_DRAG,
                mouseX,
                mouseY,
                button,
                dragX,
                dragY,
                0,
                0,
                0,
                '\0'
            );
            return true;
        }
        if (
            button == 0 &&
            isDraggingHandle &&
            isAllowOrDenyFilterListContext() &&
            currentFilterMaxSlots() > visibleFilterEntries()
        ) {
            int maxScroll = maxFilterScroll();
            if (maxScroll > 0) {
                int deltaY = (int) mouseY - dragStartY;
                float scrollRatio =
                    (float) deltaY / (SCROLLBAR_HEIGHT - HANDLE_SIZE);
                int newOffset =
                    dragStartScrollOffset + (int) (scrollRatio * maxScroll);
                setFilterScrollOffset(newOffset);
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button == 0 && isDraggingHandle) {
            isDraggingHandle = false;
            return true;
        }
        if (subView == SubView.HOW_TO_USE) {
            routeHowToUseInputToWidgetsOnly(
                HowToUseInput.MOUSE_RELEASE,
                mouseX,
                mouseY,
                button,
                0.0,
                0.0,
                0,
                0,
                0,
                '\0'
            );
            return true;
        }
        return super.mouseReleased(event);
    }

    /** {@link AbstractContainerScreen} input only (no duct widgets); for settings copier hub layer. */
    protected boolean delegateContainerMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }

    protected boolean delegateContainerKeyPressed(KeyEvent event) {
        return super.keyPressed(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();
        if (
            routingPriorityBox != null &&
            routingPriorityBox.isFocused() &&
            keyCode == InputConstants.KEY_RETURN
        ) {
            commitFieldFromEditBox();
            return true;
        }

        if (
            advCapEditBox != null &&
            advCapEditBox.isFocused() &&
            keyCode == InputConstants.KEY_RETURN
        ) {
            applyAllowCapField();
            return true;
        }

        if (remoteNodeCoordEditOpen) {
            if (keyCode == InputConstants.KEY_RETURN) {
                applyRemoteNodeCoordEdit();
                return true;
            }
            if (keyCode == InputConstants.KEY_ESCAPE) {
                if (!jeiIsHandlingKeyboard() && isMouseInsideOurGui()) {
                    undoRemoteNodeCoordEdit();
                    return true;
                }
            }
        }

        if (
            keyCode == GLFW.GLFW_KEY_S &&
            isAdvancedFilterCapSubview() &&
            (
                (advCapEditBox != null && advCapEditBox.isFocused()) ||
                (advCap2EditBox != null && advCap2EditBox.isFocused())
            )
        ) {
            int sign = minecraft != null && minecraft.hasShiftDown() ? -1 : 1;
            int step = stackStepForCapAdjust();
            if (advCap2EditBox != null && advCap2EditBox.isFocused()) {
                adjustAdvCap2WithStep(sign, step);
            } else {
                adjustAdvCapWithStep(sign, step);
            }
            return true;
        }

        if (
            subView == SubView.MAIN &&
            !inEditMode() &&
            keyCode == GLFW.GLFW_KEY_M
        ) {
            NodeMode nm = NodeMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.NODE_MODE)
            );
            if (nm.usesExtractBatchField()) {
                amountMaxField();
                return true;
            }
        }

        boolean esc = keyCode == InputConstants.KEY_ESCAPE;
        boolean inv =
            minecraft != null &&
            minecraft.options.keyInventory != null &&
            minecraft.options.keyInventory.matches(event);

        boolean editFilterFocused =
            editModeTextBox != null && editModeTextBox.isFocused();

        if (editFilterFocused) {
            if (editModeTextBox.keyPressed(event)) {
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN) {
                applyEditModeAndClose();
                return true;
            }
            // Unfocus only by clicking outside the field (do not change focus via Esc/E).
            if (esc || inv) {
                return true;
            }
        }

        if (esc || inv) {
            // JEI overlays can be active while this screen is open; only treat Esc/E as our close keys when interacting
            // with our GUI (mouse over it).
            if (jeiIsHandlingKeyboard() || !isMouseInsideOurGui()) {
                return super.keyPressed(event);
            }
            if (useSettingsCopierHubNavigation()) {
                if (subView == SubView.MAIN) {
                    if (routingPriorityBox != null && routingPriorityBox.isFocused()) {
                        return super.keyPressed(event);
                    }
                    if (advCapEditBox != null && advCapEditBox.isFocused()) {
                        return super.keyPressed(event);
                    }
                    if (advCap2EditBox != null && advCap2EditBox.isFocused()) {
                        return super.keyPressed(event);
                    }
                }
                handleCloseOrBack();
                return true;
            }
            if (isSettingsCopierFilterListEditor()) {
                returnToSettingsCopierHubFromVirtual();
                return true;
            }
            if (subView == SubView.HOW_TO_USE) {
                playClickSound();
                closeFilterSubview();
                return true;
            }
            if (subView == SubView.ADVANCED_FILTERING) {
                playClickSound();
                if (popAdvancedFilterSubLevel()) {
                    return true;
                }
                closeAdvancedFiltering();
                return true;
            }
            if (inEditMode()) {
                exitEditMode(true);
                return true;
            }
            if (subView != SubView.MAIN) {
                playClickSound();
                closeFilterSubview();
                return true;
            }
            if (routingPriorityBox != null && routingPriorityBox.isFocused()) {
                return super.keyPressed(event);
            }
            if (advCapEditBox != null && advCapEditBox.isFocused()) {
                return super.keyPressed(event);
            }
            if (advCap2EditBox != null && advCap2EditBox.isFocused()) {
                return super.keyPressed(event);
            }
            NodeMode nm = NodeMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.NODE_MODE)
            );
            if (nm.isHybrid() && hybridPanel != HybridPanel.NONE) {
                playClickSound();
                hybridPanel = HybridPanel.NONE;
                applySubViewVisibility();
                return true;
            }
            if (shouldReturnToTransportHubInsteadOfClosing()) {
                playClickSound();
                handleMenuButton(DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB);
                return true;
            }
            if (useSettingsCopierHubNavigation()) {
                playClickSound();
                ModNetwork.sendSettingsCopierReturnToHub();
                return true;
            }
            onClose();
            return true;
        }

        if (subView == SubView.HOW_TO_USE) {
            routeHowToUseInputToWidgetsOnly(
                HowToUseInput.KEY,
                0.0,
                0.0,
                0,
                0.0,
                0.0,
                keyCode,
                scanCode,
                modifiers,
                '\0'
            );
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char codePoint = (char) event.codepoint();
        if (editModeTextBox != null && editModeTextBox.isFocused()) {
            if (editModeTextBox.charTyped(event)) {
                return true;
            }
        }
        if (advCapEditBox != null && advCapEditBox.isFocused()) {
            if (advCapEditBox.charTyped(event)) {
                return true;
            }
        }
        if (advCap2EditBox != null && advCap2EditBox.isFocused()) {
            if (advCap2EditBox.charTyped(event)) {
                return true;
            }
        }
        if (subView == SubView.HOW_TO_USE) {
            routeHowToUseInputToWidgetsOnly(
                HowToUseInput.CHAR,
                0.0,
                0.0,
                0,
                0.0,
                0.0,
                0,
                0,
                0,
                codePoint
            );
            return true;
        }
        return super.charTyped(event);
    }

    private void renderHelpLineWithExample(
        GuiGraphicsExtractor guiGraphics,
        String beforeKey,
        String exampleKey,
        String afterKey,
        int x,
        int y,
        int mouseX,
        int mouseY
    ) {
        Component beforeComponent = Component.translatable(beforeKey);
        Component exampleComponent = Component.translatable(exampleKey);
        Component afterComponent = Component.translatable(afterKey);
        String beforeText = beforeComponent.getString();
        String exampleText = exampleComponent.getString();
        String afterText = afterComponent.getString();
        int rowX = x;
        int rowY = y;
        int beforeWidth = this.font.width(beforeText);
        guiGraphics.text(
            this.font,
            beforeComponent,
            rowX,
            rowY,
            0xFF404040,
            false
        );
        int exampleX = rowX + beforeWidth;
        int exampleWidth = this.font.width(exampleText);
        int sx = this.leftPos + exampleX;
        int sy = this.topPos + rowY;
        boolean hovered =
            mouseX >= sx &&
            mouseX <= sx + exampleWidth &&
            mouseY >= sy &&
            mouseY <= sy + this.font.lineHeight;
        int exampleColor = hovered ? 0xFF0066FF : 0xFF0066CC;
        guiGraphics.text(
            this.font,
            exampleText,
            exampleX,
            rowY,
            exampleColor,
            false
        );
        if (hovered) {
            int underlineY = rowY + this.font.lineHeight;
            guiGraphics.fill(
                exampleX,
                underlineY,
                exampleX + exampleWidth,
                underlineY + 1,
                exampleColor
            );
        }
        exampleDataList.add(
            new ExampleData(exampleText, x + beforeWidth, y, exampleWidth)
        );
        if (!afterText.isEmpty()) {
            int afterX = exampleX + exampleWidth;
            guiGraphics.text(
                this.font,
                afterComponent,
                afterX,
                rowY,
                0xFF404040,
                false
            );
        }
    }

    /**
     * One opening/closing pair; comma-separated clickable examples, wrapped: {@value #MACRO_HELP_FIRST_LINE_EXAMPLES}
     * on the first row, up to {@value #MACRO_HELP_NEXT_LINE_EXAMPLES} on further rows (fluid/gas: four examples → 2+2).
     */
    private int renderHelpLineWithChainedExamples(
        GuiGraphicsExtractor guiGraphics,
        String beforeKey,
        String middleKey,
        String afterKey,
        List<String> exampleKeys,
        int maxExamplesFirstLine,
        int maxExamplesNextLines,
        int x,
        int y,
        int mouseX,
        int mouseY
    ) {
        if (exampleKeys.isEmpty()) {
            return 0;
        }
        List<List<String>> rows = new ArrayList<>();
        int idx = 0;
        boolean firstChunk = true;
        while (idx < exampleKeys.size()) {
            int cap = firstChunk ? maxExamplesFirstLine : maxExamplesNextLines;
            int end = Math.min(idx + cap, exampleKeys.size());
            rows.add(new ArrayList<>(exampleKeys.subList(idx, end)));
            idx = end;
            firstChunk = false;
        }
        Component beforeC = Component.translatable(beforeKey);
        Component middleC = Component.translatable(middleKey);
        Component afterC = Component.translatable(afterKey);
        String beforeText = beforeC.getString();
        String middleText = middleC.getString();
        String afterText = afterC.getString();
        int lineStep = this.font.lineHeight + 4;
        int rowY = y;
        for (int r = 0; r < rows.size(); r++) {
            List<String> rowKeys = rows.get(r);
            boolean isFirstRow = r == 0;
            boolean isLastRow = r == rows.size() - 1;
            int cursorX = x;
            if (isFirstRow) {
                guiGraphics.text(
                    this.font,
                    beforeC,
                    cursorX,
                    rowY,
                    0xFF404040,
                    false
                );
                cursorX += this.font.width(beforeText);
            }
            for (int i = 0; i < rowKeys.size(); i++) {
                Component exC = Component.translatable(rowKeys.get(i));
                String exText = exC.getString();
                int exW = this.font.width(exText);
                int sx = this.leftPos + cursorX;
                int sy = this.topPos + rowY;
                boolean hovered =
                    mouseX >= sx &&
                    mouseX <= sx + exW &&
                    mouseY >= sy &&
                    mouseY <= sy + this.font.lineHeight;
                int color = hovered ? 0xFF0066FF : 0xFF0066CC;
                guiGraphics.text(
                    this.font,
                    exText,
                    cursorX,
                    rowY,
                    color,
                    false
                );
                if (hovered) {
                    int uy = rowY + this.font.lineHeight;
                    guiGraphics.fill(cursorX, uy, cursorX + exW, uy + 1, color);
                }
                exampleDataList.add(
                    new ExampleData(exText, cursorX, rowY, exW)
                );
                cursorX += exW;
                boolean moreInThisRow = i < rowKeys.size() - 1;
                if (moreInThisRow) {
                    guiGraphics.text(
                        this.font,
                        middleC,
                        cursorX,
                        rowY,
                        0xFF404040,
                        false
                    );
                    cursorX += this.font.width(middleText);
                } else {
                    if (!isLastRow) {
                        guiGraphics.text(
                            this.font,
                            middleC,
                            cursorX,
                            rowY,
                            0xFF404040,
                            false
                        );
                        cursorX += this.font.width(middleText);
                    } else if (!afterText.isEmpty()) {
                        guiGraphics.text(
                            this.font,
                            afterC,
                            cursorX,
                            rowY,
                            0xFF404040,
                            false
                        );
                    }
                }
            }
            if (r < rows.size() - 1) {
                rowY += lineStep;
            }
        }
        return rows.size();
    }

    private void renderExampleTooltip(
        GuiGraphicsExtractor guiGraphics,
        int mouseX,
        int mouseY
    ) {
        for (ExampleData exampleData : exampleDataList) {
            int screenX = this.leftPos + exampleData.x;
            int screenY = this.topPos + exampleData.y;
            if (
                mouseX >= screenX &&
                mouseX <= screenX + exampleData.width &&
                mouseY >= screenY &&
                mouseY <= screenY + this.font.lineHeight
            ) {
                List<Component> tooltip = new ArrayList<>(2);
                tooltip.add(
                    Component.translatable(
                        "gui.another_dynamics.general_filter_text.click_to_copy"
                    )
                );
                tooltip.add(
                    Component.translatable(
                        "gui.another_dynamics.general_filter_text.paste_hint"
                    )
                );
                guiGraphics.setTooltipForNextFrame(
                    this.font,
                    tooltip,
                    java.util.Optional.empty(),
                    ItemStack.EMPTY,
                    mouseX,
                    mouseY,
                    null);
                return;
            }
        }
    }

    @Override
    protected void extractLabels(
        @NotNull GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY
    ) {
        Component titleComponent = switch (subView) {
            case BUFFER_LIMITS -> {
                NodeMode nmBuf = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                if (nmBuf.isHybrid() && hybridPanel != HybridPanel.NONE) {
                    yield Component.translatable(
                            switch (hybridPanel) {
                                case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                                case FILTERING -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                                case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                                default -> DuctIds.nodeScreenTranslationKey(menu.getClientDuctLogicalId());
                            });
                }
                yield Component.translatable(
                        DuctIds.nodeScreenTranslationKey(menu.getClientDuctLogicalId()));
            }
            case ADVANCED_FILTERING -> advancedFilterParentSubview == SubView.DENY_FILTERS
                    ? Component.translatable(
                            "gui.another_dynamics.duct_node.advanced_filtering.title.deny_remote")
                    : Component.translatable(
                            "gui.another_dynamics.duct_node.advanced_filtering.title");
            case DENY_FILTERS -> {
                NodeMode nm = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE)
                );
                if (nm.isHybrid()) {
                    yield Component.translatable(
                        switch (activeFilterBank) {
                            case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                            case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                            case FILTER -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                        }
                    );
                }
                yield Component.translatable(
                    "gui.another_dynamics.duct_node.deny_list"
                );
            }
            case ALLOW_FILTERS -> {
                if (isSettingsCopierFilterListEditor()) {
                    yield Component.translatable(
                            "gui.another_dynamics.settings_copier.virtual_filter");
                }
                NodeMode nm = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE)
                );
                if (nm.isHybrid()) {
                    yield Component.translatable(
                        switch (activeFilterBank) {
                            case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                            case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                            case FILTER -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                        }
                    );
                }
                yield Component.translatable(
                    "gui.another_dynamics.duct_node.allow_list"
                );
            }
            case HOW_TO_USE -> Component.translatable(
                "gui.another_dynamics.duct_node.filters.how_to_use"
            );
            case MAIN -> {
                Component copierTitle = settingsCopierVirtualMainTitle();
                if (copierTitle != null) {
                    yield copierTitle;
                }
                NodeMode nm = NodeMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.NODE_MODE)
                );
                if (nm.isHybrid() && hybridPanel != HybridPanel.NONE) {
                    yield Component.translatable(
                        switch (hybridPanel) {
                            case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                            case FILTERING -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                            case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                            default -> DuctIds.nodeScreenTranslationKey(
                                menu.getClientDuctLogicalId()
                            );
                        }
                    );
                }
                yield Component.translatable(
                    DuctIds.nodeScreenTranslationKey(
                        menu.getClientDuctLogicalId()
                    )
                );
            }
        };
        int titleWidth = this.font.width(titleComponent);
        int titleX = (this.imageWidth - titleWidth) / 2;
        /* Foreground: coordinates are relative—AbstractContainerScreen applies leftPos/topPos on the pose stack. */
        graphics.text(
            this.font,
            titleComponent,
            titleX,
            7,
            0xFF404040,
            false
        );

        if (isEnergyBufferLimitsSubview()) {
            renderEnergyBufferColumnLabels(graphics);
        }

        if (showsAdvancedCapEditors()) {
            boolean limitCtx;
            if (isSettingsCopierFilterListEditor()) {
                limitCtx = true;
            } else if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
                int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
                DuctFaceNode.EligibilityMode em =
                    DuctFaceNode.EligibilityMode.fromOrdinal(ord);
                limitCtx = em.isInsertable();
            } else {
                limitCtx =
                    activeFilterBank == DuctFaceNode.FilterBank.RETRIEVER;
            }
            Component capLabel = Component.translatable(
                limitCtx
                    ? "gui.another_dynamics.duct_node.allow_cap.label.limit"
                    : "gui.another_dynamics.duct_node.allow_cap.label.keep"
            );
            int lw = this.font.width(capLabel);
            boolean useKeepBox =
                activeFilterBank == DuctFaceNode.FilterBank.FILTER &&
                DuctFaceNode.EligibilityMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
                ) ==
                DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;
            int capEditW = useKeepBox
                ? (advCap2EditBox != null
                      ? advCap2EditBox.getWidth()
                      : AMOUNT_EDIT_W)
                : (advCapEditBox != null
                      ? advCapEditBox.getWidth()
                      : AMOUNT_EDIT_W);
            int labelLeft = useKeepBox
                ? advCap2EditBoxGuiLeft
                : advCapEditBoxGuiLeft;
            int labelX = labelLeft + (capEditW - lw) / 2;
            int labelY =
                ADVANCED_CAP_NUMERIC_ROW_GUI_Y -
                this.font.lineHeight -
                AMOUNT_LABEL_ABOVE_GAP;
            graphics.text(
                this.font,
                capLabel,
                labelX,
                labelY,
                0xFF404040,
                false
            );

            if (
                activeFilterBank == DuctFaceNode.FilterBank.FILTER &&
                DuctFaceNode.EligibilityMode.fromOrdinal(
                    menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE)
                ) ==
                DuctFaceNode.EligibilityMode.BOTH
            ) {
                Component keepLabel = Component.translatable(
                    "gui.another_dynamics.duct_node.allow_cap.label.keep"
                );
                int lw2 = this.font.width(keepLabel);
                int keepEditW =
                    advCap2EditBox != null
                        ? advCap2EditBox.getWidth()
                        : AMOUNT_EDIT_W;
                int labelX2 = advCap2EditBoxGuiLeft + (keepEditW - lw2) / 2;
                int labelY2 = labelY;
                graphics.text(
                    this.font,
                    keepLabel,
                    labelX2,
                    labelY2,
                    0xFF404040,
                    false
                );
            }
        }

        if (subView == SubView.MAIN) {
            NodeMode nm = NodeMode.fromOrdinal(
                menu.getSyncData().get(DuctMenuSync.NODE_MODE)
            );
            // Hybrid selector: priority/quantity block is hidden there; it appears only inside hybrid sub-panels.
            if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                return;
            }
            boolean hubLayer =
                menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1 &&
                menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0;
            if (!hubLayer) {
                Component amountLabel = Component.translatable(
                    amountFieldEditsPriority()
                        ? "gui.another_dynamics.duct_node.amount.label.priority"
                        : "gui.another_dynamics.duct_node.amount.label.batch"
                );
                int lw = this.font.width(amountLabel);
                int labelX = amountEditBoxGuiLeft + (AMOUNT_EDIT_W - lw) / 2;
                int labelY =
                    AMOUNT_ROW_Y -
                    this.font.lineHeight -
                    AMOUNT_LABEL_ABOVE_GAP;
                graphics.text(
                    this.font,
                    amountLabel,
                    labelX,
                    labelY,
                    0xFF404040,
                    false
                );
            }
        }

        if (subView == SubView.HOW_TO_USE) {
            exampleDataList.clear();
            int titleBaseline = 7;
            int gapBelowTitle = 10;
            int helpLineStep = this.font.lineHeight + 4;
            int helpY = titleBaseline + this.font.lineHeight + gapBelowTitle;
            String p = filterHelpTextPrefix();
            renderHelpLineWithExample(
                graphics,
                p + "id",
                p + "id.example",
                p + "id.after",
                HELP_TEXT_X,
                helpY,
                mouseX,
                mouseY
            );
            helpY += helpLineStep;
            renderHelpLineWithExample(
                graphics,
                p + "modid",
                p + "modid.example",
                p + "modid.after",
                HELP_TEXT_X,
                helpY,
                mouseX,
                mouseY
            );
            helpY += helpLineStep;
            if (!isGasFilterTransport()) {
                renderHelpLineWithExample(
                    graphics,
                    p + "tag",
                    p + "tag.example",
                    p + "tag.after",
                    HELP_TEXT_X,
                    helpY,
                    mouseX,
                    mouseY
                );
                helpY += helpLineStep;
            }
            int macroRows = renderHelpLineWithChainedExamples(
                      graphics,
                      p + "macro",
                      p + "macro.middle",
                      p + "macro.after",
                      p.endsWith("general_filter_text.")
                              ? List.of(
                                      p + "macro.example1",
                                      p + "macro.example2",
                                      p + "macro.example3",
                                      p + "macro.example4")
                              : List.of(
                                      p + "macro.example1",
                                      p + "macro.example2",
                                      p + "macro.example3",
                                      p + "macro.example4",
                                      p + "macro.example5"),
                      MACRO_HELP_FIRST_LINE_EXAMPLES,
                      MACRO_HELP_NEXT_LINE_EXAMPLES,
                      HELP_TEXT_X,
                      helpY,
                      mouseX,
                      mouseY
                  );
            helpY += macroRows * helpLineStep;
            graphics.text(
                    this.font,
                    Component.translatable(p + "operators"),
                    HELP_TEXT_X,
                    helpY,
                    0xFF404040,
                    false
            );
            helpY += helpLineStep;
            // Dedicated explanation for &anything_else (same key family for item/fluid/gas help prefixes).
            renderHelpLineWithExample(
                    graphics,
                    p + "anything_else",
                    p + "anything_else.example",
                    p + "anything_else.after",
                    HELP_TEXT_X,
                    helpY,
                    mouseX,
                    mouseY);
            helpY += helpLineStep;
            graphics.text(
                    this.font,
                    Component.translatable(p + "anything_else.detail"),
                    HELP_TEXT_X,
                    helpY,
                    0xFF404040,
                    false);
            helpY += helpLineStep;
            if (!isGasFilterTransport()) {
                graphics.text(
                    this.font,
                    Component.translatable(p + "nbt"),
                    HELP_TEXT_X,
                    helpY,
                    0xFF404040,
                    false
                );
                helpY += helpLineStep;
                renderHelpLineWithExample(
                    graphics,
                    p + "nbt.example",
                    p + "nbt.example.text",
                    p + "nbt.after",
                    HELP_TEXT_X,
                    helpY,
                    mouseX,
                    mouseY
                );
            }
        }
    }

    @Override
    public void extractRenderState(
        @NotNull GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (subView == SubView.HOW_TO_USE) {
            hoveredSlot = null;
            for (Renderable renderable : this.renderables) {
                renderable.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate(this.leftPos, this.topPos);
            extractLabels(graphics, mouseX, mouseY);
            graphics.pose().popMatrix();
            renderTransientFeedback(graphics);
            return;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        renderCopySettingsSlotOnTop(graphics);
        renderTransientFeedback(graphics);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (subView == SubView.HOW_TO_USE) {
            ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()) {
                graphics.item(carried, mouseX - 8, mouseY - 8);
                graphics.itemDecorations(this.font, carried, mouseX - 8, mouseY - 8);
            }
            renderExampleTooltip(graphics, mouseX, mouseY);
            return;
        }
        super.extractTooltip(graphics, mouseX, mouseY);
        int copierSlotIdx = menu.copySettingsSlotIndex();
        if (hoveredSlot != null && copierSlotIdx >= 0 && hoveredSlot.index == copierSlotIdx) {
            ItemStack copier = hoveredSlot.getItem();
            if (!copier.isEmpty()) {
                graphics.setTooltipForNextFrame(this.font, copier, mouseX, mouseY);
            } else {
                graphics.setTooltipForNextFrame(
                        this.font,
                        List.of(
                                Component.translatable(
                                        "gui.another_dynamics.duct_node.copy_slot.tooltip.line1"),
                                Component.translatable(
                                        "gui.another_dynamics.duct_node.copy_slot.tooltip.line2")),
                        java.util.Optional.empty(),
                        ItemStack.EMPTY,
                        mouseX,
                        mouseY,
                        null);
            }
        }
    }


    // ===== JEI Ghost Ingredient Integration (IAnDynamicsGhostTarget) =====

    @Override
    @Nullable
    public IAnDynamicsGhostTarget.IGhostIngredientConsumer getGhostHandler() {
        // Allow drops in allow/deny edit mode and in advanced filtering (same ghost calibration slot).
        if (!isGhostSlotJeiContext()) {
            return null;
        }

        // Create a consumer that handles the dropped ingredient
        return new IAnDynamicsGhostTarget.IGhostIngredientConsumer() {
            @Override
            @Nullable
            public Object supportedTarget(Object ingredient) {
                // Validate ingredient type based on transport kind
                if (
                    ingredient instanceof ItemStack itemStack &&
                    !itemStack.isEmpty()
                ) {
                    return itemStack;
                }
                if (
                    ingredient instanceof FluidStack fluidStack &&
                    !fluidStack.isEmpty()
                ) {
                    return fluidStack;
                }
                // Check for Mekanism chemicals if loaded
                if (MekanismChemicalCompat.isLoaded() && ingredient != null) {
                    if (!MekanismChemicalCompat.isEmptyStack(ingredient)) {
                        return ingredient;
                    }
                }
                return null;
            }

            @Override
            public void accept(Object ingredient) {
                // Use the actual ingredient passed from JEI
                handleGhostIngredientDrop(ingredient);
            }
        };
    }

    @Override
    @Nullable
    public net.minecraft.client.renderer.Rect2i getGhostTargetArea() {
        if (!isGhostSlotJeiContext()) {
            return null;
        }

        // Return the bounding box of the ghost slot in edit mode
        // editModeSlotX/Y already return screen-space coordinates
        int slotX = editModeSlotX();
        int slotY = editModeSlotY();
        int slotSize = 18;

        return new net.minecraft.client.renderer.Rect2i(
            slotX,
            slotY,
            slotSize,
            slotSize
        );
    }
}
