package net.unfamily.another_dynamics.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.NodeMode;
import net.unfamily.another_dynamics.duct.RoutingMode;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.network.ModNetwork;

import org.jetbrains.annotations.NotNull;

/**
 * Duct node GUI: upgrades, center controls, filter sub-screens (Deep Drawer Extractor parity), right column, inventory.
 */
public final class DuctNodeScreen extends AbstractContainerScreen<DuctNodeMenu> {
    private enum SubView {
        MAIN,
        DENY_FILTERS,
        ALLOW_FILTERS,
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
    private static final ResourceLocation ENTRY_WIDE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/entry_wide.png");
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

    private static final int CHANNEL_WIDGET_W = 16;
    private static final int CHANNEL_WIDGET_H = 10;
    private static final int CHANNEL_WIDGET_Y = ROW3_Y + BTN_H - CHANNEL_WIDGET_H;

    /** Visible filter rows; scroll when there are more slots. */
    private static final int VISIBLE_FILTER_ENTRIES = 4;

    private static final int ENTRY_WIDTH = 140;
    private static final int ENTRY_HEIGHT = 24;
    private static final int ENTRY_X = CENTER_X;
    /** Filter entries start directly under the title row (Back / Valid keys are below the list). */
    private static final int FIRST_FILTER_ROW_Y = ROW1_Y;

    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_HEIGHT = 34;
    private static final int HANDLE_SIZE = 8;
    /** Gap between last entry row and Back / Valid keys row. */
    private static final int FILTER_NAV_GAP = 6;
    /** Small gap between entry list and edit-mode (ghost slot) row. */
    private static final int EDIT_MODE_GAP_BELOW_LIST = 4;

    private static final int SCROLLBAR_X_REL = ENTRY_X + ENTRY_WIDTH + 4;
    private static final int BUTTON_UP_Y_REL = FIRST_FILTER_ROW_Y;
    private static final int SCROLLBAR_Y_REL = BUTTON_UP_Y_REL + HANDLE_SIZE;
    private static final int BUTTON_DOWN_Y_REL = SCROLLBAR_Y_REL + SCROLLBAR_HEIGHT;

    private static final int HELP_TEXT_START_Y = 24;
    private static final int HELP_TEXT_X = 8;
    private static final int HELP_TEXT_LINE_HEIGHT = 12;
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
    private ChannelLetterButton channelButton;
    private EditBox routingPriorityBox;

    private Button denyNavButton;
    private Button listLogicButton;
    private Button allowNavButton;
    private Button selfFeedStub;
    private Button renderingStub;
    private Button backButton;
    private Button validKeysButton;

    private SubView subView = SubView.MAIN;
    private SubView filterListBeforeHelp = SubView.DENY_FILTERS;
    private int filterScrollOffset;
    private boolean isDraggingHandle;
    private int dragStartY;
    private int dragStartScrollOffset;

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
    private ItemStack ghostSlotItem = ItemStack.EMPTY;
    private List<String> filterVariants = new ArrayList<>();
    private int currentFilterVariantIndex = 0;

    public DuctNodeScreen(DuctNodeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = TEXTURE_WIDTH;
        this.imageHeight = TEXTURE_HEIGHT;
        this.inventoryLabelY = 10_000;
    }

    public void receiveFilterSync(
            BlockPos pos,
            Direction face,
            List<String> allow,
            List<String> deny,
            boolean denyOverridesAllow) {
        menu.receiveFilterSync(pos, face, allow, deny, denyOverridesAllow);
        menu.ensureClientFilterBufferSizes();
        rebuildFilterEntryWidgets();
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
                    openFilterSubview(SubView.DENY_FILTERS);
                })
                .bounds(this.leftPos + CENTER_X, this.topPos + ROW1_Y, ROW_BTN_W, BTN_H)
                .build();
        addRenderableWidget(denyNavButton);

        listLogicButton = Button.builder(Component.literal(">>>>>"), b -> {
                    playClickSound();
                    ModNetwork.sendListLogicToggle(menuSyncedPos(), menuSyncedFace());
                })
                .bounds(
                        this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP,
                        this.topPos + ROW1_Y,
                        ROW_BTN_W,
                        BTN_H)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.list_logic.tooltip")))
                .build();
        addRenderableWidget(listLogicButton);

