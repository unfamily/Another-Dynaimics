package net.unfamily.another_dynamics.client.gui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctBlockEntity;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.registry.ModAttachments;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

import org.jetbrains.annotations.NotNull;

/**
 * Duct node GUI: upgrades, center controls, filter sub-screens (Deep Drawer Extractor parity), right column, inventory.
 */
public final class DuctNodeScreen extends AbstractContainerScreen<DuctNodeMenu> {
    private enum HybridPanel {
        NONE,
        EXTRACTOR,
        FILTERING,
        RETRIEVER
    }
    private enum SubView {
        MAIN,
        DENY_FILTERS,
        ALLOW_FILTERS,
        ADVANCED_FILTERING,
        HOW_TO_USE
    }

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/background/node.png");
    private static final ResourceLocation VALID_KEYS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/background/valid_keys.png");
    private static final ResourceLocation MEDIUM_BUTTONS =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/medium_buttons.png");
    private static final ResourceLocation REDSTONE_GUI =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/redstone_gui.png");
    private static final ResourceLocation SINGLE_SLOT =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot.png");
    private static final ResourceLocation ENTRY_ROW_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/entry_duct.png");
    private static final ResourceLocation SCROLLBAR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/scrollbar.png");

    private static final int TEXTURE_WIDTH = 320;
    private static final int TEXTURE_HEIGHT = 256;

    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_Y = 5;
    private static final int CLOSE_BUTTON_X = TEXTURE_WIDTH - CLOSE_BUTTON_SIZE - 5;

    private static final int REDSTONE_BUTTON_SIZE = 16;
    private static final int REDSTONE_ICON_SIZE = 12;

    private static final int CENTER_X = 38;
    private static final int ROW1_Y = 32;
    private static final int ROW2_Y = 50;
    private static final int ROW3_Y = 70;
    private static final int BTN_H = 14;

    private static final int ROW_BTN_W = 76;
    private static final int ROW_GAP = 4;

    /**
     * Priority / quantity block: centered, two rows (numeric then 0/A/[M]/Close-without-saving). Slightly above player inventory.
     */
    /** +/- steps: priority uses 1 / Ctrl 10 / Alt 100. Quantity and allow-cap Limit/Keep: 1 / Ctrl+Alt 8 / Shift 64. */
    private static final int PRIORITY_STEP_PLAIN = 1;
    private static final int PRIORITY_STEP_CTRL = 10;
    private static final int PRIORITY_STEP_ALT = 100;
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

    private static final int CHANNEL_WIDGET_W = 18;
    private static final int CHANNEL_WIDGET_H = 18;
    /** Below the copy-settings slot in the same column (slot is 18px tall). */
    private static final int CHANNEL_WIDGET_GAP_BELOW_COPY_SLOT = 4;
    private static final int CHANNEL_WIDGET_Y =
            DuctNodeMenu.SLOT_COPY_Y + 18 + CHANNEL_WIDGET_GAP_BELOW_COPY_SLOT;
    private static final int TRANSPORT_KIND_BUTTON_Y = CHANNEL_WIDGET_Y + CHANNEL_WIDGET_H + 4;
    private static final int TRANSPORT_PICKER_BTN_W = 72;
    private static final int TRANSPORT_PICKER_GAP = 4;

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
    private static final int BUTTON_DOWN_Y_REL = SCROLLBAR_Y_REL + SCROLLBAR_HEIGHT;

    /** Left inset for Valid keys body text (inside panel border). */
    private static final int HELP_TEXT_X = 14;
    private static final int HELP_BACK_BUTTON_X = 8;
    private static final int HELP_BACK_BUTTON_Y = TEXTURE_HEIGHT - 25;

    private int redstoneButtonScreenX;
    private int redstoneButtonScreenY;
    private int redstoneModeStub;

    private Button closeButton;
    private Button nodeModeButton;
    private Button routingModeButton;
    private Button routingMinusButton;
    private Button routingPlusButton;
    private Button amountClearButton;
    private Button amountApplyButton;
    private Button amountMaxButton;
    private Button amountDiscardButton;
    private ChannelLetterButton channelButton;
    private final List<Button> transportKindPickerButtons = new ArrayList<>();
    private Button hubBackButton;
    private EditBox routingPriorityBox;

    private Button denyNavButton;
    private Button listLogicButton;
    private Button allowNavButton;
    private Button selfFeedStub;
    private Button opaqueRenderingButton;
    private Button backButton;
    private Button validKeysButton;

    private SubView subView = SubView.MAIN;
    private SubView filterListBeforeHelp = SubView.DENY_FILTERS;
    private DuctFaceNode.FilterBank activeFilterBank = DuctFaceNode.FilterBank.FILTER;
    private HybridPanel hybridPanel = HybridPanel.NONE;
    private int filterScrollOffset;
    private boolean isDraggingHandle;
    private int dragStartY;
    private int dragStartScrollOffset;

    /** Main GUI priority/batch: unsent local edits until Apply (or Enter); discard reverts draft (tooltip Cancel). */
    private boolean amountFieldsDirty;
    private boolean syncingAmountBoxFromServer;
    /** Packed {@link #amountBlockLayoutKey}: relayout amount row when mode or hybrid sub-panel changes (M button / steps). */
    private int amountBlockLayoutCache = -1;

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

    // Edit mode (ported from DeepDrawerExtractorScreen)
    private int editModeFilterIndex = -1;
    private String originalFilterValue = "";
    private EditBox editModeTextBox;
    private Button leftArrowButton;
    private Button rightArrowButton;
    private Button editModeClearButton;
    private Button editModeApplyButton;
    private Button editModeCloseButton;
    private Button advancedFilteringOpenButton;
    private int editModeAllowCapValue;
    private int originalAllowCapValue;
    private Button advCapMinusButton;
    private Button advCapPlusButton;
    private EditBox advCapEditBox;
    private Button advCapInfinityButton;
    private Button advCapApplyButton;
    private Button advCapUndoButton;
    private boolean advCapEditHadFocus;
    private ItemStack ghostSlotItem = ItemStack.EMPTY;
    /** When editing fluid filters, preview still sprite from {@link FluidUtil#getFluidContained} on the ghost item. */
    private FluidStack ghostSlotFluid = FluidStack.EMPTY;
    private List<String> filterVariants = new ArrayList<>();
    private int currentFilterVariantIndex = 0;

    private int editGhostSlotScreenX;
    private int editGhostSlotScreenY;
    private int lastMouseX;
    private int lastMouseY;