        allowNavButton = Button.builder(Component.translatable("gui.another_dynamics.duct_node.allow_list"), b -> {
                    playClickSound();
                    openFilterSubview(SubView.ALLOW_FILTERS);
                })
                .bounds(
                        this.leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                        this.topPos + ROW1_Y,
                        ROW_BTN_W,
                        BTN_H)
                .build();
        addRenderableWidget(allowNavButton);

        int r2x = CENTER_X;
        routingModeButton = Button.builder(Component.empty(), b -> handleMenuButton(1))
                .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, ROW_BTN_W, BTN_H)
                .build();
        addRenderableWidget(routingModeButton);
        r2x += ROW_BTN_W + ROW_GAP;
        int midX = r2x;
        int stepperW = 11;
        int innerGap = 2;
        int editW = ROW_BTN_W - 2 * stepperW - 2 * innerGap;
        routingMinusButton = Button.builder(Component.literal("-"), b -> adjustRoutingPriority(-1))
                .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, stepperW, BTN_H)
                .build();
        addRenderableWidget(routingMinusButton);
        r2x += stepperW + innerGap;
        routingPriorityBox = new EditBox(this.font, this.leftPos + r2x, this.topPos + ROW2_Y, editW, BTN_H, Component.empty());
        routingPriorityBox.setMaxLength(6);
        routingPriorityBox.setValue("0");
        addRenderableWidget(routingPriorityBox);
        r2x += editW + innerGap;
        routingPlusButton = Button.builder(Component.literal("+"), b -> adjustRoutingPriority(1))
                .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, stepperW, BTN_H)
                .build();
        addRenderableWidget(routingPlusButton);
        r2x = midX + ROW_BTN_W;
        r2x += ROW_GAP;

        nodeModeButton = Button.builder(Component.empty(), b -> handleMenuButton(0))
                .bounds(this.leftPos + CENTER_X, this.topPos + ROW3_Y, ROW_BTN_W, BTN_H)
                .build();
        addRenderableWidget(nodeModeButton);
        selfFeedStub = Button.builder(Component.translatable("gui.another_dynamics.duct_node.self_feed"), b -> playClickSound())
                .bounds(this.leftPos + CENTER_X + ROW_BTN_W + ROW_GAP, this.topPos + ROW3_Y, ROW_BTN_W, BTN_H)
                .build();
        addRenderableWidget(selfFeedStub);
        renderingStub = Button.builder(Component.translatable("gui.another_dynamics.duct_node.rendering"), b -> playClickSound())
                .bounds(
                        this.leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                        this.topPos + ROW3_Y,
                        ROW_BTN_W,
                        BTN_H)
                .build();
        addRenderableWidget(renderingStub);

        backButton = Button.builder(Component.translatable("gui.another_dynamics.duct_node.filters.back"), b -> {
                    playClickSound();
                    closeFilterSubview();
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

        menu.ensureClientFilterBufferSizes();
        rebuildFilterEntryWidgets();
        applySubViewVisibility();
    }

    private void openFilterSubview(SubView v) {
        exitEditMode(false);
        subView = v;
        filterScrollOffset = 0;
        menu.ensureClientFilterBufferSizes();
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

    private void handleCloseOrBack() {
        if (subView == SubView.MAIN) {
            onClose();
        } else {
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

    private void applySubViewVisibility() {
        boolean main = subView == SubView.MAIN;
        boolean filterList = subView == SubView.DENY_FILTERS || subView == SubView.ALLOW_FILTERS;
        boolean howto = subView == SubView.HOW_TO_USE;
        boolean edit = inEditMode();

        denyNavButton.visible = main;
        listLogicButton.visible = main;
        allowNavButton.visible = main;
        routingModeButton.visible = main && !howto;
        routingMinusButton.visible = main && !howto;
        routingPlusButton.visible = main && !howto;
        routingPriorityBox.visible = main && !howto;
        nodeModeButton.visible = main && !howto;
        selfFeedStub.visible = main && !howto;
        renderingStub.visible = main && !howto;

        closeButton.visible = true;
        channelButton.visible = !howto;

        backButton.visible = !main && !edit;
        validKeysButton.visible = filterList && !edit && !howto;

        for (Button b : filterEditButtons) {
            b.visible = filterList && !edit;
        }
        for (Button b : filterDeleteButtons) {
            b.visible = filterList && !edit;
        }

        if (editModeTextBox != null) {
            boolean showEdit = edit;
            editModeTextBox.visible = showEdit;
            if (leftArrowButton != null) {
                leftArrowButton.visible = showEdit;
            }
            if (rightArrowButton != null) {
                rightArrowButton.visible = showEdit;
            }
            if (editModeClearButton != null) {
                editModeClearButton.visible = showEdit;
            }
            if (editModeApplyButton != null) {
                editModeApplyButton.visible = showEdit;
            }
            if (editModeCloseButton != null) {
                editModeCloseButton.visible = showEdit;
            }
        }

        layoutFilterNavAndHelpButtons();
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
        for (int i = 0; i < VISIBLE_FILTER_ENTRIES; i++) {
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
        return subView == SubView.ALLOW_FILTERS ? menu.filterAllowCap() : menu.filterDenyCap();
    }

    private List<String> getEditingList() {
        return subView == SubView.ALLOW_FILTERS ? menu.getClientAllowFilters() : menu.getClientDenyFilters();
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
            if (visibleIndex < 0 || visibleIndex >= VISIBLE_FILTER_ENTRIES) {
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

    private int editModeInventoryStartX() {
        return DuctNodeMenu.PLAYER_SLOTS_X;
    }

    private int editModeSlotX() {
        return this.leftPos + editModeInventoryStartX() + 18 - 1;
    }

    private int editModeSlotY() {
        return this.topPos + FIRST_FILTER_ROW_Y + VISIBLE_FILTER_ENTRIES * ENTRY_HEIGHT + EDIT_MODE_GAP_BELOW_LIST;
    }

    private void enterEditMode(int index) {
        if (editModeFilterIndex == index) {
            return;
        }
        exitEditMode(false);
        editModeFilterIndex = index;
        List<String> list = getEditingList();
        while (list.size() <= index) {
            list.add("");
        }
        originalFilterValue = list.get(index) != null ? list.get(index) : "";
        createEditModeUI();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    private void exitEditMode(boolean restoreValue) {
        if (editModeFilterIndex >= 0 && restoreValue) {
            List<String> list = getEditingList();
            while (list.size() <= editModeFilterIndex) {
                list.add("");
            }
            list.set(editModeFilterIndex, originalFilterValue);
        }
        editModeFilterIndex = -1;
        originalFilterValue = "";
        removeEditModeUI();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
    }

    private void createEditModeUI() {
        removeEditModeUI();

        int inventoryStartX = editModeInventoryStartX();
        int slotSize = 18;
        int slotX = editModeSlotX();
        int slotY = editModeSlotY();

        int buttonSize = 12;
        int buttonSpacing = 2;
        int leftButtonX = slotX - buttonSize - buttonSpacing;
        int leftButtonY = slotY + (slotSize - buttonSize) / 2;

        leftArrowButton = Button.builder(Component.literal("\u2190"), b -> {
                    playClickSound();
                    cycleFilterVariant(-1);
                })
                .bounds(leftButtonX, leftButtonY, buttonSize, buttonSize)
                .build();
        addRenderableWidget(leftArrowButton);

        int rightButtonX = slotX + slotSize + buttonSpacing;
        int rightButtonY = slotY + (slotSize - buttonSize) / 2;
        rightArrowButton = Button.builder(Component.literal("\u2192"), b -> {
                    playClickSound();
                    cycleFilterVariant(1);
                })
                .bounds(rightButtonX, rightButtonY, buttonSize, buttonSize)
                .build();
        addRenderableWidget(rightArrowButton);

        int rightEdge = this.leftPos + this.imageWidth;
        int margin = 5;
        int closeButtonX = rightEdge - margin - buttonSize;
        int applyButtonX = closeButtonX - buttonSize - buttonSpacing;
        int clearButtonX = applyButtonX - buttonSize - buttonSpacing;
        int buttonRowY = slotY + (slotSize - buttonSize) / 2;

        int textBoxX = this.leftPos + inventoryStartX;
        int textBoxY = slotY + slotSize + 2;
        int textBoxHeight = 15;
        int textBoxWidth = rightEdge - textBoxX - margin;

        editModeTextBox = new EditBox(this.font, textBoxX, textBoxY, textBoxWidth, textBoxHeight, Component.literal("Edit Filter"));
        editModeTextBox.setMaxLength(512);
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
                .bounds(clearButtonX, buttonRowY, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.clear")))
                .build();
        addRenderableWidget(editModeClearButton);

        editModeApplyButton = Button.builder(Component.literal("A"), b -> {
                    playClickSound();
                    applyEditModeAndClose();
                })
                .bounds(applyButtonX, buttonRowY, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.apply")))
                .build();
        addRenderableWidget(editModeApplyButton);

        editModeCloseButton = Button.builder(Component.literal("\u2715"), b -> {
                    playClickSound();
                    exitEditMode(true);
                })
                .bounds(closeButtonX, buttonRowY, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.translatable("gui.another_dynamics.duct_node.filters.close_without_saving")))
                .build();
        addRenderableWidget(editModeCloseButton);

        ghostSlotItem = ItemStack.EMPTY;
        filterVariants.clear();
        currentFilterVariantIndex = 0;
    }

    private void applyEditModeAndClose() {
        if (editModeTextBox != null && editModeFilterIndex >= 0) {
            String value = editModeTextBox.getValue();
            List<String> list = getEditingList();
            while (list.size() <= editModeFilterIndex) {
                list.add("");
            }
            list.set(editModeFilterIndex, value);
            pushFiltersToServer();
        }
        editModeFilterIndex = -1;
        originalFilterValue = "";
        removeEditModeUI();
        applySubViewVisibility();
        rebuildFilterEntryWidgets();
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
        ghostSlotItem = ItemStack.EMPTY;
        filterVariants.clear();
        currentFilterVariantIndex = 0;
    }

    private void renderEditModeSlot(GuiGraphics guiGraphics) {
        int slotSize = 18;
        int slotX = editModeSlotX();
        int slotY = editModeSlotY();
        guiGraphics.blit(SINGLE_SLOT, slotX, slotY, 0, 0, slotSize, slotSize, slotSize, slotSize);
        if (!ghostSlotItem.isEmpty()) {
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
            filterVariants.clear();
            currentFilterVariantIndex = 0;
            if (editModeTextBox != null) {
                editModeTextBox.setValue("");
                editModeTextBox.setCursorPosition(0);
                editModeTextBox.setHighlightPos(0);
            }
            playClickSound();
        } else {
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

    /** Same order as DeepDrawerExtractorScreen.generateAllFilterVariants. */
    private static List<String> generateAllFilterVariants(ItemStack stack) {
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
        menu.ensureClientFilterBufferSizes();
        menu.pushFilterConfigToServer(
                new ArrayList<>(menu.getClientAllowFilters()),
                new ArrayList<>(menu.getClientDenyFilters()),
                menu.getClientDenyOverridesAllow());
    }

    private void handleMenuButton(int id) {
        playClickSound();
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        menu.updateClientDenyOverridesFromSync();
        menu.ensureClientFilterBufferSizes();
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        nodeModeButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.mode." + nm.name().toLowerCase()));
        boolean routing = (menu.getSyncData().get(DuctMenuSync.FLAGS) & DuctMenuSync.FLAG_ROUTING_ACTIVE) != 0;
        routingModeButton.active = routing;
        if (routing) {
            RoutingMode rm = RoutingMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.ROUTING_MODE));
            routingModeButton.setMessage(
                    Component.translatable("gui.another_dynamics.duct_node.routing." + rm.name().toLowerCase()));
        } else {
            routingModeButton.setMessage(Component.translatable("gui.another_dynamics.duct_node.routing_unroutable"));
        }
        boolean denyOver = menu.getSyncData().get(DuctMenuSync.DENY_OVERRIDES_ALLOW) != 0;
        listLogicButton.setMessage(Component.literal(denyOver ? ">>>>>" : "<<<<<"));

        redstoneModeStub = menu.getSyncData().get(DuctMenuSync.REDSTONE_MODE);
        channelButton.setLetterValue(menu.getSyncData().get(DuctMenuSync.CHANNEL));
        if (!routingPriorityBox.isFocused()) {
            routingPriorityBox.setValue(Integer.toString(menu.getSyncData().get(DuctMenuSync.AMOUNT_FIELD)));
        }

        filterScrollOffset = Mth.clamp(filterScrollOffset, 0, maxFilterScroll());
    }

    private BlockPos menuSyncedPos() {
        var d = menu.getSyncData();
        return new BlockPos(d.get(DuctMenuSync.POS_X), d.get(DuctMenuSync.POS_Y), d.get(DuctMenuSync.POS_Z));
    }

    private Direction menuSyncedFace() {
        int o = menu.getSyncData().get(DuctMenuSync.ACCESS_FACE);
        return Direction.values()[Mth.clamp(o, 0, Direction.values().length - 1)];
    }

    private void adjustRoutingPriority(int delta) {
        playClickSound();
        int v = parsePriority(routingPriorityBox.getValue());
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        if (nm.usesInsertionPriorityField()) {
            v += delta;
        } else {
            v = Math.max(0, Math.min(999_999, v + delta));
        }
        routingPriorityBox.setValue(Integer.toString(v));
        ModNetwork.sendFieldUpdate(menuSyncedPos(), menuSyncedFace(), v);
    }

    private void commitFieldFromEditBox() {
        int v = parsePriority(routingPriorityBox.getValue());
        NodeMode nm = NodeMode.fromOrdinal(menu.getSyncData().get(DuctMenuSync.NODE_MODE));
        if (nm.usesExtractBatchField()) {
            v = Math.max(0, v);
        }
        ModNetwork.sendFieldUpdate(menuSyncedPos(), menuSyncedFace(), v);
    }

    private static int parsePriority(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
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
            if (inEditMode()) {
                renderEditModeSlot(graphics);
            }
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
            case 3 -> renderScaledItem(graphics, new ItemStack(Items.REPEATER), iconX, iconY);
            case 4 -> renderScaledItem(graphics, new ItemStack(Items.BARRIER), iconX, iconY);
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
            graphics.blit(ENTRY_WIDE, entryX, entryY, 0, 0, ENTRY_WIDTH, ENTRY_HEIGHT, ENTRY_WIDTH, ENTRY_HEIGHT);

            int slotX = entryX + 3;
            int slotY = entryY + 3;
            graphics.blit(SINGLE_SLOT, slotX, slotY, 0, 0, 18, 18, 18, 18);
            String filter = idx < list.size() && list.get(idx) != null ? list.get(idx) : "";
            ItemStack displayItem = getDisplayItemForFilter(filter);
            if (!displayItem.isEmpty()) {
                graphics.renderItem(displayItem, slotX + 1, slotY + 1);
                graphics.renderItemDecorations(this.font, displayItem, slotX + 1, slotY + 1);
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (subView == SubView.HOW_TO_USE && button == 0) {
            for (ExampleData exampleData : exampleDataList) {
                int sx = this.leftPos + exampleData.x;
                int sy = this.topPos + exampleData.y;
                if (mouseX >= sx && mouseX <= sx + exampleData.width
                        && mouseY >= sy && mouseY <= sy + HELP_TEXT_LINE_HEIGHT) {
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
        if (button == 0
                && mouseX >= redstoneButtonScreenX
                && mouseX < redstoneButtonScreenX + REDSTONE_BUTTON_SIZE
                && mouseY >= redstoneButtonScreenY
                && mouseY < redstoneButtonScreenY + REDSTONE_BUTTON_SIZE) {
            handleMenuButton(2);
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
        if (editModeTextBox != null && editModeTextBox.isFocused() && keyCode == InputConstants.KEY_RETURN) {
            applyEditModeAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        int absX = this.leftPos + x;
        int absY = this.topPos + y;
        int beforeWidth = this.font.width(beforeText);
        guiGraphics.drawString(this.font, beforeComponent, absX, absY, 0x404040, false);
        int exampleX = absX + beforeWidth;
        int exampleWidth = this.font.width(exampleText);
        boolean hovered =
                mouseX >= exampleX && mouseX <= exampleX + exampleWidth && mouseY >= absY && mouseY <= absY + HELP_TEXT_LINE_HEIGHT;
        int exampleColor = hovered ? 0x0066FF : 0x0066CC;
        guiGraphics.drawString(this.font, exampleText, exampleX, absY, exampleColor, false);
        if (hovered) {
            int underlineY = absY + this.font.lineHeight;
            guiGraphics.fill(exampleX, underlineY, exampleX + exampleWidth, underlineY + 1, exampleColor);
        }
        exampleDataList.add(new ExampleData(exampleText, x + beforeWidth, y, exampleWidth));
        if (!afterText.isEmpty()) {
            int afterX = exampleX + exampleWidth;
            guiGraphics.drawString(this.font, afterComponent, afterX, absY, 0x404040, false);
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
        int absX = this.leftPos + x;
        int absY = this.topPos + y;
        int beforeWidth = this.font.width(beforeText);
        guiGraphics.drawString(this.font, beforeComponent, absX, absY, 0x404040, false);
        int example1X = absX + beforeWidth;
        int example1Width = this.font.width(example1Text);
        boolean isHovered1 =
                mouseX >= example1X && mouseX <= example1X + example1Width && mouseY >= absY && mouseY <= absY + HELP_TEXT_LINE_HEIGHT;
        int example1Color = isHovered1 ? 0x0066FF : 0x0066CC;
        guiGraphics.drawString(this.font, example1Text, example1X, absY, example1Color, false);
        if (isHovered1) {
            int underlineY = absY + this.font.lineHeight;
            guiGraphics.fill(example1X, underlineY, example1X + example1Width, underlineY + 1, example1Color);
        }
        exampleDataList.add(new ExampleData(example1Text, x + beforeWidth, y, example1Width));
        int middleX = example1X + example1Width;
        guiGraphics.drawString(this.font, middleComponent, middleX, absY, 0x404040, false);
        int middleWidth = this.font.width(middleText);
        int example2X = middleX + middleWidth;
        int example2Width = this.font.width(example2Text);
        boolean isHovered2 =
                mouseX >= example2X && mouseX <= example2X + example2Width && mouseY >= absY && mouseY <= absY + HELP_TEXT_LINE_HEIGHT;
        int example2Color = isHovered2 ? 0x0066FF : 0x0066CC;
        guiGraphics.drawString(this.font, example2Text, example2X, absY, example2Color, false);
        if (isHovered2) {
            int underlineY = absY + this.font.lineHeight;
            guiGraphics.fill(example2X, underlineY, example2X + example2Width, underlineY + 1, example2Color);
        }
        exampleDataList.add(new ExampleData(example2Text, x + beforeWidth + example1Width + middleWidth, y, example2Width));
        if (!afterText.isEmpty()) {
            int afterX = example2X + example2Width;
            guiGraphics.drawString(this.font, afterComponent, afterX, absY, 0x404040, false);
        }
    }

    private void renderExampleTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (ExampleData exampleData : exampleDataList) {
            int screenX = this.leftPos + exampleData.x;
            int screenY = this.topPos + exampleData.y;
            if (mouseX >= screenX && mouseX <= screenX + exampleData.width
                    && mouseY >= screenY && mouseY <= screenY + HELP_TEXT_LINE_HEIGHT) {
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
                    case DENY_FILTERS -> Component.translatable("gui.another_dynamics.duct_node.deny_list");
                    case ALLOW_FILTERS -> Component.translatable("gui.another_dynamics.duct_node.allow_list");
                    case HOW_TO_USE -> Component.translatable("gui.another_dynamics.duct_node.filters.how_to_use");
                    case MAIN -> this.title;
                };
        int titleWidth = this.font.width(titleComponent);
        int titleX = this.leftPos + (this.imageWidth - titleWidth) / 2;
        graphics.drawString(this.font, titleComponent, titleX, this.topPos + 7, 0x404040, false);

        if (subView == SubView.HOW_TO_USE) {
            exampleDataList.clear();
            int helpY = HELP_TEXT_START_Y;
            String p = "gui.another_dynamics.general_filter_text.";
            renderHelpLineWithExample(graphics, p + "id", p + "id.example", p + "id.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += HELP_TEXT_LINE_HEIGHT;
            renderHelpLineWithExample(graphics, p + "tag", p + "tag.example", p + "tag.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += HELP_TEXT_LINE_HEIGHT;
            renderHelpLineWithExample(graphics, p + "modid", p + "modid.example", p + "modid.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += HELP_TEXT_LINE_HEIGHT;
            graphics.drawString(this.font, Component.translatable(p + "nbt"), this.leftPos + HELP_TEXT_X, this.topPos + helpY, 0x404040, false);
            helpY += HELP_TEXT_LINE_HEIGHT;
            renderHelpLineWithExample(
                    graphics, p + "nbt.example", p + "nbt.example.text", p + "nbt.after", HELP_TEXT_X, helpY, mouseX, mouseY);
            helpY += HELP_TEXT_LINE_HEIGHT;
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
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (subView == SubView.HOW_TO_USE) {
            renderExampleTooltip(graphics, mouseX, mouseY);
            return;
        }
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