    public DuctNodeScreen(DuctNodeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = TEXTURE_WIDTH;
        this.imageHeight = TEXTURE_HEIGHT;
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
            boolean denyOverridesAllow) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!(mc.player.containerMenu instanceof DuctNodeMenu menu)) {
            return;
        }
        menu.receiveFilterSync(
                pos,
                face,
                transportKindOrdinal,
                filterBankOrdinal,
                allow,
                deny,
                allowCaps,
                allowCaps2,
                denyOverridesAllow);
        if (mc.screen instanceof DuctNodeScreen screen && screen.getMenu() == menu) {
            screen.menu.ensureClientFilterBufferSizes(screen.useHybridFilterCaps());
            screen.rebuildFilterEntryWidgets();
        }
    }

    @Override
    protected void init() {
        super.init();

        redstoneButtonScreenX = this.leftPos + DuctNodeMenu.REDSTONE_GUI_X;
        redstoneButtonScreenY = this.topPos + DuctNodeMenu.REDSTONE_GUI_Y;

        closeButton = Button.builder(Component.literal("\u2715"), b -> {
                    playClickSound();
                    handleCloseOrBack();
                })
                .bounds(this.leftPos + CLOSE_BUTTON_X, this.topPos + CLOSE_BUTTON_Y, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE)
                .build();
        addRenderableWidget(closeButton);

        denyNavButton = Button.builder(Component.translatable("gui.another_dynamics.duct_node.deny_list"), b -> {
                    playClickSound();
                    NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                    if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                        hybridPanel = (nm == NodeMode.EXTRACTION_FILTERING) ? HybridPanel.EXTRACTOR : HybridPanel.RETRIEVER;
                        activeFilterBank = (hybridPanel == HybridPanel.EXTRACTOR)
                                ? DuctFaceNode.FilterBank.EXTRACTOR
                                : DuctFaceNode.FilterBank.RETRIEVER;
                        applySubViewVisibility();
                        return;
                    }
                    openFilterSubview(SubView.DENY_FILTERS);
                })
                .bounds(this.leftPos + CENTER_X, this.topPos + ROW1_Y, ROW_BTN_W, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.deny_list.tooltip")))
                .build();
        addRenderableWidget(denyNavButton);

        listLogicButton = Button.builder(Component.literal(">>>>>"), b -> {
                    playClickSound();
                    NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                    if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                        boolean on = (menu.getSyncData().get(DuctMenuSync.SELF_FEED) != 0);
                        ModNetwork.sendSelfFeedSet(menuSyncedPos(), menuSyncedFace(), !on);
                        return;
                    }
                    ModNetwork.sendListLogicToggle(
                            menuSyncedPos(),
                            menuSyncedFace(),
                            menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                            activeFilterBank.ordinal());
                })
                .bounds(
                        this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                        this.topPos + ROW1_Y,
                        ROW_BTN_W,
                        BTN_H)
                .build();
        addRenderableWidget(listLogicButton);

        allowNavButton = Button.builder(Component.translatable("gui.another_dynamics.duct_node.allow_list"), b -> {
                    playClickSound();
                    NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                    if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                        hybridPanel = (nm == NodeMode.EXTRACTION_FILTERING) ? HybridPanel.FILTERING : HybridPanel.EXTRACTOR;
                        activeFilterBank = (hybridPanel == HybridPanel.FILTERING)
                                ? DuctFaceNode.FilterBank.FILTER
                                : DuctFaceNode.FilterBank.EXTRACTOR;
                        applySubViewVisibility();
                        return;
                    }
                    openFilterSubview(SubView.ALLOW_FILTERS);
                })
                .bounds(
                        this.leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                        this.topPos + ROW1_Y,
                        ROW_BTN_W,
                        BTN_H)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.allow_list.tooltip")))
                .build();
        addRenderableWidget(allowNavButton);

        int r2x = CENTER_X + 2 * (ROW_BTN_W + ROW_GAP);
        routingModeButton = Button.builder(Component.empty(), b -> handleMenuButton(1))
                .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, ROW_BTN_W, BTN_H)
                .build();
        addRenderableWidget(routingModeButton);

        routingMinusButton =
                Button.builder(Component.literal("-"), b -> adjustAmountField(-1))
                        .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.amount.minus.tooltip.priority")))
                        .build();
        addRenderableWidget(routingMinusButton);
        routingPriorityBox = new EditBox(this.font, 0, 0, AMOUNT_EDIT_W, BTN_H, Component.empty());
        routingPriorityBox.setMaxLength(6);
        routingPriorityBox.setResponder(s -> {
            if (!syncingAmountBoxFromServer) {
                amountFieldsDirty = true;
            }
        });
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue("0");
        syncingAmountBoxFromServer = false;
        addRenderableWidget(routingPriorityBox);
        routingPlusButton =
                Button.builder(Component.literal("+"), b -> adjustAmountField(1))
                        .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable("gui.another_dynamics.duct_node.amount.plus.tooltip.priority")))
                        .build();
        addRenderableWidget(routingPlusButton);

        amountClearButton =
                Button.builder(Component.literal("0"), b -> amountClearField())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.amount.set_to_zero.tooltip")))
                        .build();
        addRenderableWidget(amountClearButton);
        amountApplyButton =
                Button.builder(Component.literal("A"), b -> amountApplyField())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.apply")))
                        .build();
        addRenderableWidget(amountApplyButton);
        amountMaxButton =
                Button.builder(Component.literal("M"), b -> amountMaxField())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.amount.set_to_max.tooltip")))
                        .build();
        addRenderableWidget(amountMaxButton);
        amountDiscardButton =
                Button.builder(Component.literal("\u2715"), b -> amountDiscardDraft())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.amount.undo.tooltip")))
                        .build();
        addRenderableWidget(amountDiscardButton);

        layoutAmountBlock();

        advCapMinusButton =
                Button.builder(Component.literal("-"), b -> adjustAdvCap(-1))
                        .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.allow_cap.minus.limit")))
                        .build();
        addRenderableWidget(advCapMinusButton);
        advCapEditBox = new EditBox(this.font, 0, 0, AMOUNT_EDIT_W, BTN_H, Component.literal("Limit/Keep"));
        advCapEditBox.setMaxLength(10);
        advCapEditBox.setResponder(
                v -> {
                    String t = v.trim();
                    if (t.isEmpty() || t.equals("\u221e") || t.equalsIgnoreCase("inf")) {
                        editModeAllowCapValue = 0;
                        return;
                    }
                    try {
                        editModeAllowCapValue =
                                (int) Mth.clamp(Long.parseLong(t), 0L, Integer.MAX_VALUE);
                    } catch (NumberFormatException ignored) {
                    }
                });
        addRenderableWidget(advCapEditBox);
        advCapPlusButton =
                Button.builder(Component.literal("+"), b -> adjustAdvCap(1))
                        .bounds(0, 0, AMOUNT_STEPPER_W, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.allow_cap.plus.limit")))
                        .build();
        addRenderableWidget(advCapPlusButton);
        advCapInfinityButton =
                Button.builder(Component.literal("0"), b -> {
                            playClickSound();
                            editModeAllowCapValue = 0;
                            syncAllowCapEditBoxDisplay();
                        })
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable("gui.another_dynamics.duct_node.allow_cap.set_to_zero")))
                        .build();
        addRenderableWidget(advCapInfinityButton);
        advCapApplyButton =
                Button.builder(Component.literal("A"), b -> applyAllowCapField())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.apply")))
                        .build();
        addRenderableWidget(advCapApplyButton);
        advCapUndoButton =
                Button.builder(Component.literal("\u2715"), b -> undoAllowCapDraft())
                        .bounds(0, 0, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable("gui.another_dynamics.duct_node.amount.undo.tooltip")))
                        .build();
        addRenderableWidget(advCapUndoButton);
        layoutAdvancedCapBlock();

        nodeModeButton = Button.builder(Component.empty(), b -> {
                    playClickSound();
                    NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                    if (nm.isHybrid() && hybridPanel != HybridPanel.NONE) {
                        hybridPanel = HybridPanel.NONE;
                        applySubViewVisibility();
                        return;
                    }
                    handleMenuButton(0);
                })
                .bounds(this.leftPos + CENTER_X, this.topPos + ROW2_Y, ROW_BTN_W, BTN_H)
                .build();
        addRenderableWidget(nodeModeButton);
        selfFeedStub = Button.builder(Component.translatable("gui.another_dynamics.duct_node.self_feed"), b -> playClickSound())
                .bounds(
                        this.leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                        this.topPos + ROW3_Y,
                        ROW_BTN_W,
                        BTN_H)
                .build();
        addRenderableWidget(selfFeedStub);
        opaqueRenderingButton =
                Button.builder(Component.empty(), b -> {
                            if (menu.isDuctAlwaysOpaqueLocked()) {
                                return;
                            }
                            playClickSound();
                            ModNetwork.sendDuctOpaqueToggle();
                        })
                        .bounds(
                                this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                                this.topPos + ROW2_Y,
                                ROW_BTN_W,
                                BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.opaque_rendering.tooltip")))
                        .build();
        addRenderableWidget(opaqueRenderingButton);

        hubBackButton =
                Button.builder(Component.translatable("gui.another_dynamics.duct_node.hub_back"), b -> {
                            playClickSound();
                            handleMenuButton(DuctBlockEntity.MENU_BUTTON_BACK_TO_HUB);
                        })
                        .bounds(0, 0, ROW_BTN_W, BTN_H)
                        .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hub_back.tooltip")))
                        .build();
        addRenderableWidget(hubBackButton);

        backButton = Button.builder(Component.translatable("gui.another_dynamics.duct_node.filters.back"), b -> {
                    playClickSound();
                    if (subView == SubView.ADVANCED_FILTERING) {
                        closeAdvancedFiltering();
                    } else {
                        closeFilterSubview();
                    }
                })
                .bounds(
                        this.leftPos + CENTER_X,
                        this.topPos + filterNavRowScreenY(),
                        ROW_BTN_W,
                        BTN_H)
                .build();
        addRenderableWidget(backButton);

        validKeysButton = Button.builder(Component.translatable("gui.another_dynamics.duct_node.filters.how_to_use"), b -> {
                    playClickSound();
                    exitEditMode(false);
                    filterListBeforeHelp = subView;
                    subView = SubView.HOW_TO_USE;
                    applySubViewVisibility();
                    rebuildFilterEntryWidgets();
                })
                .bounds(
                        this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                        this.topPos + filterNavRowScreenY(),
                        ROW_BTN_W,
                        BTN_H)
                .build();
        addRenderableWidget(validKeysButton);

        int channelX = DuctNodeMenu.SLOT_COPY_X + (18 - CHANNEL_WIDGET_W) / 2;
        channelButton = new ChannelLetterButton(
                this.leftPos + channelX,
                this.topPos + CHANNEL_WIDGET_Y,
                CHANNEL_WIDGET_W,
                CHANNEL_WIDGET_H,
                dir -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        int id = dir > 0 ? 4 : 5;
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
                    }
                });
        addRenderableWidget(channelButton);

        transportKindPickerButtons.clear();
        for (DuctTransportKind k : DuctTransportKind.values()) {
            final int kindOrdinal = k.ordinal();
            Button b =
                    Button.builder(Component.empty(), btn -> handleMenuButton(DuctBlockEntity.MENU_BUTTON_TRANSPORT_KIND_BASE + kindOrdinal))
                            .bounds(0, 0, TRANSPORT_PICKER_BTN_W, BTN_H)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable("gui.another_dynamics.duct_node.transport_kind.pick.tooltip")))
                            .build();
            transportKindPickerButtons.add(b);
            addRenderableWidget(b);
        }

        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        rebuildFilterEntryWidgets();
        layoutTransportKindPickers();
        layoutHubBackButton();
        applySubViewVisibility();

        // JEI (and some UI transitions) can cause a screen re-init that clears widgets.
        // If we are mid edit-mode, restore the edit widgets without grabbing keyboard focus.
        if (inEditMode()) {
            createEditModeUI();
            applySubViewVisibility();
            reloadFilterEntryTextBoxFromList(false);
        }
    }

    private boolean shouldReturnToTransportHubInsteadOfClosing() {
        return menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1
                && menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) != 0;
    }

    private void layoutTransportKindPickers() {
        int n = transportKindPickerButtons.size();
        if (n == 0) {
            return;
        }
        int totalW = n * TRANSPORT_PICKER_BTN_W + (n - 1) * TRANSPORT_PICKER_GAP;
        int startX = this.leftPos + CENTER_X + (3 * (ROW_BTN_W + ROW_GAP) - totalW) / 2;
        int y = this.topPos + TRANSPORT_KIND_BUTTON_Y;
        for (int i = 0; i < n; i++) {
            Button b = transportKindPickerButtons.get(i);
            b.setX(startX + i * (TRANSPORT_PICKER_BTN_W + TRANSPORT_PICKER_GAP));
            b.setY(y);
            b.setWidth(TRANSPORT_PICKER_BTN_W);
            b.setHeight(BTN_H);
        }
    }

    private void layoutHubBackButton() {
        if (hubBackButton == null) {
            return;
        }
        int ox = CENTER_X + ROW_BTN_W + ROW_GAP;
        int oy = ROW2_Y + BTN_H + 4;
        hubBackButton.setX(this.leftPos + ox);
        hubBackButton.setY(this.topPos + oy);
        hubBackButton.setWidth(ROW_BTN_W);
        hubBackButton.setHeight(BTN_H);
    }

    private void openFilterSubview(SubView v) {
        exitEditMode(false);
        subView = v;
        filterScrollOffset = 0;
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        rebuildFilterEntryWidgets();
        applySubViewVisibility();
    }

    private void closeFilterSubview() {
        exitEditMode(false);
        if (subView == SubView.HOW_TO_USE) {
            subView = filterListBeforeHelp;
        } else {
            subView = SubView.MAIN;
        }
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    /** When node mode is {@link NodeMode#NONE}, filter subviews must not stay open (controls are inactive). */
    private void forceExitFilterUiToMain() {
        exitEditMode(false);
        subView = SubView.MAIN;
        rebuildFilterEntryWidgets();
        applySubViewVisibility();
    }

    private void handleCloseOrBack() {
        if (subView == SubView.MAIN) {
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
            if (subView == SubView.ADVANCED_FILTERING) {
                playClickSound();
                closeAdvancedFiltering();
                return;
            }
            closeFilterSubview();
        }
    }

    /** Screen Y of the row with Back and Valid keys (below the filter entry list). */
    private int filterNavRowScreenY() {
        return FIRST_FILTER_ROW_Y + VISIBLE_FILTER_ENTRIES * ENTRY_HEIGHT + FILTER_NAV_GAP;
    }

    private void layoutFilterNavAndHelpButtons() {
        boolean howto = subView == SubView.HOW_TO_USE;
        boolean filterList = subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS;
        if (howto) {
            backButton.setX(this.leftPos + HELP_BACK_BUTTON_X);
            backButton.setY(this.topPos + HELP_BACK_BUTTON_Y);
            backButton.setWidth(ROW_BTN_W);
            backButton.setHeight(BTN_H);
        } else if (subView == SubView.ADVANCED_FILTERING) {
            // Back is placed with {@link #layoutEditModeWidgets()} (same slot as the old Advanced button).
        } else if (filterList && !inEditMode()) {
            int ny = this.topPos + filterNavRowScreenY();
            backButton.setX(this.leftPos + CENTER_X);
            backButton.setY(ny);
            backButton.setWidth(ROW_BTN_W);
            backButton.setHeight(BTN_H);
            validKeysButton.setX(this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP);
            validKeysButton.setY(ny);
            validKeysButton.setWidth(ROW_BTN_W);
            validKeysButton.setHeight(BTN_H);
        }
    }

    private boolean inEditMode() {
        return editModeFilterIndex >= 0;
    }

    private static int amountBlockLayoutKey(NodeMode nm, HybridPanel hybrid) {
        return nm.ordinal() * 32 + hybrid.ordinal();
    }

    /**
     * Whether the main numeric field edits insertion priority ({@link DuctMenuSync#PRIORITY}) vs extract/retrieve batch
     * ({@link DuctMenuSync#AMOUNT_FIELD}). In {@link NodeMode#EXTRACTION_FILTERING}, the filtering sub-panel edits
     * priority; the extractor sub-panel edits batch.
     */
    private boolean amountFieldEditsPriority() {
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        if (nm == NodeMode.EXTRACTION_FILTERING && hybridPanel == HybridPanel.FILTERING) {
            return true;
        }
        return nm.usesInsertionPriorityField();
    }

    /** Repositions priority/quantity widgets when {@link NodeMode} or hybrid panel toggles batch vs priority (M button slot). */
    private void layoutAmountBlock() {
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        boolean showMax = nm.usesExtractBatchField() && !amountFieldEditsPriority();

        int numericRowW = AMOUNT_STEPPER_W + AMOUNT_INNER_GAP + AMOUNT_EDIT_W + AMOUNT_INNER_GAP + AMOUNT_STEPPER_W;
        int actionRowW = AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        if (showMax) {
            actionRowW += AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        }
        actionRowW += AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;

        int blockW = Math.max(numericRowW, actionRowW);
        int blockGuiX = (TEXTURE_WIDTH - blockW) / 2;
        int numericGuiX = blockGuiX + (blockW - numericRowW) / 2;
        int actionGuiX = blockGuiX + (blockW - actionRowW) / 2;

        int amX = this.leftPos + numericGuiX;
        int amY = this.topPos + AMOUNT_ROW_Y;
        routingMinusButton.setPosition(amX, amY);

        int boxX = amX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        amountEditBoxGuiLeft = numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        routingPriorityBox.setPosition(boxX, amY);

        int plusX = boxX + AMOUNT_EDIT_W + AMOUNT_INNER_GAP;
        routingPlusButton.setPosition(plusX, amY);

        int actY = amY + BTN_H + AMOUNT_ROWS_GAP;
        int ax = this.leftPos + actionGuiX;
        amountClearButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        amountApplyButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        if (showMax) {
            amountMaxButton.setPosition(ax, actY);
            ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        }
        amountDiscardButton.setPosition(ax, actY);
    }

    /** Centered Limit/Keep editor (same geometry as {@link #layoutAmountBlock}). */
    private void layoutAdvancedCapBlock() {
        int numericRowW = AMOUNT_STEPPER_W + AMOUNT_INNER_GAP + AMOUNT_EDIT_W + AMOUNT_INNER_GAP + AMOUNT_STEPPER_W;
        int actionRowW = AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP + AMOUNT_ACTION_BTN;
        int blockW = Math.max(numericRowW, actionRowW);
        int blockGuiX = (TEXTURE_WIDTH - blockW) / 2;
        int numericGuiX = blockGuiX + (blockW - numericRowW) / 2;
        int actionGuiX = blockGuiX + (blockW - actionRowW) / 2;

        int capRowGuiY = subView == SubView.ADVANCED_FILTERING ? ADVANCED_CAP_NUMERIC_ROW_GUI_Y : AMOUNT_ROW_Y;
        int amX = this.leftPos + numericGuiX;
        int amY = this.topPos + capRowGuiY;
        advCapMinusButton.setPosition(amX, amY);

        int boxX = amX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        advCapEditBoxGuiLeft = numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        advCapEditBox.setPosition(boxX, amY);

        int plusX = boxX + AMOUNT_EDIT_W + AMOUNT_INNER_GAP;
        advCapPlusButton.setPosition(plusX, amY);

        int actY = amY + BTN_H + AMOUNT_ROWS_GAP;
        int ax = this.leftPos + actionGuiX;
        advCapInfinityButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        advCapApplyButton.setPosition(ax, actY);
        ax += AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        advCapUndoButton.setPosition(ax, actY);
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
        String line = list.get(editModeFilterIndex) != null ? list.get(editModeFilterIndex) : "";
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
        if (subView != SubView.ALLOW_FILTERS || editModeFilterIndex < 0) {
            return;
        }
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
        while (caps.size() <= editModeFilterIndex) {
            caps.add(0);
        }
        editModeAllowCapValue = caps.get(editModeFilterIndex);
        originalAllowCapValue = editModeAllowCapValue;
        subView = SubView.ADVANCED_FILTERING;
        reloadFilterEntryTextBoxFromList(false);
        layoutAdvancedCapBlock();
        layoutEditModeWidgets();
        layoutFilterNavAndHelpButtons();
        syncAllowCapEditBoxDisplay();
        advCapEditHadFocus = advCapEditBox.isFocused();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    private void closeAdvancedFiltering() {
        if (subView != SubView.ADVANCED_FILTERING) {
            return;
        }
        if (advCapEditBox != null) {
            advCapEditBox.setFocused(false);
        }
        subView = SubView.ALLOW_FILTERS;
        layoutAdvancedCapBlock();
        layoutEditModeWidgets();
        layoutFilterNavAndHelpButtons();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    private void applySubViewVisibility() {
        boolean main = subView == SubView.MAIN;
        boolean advancedFiltering = subView == SubView.ADVANCED_FILTERING;
        boolean filterList = subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS;
        boolean howto = subView == SubView.HOW_TO_USE;
        boolean edit = inEditMode();
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        boolean inHybridSelector = nm.isHybrid() && hybridPanel == HybridPanel.NONE;
        boolean showMainStyleChrome = main || advancedFiltering;
        boolean multiTransport = menu.getSyncData().get(DuctMenuSync.TRANSPORT_KIND_COUNT) > 1;
        boolean hubLayer = multiTransport && menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0;
        boolean detailMain = main && !hubLayer;

        denyNavButton.visible = detailMain;
        listLogicButton.visible = detailMain;
        allowNavButton.visible = detailMain;
        routingModeButton.visible = showMainStyleChrome && !howto && !advancedFiltering && !hubLayer;

        boolean showAmountBlock = (detailMain && !howto && !inHybridSelector);
        routingMinusButton.visible = showAmountBlock;
        routingPlusButton.visible = showAmountBlock;
        routingPriorityBox.visible = showAmountBlock;
        amountClearButton.visible = showAmountBlock;
        amountApplyButton.visible = showAmountBlock;
        NodeMode amountNodeMode = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        amountMaxButton.visible =
                showAmountBlock && amountNodeMode.usesExtractBatchField() && !amountFieldEditsPriority();
        amountDiscardButton.visible = showAmountBlock;

        boolean showAdvCapBlock = advancedFiltering;
        advCapMinusButton.visible = showAdvCapBlock;
        advCapPlusButton.visible = showAdvCapBlock;
        advCapEditBox.visible = showAdvCapBlock;
        advCapInfinityButton.visible = showAdvCapBlock;
        advCapApplyButton.visible = showAdvCapBlock;
        advCapUndoButton.visible = showAdvCapBlock;

        nodeModeButton.visible = showMainStyleChrome && !howto && !advancedFiltering && !hubLayer;
        selfFeedStub.visible = false;
        opaqueRenderingButton.visible = detailMain && !howto && !advancedFiltering;

        closeButton.visible = true;
        channelButton.visible = detailMain && !howto;
        boolean showTransportPickers = main && hubLayer && !howto;
        for (Button b : transportKindPickerButtons) {
            b.visible = showTransportPickers;
        }
        if (hubBackButton != null) {
            hubBackButton.visible = detailMain && multiTransport && !howto && !advancedFiltering;
        }

        backButton.visible = howto || advancedFiltering || (filterList && !edit);
        validKeysButton.visible = filterList && !edit && !howto && !advancedFiltering;

        for (Button b : filterEditButtons) {
            b.visible = filterList;
        }
        for (Button b : filterDeleteButtons) {
            b.visible = filterList;
        }

        if (editModeTextBox != null) {
            boolean showFilterEditChrome =
                    edit
                            && (subView == SubView.DENY_FILTERS
                                    || subView == SubView.ALLOW_FILTERS
                                    || subView == SubView.ADVANCED_FILTERING);
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
            if (editModeApplyButton != null) {
                editModeApplyButton.visible = showFilterEditChrome;
            }
            if (editModeCloseButton != null) {
                editModeCloseButton.visible = showFilterEditChrome;
            }
            if (advancedFilteringOpenButton != null) {
                advancedFilteringOpenButton.visible =
                        showFilterEditChrome && subView == SubView.ALLOW_FILTERS;
            }
            if (editModeApplyButton != null && editModeCloseButton != null) {
                if (subView == SubView.ADVANCED_FILTERING) {
                    editModeApplyButton.setTooltip(
                            Tooltip.create(
                                    Component.translatable(
                                            "gui.another_dynamics.duct_node.filters.apply_keep_open.tooltip")));
                    editModeCloseButton.setMessage(Component.literal("\u2715"));
                    editModeCloseButton.setTooltip(
                            Tooltip.create(
                                    Component.translatable("gui.another_dynamics.duct_node.amount.undo.tooltip")));
                } else {
                    editModeApplyButton.setTooltip(
                            Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.apply")));
                    editModeCloseButton.setMessage(Component.literal("\u2715"));
                    editModeCloseButton.setTooltip(
                            Tooltip.create(
                                    Component.translatable("gui.another_dynamics.duct_node.filters.close_without_saving")));
                }
            }
            applyFilterEntryEditBoxTextStyle();
        }

        layoutFilterNavAndHelpButtons();
        layoutTransportKindPickers();
        layoutHubBackButton();
        if (subView == SubView.ADVANCED_FILTERING && editModeTextBox != null) {
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

        if (subView != SubView.DENY_FILTERS && subView != SubView.ALLOW_FILTERS) {
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
            int buttonY = entryY + (ENTRY_HEIGHT - buttonSize) / 2;

            final int idx = filterIndex;
            Button del =
                    Button.builder(Component.literal("C"), b -> {
                                playClickSound();
                                getEditingList().set(idx, "");
                                if (subView == SubView.ALLOW_FILTERS) {
                                    List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
                                    while (caps.size() <= idx) {
                                        caps.add(0);
                                    }
                                    caps.set(idx, 0);
                                }
                                pushFiltersToServer();
                            })
                            .bounds(deleteX, buttonY, buttonSize, buttonSize)
                            .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.clear")))
                            .build();
            filterDeleteButtons.add(del);
            addRenderableWidget(del);

            Button ed =
                    Button.builder(Component.literal("\u270e"), b -> {
                                playClickSound();
                                enterEditMode(idx);
                            })
                            .bounds(editX, buttonY, buttonSize, buttonSize)
                            .build();
            filterEditButtons.add(ed);
            addRenderableWidget(ed);
        }
        applySubViewVisibility();
    }

    private int currentFilterMaxSlots() {
        boolean hyb = useHybridFilterCaps();
        int raw =
                (subView == SubView.ALLOW_FILTERS || subView == SubView.ADVANCED_FILTERING)
                        ? menu.filterAllowCap(hyb)
                        : menu.filterDenyCap(hyb);
        return Math.max(0, raw);
    }

    /** Hybrid selector uses no filter caps; sub-panels use {@code filter.*_hybrid} from the duct datapack. */
    private boolean useHybridFilterCaps() {
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        return nm.isHybrid() && hybridPanel != HybridPanel.NONE;
    }

    private List<String> getEditingList() {
        if (subView == SubView.ALLOW_FILTERS || subView == SubView.ADVANCED_FILTERING) {
            return menu.getClientAllowFilters(activeFilterBank);
        }
        return menu.getClientDenyFilters(activeFilterBank);
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
        if (currentFilterMaxSlots() > visibleFilterEntries() && filterScrollOffset > 0) {
            setFilterScrollOffset(filterScrollOffset - 1);
            return true;
        }
        return false;
    }

    private boolean scrollDownSilent() {
        int maxScroll = maxFilterScroll();
        if (currentFilterMaxSlots() > visibleFilterEntries() && filterScrollOffset < maxScroll) {
            setFilterScrollOffset(filterScrollOffset + 1);
            return true;
        }
        return false;
    }

    private boolean handleFilterScrollButtonClick(double mouseX, double mouseY) {
        if (currentFilterMaxSlots() <= visibleFilterEntries()) {
            return false;
        }
        int gx = this.leftPos;
        int gy = this.topPos;
        if (mouseX >= gx + SCROLLBAR_X_REL && mouseX < gx + SCROLLBAR_X_REL + SCROLLBAR_WIDTH
                && mouseY >= gy + BUTTON_UP_Y_REL && mouseY < gy + BUTTON_UP_Y_REL + HANDLE_SIZE) {
            scrollUp();
            return true;
        }
        if (mouseX >= gx + SCROLLBAR_X_REL && mouseX < gx + SCROLLBAR_X_REL + SCROLLBAR_WIDTH
                && mouseY >= gy + BUTTON_DOWN_Y_REL && mouseY < gy + BUTTON_DOWN_Y_REL + HANDLE_SIZE) {
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
        int handleY = gy + SCROLLBAR_Y_REL + (int) (scrollRatio * (SCROLLBAR_HEIGHT - HANDLE_SIZE));
        if (mouseX >= gx + SCROLLBAR_X_REL && mouseX < gx + SCROLLBAR_X_REL + HANDLE_SIZE
                && mouseY >= handleY && mouseY < handleY + HANDLE_SIZE) {
            isDraggingHandle = true;
            dragStartY = (int) mouseY;
            dragStartScrollOffset = filterScrollOffset;
            playClickSound();
            return true;
        }
        return false;
    }

    private boolean handleFilterScrollbarTrackClick(double mouseX, double mouseY) {
        if (currentFilterMaxSlots() <= visibleFilterEntries()) {
            return false;
        }
        int gx = this.leftPos;
        int gy = this.topPos;
        if (mouseX >= gx + SCROLLBAR_X_REL && mouseX < gx + SCROLLBAR_X_REL + SCROLLBAR_WIDTH
                && mouseY >= gy + SCROLLBAR_Y_REL && mouseY < gy + SCROLLBAR_Y_REL + SCROLLBAR_HEIGHT) {
            float clickRatio = (float) (mouseY - (gy + SCROLLBAR_Y_REL)) / SCROLLBAR_HEIGHT;
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
        return this.topPos
                + FIRST_FILTER_ROW_Y
                + VISIBLE_FILTER_ENTRIES * ENTRY_HEIGHT
                + EDIT_MODE_GAP_BELOW_LIST;
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

        if (advancedFilteringOpenButton != null) {
            int advBtnW = ADVANCED_FILTER_BUTTON_WIDTH;
            int advBtnX = rightArrowX + buttonSize + buttonSpacing;
            int advBtnY = slotY + (slotSize - BTN_H) / 2;
            advancedFilteringOpenButton.setPosition(advBtnX, advBtnY);
            advancedFilteringOpenButton.setWidth(advBtnW);
            advancedFilteringOpenButton.setHeight(BTN_H);
        }

        int textBoxY = slotY + slotSize + 2;
        int textBoxHeight = 15;
        int textBoxX = this.leftPos + ENTRY_X + EDIT_MODE_TEXT_INSET_X;
        int entryContentRight = this.leftPos + ENTRY_X + ENTRY_WIDTH - EDIT_MODE_TEXT_INSET_X;
        int textBoxWidth = entryContentRight - textBoxX;
        editModeTextBox.setPosition(textBoxX, textBoxY);
        editModeTextBox.setWidth(textBoxWidth);
        editModeTextBox.setHeight(textBoxHeight);

        editModeClearButton.setPosition(clearButtonX, buttonRowY);
        editModeApplyButton.setPosition(applyButtonX, buttonRowY);
        editModeCloseButton.setPosition(closeButtonX, buttonRowY);

        if (subView == SubView.ADVANCED_FILTERING) {
            int advBtnW = ADVANCED_FILTER_BUTTON_WIDTH;
            int advBtnX = rightArrowX + buttonSize + buttonSpacing;
            int advBtnY = slotY + (slotSize - BTN_H) / 2;
            backButton.setX(advBtnX);
            backButton.setY(advBtnY);
            backButton.setWidth(advBtnW);
            backButton.setHeight(BTN_H);
        }
    }

    private void enterEditMode(int index) {
        if (editModeFilterIndex == index) {
            exitEditMode(true);
            return;
        }
        exitEditMode(false);
        editModeFilterIndex = index;
        List<String> list = getEditingList();
        while (list.size() <= index) {
            list.add("");
        }
        originalFilterValue = list.get(index) != null ? list.get(index) : "";
        if (subView == SubView.ALLOW_FILTERS) {
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
        createEditModeUI();
        filterScrollOffset = Mth.clamp(filterScrollOffset, 0, maxFilterScroll());
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
            if (subView == SubView.ALLOW_FILTERS || subView == SubView.ADVANCED_FILTERING) {
                List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
                while (caps.size() <= editModeFilterIndex) {
                    caps.add(0);
                }
                caps.set(editModeFilterIndex, originalAllowCapValue);
            }
        }
        editModeFilterIndex = -1;
        originalFilterValue = "";
        removeEditModeUI();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
        filterScrollOffset = Mth.clamp(filterScrollOffset, 0, maxFilterScroll());
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

        advancedFilteringOpenButton = null;
        if (subView == SubView.ALLOW_FILTERS) {
            advancedFilteringOpenButton =
                    Button.builder(
                                    Component.translatable("gui.another_dynamics.duct_node.advanced_filtering.button"),
                                    b -> {
                                        playClickSound();
                                        openAdvancedFiltering();
                                    })
                            .bounds(0, 0, ADVANCED_FILTER_BUTTON_WIDTH, BTN_H)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable(
                                                    "gui.another_dynamics.duct_node.advanced_filtering.button.tooltip")))
                            .build();
            addRenderableWidget(advancedFilteringOpenButton);
        }

        editModeTextBox = new EditBox(this.font, 0, 0, 1, 15, Component.empty());
        editModeTextBox.setMaxLength(16_384);
        editModeTextBox.setValue(originalFilterValue);
        editModeTextBox.setResponder(v -> {});
        addRenderableWidget(editModeTextBox);

        editModeClearButton = Button.builder(Component.literal("C"), b -> {
                    playClickSound();
                    if (editModeTextBox != null) {
                        editModeTextBox.setValue("");
                        editModeTextBox.setCursorPosition(0);
                        editModeTextBox.setHighlightPos(0);
                    }
                })
                .bounds(0, 0, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.clear")))
                .build();
        addRenderableWidget(editModeClearButton);

        editModeApplyButton = Button.builder(Component.literal("A"), b -> {
                    if (subView == SubView.ADVANCED_FILTERING) {
                        applyFilterEditDraft();
                    } else {
                        playClickSound();
                        applyEditModeAndClose();
                    }
                })
                .bounds(0, 0, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.apply")))
                .build();
        addRenderableWidget(editModeApplyButton);

        editModeCloseButton = Button.builder(Component.literal("\u2715"), b -> {
                    if (subView == SubView.ADVANCED_FILTERING) {
                        undoFilterEditDraft();
                    } else {
                        playClickSound();
                        exitEditMode(true);
                    }
                })
                .bounds(0, 0, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.close_without_saving")))
                .build();
        addRenderableWidget(editModeCloseButton);

        layoutEditModeWidgets();
        applyFilterEntryEditBoxTextStyle();

        ghostSlotItem = ItemStack.EMPTY;
        ghostSlotFluid = FluidStack.EMPTY;
        filterVariants.clear();
        currentFilterVariantIndex = 0;
    }

    /**
     * Saves filter text (and allow caps for this line) while staying in {@link SubView#ADVANCED_FILTERING} and keeping
     * edit widgets open.
     */
    private void applyFilterEditDraft() {
        if (editModeTextBox == null || editModeFilterIndex < 0 || subView != SubView.ADVANCED_FILTERING) {
            return;
        }
        playClickSound();
        String value = editModeTextBox.getValue();
        List<String> list = getEditingList();
        while (list.size() <= editModeFilterIndex) {
            list.add("");
        }
        list.set(editModeFilterIndex, value);
        List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
        while (caps.size() <= editModeFilterIndex) {
            caps.add(0);
        }
        caps.set(editModeFilterIndex, editModeAllowCapValue);
        originalAllowCapValue = editModeAllowCapValue;
        originalFilterValue = value;
        pushFiltersToServer();
    }

    /** Reverts filter text and Limit/Keep draft to last applied values (advanced filtering only). */
    private void undoFilterEditDraft() {
        if (editModeTextBox == null || editModeFilterIndex < 0 || subView != SubView.ADVANCED_FILTERING) {
            return;
        }
        playClickSound();
        editModeTextBox.setValue(originalFilterValue);
        editModeTextBox.setCursorPosition(0);
        editModeTextBox.setHighlightPos(0);
        editModeAllowCapValue = originalAllowCapValue;
        syncAllowCapEditBoxDisplay();
    }

    private void applyEditModeAndClose() {
        if (editModeTextBox != null && editModeFilterIndex >= 0) {
            String value = editModeTextBox.getValue();
            List<String> list = getEditingList();
            while (list.size() <= editModeFilterIndex) {
                list.add("");
            }
            list.set(editModeFilterIndex, value);
            if (subView == SubView.ALLOW_FILTERS || subView == SubView.ADVANCED_FILTERING) {
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
        filterScrollOffset = Mth.clamp(filterScrollOffset, 0, maxFilterScroll());
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
        ghostSlotItem = ItemStack.EMPTY;
        ghostSlotFluid = FluidStack.EMPTY;
        filterVariants.clear();
        currentFilterVariantIndex = 0;
    }

    private boolean isFluidFilterTransport() {
        return menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.FLUID.ordinal();
    }

    private void renderEditModeSlot(GuiGraphics guiGraphics) {
        int slotSize = 18;
        int slotX = editModeSlotX();
        int slotY = editModeSlotY();
        guiGraphics.blit(SINGLE_SLOT, slotX, slotY, 0, 0, slotSize, slotSize, slotSize, slotSize);
        if (!ghostSlotFluid.isEmpty()) {
            GuiFluidStillBlit.blit16(guiGraphics, ghostSlotFluid, slotX + 1, slotY + 1);
        } else if (!ghostSlotItem.isEmpty()) {
            guiGraphics.renderItem(ghostSlotItem, slotX + 1, slotY + 1);
            guiGraphics.renderItemDecorations(this.font, ghostSlotItem, slotX + 1, slotY + 1);
        }
    }

    private void handleGhostSlotClick() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack cursorItem = this.menu.getCarried();
        if (cursorItem.isEmpty()) {
            ghostSlotItem = ItemStack.EMPTY;
            ghostSlotFluid = FluidStack.EMPTY;
            filterVariants.clear();
            currentFilterVariantIndex = 0;
            if (editModeTextBox != null) {
                editModeTextBox.setValue("");
                editModeTextBox.setCursorPosition(0);
                editModeTextBox.setHighlightPos(0);
            }
            playClickSound();
        } else if (isFluidFilterTransport()) {
            Optional<FluidStack> contained = FluidUtil.getFluidContained(cursorItem);
            if (contained.isPresent() && !contained.get().isEmpty()) {
                ghostSlotItem = ItemStack.EMPTY;
                ghostSlotFluid = contained.get().copy();
                filterVariants = generateFluidFilterVariants(ghostSlotFluid);
            } else {
                // In fluid filter mode, do not fall back to item filters: this ghost slot is a "calibrator" for fluids.
                ghostSlotFluid = FluidStack.EMPTY;
                ghostSlotItem = ItemStack.EMPTY;
                filterVariants = List.of();
            }
            currentFilterVariantIndex = 0;
            if (editModeTextBox != null) {
                String v = filterVariants.isEmpty() ? "" : filterVariants.getFirst();
                editModeTextBox.setValue(v);
                editModeTextBox.setCursorPosition(0);
                editModeTextBox.setHighlightPos(0);
            }
            playClickSound();
        } else {
            ghostSlotFluid = FluidStack.EMPTY;
            ghostSlotItem = cursorItem.copy();
            filterVariants = generateAllFilterVariants(cursorItem);
            currentFilterVariantIndex = 0;
            if (editModeTextBox != null && !filterVariants.isEmpty()) {
                editModeTextBox.setValue(filterVariants.getFirst());
                editModeTextBox.setCursorPosition(0);
                editModeTextBox.setHighlightPos(0);
            }
            playClickSound();
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
        String filterString = filterVariants.get(currentFilterVariantIndex);
        if (editModeTextBox != null) {
            editModeTextBox.setValue(filterString);
            editModeTextBox.setCursorPosition(0);
            editModeTextBox.setHighlightPos(0);
        }
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
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return variants;
        }
        variants.add("-" + itemId);
        String namespace = itemId.getNamespace();
        if (!"minecraft".equals(namespace)) {
            variants.add("@" + namespace);
        }
        if (stack.isEnchanted()) {
            variants.add("&enchanted");
        }
        if (stack.isDamaged()) {
            variants.add("&damaged");
        }
        Item item = stack.getItem();
        var holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        List<String> itemTags =
                BuiltInRegistries.ITEM.getTagNames()
                        .filter(
                                tagKey -> BuiltInRegistries.ITEM.getTag(tagKey)
                                        .map(t -> t.contains(holder))
                                        .orElse(false))
                        .map(TagKey::location)
                        .map(ResourceLocation::toString)
                        .sorted()
                        .toList();
        for (String tagId : itemTags) {
            variants.add("#" + tagId);
        }
        if (minecraft != null && minecraft.level != null) {
            try {
                Tag saved = stack.save(minecraft.level.registryAccess());
                if (saved instanceof CompoundTag compound) {
                    String snbt = compound.toString();
                    if (!snbt.isEmpty()) {
                        variants.add("?" + snbt);
                    }
                }
            } catch (Exception ignored) {
            }
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
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null) {
            return variants;
        }
        variants.add("-" + fluidId);
        String namespace = fluidId.getNamespace();
        if (!"minecraft".equals(namespace)) {
            variants.add("@" + namespace);
        }
        var holder = BuiltInRegistries.FLUID.wrapAsHolder(fluid);
        List<String> fluidTags =
                BuiltInRegistries.FLUID.getTagNames()
                        .filter(
                                tagKey -> BuiltInRegistries.FLUID.getTag(tagKey)
                                        .map(t -> t.contains(holder))
                                        .orElse(false))
                        .map(TagKey::location)
                        .map(ResourceLocation::toString)
                        .sorted()
                        .toList();
        for (String tagId : fluidTags) {
            variants.add("#" + tagId);
        }
        try {
            Tag saved = stack.save(registries);
            if (saved instanceof CompoundTag compound) {
                String snbt = compound.toString();
                if (!snbt.isEmpty()) {
                    variants.add("?" + snbt);
                }
            }
        } catch (Exception ignored) {
        }
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
                ResourceLocation id = ResourceLocation.parse(idFilter);
                Item item = BuiltInRegistries.ITEM.get(id);
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
            return switch (macro) {
                case "enchanted" -> {
                    String snbt =
                            "{components:{\"minecraft:enchantments\":{levels:{\"minecraft:aqua_affinity\":1}},\"minecraft:repair_cost\":1},count:1,id:\"minecraft:iron_pickaxe\"}";
                    ItemStack st = parseItemStackFromSNBT(snbt);
                    yield st.isEmpty() ? new ItemStack(Items.DIAMOND_PICKAXE) : st;
                }
                case "damaged" -> {
                    ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
                    st.setDamageValue(st.getMaxDamage() / 2);
                    yield st;
                }
                default -> ItemStack.EMPTY;
            };
        }
        try {
            ResourceLocation id = ResourceLocation.parse(filter);
            return new ItemStack(BuiltInRegistries.ITEM.get(id));
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
            ResourceLocation id = ResourceLocation.parse(f);
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
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
            ResourceLocation id = ResourceLocation.parse(idStr);
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
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
            ResourceLocation loc = ResourceLocation.parse(tagId);
            TagKey<Fluid> tagKey = TagKey.create(Registries.FLUID, loc);
            return BuiltInRegistries.FLUID.getTag(tagKey)
                    .flatMap(t -> t.stream().findFirst())
                    .map(h -> new FluidStack(h.value(), 1000))
                    .orElse(FluidStack.EMPTY);
        } catch (Exception e) {
            return FluidStack.EMPTY;
        }
    }

    private static FluidStack firstFluidInMod(String modId) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            if (id != null && id.getNamespace().startsWith(modId) && fluid != Fluids.EMPTY) {
                return new FluidStack(fluid, 1000);
            }
        }
        return FluidStack.EMPTY;
    }

    private static ItemStack parseItemStackFromSNBT(String snbtString) {
        try {
            CompoundTag tag = TagParser.parseTag(snbtString);
            if (Minecraft.getInstance().level == null) {
                return ItemStack.EMPTY;
            }
            return ItemStack.parse(Minecraft.getInstance().level.registryAccess(), tag).orElse(ItemStack.EMPTY);
        } catch (CommandSyntaxException e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack getItemForTag(String tagId) {
        try {
            ResourceLocation loc = ResourceLocation.parse(tagId);
            TagKey<Item> itemTag = ItemTags.create(loc);
            var contents = BuiltInRegistries.ITEM.getTag(itemTag);
            if (contents.isPresent()) {
                var items = contents.get();
                if (items.size() > 0) {
                    int index = (int) ((System.currentTimeMillis() / 2000) % items.size());
                    return new ItemStack(items.get(index).value());
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack getItemForMod(String modId) {
        List<Item> modItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && id.getNamespace().startsWith(modId)) {
                modItems.add(item);
            }
        }
        if (!modItems.isEmpty()) {
            int index = (int) ((System.currentTimeMillis() / 2000) % modItems.size());
            return new ItemStack(modItems.get(index));
        }
        return ItemStack.EMPTY;
    }

    private void pushFiltersToServer() {
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
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
                menu.getClientDenyOverridesAllow(activeFilterBank));
    }

    private void syncAllowCapEditBoxDisplay() {
        if (advCapEditBox == null || subView != SubView.ADVANCED_FILTERING) {
            return;
        }
        if (advCapEditBox.isFocused()) {
            return;
        }
        boolean limitCtx;
        if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
            int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
            DuctFaceNode.EligibilityMode em = DuctFaceNode.EligibilityMode.fromOrdinal(ord);
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

    private static int parseAllowCapFromEditBox(String raw) {
        if (raw == null) {
            return 0;
        }
        String t = raw.trim();
        if (t.isEmpty()
                || t.equals("\u221e")
                || t.equalsIgnoreCase("inf")) {
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
                editModeAllowCapValue =
                        (int) Mth.clamp((long) editModeAllowCapValue + (long) step, 1L, Integer.MAX_VALUE);
            }
        }
        syncAllowCapEditBoxDisplay();
    }

    /** Commits allow-line Limit/Keep to the client menu cache and server. */
    private void applyAllowCapField() {
        if (subView != SubView.ADVANCED_FILTERING || editModeFilterIndex < 0 || advCapEditBox == null) {
            return;
        }
        playClickSound();
        editModeAllowCapValue = parseAllowCapFromEditBox(advCapEditBox.getValue());
        List<Integer> caps = menu.getClientAllowCaps(activeFilterBank);
        while (caps.size() <= editModeFilterIndex) {
            caps.add(0);
        }
        caps.set(editModeFilterIndex, editModeAllowCapValue);
        originalAllowCapValue = editModeAllowCapValue;
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

    private void handleMenuButton(int id) {
        if (id == 1) {
            NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
            boolean inHybridPanel = nm.isHybrid() && hybridPanel != HybridPanel.NONE;
            boolean eligibilityCtx =
                    nm == NodeMode.NONE
                            || nm == NodeMode.FILTERING_INSERTION
                            || (nm == NodeMode.EXTRACTION_FILTERING && inHybridPanel);
            if (eligibilityCtx) {
                playClickSound();
                int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
                var cur = DuctFaceNode.EligibilityMode.fromOrdinal(ord);
                var next =
                        switch (cur) {
                            case BOTH -> DuctFaceNode.EligibilityMode.INSERT_ONLY;
                            case INSERT_ONLY -> DuctFaceNode.EligibilityMode.RETRIEVE_ONLY;
                            case RETRIEVE_ONLY -> DuctFaceNode.EligibilityMode.BOTH;
                        };
                pushAmountFields(
                        menu.getSyncData().get(DuctMenuSync.PRIORITY),
                        menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD),
                        next.ordinal());
                return;
            }
        }
        playClickSound();
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        menu.updateClientDenyOverridesFromSync();
        menu.ensureClientFilterBufferSizes(useHybridFilterCaps());
        if (subView == SubView.ADVANCED_FILTERING && advCapEditBox != null) {
            boolean f = advCapEditBox.isFocused();
            if (f != advCapEditHadFocus) {
                advCapEditHadFocus = f;
                if (!f) {
                    syncAllowCapEditBoxDisplay();
                } else if (editModeAllowCapValue == 0) {
                    advCapEditBox.setValue("");
                }
            }
            boolean limitCtx;
            if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
                // FILTER bank cap is interpreted as Limit when the face is insertable, and as Keep when it is retriever-only.
                int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
                DuctFaceNode.EligibilityMode em = DuctFaceNode.EligibilityMode.fromOrdinal(ord);
                limitCtx = em.isInsertable();
            } else {
                limitCtx = activeFilterBank == DuctFaceNode.FilterBank.RETRIEVER;
            }
            advCapMinusButton.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    limitCtx
                                            ? "gui.another_dynamics.duct_node.allow_cap.minus.limit"
                                            : "gui.another_dynamics.duct_node.allow_cap.minus.keep")));
            advCapPlusButton.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    limitCtx
                                            ? "gui.another_dynamics.duct_node.allow_cap.plus.limit"
                                            : "gui.another_dynamics.duct_node.allow_cap.plus.keep")));
            advCapEditBox.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    limitCtx
                                            ? (isFluidFilterTransport()
                                                    ? "gui.another_dynamics.duct_node.allow_cap.field.limit_mb"
                                                    : "gui.another_dynamics.duct_node.allow_cap.field.limit")
                                            : (isFluidFilterTransport()
                                                    ? "gui.another_dynamics.duct_node.allow_cap.field.keep_mb"
                                                    : "gui.another_dynamics.duct_node.allow_cap.field.keep"))));
            // Infinity/zero convenience button is contextual: Limit uses ∞, Keep uses 0.
            advCapInfinityButton.setMessage(Component.literal(limitCtx ? "\u221e" : "0"));
            advCapInfinityButton.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    limitCtx
                                            ? "gui.another_dynamics.duct_node.allow_cap.set_unlimited"
                                            : "gui.another_dynamics.duct_node.allow_cap.set_to_zero")));
        }
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        syncActiveFilterBankToNodeMode(nm);
        boolean inHybridPanel = nm.isHybrid() && hybridPanel != HybridPanel.NONE;
        if (inHybridPanel) {
            nodeModeButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.hybrid.back"));
            nodeModeButton.setTooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hybrid.back.tooltip")));
        } else {
            nodeModeButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.mode." + nm.name().toLowerCase()));
            nodeModeButton.setTooltip(
                    Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.mode.tooltip." + nm.name().toLowerCase())));
        }
        int menuFlags = menu.getSyncData().get(DuctMenuSync.FLAGS);
        boolean filtersActive = (menuFlags & DuctMenuSync.FLAG_FILTERS_ACTIVE) != 0;
        if (!filtersActive && subView != SubView.MAIN) {
            forceExitFilterUiToMain();
        }
        boolean routingUsable = nm.usesRouting();
        boolean hybridAllowsRoutingUi =
                !nm.isHybrid()
                        || (nm == NodeMode.EXTRACTION_FILTERING
                                && (hybridPanel == HybridPanel.NONE
                                        || hybridPanel == HybridPanel.EXTRACTOR
                                        || hybridPanel == HybridPanel.FILTERING))
                        || (nm == NodeMode.RETRIEVING_EXTRACTION && hybridPanel == HybridPanel.RETRIEVER);
        boolean routingMovedUi =
                (nm == NodeMode.RETRIEVING_EXTRACTION && hybridPanel != HybridPanel.RETRIEVER)
                        || (nm == NodeMode.EXTRACTION_FILTERING && hybridPanel == HybridPanel.NONE);
        boolean routingActive = routingUsable && hybridAllowsRoutingUi;

        boolean eligibilityCtx =
                nm == NodeMode.NONE
                        || nm == NodeMode.FILTERING_INSERTION
                        || (nm == NodeMode.EXTRACTION_FILTERING && inHybridPanel);

        if (routingMovedUi) {
            routingModeButton.active = false;
            routingModeButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.routing_moved"));
            routingModeButton.setTooltip(
                    Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.routing.tooltip.moved")));
        } else if (eligibilityCtx) {
            int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
            DuctFaceNode.EligibilityMode em = DuctFaceNode.EligibilityMode.fromOrdinal(ord);
            routingModeButton.active = true;
            routingModeButton.setMessage(
                    Component.translatable("gui.another_dynamics.duct_node.eligibility." + em.name().toLowerCase()));
            routingModeButton.setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "gui.another_dynamics.duct_node.eligibility.tooltip." + em.name().toLowerCase())));
        } else if (!routingActive) {
            routingModeButton.active = false;
            routingModeButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.routing_unroutable"));
            routingModeButton.setTooltip(
                    Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.routing.tooltip.unroutable")));
        } else {
            routingModeButton.active = true;
            int rmOrd =
                    nm.isHybrid()
                            ? (hybridPanel == HybridPanel.RETRIEVER
                                    ? menu.getSyncData().get(DuctMenuSync.ROUTING_MODE_RETRIEVER)
                                    : menu.getSyncData().get(DuctMenuSync.ROUTING_MODE_EXTRACTOR))
                            : menu.getSyncData().get(DuctMenuSync.ROUTING_MODE);
            RoutingMode rm = RoutingMode.fromOrdinal(rmOrd);
            routingModeButton.setMessage(
                    Component.translatable("gui.another_dynamics.duct_node.routing." + rm.name().toLowerCase()));
            routingModeButton.setTooltip(
                    Tooltip.create(
                            Component.translatable("gui.another_dynamics.duct_node.routing.tooltip." + rm.name().toLowerCase())));
        }
        if (minecraft != null && minecraft.player != null && opaqueRenderingButton != null) {
            boolean locked = menu.isDuctAlwaysOpaqueLocked();
            boolean opaqueOn =
                    locked || minecraft.player.getData(ModAttachments.DUCT_TRANSIT_OPAQUE.get());
            opaqueRenderingButton.setMessage(
                    Component.translatable(
                            opaqueOn
                                    ? "gui.another_dynamics.duct_node.opaque_rendering.on"
                                    : "gui.another_dynamics.duct_node.opaque_rendering.off"));
            opaqueRenderingButton.active = !locked;
        }
        if (nm.isHybrid()) {
            if (!inHybridPanel) {
                if (nm == NodeMode.EXTRACTION_FILTERING) {
                    denyNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.extractor"));
                    allowNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.filtering"));
                } else {
                    denyNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.retrieving"));
                    allowNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.extractor"));
                }
                boolean on = (menu.getSyncData().get(DuctMenuSync.SELF_FEED) != 0);
                listLogicButton.setMessage(
                        Component.translatable(
                                on
                                        ? "gui.another_dynamics.duct_node.hybrid.self_feed.on"
                                        : "gui.another_dynamics.duct_node.hybrid.self_feed.off"));
                denyNavButton.active = true;
                listLogicButton.active = true;
                allowNavButton.active = true;
                if (nm == NodeMode.EXTRACTION_FILTERING) {
                    denyNavButton.setTooltip(
                            Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.extractor.tooltip")));
                    allowNavButton.setTooltip(
                            Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.filtering.tooltip")));
                } else {
                    denyNavButton.setTooltip(
                            Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.retrieving.tooltip")));
                    allowNavButton.setTooltip(
                            Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hybrid.selector.extractor.tooltip")));
                }
                listLogicButton.setTooltip(
                        Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.hybrid.self_feed.tooltip")));
            } else {
                denyNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.deny_list"));
                allowNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.allow_list"));
                denyNavButton.active = filtersActive;
                allowNavButton.active = filtersActive;
                listLogicButton.active = filtersActive;
                boolean denyOver = menu.getClientDenyOverridesAllow(activeFilterBank);
                listLogicButton.setMessage(Component.literal(denyOver ? ">>>>>" : "<<<<<"));
            }
        } else {
            denyNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.deny_list"));
            allowNavButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.allow_list"));
            denyNavButton.active = filtersActive;
            listLogicButton.active = filtersActive;
            allowNavButton.active = filtersActive;
            boolean denyOver = menu.getSyncData().get(DuctMenuSync.DENY_OVERRIDES_ALLOW) != 0;
            listLogicButton.setMessage(Component.literal(denyOver ? ">>>>>" : "<<<<<"));
            if (filtersActive) {
                denyNavButton.setTooltip(
                        Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.deny_list.tooltip")));
                allowNavButton.setTooltip(
                        Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.allow_list.tooltip")));
                listLogicButton.setTooltip(
                        Tooltip.create(
                                Component.translatable(
                                        denyOver
                                                ? "gui.another_dynamics.duct_node.list_logic.tooltip.deny_wins"
                                                : "gui.another_dynamics.duct_node.list_logic.tooltip.allow_bypass")));
            } else {
                var inactive =
                        Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.tooltip.inactive"));
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
                                        : "gui.another_dynamics.duct_node.amount.field.tooltip.batch")));
        routingMinusButton.setTooltip(
                Tooltip.create(
                        Component.translatable(
                                amountIsPriority
                                        ? "gui.another_dynamics.duct_node.amount.minus.tooltip.priority"
                                        : "gui.another_dynamics.duct_node.amount.minus.tooltip.batch")));
        routingPlusButton.setTooltip(
                Tooltip.create(
                        Component.translatable(
                                amountIsPriority
                                        ? "gui.another_dynamics.duct_node.amount.plus.tooltip.priority"
                                        : "gui.another_dynamics.duct_node.amount.plus.tooltip.batch")));

        redstoneModeStub = menu.getSyncData().get(DuctMenuSync.REDSTONE_MODE);
        channelButton.setLetterValue(menu.getSyncData().get(DuctMenuSync.CHANNEL));

        EnumSet<DuctTransportKind> enabledKinds =
                DuctDefinitionRegistry.getByLogicalId(menu.getClientDuctLogicalId())
                        .map(DuctDefinition::enabledTransportKinds)
                        .orElse(EnumSet.of(DuctTransportKind.ITEM));
        DuctTransportKind[] kinds = DuctTransportKind.values();
        for (int i = 0; i < transportKindPickerButtons.size() && i < kinds.length; i++) {
            DuctTransportKind k = kinds[i];
            Button b = transportKindPickerButtons.get(i);
            b.setMessage(Component.translatable("gui.another_dynamics.duct_node.transport." + k.name().toLowerCase()));
            b.active = enabledKinds.contains(k);
        }

        int layoutKey = amountBlockLayoutKey(nm, hybridPanel);
        if (amountBlockLayoutCache != layoutKey) {
            amountBlockLayoutCache = layoutKey;
            amountFieldsDirty = false;
            layoutAmountBlock();
            if (subView == SubView.ADVANCED_FILTERING) {
                layoutAdvancedCapBlock();
                layoutEditModeWidgets();
            }
            applySubViewVisibility();
        }
        if (!routingPriorityBox.isFocused() && !amountFieldsDirty) {
            int v =
                    amountIsPriority
                            ? menu.getSyncData().get(DuctMenuSync.PRIORITY)
                            : menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD);
            syncingAmountBoxFromServer = true;
            routingPriorityBox.setValue(Integer.toString(v));
            syncingAmountBoxFromServer = false;
        }

        filterScrollOffset = Mth.clamp(filterScrollOffset, 0, maxFilterScroll());
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
            activeFilterBank =
                    switch (hybridPanel) {
                        case EXTRACTOR -> DuctFaceNode.FilterBank.EXTRACTOR;
                        case FILTERING -> DuctFaceNode.FilterBank.FILTER;
                        case RETRIEVER -> DuctFaceNode.FilterBank.RETRIEVER;
                        case NONE -> activeFilterBank;
                    };
            return;
        }
        activeFilterBank =
                switch (nm) {
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
        return menu.getSyncData().get(DuctMenuSync.PRIORITY);
    }

    private int syncedExtractBatch() {
        return menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD);
    }

    /**
     * Server-authoritative max configurable extract batch; before {@link DuctMenuSync#EXTRACT_BATCH_CAP} syncs, derives
     * the same cap from registry + local upgrade slots (bonus currently zero until upgrade items exist).
     */
    private int syncedExtractBatchCap() {
        int c = menu.getSyncData().get(DuctMenuSync.EXTRACT_BATCH_CAP);
        if (c > 0) {
            return c;
        }
        int bonus = 0;
        for (int i = 0; i < DuctNodeMenu.UPGRADE_SLOT_COUNT; i++) {
            Slot s = menu.getSlot(i);
            if (s != null && s.hasItem()) {
                // Future: parse upgrade item stats (keep in sync with DuctBlockEntity#getExtractBatchUpgradeBonus).
            }
        }
        if (menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.FLUID.ordinal()) {
            return DuctDefinitionRegistry.getByLogicalId(menu.getClientDuctLogicalId())
                    .map(DuctDefinition::fluidTransportOrFallback)
                    .orElseGet(DuctDefinitionRegistry::fluidDuctTransportSpec)
                    .extractBatchSettingCapMb(bonus);
        }
        return DuctDefinitionRegistry.getByLogicalId(menu.getClientDuctLogicalId())
                .map(DuctDefinition::itemTransportOrFallback)
                .orElseGet(DuctDefinitionRegistry::itemDuctTransportSpec)
                .extractBatchSettingCap(bonus);
    }

    private void pushAmountFields(int insertionPriority, int extractBatch) {
        pushAmountFields(insertionPriority, extractBatch, menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE));
    }

    private void pushAmountFields(int insertionPriority, int extractBatch, int eligibilityModeOrdinal) {
        ModNetwork.sendFieldUpdate(
                menuSyncedPos(),
                menuSyncedFace(),
                menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND),
                insertionPriority,
                extractBatch,
                eligibilityModeOrdinal);
    }

    private int stepForPriorityAdjust() {
        boolean c = hasControlDown();
        boolean a = hasAltDown();
        if (a) {
            return PRIORITY_STEP_ALT;
        }
        if (c) {
            return PRIORITY_STEP_CTRL;
        }
        return PRIORITY_STEP_PLAIN;
    }

    private int stepForBatchAdjust() {
        if (hasShiftDown()) {
            return BATCH_STEP_SHIFT;
        }
        if (hasControlDown() || hasAltDown()) {
            return BATCH_STEP_CTRL_OR_ALT;
        }
        return BATCH_STEP_PLAIN;
    }

    private void adjustAmountField(int sign) {
        playClickSound();
        int priSynced = syncedInsertionPriority();
        int batchSynced = syncedExtractBatch();
        if (amountFieldEditsPriority()) {
            int base = parsePriorityOr(routingPriorityBox.getValue(), priSynced);
            int step = stepForPriorityAdjust();
            int pri =
                    (int)
                            Mth.clamp(
                                    (long) base + (long) sign * step,
                                    Integer.MIN_VALUE,
                                    Integer.MAX_VALUE);
            syncingAmountBoxFromServer = true;
            routingPriorityBox.setValue(Integer.toString(pri));
            syncingAmountBoxFromServer = false;
        } else {
            int base = parsePriorityOr(routingPriorityBox.getValue(), batchSynced);
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

    private void amountClearField() {
        playClickSound();
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue("0");
        syncingAmountBoxFromServer = false;
        amountFieldsDirty = true;
    }

    private void amountMaxField() {
        if (amountFieldEditsPriority()) {
            return;
        }
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        if (!nm.usesExtractBatchField()) {
            return;
        }
        playClickSound();
        syncingAmountBoxFromServer = true;
        routingPriorityBox.setValue(Integer.toString(syncedExtractBatchCap()));
        syncingAmountBoxFromServer = false;
        amountFieldsDirty = true;
    }

    private void amountApplyField() {
        playClickSound();
        commitFieldFromEditBox();
    }

    private void revertAmountDraft(boolean defocus) {
        int v =
                amountFieldEditsPriority()
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
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (subView == SubView.HOW_TO_USE) {
            graphics.blit(
                    VALID_KEYS_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0,
                    0,
                    this.imageWidth,
                    this.imageHeight,
                    TEXTURE_WIDTH,
                    TEXTURE_HEIGHT);
            return;
        }

        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        blitMachineSlotBackgrounds(graphics);

        if (subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS) {
            renderFilterPanel(graphics, mouseX, mouseY);
            if (inEditMode() && (subView == SubView.ALLOW_FILTERS || subView == SubView.DENY_FILTERS)) {
                renderEditModeSlot(graphics);
            }
        } else if (subView == SubView.ADVANCED_FILTERING && inEditMode()) {
            renderEditModeSlot(graphics);
        }

        boolean hovered = mouseX >= redstoneButtonScreenX
                && mouseX < redstoneButtonScreenX + REDSTONE_BUTTON_SIZE
                && mouseY >= redstoneButtonScreenY
                && mouseY < redstoneButtonScreenY + REDSTONE_BUTTON_SIZE;
        int textureY = hovered ? 16 : 0;
        graphics.blit(
                MEDIUM_BUTTONS,
                redstoneButtonScreenX,
                redstoneButtonScreenY,
                0,
                textureY,
                REDSTONE_BUTTON_SIZE,
                REDSTONE_BUTTON_SIZE,
                96,
                96);

        int iconX = redstoneButtonScreenX + 2;
        int iconY = redstoneButtonScreenY + 2;
        switch (redstoneModeStub) {
            case 0 -> renderScaledItem(graphics, new ItemStack(Items.GUNPOWDER), iconX, iconY);
            case 1 -> renderScaledItem(graphics, new ItemStack(Items.REDSTONE), iconX, iconY);
            case 2 -> renderScaledTexture(graphics, REDSTONE_GUI, iconX, iconY);
            case 3 -> renderScaledItem(graphics, new ItemStack(Items.BARRIER), iconX, iconY);
            default -> {}
        }
    }

    private void renderFilterPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int maxSlots = currentFilterMaxSlots();
        int vis = visibleFilterEntries();
        if (maxSlots > vis) {
            int scrollbarX = this.leftPos + SCROLLBAR_X_REL;
            int buttonUpY = this.topPos + BUTTON_UP_Y_REL;
            int scrollbarY = this.topPos + SCROLLBAR_Y_REL;
            int buttonDownY = this.topPos + BUTTON_DOWN_Y_REL;
            graphics.blit(
                    SCROLLBAR_TEXTURE,
                    scrollbarX,
                    scrollbarY,
                    0,
                    0,
                    SCROLLBAR_WIDTH,
                    SCROLLBAR_HEIGHT,
                    32,
                    34);
            int upV =
                    mouseX >= scrollbarX && mouseX < scrollbarX + SCROLLBAR_WIDTH && mouseY >= buttonUpY && mouseY < buttonUpY + HANDLE_SIZE
                            ? HANDLE_SIZE
                            : 0;
            graphics.blit(SCROLLBAR_TEXTURE, scrollbarX, buttonUpY, SCROLLBAR_WIDTH * 2, upV, HANDLE_SIZE, HANDLE_SIZE, 32, 34);
            int downV =
                    mouseX >= scrollbarX && mouseX < scrollbarX + SCROLLBAR_WIDTH && mouseY >= buttonDownY && mouseY < buttonDownY + HANDLE_SIZE
                            ? HANDLE_SIZE
                            : 0;
            graphics.blit(
                    SCROLLBAR_TEXTURE, scrollbarX, buttonDownY, SCROLLBAR_WIDTH * 3, downV, HANDLE_SIZE, HANDLE_SIZE, 32, 34);
            int maxScroll = maxFilterScroll();
            if (maxScroll > 0) {
                double ratio = (double) filterScrollOffset / maxScroll;
                int handleY = scrollbarY + (int) (ratio * (SCROLLBAR_HEIGHT - HANDLE_SIZE));
                int hV =
                        mouseX >= scrollbarX && mouseX < scrollbarX + HANDLE_SIZE && mouseY >= handleY && mouseY < handleY + HANDLE_SIZE
                                ? HANDLE_SIZE
                                : 0;
                graphics.blit(SCROLLBAR_TEXTURE, scrollbarX, handleY, SCROLLBAR_WIDTH, hV, HANDLE_SIZE, HANDLE_SIZE, 32, 34);
            }
        }

        List<String> list = getEditingList();
        for (int i = 0; i < vis; i++) {
            int idx = filterScrollOffset + i;
            if (idx >= maxSlots) {
                break;
            }
            int entryX = this.leftPos + ENTRY_X;
            int entryY = this.topPos + FIRST_FILTER_ROW_Y + i * ENTRY_HEIGHT;
            graphics.blit(ENTRY_ROW_TEXTURE, entryX, entryY, 0, 0, ENTRY_WIDTH, ENTRY_HEIGHT, ENTRY_WIDTH, ENTRY_HEIGHT);

            int slotX = entryX + 3;
            int slotY = entryY + (ENTRY_HEIGHT - 18) / 2;
            graphics.blit(SINGLE_SLOT, slotX, slotY, 0, 0, 18, 18, 18, 18);
            String filter = idx < list.size() && list.get(idx) != null ? list.get(idx) : "";
            if (isFluidFilterTransport() && minecraft != null && minecraft.level != null) {
                FluidStack displayFluid = getDisplayFluidForFilter(filter);
                if (!displayFluid.isEmpty()) {
                    GuiFluidStillBlit.blit16(graphics, displayFluid, slotX + 1, slotY + 1);
                } else {
                    ItemStack displayItem = getDisplayItemForFilter(filter);
                    if (!displayItem.isEmpty()) {
                        graphics.renderItem(displayItem, slotX + 1, slotY + 1);
                        graphics.renderItemDecorations(this.font, displayItem, slotX + 1, slotY + 1);
                    }
                }
            } else {
                ItemStack displayItem = getDisplayItemForFilter(filter);
                if (!displayItem.isEmpty()) {
                    graphics.renderItem(displayItem, slotX + 1, slotY + 1);
                    graphics.renderItemDecorations(this.font, displayItem, slotX + 1, slotY + 1);
                }
            }

            int textX = slotX + 18 + 6;
            int textY = entryY + (ENTRY_HEIGHT - this.font.lineHeight) / 2;
            int buttonSize = 12;
            int buttonMargin = 4;
            int buttonSpacing = 2;
            int editButtonX = entryX + ENTRY_WIDTH - buttonMargin - buttonSize;
            int deleteButtonX = editButtonX - buttonSize - buttonSpacing;
            int maxTextWidth = deleteButtonX - textX - 5;
            String displayText = filter;
            if (font.width(displayText) > maxTextWidth && !displayText.isEmpty()) {
                displayText = font.plainSubstrByWidth(displayText, maxTextWidth - font.width("...")) + "...";
            }
            graphics.drawString(font, displayText, textX, textY, 0x404040, false);
        }
    }

    private void blitMachineSlotBackgrounds(GuiGraphics graphics) {
        int sw = 18;
        int sh = 18;
        for (int i = 0; i < DuctNodeMenu.UPGRADE_SLOT_COUNT; i++) {
            int y = DuctNodeMenu.SLOT_UPGRADE_Y0 + i * 18;
            graphics.blit(
                    SINGLE_SLOT,
                    this.leftPos + DuctNodeMenu.SLOT_UPGRADE_X,
                    this.topPos + y,
                    0,
                    0,
                    sw,
                    sh,
                    sw,
                    sh);
        }
        graphics.blit(
                SINGLE_SLOT,
                this.leftPos + DuctNodeMenu.SLOT_COPY_X,
                this.topPos + DuctNodeMenu.SLOT_COPY_Y,
                0,
                0,
                sw,
                sh,
                sw,
                sh);
    }

    @Override
    protected void renderSlotHighlight(
            @NotNull GuiGraphics guiGraphics,
            @NotNull Slot slot,
            int mouseX,
            int mouseY,
            float partialTick) {
        if (subView == SubView.HOW_TO_USE) {
            return;
        }
        super.renderSlotHighlight(guiGraphics, slot, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderSlot(@NotNull GuiGraphics graphics, @NotNull Slot slot) {
        if (subView == SubView.HOW_TO_USE) {
            return;
        }
        if (slot.index >= 0 && slot.index < DuctNodeMenu.MACHINE_SLOTS) {
            ItemStack stack = slot.getItem();
            int x = this.leftPos + slot.x;
            int y = this.topPos + slot.y;
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x, y);
                graphics.renderItemDecorations(this.font, stack, x, y);
            }
            return;
        }
        super.renderSlot(graphics, slot);
    }

    private static void renderScaledItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.pose().pushPose();
        float scale = REDSTONE_ICON_SIZE / 16.0f;
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    private static void renderScaledTexture(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        graphics.pose().pushPose();
        float scale = REDSTONE_ICON_SIZE / 16.0f;
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.blit(texture, 0, 0, 0, 0, 16, 16, 16, 16);
        graphics.pose().popPose();
    }

    private boolean isMouseOverAnyVisibleTextField(double mouseX, double mouseY) {
        if (routingPriorityBox != null && routingPriorityBox.visible && routingPriorityBox.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        if (editModeTextBox != null && editModeTextBox.visible && editModeTextBox.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        if (advCapEditBox != null && advCapEditBox.visible && advCapEditBox.isMouseOver(mouseX, mouseY)) {
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
    }

    private boolean isMouseInsideOurGui() {
        return lastMouseX >= this.leftPos
                && lastMouseX < this.leftPos + this.imageWidth
                && lastMouseY >= this.topPos
                && lastMouseY < this.topPos + this.imageHeight;
    }

    private static boolean jeiIsHandlingKeyboard() {
        try {
            Class<?> c = Class.forName("net.unfamily.another_dynamics.integration.jei.JeiRuntimeState");
            return (boolean) c.getMethod("jeiHasKeyboardFocusOrRecipesGuiOpen").invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && subView != SubView.HOW_TO_USE && !isMouseOverAnyVisibleTextField(mouseX, mouseY)) {
            unfocusAllTextFields();
        }
        // Right-click on Mode cycles backward.
        if (button == 1 && nodeModeButton != null && nodeModeButton.visible) {
            if (mouseX >= nodeModeButton.getX()
                    && mouseX < nodeModeButton.getX() + nodeModeButton.getWidth()
                    && mouseY >= nodeModeButton.getY()
                    && mouseY < nodeModeButton.getY() + nodeModeButton.getHeight()) {
                NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                boolean inHybridPanel = nm.isHybrid() && hybridPanel != HybridPanel.NONE;
                // In hybrid sub-panels, right-click behaves like Back.
                handleMenuButton(inHybridPanel ? 0 : 10);
                return true;
            }
        }
        if (button == 1 && routingModeButton != null && routingModeButton.visible && routingModeButton.active) {
            if (mouseX >= routingModeButton.getX()
                    && mouseX < routingModeButton.getX() + routingModeButton.getWidth()
                    && mouseY >= routingModeButton.getY()
                    && mouseY < routingModeButton.getY() + routingModeButton.getHeight()) {
                handleMenuButton(11);
                return true;
            }
        }
        if (subView == SubView.HOW_TO_USE && button == 0) {
            for (ExampleData exampleData : exampleDataList) {
                int sx = this.leftPos + exampleData.x;
                int sy = this.topPos + exampleData.y;
                if (mouseX >= sx && mouseX <= sx + exampleData.width
                        && mouseY >= sy && mouseY <= sy + this.font.lineHeight) {
                    if (minecraft != null && minecraft.keyboardHandler != null) {
                        minecraft.keyboardHandler.setClipboard(exampleData.example);
                        playClickSound();
                    }
                    return true;
                }
            }
        }
        if (subView == SubView.HOW_TO_USE) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (inEditMode() && button == 0) {
            int slotX = editModeSlotX();
            int slotY = editModeSlotY();
            if (mouseX >= slotX && mouseX < slotX + 18 && mouseY >= slotY && mouseY < slotY + 18) {
                handleGhostSlotClick();
                return true;
            }
        }
        if ((button == 0 || button == 1)
                && mouseX >= redstoneButtonScreenX
                && mouseX < redstoneButtonScreenX + REDSTONE_BUTTON_SIZE
                && mouseY >= redstoneButtonScreenY
                && mouseY < redstoneButtonScreenY + REDSTONE_BUTTON_SIZE) {
            handleMenuButton(button == 0 ? 2 : 12);
            return true;
        }

        if (subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS) {
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

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS) {
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
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0
                && isDraggingHandle
                && (subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS)
                && currentFilterMaxSlots() > visibleFilterEntries()) {
            int maxScroll = maxFilterScroll();
            if (maxScroll > 0) {
                int deltaY = (int) mouseY - dragStartY;
                float scrollRatio = (float) deltaY / (SCROLLBAR_HEIGHT - HANDLE_SIZE);
                int newOffset = dragStartScrollOffset + (int) (scrollRatio * maxScroll);
                setFilterScrollOffset(newOffset);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && isDraggingHandle) {
            isDraggingHandle = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (routingPriorityBox.isFocused() && keyCode == InputConstants.KEY_RETURN) {
            commitFieldFromEditBox();
            return true;
        }

        if (advCapEditBox != null && advCapEditBox.isFocused() && keyCode == InputConstants.KEY_RETURN) {
            applyAllowCapField();
            return true;
        }

        if (subView == SubView.MAIN
                && !inEditMode()
                && keyCode == GLFW.GLFW_KEY_M) {
            NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
            if (nm.usesExtractBatchField()) {
                amountMaxField();
                return true;
            }
        }

        boolean esc = keyCode == InputConstants.KEY_ESCAPE;
        boolean inv =
                minecraft != null && minecraft.options.keyInventory != null
                        && minecraft.options.keyInventory.matches(keyCode, scanCode);

        boolean editFilterFocused = editModeTextBox != null && editModeTextBox.isFocused();

        if (editFilterFocused) {
            if (editModeTextBox.keyPressed(keyCode, scanCode, modifiers)) {
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
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            if (subView == SubView.ADVANCED_FILTERING) {
                playClickSound();
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
            NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
            if (nm.isHybrid() && hybridPanel != HybridPanel.NONE) {
                playClickSound();
                hybridPanel = HybridPanel.NONE;
                applySubViewVisibility();
                return true;
            }
            onClose();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editModeTextBox != null && editModeTextBox.isFocused()) {
            if (editModeTextBox.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        if (advCapEditBox != null && advCapEditBox.isFocused()) {
            if (advCapEditBox.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void renderHelpLineWithExample(
            GuiGraphics guiGraphics,
            String beforeKey,
            String exampleKey,
            String afterKey,
            int x,
            int y,
            int mouseX,
            int mouseY) {
        Component beforeComponent = Component.translatable(beforeKey);
        Component exampleComponent = Component.translatable(exampleKey);
        Component afterComponent = Component.translatable(afterKey);
        String beforeText = beforeComponent.getString();
        String exampleText = exampleComponent.getString();
        String afterText = afterComponent.getString();
        int rowX = x;
        int rowY = y;
        int beforeWidth = this.font.width(beforeText);
        guiGraphics.drawString(this.font, beforeComponent, rowX, rowY, 0x404040, false);
        int exampleX = rowX + beforeWidth;
        int exampleWidth = this.font.width(exampleText);
        int sx = this.leftPos + exampleX;
        int sy = this.topPos + rowY;
        boolean hovered =
                mouseX >= sx && mouseX <= sx + exampleWidth && mouseY >= sy && mouseY <= sy + this.font.lineHeight;
        int exampleColor = hovered ? 0x0066FF : 0x0066CC;
        guiGraphics.drawString(this.font, exampleText, exampleX, rowY, exampleColor, false);
        if (hovered) {
            int underlineY = rowY + this.font.lineHeight;
            guiGraphics.fill(exampleX, underlineY, exampleX + exampleWidth, underlineY + 1, exampleColor);
        }
        exampleDataList.add(new ExampleData(exampleText, x + beforeWidth, y, exampleWidth));
        if (!afterText.isEmpty()) {
            int afterX = exampleX + exampleWidth;
            guiGraphics.drawString(this.font, afterComponent, afterX, rowY, 0x404040, false);
        }
    }

    private void renderHelpLineWithTwoExamples(
            GuiGraphics guiGraphics,
            String beforeKey,
            String example1Key,
            String middleKey,
            String example2Key,
            String afterKey,
            int x,
            int y,
            int mouseX,
            int mouseY) {
        Component beforeComponent = Component.translatable(beforeKey);
        Component example1Component = Component.translatable(example1Key);
        Component middleComponent = Component.translatable(middleKey);
        Component example2Component = Component.translatable(example2Key);
        Component afterComponent = Component.translatable(afterKey);
        String beforeText = beforeComponent.getString();
        String example1Text = example1Component.getString();
        String middleText = middleComponent.getString();
        String example2Text = example2Component.getString();
        String afterText = afterComponent.getString();
        int rowX = x;
        int rowY = y;
        int beforeWidth = this.font.width(beforeText);
        guiGraphics.drawString(this.font, beforeComponent, rowX, rowY, 0x404040, false);
        int example1X = rowX + beforeWidth;
        int example1Width = this.font.width(example1Text);
        int s1x = this.leftPos + example1X;
        int s1y = this.topPos + rowY;
        boolean isHovered1 =
                mouseX >= s1x && mouseX <= s1x + example1Width && mouseY >= s1y && mouseY <= s1y + this.font.lineHeight;
        int example1Color = isHovered1 ? 0x0066FF : 0x0066CC;
        guiGraphics.drawString(this.font, example1Text, example1X, rowY, example1Color, false);
        if (isHovered1) {
            int underlineY = rowY + this.font.lineHeight;
            guiGraphics.fill(example1X, underlineY, example1X + example1Width, underlineY + 1, example1Color);
        }
        exampleDataList.add(new ExampleData(example1Text, x + beforeWidth, y, example1Width));
        int middleX = example1X + example1Width;
        guiGraphics.drawString(this.font, middleComponent, middleX, rowY, 0x404040, false);
        int middleWidth = this.font.width(middleText);
        int example2X = middleX + middleWidth;
        int example2Width = this.font.width(example2Text);
        int s2x = this.leftPos + example2X;
        boolean isHovered2 =
                mouseX >= s2x && mouseX <= s2x + example2Width && mouseY >= s1y && mouseY <= s1y + this.font.lineHeight;
        int example2Color = isHovered2 ? 0x0066FF : 0x0066CC;
        guiGraphics.drawString(this.font, example2Text, example2X, rowY, example2Color, false);
        if (isHovered2) {
            int underlineY = rowY + this.font.lineHeight;
            guiGraphics.fill(example2X, underlineY, example2X + example2Width, underlineY + 1, example2Color);
        }
        exampleDataList.add(new ExampleData(example2Text, x + beforeWidth + example1Width + middleWidth, y, example2Width));
        if (!afterText.isEmpty()) {
            int afterX = example2X + example2Width;
            guiGraphics.drawString(this.font, afterComponent, afterX, rowY, 0x404040, false);
        }
    }

    private void renderExampleTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (ExampleData exampleData : exampleDataList) {
            int screenX = this.leftPos + exampleData.x;
            int screenY = this.topPos + exampleData.y;
            if (mouseX >= screenX && mouseX <= screenX + exampleData.width
                    && mouseY >= screenY && mouseY <= screenY + this.font.lineHeight) {
                List<Component> tooltip = new ArrayList<>(2);
                tooltip.add(Component.translatable("gui.another_dynamics.general_filter_text.click_to_copy"));
                tooltip.add(Component.translatable("gui.another_dynamics.general_filter_text.paste_hint"));
                guiGraphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        Component titleComponent =
                switch (subView) {
                    case ADVANCED_FILTERING ->
                            Component.translatable("gui.another_dynamics.duct_node.advanced_filtering.title");
                    case DENY_FILTERS -> {
                        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                        if (nm.isHybrid()) {
                            yield Component.translatable(
                                    switch (activeFilterBank) {
                                        case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                                        case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                                        case FILTER -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                                    });
                        }
                        yield Component.translatable("gui.another_dynamics.duct_node.deny_list");
                    }
                    case ALLOW_FILTERS -> {
                        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                        if (nm.isHybrid()) {
                            yield Component.translatable(
                                    switch (activeFilterBank) {
                                        case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                                        case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                                        case FILTER -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                                    });
                        }
                        yield Component.translatable("gui.another_dynamics.duct_node.allow_list");
                    }
                    case HOW_TO_USE -> Component.translatable("gui.another_dynamics.duct_node.filters.how_to_use");
                    case MAIN -> {
                        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
                        if (nm.isHybrid() && hybridPanel != HybridPanel.NONE) {
                            yield Component.translatable(
                                    switch (hybridPanel) {
                                        case EXTRACTOR -> "gui.another_dynamics.duct_node.hybrid.title.extractor";
                                        case FILTERING -> "gui.another_dynamics.duct_node.hybrid.title.filter";
                                        case RETRIEVER -> "gui.another_dynamics.duct_node.hybrid.title.retriever";
                                        default -> DuctIds.nodeScreenTranslationKey(menu.getClientDuctLogicalId());
                                    });
                        }
                        yield Component.translatable(DuctIds.nodeScreenTranslationKey(menu.getClientDuctLogicalId()));
                    }
                };
        int titleWidth = this.font.width(titleComponent);
        int titleX = (this.imageWidth - titleWidth) / 2;
        /* Foreground: coordinates are relative—AbstractContainerScreen applies leftPos/topPos on the pose stack. */
        graphics.drawString(this.font, titleComponent, titleX, 7, 0x404040, false);

        if (subView == SubView.ADVANCED_FILTERING) {
            boolean limitCtx;
            if (activeFilterBank == DuctFaceNode.FilterBank.FILTER) {
                int ord = menu.getSyncData().get(DuctMenuSync.ELIGIBILITY_MODE);
                DuctFaceNode.EligibilityMode em = DuctFaceNode.EligibilityMode.fromOrdinal(ord);
                limitCtx = em.isInsertable();
            } else {
                limitCtx = activeFilterBank == DuctFaceNode.FilterBank.RETRIEVER;
            }
            Component capLabel =
                    Component.translatable(
                            limitCtx
                                    ? "gui.another_dynamics.duct_node.allow_cap.label.limit"
                                    : "gui.another_dynamics.duct_node.allow_cap.label.keep");
            int lw = this.font.width(capLabel);
            int labelX = advCapEditBoxGuiLeft + (AMOUNT_EDIT_W - lw) / 2;
            int labelY = ADVANCED_CAP_NUMERIC_ROW_GUI_Y - this.font.lineHeight - AMOUNT_LABEL_ABOVE_GAP;
            graphics.drawString(this.font, capLabel, labelX, labelY, 0x404040, false);
        }

        if (subView == SubView.MAIN) {
            NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
            // Hybrid selector: priority/quantity block is hidden there; it appears only inside hybrid sub-panels.
            if (nm.isHybrid() && hybridPanel == HybridPanel.NONE) {
                return;
            }
            Component amountLabel =
                    Component.translatable(
                            amountFieldEditsPriority()
                                    ? "gui.another_dynamics.duct_node.amount.label.priority"
                                    : "gui.another_dynamics.duct_node.amount.label.batch");
            int lw = this.font.width(amountLabel);
            int labelX = amountEditBoxGuiLeft + (AMOUNT_EDIT_W - lw) / 2;
            int labelY = AMOUNT_ROW_Y - this.font.lineHeight - AMOUNT_LABEL_ABOVE_GAP;
            graphics.drawString(this.font, amountLabel, labelX, labelY, 0x404040, false);
        }

        if (subView == SubView.HOW_TO_USE) {
            exampleDataList.clear();
            int titleBaseline = 7;
            int gapBelowTitle = 10;
            int helpLineStep = this.font.lineHeight + 4;
            int helpY = titleBaseline + this.font.lineHeight + gapBelowTitle;
            String p =
                    menu.getSyncData().get(DuctMenuSync.ACTIVE_TRANSPORT_KIND) == DuctTransportKind.FLUID.ordinal()
                            ? "gui.another_dynamics.fluid_filter_text."
                            : "gui.another_dynamics.general_filter_text.";
            renderHelpLineWithExample(graphics, p + "id", p + "id.example", p + "id.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += helpLineStep;
            renderHelpLineWithExample(graphics, p + "tag", p + "tag.example", p + "tag.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += helpLineStep;
            renderHelpLineWithExample(graphics, p + "modid", p + "modid.example", p + "modid.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += helpLineStep;
            graphics.drawString(this.font, Component.translatable(p + "nbt"), HELP_TEXT_X, helpY, 0x404040, false);
            helpY += helpLineStep;
            renderHelpLineWithExample(
                    graphics, p + "nbt.example", p + "nbt.example.text", p + "nbt.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += helpLineStep;
            if (p.endsWith("general_filter_text.")) {
                renderHelpLineWithTwoExamples(
                        graphics,
                        p + "macro",
                        p + "macro.example1",
                        p + "macro.middle",
                        p + "macro.example2",
                        p + "macro.after",
                        HELP_TEXT_X,
                        helpY,
                        mouseX,
                        mouseY);
            } else {
                renderHelpLineWithExample(graphics, p + "macro", p + "macro.example1", p + "macro.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            }
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (subView == SubView.HOW_TO_USE) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            // renderBg blits at absolute (leftPos, topPos); do not pre-translate or the panel draws twice (shifted).
            renderBg(graphics, partialTick, mouseX, mouseY);
            for (Renderable renderable : this.renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
            // renderLabels uses coordinates relative to the GUI (same as AbstractContainerScreen after translate).
            graphics.pose().pushPose();
            graphics.pose().translate(this.leftPos, this.topPos, 0.0F);
            renderLabels(graphics, mouseX, mouseY);
            graphics.pose().popPose();
            renderTooltip(graphics, mouseX, mouseY);
            ItemStack carried = this.menu.getCarried();
            if (!carried.isEmpty()) {
                int cx = mouseX - 8;
                int cy = mouseY - 8;
                graphics.renderItem(carried, cx, cy);
                graphics.renderItemDecorations(this.font, carried, cx, cy);
            }
            renderExampleTooltip(graphics, mouseX, mouseY);
            return;
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        // Slot / container tooltips (same pattern as DeepDrawerExtractorScreen — not always drawn by super alone).
        this.renderTooltip(graphics, mouseX, mouseY);

        if (mouseX >= redstoneButtonScreenX
                && mouseX < redstoneButtonScreenX + REDSTONE_BUTTON_SIZE
                && mouseY >= redstoneButtonScreenY
                && mouseY < redstoneButtonScreenY + REDSTONE_BUTTON_SIZE) {
            graphics.renderTooltip(
                    this.font,
                    Component.translatable("gui.another_dynamics.duct_node.redstone_mode." + redstoneModeStub),
                    mouseX,
                    mouseY);
        }
    }
}
