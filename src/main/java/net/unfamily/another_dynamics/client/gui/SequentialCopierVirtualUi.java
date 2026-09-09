package net.unfamily.another_dynamics.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.SettingsCopierClient;
import net.unfamily.another_dynamics.duct.DuctGuiLayout;
import net.unfamily.another_dynamics.duct.FilterLineTextUtil;
import net.unfamily.another_dynamics.integration.jei.ghost.IAnDynamicsGhostTarget;
import net.unfamily.another_dynamics.integration.mekanism.MekanismChemicalCompat;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.machine.sequential.SequentialTaskData;
import net.unfamily.another_dynamics.machine.sequential.SequenceStepData;
import net.unfamily.another_dynamics.machine.sequential.SequentialBufferBlockEntity;
import net.unfamily.another_dynamics.machine.sequential.SequentialGateMode;
import net.unfamily.another_dynamics.machine.sequential.SequentialRedstoneMode;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.network.SequentialBufferActionPayload;

import org.jetbrains.annotations.Nullable;

public final class SequentialCopierVirtualUi {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/background/node.png");
    private static final Identifier VALID_KEYS_TEXTURE =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/background/valid_keys.png");
    private static final Identifier ENTRY_ROW_TEXTURE =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/entry_duct.png");
    private static final Identifier SCROLLER_TEXTURE =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/scroller.png");
    private static final Identifier REDSTONE_GUI =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/redstone_gui.png");
    private static final Identifier SINGLE_SLOT =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot.png");

    private static final int TEXTURE_WIDTH = DuctGuiLayout.NODE_TEXTURE_WIDTH;
    private static final int TEXTURE_HEIGHT = DuctGuiLayout.NODE_TEXTURE_HEIGHT;

    private static final int CLOSE_SIZE = 12;
    private static final int CLOSE_X = 303;
    private static final int CLOSE_Y = 5;
    private static final int REDSTONE_SIZE = 16;
    private static final int COPIER_BTN_W = 18;
    private static final int COPIER_BTN_H = DuctNodeMenu.COPIER_ACTION_BUTTON_H;
    private static final int BTN_H = 14;
    private static final int ADJACENT_BTN_GAP = 2;
    private static final int ADVANCED_FILTER_BUTTON_WIDTH = 64;

    private static final int ENTRY_X = 38;
    private static final int FIRST_Y = 32;
    private static final int CENTER_X = ENTRY_X;
    private static final int ROW_BTN_W = 76;
    private static final int ROW_GAP = 4;
    private static final int CHROME_Y = FIRST_Y;
    private static final int REORDER_BTN_W = 16;
    private static final int ENTRY_WIDTH = 220;
    private static final int ENTRY_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 4;
    private static final int ROW_BTN = 12;
    private static final int BUTTON_MARGIN = 4;

    private static final int SCROLLER_WIDTH = 12;
    private static final int SCROLLER_HEIGHT = 15;
    private static final int SCROLL_ARROW = 12;
    private static final int SCROLL_ARROW_TRACK_GAP = 4;
    private static final int SCROLL_TRACK_COLOR = 0xFF000000;
    /** Same as duct: ENTRY_X + ENTRY_WIDTH + 4 = 262. */
    private static final int SCROLLBAR_X_REL = ENTRY_X + ENTRY_WIDTH + 4;
    private static final int FILTER_LIST_BOTTOM_Y = FIRST_Y + VISIBLE_ROWS * ENTRY_HEIGHT;
    private static final int BUTTON_UP_Y_REL = FIRST_Y;
    private static final int BUTTON_DOWN_Y_REL = FILTER_LIST_BOTTOM_Y - SCROLL_ARROW;
    /** Track Y = 48. */
    private static final int SCROLLBAR_Y_REL = BUTTON_UP_Y_REL + SCROLL_ARROW + SCROLL_ARROW_TRACK_GAP;
    /** Track H = 64. */
    private static final int SCROLLBAR_HEIGHT = BUTTON_DOWN_Y_REL - SCROLL_ARROW_TRACK_GAP - SCROLLBAR_Y_REL;
    private static final int EDIT_MODE_GAP_BELOW_LIST = 4;
    private static final int EDIT_MODE_TEXT_INSET_X = 10;

    private static final int AMOUNT_STEPPER_W = 14;
    private static final int AMOUNT_INNER_GAP = 2;
    private static final int AMOUNT_EDIT_W = 56;
    private static final int AMOUNT_ACTION_BTN = 12;
    private static final int AMOUNT_BTN_GAP = 2;
    private static final int AMOUNT_ROWS_GAP = 4;
    private static final int NUMERIC_ROW_Y = 88;
    private static final int AMOUNT_LABEL_ABOVE_GAP = 3;
    private static final int HELP_TEXT_X = 14;
    private static final int HELP_BACK_BUTTON_X = 8;
    private static final int HELP_BACK_BUTTON_Y = TEXTURE_HEIGHT - 25;
    private static final int PLAYER_SLOTS_Y = DuctNodeMenu.PLAYER_SLOTS_Y;
    private static final int HELP_CLIP_BOTTOM = PLAYER_SLOTS_Y;
    private static final int HELP_CONTENT_TOP = 7 + 9 + 10;
    private static final int AMOUNT_STEP_PLAIN = 1;
    private static final int AMOUNT_STEP_CTRL_OR_ALT = 10;
    private static final int AMOUNT_STEP_SHIFT = 100;

    /** Hub list / hub tag-member icon cycle period. */
    private static final long HUB_ICON_CYCLE_MS = 5000L;
    /** Edit-list step preview / tag-member icon cycle period. */
    private static final long LIST_ICON_CYCLE_MS = 2000L;

    enum SubView {
        HUB,
        SEQUENTIAL_TASKS,
        EDIT_LIST,
        STEP_EDIT,
        VALID_KEYS
    }

    /**
     * Survives {@link SettingsCopierScreen#init()} re-entry (JEI recipe book / resize) so the submenu and step draft
     * are not reset to hub.
     */
    public record PersistedView(
            SubView subView,
            SubView editListBeforeHelp,
            int hubScroll,
            int stepScroll,
            int helpScroll,
            int editingListIndex,
            int editingStepIndex,
            SequenceStepData.Kind draftKind,
            int draftConcatOrdinal,
            String draftFilterText,
            String draftAmountText,
            ItemStack ghostStack,
            FluidStack ghostFluid,
            @Nullable Object ghostGas,
            List<String> filterVariants,
            int filterVariantIndex) {}

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

    private SubView subView = SubView.HUB;
    private SubView editListBeforeHelp = SubView.EDIT_LIST;
    private int hubScroll;
    private int stepScroll;
    private int helpScroll;
    private int editingListIndex = -1;
    private int editingStepIndex = -1;
    private SequenceStepData.Kind draftKind = SequenceStepData.Kind.ITEM;
    private int draftConcatOrdinal;
    private String draftFilterText = "";
    private String draftAmountText = "1";
    private ItemStack ghostStack = ItemStack.EMPTY;
    private FluidStack ghostFluid = FluidStack.EMPTY;
    private @Nullable Object ghostGas;
    private final List<String> filterVariants = new ArrayList<>();
    private int filterVariantIndex;
    private final List<ExampleData> exampleDataList = new ArrayList<>();
    private int helpContentHeight;

    private EditBox filterBox;
    private EditBox amountBox;
    private EditBox listNameBox;
    private ItemIconButton gateButton;
    private Button convertButton;
    private Button scrollUpButton;
    private Button scrollDownButton;
    private Button leftArrowButton;
    private Button rightArrowButton;
    private Button amountMinusButton;
    private Button amountPlusButton;
    private Button amountSetOneButton;
    private Button amountSetThousandButton;
    private Button applyButton;
    private Button clearStepDraftButton;
    private Button closeWithoutSavingButton;
    private Button backButton;
    private Button validKeysButton;
    private Button strictIntakeButton;
    private Button reorderButton;
    private final List<Button> dynamicButtons = new ArrayList<>();
    private final List<FilterConcatChannelButton> concatButtons = new ArrayList<>();

    private boolean draggingScroll;
    private int dragScrollKind; // 0 hub, 1 edit-list, 2 valid-keys
    private int ghostSlotX;
    private int ghostSlotY;
    private int amountEditBoxGuiLeft;

    private final SettingsCopierScreen host;
    private final SettingsCopierMenu menu;
    private int leftPos;
    private int topPos;
    private int imageWidth = TEXTURE_WIDTH;
    private int imageHeight = TEXTURE_HEIGHT;
    private Font font;
    private Minecraft minecraft;

    public SequentialCopierVirtualUi(SettingsCopierScreen host) {
        this.host = host;
        this.menu = host.getMenu();
    }

    public PersistedView capturePersistedView() {
        return new PersistedView(
                subView,
                editListBeforeHelp,
                hubScroll,
                stepScroll,
                helpScroll,
                editingListIndex,
                editingStepIndex,
                draftKind,
                draftConcatOrdinal,
                draftFilterText,
                draftAmountText,
                ghostStack.copy(),
                ghostFluid.copy(),
                ghostGas,
                List.copyOf(filterVariants),
                filterVariantIndex);
    }

    private void applyPersistedView(PersistedView view) {
        this.subView = view.subView();
        this.editListBeforeHelp = view.editListBeforeHelp();
        this.hubScroll = view.hubScroll();
        this.stepScroll = view.stepScroll();
        this.helpScroll = view.helpScroll();
        this.editingListIndex = view.editingListIndex();
        this.editingStepIndex = view.editingStepIndex();
        this.draftKind = view.draftKind();
        this.draftConcatOrdinal = view.draftConcatOrdinal();
        this.draftFilterText = view.draftFilterText() != null ? view.draftFilterText() : "";
        this.draftAmountText = view.draftAmountText() != null ? view.draftAmountText() : "1";
        this.ghostStack = view.ghostStack() != null ? view.ghostStack().copy() : ItemStack.EMPTY;
        this.ghostFluid = view.ghostFluid() != null ? view.ghostFluid().copy() : FluidStack.EMPTY;
        this.ghostGas = view.ghostGas();
        this.filterVariants.clear();
        if (view.filterVariants() != null) {
            this.filterVariants.addAll(view.filterVariants());
        }
        this.filterVariantIndex = view.filterVariantIndex();
    }

    public void init() {
        init(null);
    }

    public void init(@Nullable PersistedView restore) {
        this.minecraft = host.sequentialUiMinecraft();
        this.font = host.sequentialUiFont();
        this.leftPos = host.getGuiLeft();
        this.topPos = host.getGuiTop();
        this.imageWidth = TEXTURE_WIDTH;
        this.imageHeight = TEXTURE_HEIGHT;
        // Do not reload from the held item: the session mirror is pushed via syncClientMirror.
        // Reloading here overwrote drafts with stale held NBT (and JEI re-init lost in-progress edits).
        if (restore != null) {
            applyPersistedView(restore);
        }
        rebuildUi();
    }

    public void tick() {
        refreshHubRowState();
        refreshGateTooltip();
        refreshStrictIntakeButton();
        if (subView == SubView.EDIT_LIST || subView == SubView.STEP_EDIT) {
            syncListNameBoxFromBeIfUnfocused();
        }
        if (subView == SubView.EDIT_LIST) {
            refreshEditListRowState();
        }
    }

    private SequentialGateMode gate() {
        return menu.sequentialGateMode();
    }

    private boolean strictSequentialIntake() {
        return menu.sequentialStrictIntake();
    }

    private SequentialTaskData listAt(int index) {
        return menu.sequentialList(index);
    }

    private void rebuildUi() {
        host.clearWidgetsForSequentialVirtual();
        dynamicButtons.clear();
        concatButtons.clear();
        filterBox = null;
        amountBox = null;
        listNameBox = null;
        gateButton = null;
        convertButton = null;
        scrollUpButton = null;
        scrollDownButton = null;
        leftArrowButton = null;
        rightArrowButton = null;
        amountMinusButton = null;
        amountPlusButton = null;
        amountSetOneButton = null;
        amountSetThousandButton = null;
        applyButton = null;
        clearStepDraftButton = null;
        closeWithoutSavingButton = null;
        backButton = null;
        validKeysButton = null;
        strictIntakeButton = null;
        reorderButton = null;
        draggingScroll = false;
        exampleDataList.clear();

        Button close =
                Button.builder(Component.literal("\u2715"), b -> requestBackOrLeave())
                        .bounds(leftPos + CLOSE_X, topPos + CLOSE_Y, CLOSE_SIZE, CLOSE_SIZE)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                subView == SubView.HUB
                                                        ? "gui.another_dynamics.settings_copier.back_to_hub"
                                                        : "gui.another_dynamics.sequential_buffer.close")))
                        .build();
        host.addSequentialWidget(close);

        switch (subView) {
            case HUB -> initHub();
            case SEQUENTIAL_TASKS -> initSequentialTasks();
            case EDIT_LIST -> initEditList();
            case STEP_EDIT -> initStepEdit();
            case VALID_KEYS -> initValidKeys();
        }
    }

    private void initRightColumn() {
        gateButton =
                new ItemIconButton(
                        leftPos + DuctNodeMenu.REDSTONE_GUI_X,
                        topPos + DuctNodeMenu.REDSTONE_GUI_Y,
                        REDSTONE_SIZE,
                        b -> sendAction(new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_CYCLE_GATE)),
                        this::gateIconStack,
                        () -> gate() == SequentialGateMode.HIGH ? REDSTONE_GUI : null,
                        () -> sendAction(
                                new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_CYCLE_GATE_PREV)),
                        Component.translatable(gateTooltipKey()));
        host.addSequentialWidget(gateButton);
    }

    private void initHub() {
        initRightColumn();

        int y = topPos + CHROME_Y;
        strictIntakeButton =
                Button.builder(
                                strictIntakeButtonMessage(),
                                b ->
                                        sendAction(
                                                new SequentialBufferActionPayload(
                                                        SequentialBufferActionPayload.ACTION_TOGGLE_STRICT_INTAKE)))
                        .bounds(leftPos + CENTER_X, y, ROW_BTN_W, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.sequential_buffer.strict_intake.tooltip")))
                        .build();
        host.addSequentialWidget(strictIntakeButton);

        Button sequenceLists =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.sequence_lists"),
                                b -> {
                                    subView = SubView.SEQUENTIAL_TASKS;
                                    rebuildUi();
                                })
                        .bounds(leftPos + CENTER_X + ROW_BTN_W + ROW_GAP, y, ROW_BTN_W, BTN_H)
                        .build();
        host.addSequentialWidget(sequenceLists);

        Button hubBack =
                Button.builder(
                                Component.translatable("gui.another_dynamics.settings_copier.back_to_hub"),
                                b -> host.requestSequentialVirtualLeave())
                        .bounds(leftPos + CENTER_X + 2 * (ROW_BTN_W + ROW_GAP), y, ROW_BTN_W, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.settings_copier.back_to_hub")))
                        .build();
        host.addSequentialWidget(hubBack);
    }

    private void initSequentialTasks() {
        initRightColumn();
        addScrollControls(0);

        int bx = fixedNavButtonScreenX();
        int by = fixedNavButtonScreenY();
        backButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.back"),
                                b -> {
                                    subView = SubView.HUB;
                                    rebuildUi();
                                })
                        .bounds(bx, by, ADVANCED_FILTER_BUTTON_WIDTH, BTN_H)
                        .build();
        host.addSequentialWidget(backButton);

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            final int row = i;
            int entryX = leftPos + ENTRY_X;
            int entryY = topPos + FIRST_Y + i * ENTRY_HEIGHT;
            int btnY = entryY + (ENTRY_HEIGHT - ROW_BTN) / 2;

            // Pack from the right with uniform gaps (same as EDIT_LIST / duct rows).
            int editX = entryX + ENTRY_WIDTH - BUTTON_MARGIN - ROW_BTN;
            int clearX = editX - ROW_BTN - ADJACENT_BTN_GAP;
            int enableX = clearX - ROW_BTN - ADJACENT_BTN_GAP;
            int rsX = enableX - REDSTONE_SIZE - ADJACENT_BTN_GAP;

            ItemIconButton output =
                    new ItemIconButton(
                            rsX,
                            entryY + (ENTRY_HEIGHT - REDSTONE_SIZE) / 2,
                            REDSTONE_SIZE,
                            b -> {
                                int idx = hubScroll + row;
                                sendAction(
                                        new SequentialBufferActionPayload(
                                                SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT, idx));
                            },
                            () -> listOutputIcon(hubScroll + row),
                            () -> listOutputOverlay(hubScroll + row),
                            () -> {
                                int idx = hubScroll + row;
                                sendAction(
                                        new SequentialBufferActionPayload(
                                                SequentialBufferActionPayload.ACTION_CYCLE_LIST_OUTPUT_PREV, idx));
                            },
                            Component.empty());
            Button enable =
                    Button.builder(
                                    Component.empty(),
                                    b -> {
                                        int idx = hubScroll + row;
                                        SequentialTaskData list = listAt(idx);
                                        if (!list.hasContent()) {
                                            return;
                                        }
                                        sendAction(
                                                new SequentialBufferActionPayload(
                                                        SequentialBufferActionPayload.ACTION_TOGGLE_ENABLE, idx));
                                    })
                            .bounds(enableX, btnY, ROW_BTN, ROW_BTN)
                            .build();
            Button clear =
                    Button.builder(
                                    Component.literal("C"),
                                    b -> {
                                        int idx = hubScroll + row;
                                        sendAction(
                                                new SequentialBufferActionPayload(
                                                        SequentialBufferActionPayload.ACTION_CLEAR_LIST, idx));
                                    })
                            .bounds(clearX, btnY, ROW_BTN, ROW_BTN)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable("gui.another_dynamics.sequential_buffer.clear")))
                            .build();
            Button edit =
                    Button.builder(
                                    Component.literal("✎"),
                                    b -> {
                                        int idx = hubScroll + row;
                                        if (idx < 0 || idx >= SequentialBufferBlockEntity.sequentialTaskCount()) {
                                            return;
                                        }
                                        openEditList(idx);
                                    })
                            .bounds(editX, btnY, ROW_BTN, ROW_BTN)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable("gui.another_dynamics.sequential_buffer.edit")))
                            .build();
            addDyn(output);
            addDyn(enable);
            addDyn(clear);
            addDyn(edit);
        }
        refreshHubRowState();
    }

    private void initEditList() {
        initRightColumn();
        addScrollControls(1);
        addFixedNavButtons(SubView.EDIT_LIST);
        initListTitleEditBox();

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            final int row = i;
            int entryX = leftPos + ENTRY_X;
            int entryY = topPos + FIRST_Y + i * ENTRY_HEIGHT;
            int buttonSize = ROW_BTN;
            // Exact duct formula: edit → delete → concat from the right.
            int editX = entryX + ENTRY_WIDTH - BUTTON_MARGIN - buttonSize;
            int deleteX = editX - buttonSize - 2;
            int concatX = deleteX - buttonSize - 2;
            int buttonY = entryY + (ENTRY_HEIGHT - buttonSize) / 2;

            FilterConcatChannelButton concatBtn =
                    new FilterConcatChannelButton(
                            concatX,
                            buttonY,
                            buttonSize,
                            buttonSize,
                            v -> {
                                int idx = stepScroll + row;
                                if (idx < 0 || idx >= SequentialTaskData.maxSteps()) {
                                    return;
                                }
                                sendAction(
                                        new SequentialBufferActionPayload(
                                                SequentialBufferActionPayload.ACTION_SET_CONCAT,
                                                editingListIndex,
                                                idx,
                                                0,
                                                1,
                                                "",
                                                v));
                            });
            concatButtons.add(concatBtn);
            host.addSequentialWidget(concatBtn);

            Button clear =
                    Button.builder(
                                    Component.literal("C"),
                                    b -> {
                                        int idx = stepScroll + row;
                                        SequentialTaskData list = currentList();
                                        if (list == null || idx < 0 || idx >= list.steps().size()) {
                                            return;
                                        }
                                        SequenceStepData step = list.steps().get(idx);
                                        if (step == null || step.isEmpty()) {
                                            return;
                                        }
                                        sendAction(
                                                new SequentialBufferActionPayload(
                                                        SequentialBufferActionPayload.ACTION_CLEAR_STEP,
                                                        editingListIndex,
                                                        idx));
                                    })
                            .bounds(deleteX, buttonY, buttonSize, buttonSize)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable(
                                                    "gui.another_dynamics.sequential_buffer.clear_step")))
                            .build();
            Button edit =
                    Button.builder(
                                    Component.literal("\u270E"),
                                    b -> {
                                        int idx = stepScroll + row;
                                        if (idx < 0 || idx >= SequentialTaskData.maxSteps()) {
                                            return;
                                        }
                                        SequentialTaskData list = currentList();
                                        SequenceStepData step = null;
                                        if (list != null && idx < list.steps().size()) {
                                            SequenceStepData at = list.steps().get(idx);
                                            if (at != null && !at.isEmpty()) {
                                                step = at;
                                            }
                                        }
                                        openStepEdit(idx, step);
                                    })
                            .bounds(editX, buttonY, buttonSize, buttonSize)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable("gui.another_dynamics.sequential_buffer.edit_step")))
                            .build();
            addDyn(clear);
            addDyn(edit);
        }

        refreshEditListRowState();
    }

    private void initStepEdit() {
        // Ghost arrows | slot; nav: Convert, Back, Valid keys, C A X. Amount: [−][box][+] then [1][K].
        initListTitleEditBox();
        int numericRowW = AMOUNT_STEPPER_W + AMOUNT_INNER_GAP + AMOUNT_EDIT_W + AMOUNT_INNER_GAP + AMOUNT_STEPPER_W;
        int actionRowW = AMOUNT_ACTION_BTN * 2 + AMOUNT_BTN_GAP;
        int blockW = Math.max(numericRowW, actionRowW);
        int blockGuiX = (TEXTURE_WIDTH - blockW) / 2;
        int numericGuiX = blockGuiX + (blockW - numericRowW) / 2;
        int amX = leftPos + numericGuiX;
        int amY = topPos + NUMERIC_ROW_Y;

        amountMinusButton =
                Button.builder(Component.literal("\u2212"), b -> nudgeAmount(-amountStep()))
                        .bounds(amX, amY, AMOUNT_STEPPER_W, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.amount.minus.tooltip.priority")))
                        .build();
        host.addSequentialWidget(amountMinusButton);

        amountEditBoxGuiLeft = numericGuiX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        int boxX = amX + AMOUNT_STEPPER_W + AMOUNT_INNER_GAP;
        amountBox =
                new EditBox(
                        font,
                        boxX,
                        amY,
                        AMOUNT_EDIT_W,
                        BTN_H,
                        Component.translatable("gui.another_dynamics.sequential_buffer.amount"));
        amountBox.setMaxLength(10);
        amountBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        amountBox.setValue("1");
        host.addSequentialWidget(amountBox);

        amountPlusButton =
                Button.builder(Component.literal("+"), b -> nudgeAmount(amountStep()))
                        .bounds(boxX + AMOUNT_EDIT_W + AMOUNT_INNER_GAP, amY, AMOUNT_STEPPER_W, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.amount.plus.tooltip.priority")))
                        .build();
        host.addSequentialWidget(amountPlusButton);

        int actY = amY + BTN_H + AMOUNT_ROWS_GAP;
        int editCenterX = boxX + AMOUNT_EDIT_W / 2;
        int ax = editCenterX - actionRowW / 2;
        amountSetOneButton =
                Button.builder(Component.literal("1"), b -> {
                            if (amountBox != null) {
                                amountBox.setValue("1");
                            }
                            playClickSound();
                        })
                        .bounds(ax, actY, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.sequential_buffer.amount.set_to_1.tooltip")))
                        .build();
        host.addSequentialWidget(amountSetOneButton);

        int kX = ax + AMOUNT_ACTION_BTN + AMOUNT_BTN_GAP;
        amountSetThousandButton =
                Button.builder(Component.literal("K"), b -> {
                            if (amountBox != null) {
                                amountBox.setValue("1000");
                            }
                            playClickSound();
                        })
                        .bounds(kX, actY, AMOUNT_ACTION_BTN, BTN_H)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.sequential_buffer.amount.set_to_1000.tooltip")))
                        .build();
        host.addSequentialWidget(amountSetThousandButton);

        int slotSize = 18;
        int buttonSize = 12;
        int buttonSpacing = 2;
        int slotY = editModeRowAnchorScreenY();
        int rowLeft = leftPos + ENTRY_X;
        int leftArrowX = rowLeft;
        int slotX = rowLeft + buttonSize + buttonSpacing;
        int rightArrowX = slotX + slotSize + buttonSpacing;
        int buttonRowY = slotY + (slotSize - buttonSize) / 2;
        ghostSlotX = slotX;
        ghostSlotY = slotY;

        leftArrowButton =
                Button.builder(Component.literal("\u2190"), b -> cycleFilterVariant(-1))
                        .bounds(leftArrowX, buttonRowY, buttonSize, buttonSize)
                        .build();
        host.addSequentialWidget(leftArrowButton);

        rightArrowButton =
                Button.builder(Component.literal("\u2192"), b -> cycleFilterVariant(1))
                        .bounds(rightArrowX, buttonRowY, buttonSize, buttonSize)
                        .build();
        host.addSequentialWidget(rightArrowButton);

        // [Convert] [Back] [Valid keys] [C][A][X]
        int convertX = rightArrowX + buttonSize + buttonSpacing;
        int convertY = slotY + (slotSize - REDSTONE_SIZE) / 2;
        convertButton =
                Button.builder(Component.literal("\uD83E\uDEA3"), b -> convertResource())
                        .bounds(convertX, convertY, REDSTONE_SIZE, REDSTONE_SIZE)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.sequential_buffer.convert.tooltip")))
                        .build();
        host.addSequentialWidget(convertButton);

        int backX = convertX + REDSTONE_SIZE + ADJACENT_BTN_GAP;
        int navY = slotY + (slotSize - BTN_H) / 2;
        backButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.back"),
                                b -> closeStepEdit())
                        .bounds(backX, navY, ADVANCED_FILTER_BUTTON_WIDTH, BTN_H)
                        .build();
        host.addSequentialWidget(backButton);

        int validKeysX = backX + ADVANCED_FILTER_BUTTON_WIDTH + ADJACENT_BTN_GAP;
        validKeysButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.valid_keys"),
                                b -> openValidKeys(SubView.STEP_EDIT))
                        .bounds(validKeysX, navY, ADVANCED_FILTER_BUTTON_WIDTH, BTN_H)
                        .build();
        host.addSequentialWidget(validKeysButton);

        int clearButtonX = validKeysX + ADVANCED_FILTER_BUTTON_WIDTH + ADJACENT_BTN_GAP;
        int applyButtonX = clearButtonX + buttonSize + buttonSpacing;
        int closeButtonX = applyButtonX + buttonSize + buttonSpacing;

        clearStepDraftButton =
                Button.builder(Component.literal("C"), b -> clearStepDraftInEditor())
                        .bounds(clearButtonX, buttonRowY, buttonSize, buttonSize)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.sequential_buffer.clear_step")))
                        .build();
        host.addSequentialWidget(clearStepDraftButton);

        applyButton =
                Button.builder(Component.literal("A"), b -> applyStepDraft())
                        .bounds(applyButtonX, buttonRowY, buttonSize, buttonSize)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable("gui.another_dynamics.duct_node.filters.apply")))
                        .build();
        host.addSequentialWidget(applyButton);

        closeWithoutSavingButton =
                Button.builder(Component.literal("\u2715"), b -> closeStepEdit())
                        .bounds(closeButtonX, buttonRowY, buttonSize, buttonSize)
                        .tooltip(
                                Tooltip.create(
                                        Component.translatable(
                                                "gui.another_dynamics.duct_node.filters.close_without_saving")))
                        .build();
        host.addSequentialWidget(closeWithoutSavingButton);

        int textBoxY = slotY + slotSize + 2;
        filterBox =
                new EditBox(
                        font,
                        leftPos + ENTRY_X + EDIT_MODE_TEXT_INSET_X,
                        textBoxY,
                        ENTRY_WIDTH - EDIT_MODE_TEXT_INSET_X * 2,
                        15,
                        Component.translatable("gui.another_dynamics.sequential_buffer.filter"));
        filterBox.setMaxLength(256);
        filterBox.setBordered(true);
        filterBox.setValue("");
        host.addSequentialWidget(filterBox);

        if (filterBox != null && !draftFilterText.isEmpty()) {
            filterBox.setValue(draftFilterText);
        }
        if (amountBox != null) {
            amountBox.setValue(
                    draftAmountText == null || draftAmountText.isEmpty() ? "1" : draftAmountText);
        }
        tryPopulateGhostFromExactIdFilterLine(draftFilterText);
    }

    private void initValidKeys() {
        backButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.back"),
                                b -> closeValidKeys())
                        .bounds(
                                leftPos + HELP_BACK_BUTTON_X,
                                topPos + HELP_BACK_BUTTON_Y,
                                ADVANCED_FILTER_BUTTON_WIDTH,
                                BTN_H)
                        .build();
        host.addSequentialWidget(backButton);

        addScrollControls(2);
        helpScroll = Mth.clamp(helpScroll, 0, maxHelpScroll());
        updateScrollButtonVisibility(2);
    }

    /**
     * Fixed Advanced-slot Back + Valid keys (same coords in EDIT_LIST and STEP_EDIT).
     * From {@code AbstractUniversalDuctScreen.editModeAdvancedButtonScreenX} / browse layout.
     */
    private void addFixedNavButtons(SubView forView) {
        int bx = fixedNavButtonScreenX();
        int by = fixedNavButtonScreenY();
        backButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.back"),
                                b -> {
                                    if (forView == SubView.STEP_EDIT) {
                                        closeStepEdit();
                                    } else {
                                        closeEditList();
                                    }
                                })
                        .bounds(bx, by, ADVANCED_FILTER_BUTTON_WIDTH, BTN_H)
                        .build();
        host.addSequentialWidget(backButton);

        validKeysButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.sequential_buffer.valid_keys"),
                                b -> openValidKeys(forView))
                        .bounds(
                                bx + ADVANCED_FILTER_BUTTON_WIDTH + ADJACENT_BTN_GAP,
                                by,
                                ADVANCED_FILTER_BUTTON_WIDTH,
                                BTN_H)
                        .build();
        host.addSequentialWidget(validKeysButton);

        if (forView == SubView.EDIT_LIST) {
            int rx =
                    bx
                            + ADVANCED_FILTER_BUTTON_WIDTH
                            + ADJACENT_BTN_GAP
                            + ADVANCED_FILTER_BUTTON_WIDTH
                            + ADJACENT_BTN_GAP;
            reorderButton =
                    Button.builder(
                                    Component.literal("R"),
                                    b -> {
                                        if (editingListIndex < 0) {
                                            return;
                                        }
                                        sendAction(
                                                new SequentialBufferActionPayload(
                                                        SequentialBufferActionPayload.ACTION_REORDER_STEPS,
                                                        editingListIndex));
                                    })
                            .bounds(rx, by, REORDER_BTN_W, BTN_H)
                            .tooltip(
                                    Tooltip.create(
                                            Component.translatable(
                                                    "gui.another_dynamics.sequential_buffer.reorder_steps.tooltip")))
                            .build();
            host.addSequentialWidget(reorderButton);
        }
    }

    private int fixedNavButtonScreenX() {
        int buttonSize = 12;
        int buttonSpacing = 2;
        int slotSize = 18;
        int rowLeft = leftPos + ENTRY_X;
        int slotX = rowLeft + buttonSize + buttonSpacing;
        int rightArrowX = slotX + slotSize + buttonSpacing;
        return rightArrowX + buttonSize + buttonSpacing;
    }

    private int fixedNavButtonScreenY() {
        int slotSize = 18;
        int slotY = editModeRowAnchorScreenY();
        return slotY + (slotSize - BTN_H) / 2;
    }

    private void addScrollControls(int kind) {
        scrollUpButton =
                Button.builder(Component.literal("\u25b2"), b -> scrollBy(kind, -1))
                        .bounds(leftPos + SCROLLBAR_X_REL, topPos + BUTTON_UP_Y_REL, SCROLL_ARROW, SCROLL_ARROW)
                        .build();
        scrollDownButton =
                Button.builder(Component.literal("\u25bc"), b -> scrollBy(kind, 1))
                        .bounds(leftPos + SCROLLBAR_X_REL, topPos + BUTTON_DOWN_Y_REL, SCROLL_ARROW, SCROLL_ARROW)
                        .build();
        if (kind == 2) {
            // Valid-keys: arrows on help track (title → player-slot band).
            int helpTrackTop = topPos + HELP_CONTENT_TOP;
            int helpTrackBottom = topPos + HELP_CLIP_BOTTOM - 4;
            scrollUpButton.setY(helpTrackTop);
            scrollDownButton.setY(helpTrackBottom - SCROLL_ARROW);
        }
        host.addSequentialWidget(scrollUpButton);
        host.addSequentialWidget(scrollDownButton);
        updateScrollButtonVisibility(kind);
    }

    private void addDyn(Button button) {
        dynamicButtons.add(button);
        host.addSequentialWidget(button);
    }

    private int editModeRowAnchorScreenY() {
        return topPos + FIRST_Y + VISIBLE_ROWS * ENTRY_HEIGHT + EDIT_MODE_GAP_BELOW_LIST;
    }

    private void openEditList(int index) {
        editingListIndex = index;
        editingStepIndex = -1;
        stepScroll = 0;
        clearDraftFields();
        subView = SubView.EDIT_LIST;
        sendAction(new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_OPEN_EDIT, index));
        rebuildUi();
    }

    private void closeEditList() {
        editingListIndex = -1;
        editingStepIndex = -1;
        subView = SubView.SEQUENTIAL_TASKS;
        sendAction(new SequentialBufferActionPayload(SequentialBufferActionPayload.ACTION_CLOSE_EDIT));
        rebuildUi();
    }

    private SequentialTaskData currentList() {
        if (editingListIndex < 0 || editingListIndex >= SequentialBufferBlockEntity.sequentialTaskCount()) {
            return null;
        }
        return listAt(editingListIndex);
    }

    /** Always 50 fixed slots. */
    private int displayStepCount() {
        return SequentialTaskData.maxSteps();
    }

    private void openStepEdit(int index, SequenceStepData step) {
        editingStepIndex = index;
        SequentialTaskData list = currentList();
        draftConcatOrdinal = list == null || index < 0 ? 0 : list.concatAt(index);
        if (step == null || step.isEmpty()) {
            draftKind = SequenceStepData.Kind.ITEM;
            draftFilterText = "";
            draftAmountText = "1";
            clearGhostCalibration(false);
        } else {
            draftKind = step.kind();
            draftFilterText = step.filter() == null ? "" : step.filter();
            draftAmountText = Integer.toString(Math.max(1, step.amount()));
        }
        subView = SubView.STEP_EDIT;
        rebuildUi();
        playClickSound();
    }

    private void closeStepEdit() {
        editingStepIndex = -1;
        clearDraftFields();
        subView = SubView.EDIT_LIST;
        rebuildUi();
        playClickSound();
    }

    private void openValidKeys(SubView from) {
        editListBeforeHelp = from;
        if (from == SubView.STEP_EDIT) {
            draftFilterText = filterBox == null || filterBox.getValue() == null ? "" : filterBox.getValue();
            draftAmountText = amountBox == null || amountBox.getValue() == null ? "1" : amountBox.getValue();
        }
        helpScroll = 0;
        subView = SubView.VALID_KEYS;
        rebuildUi();
        playClickSound();
    }

    private void closeValidKeys() {
        subView = editListBeforeHelp == SubView.STEP_EDIT ? SubView.STEP_EDIT : SubView.EDIT_LIST;
        rebuildUi();
        if (subView == SubView.STEP_EDIT) {
            if (filterBox != null) {
                filterBox.setValue(draftFilterText);
            }
            if (amountBox != null) {
                amountBox.setValue(draftAmountText == null || draftAmountText.isEmpty() ? "1" : draftAmountText);
            }
            tryPopulateGhostFromExactIdFilterLine(draftFilterText);
        }
        playClickSound();
    }

    private void clearDraftFields() {
        draftKind = SequenceStepData.Kind.ITEM;
        draftConcatOrdinal = 0;
        draftFilterText = "";
        draftAmountText = "1";
        clearGhostCalibration(false);
        GuiInput.clearEditBox(filterBox);
        if (amountBox != null) {
            amountBox.setValue("1");
        }
    }

    /** Clears filter/amount/ghost in the step editor without closing. */
    private void clearStepDraftInEditor() {
        clearDraftFields();
        playClickSound();
    }

    private void applyStepDraft() {
        if (editingListIndex < 0 || filterBox == null || amountBox == null) {
            return;
        }
        String filter = FilterLineTextUtil.normalizeForCommit(filterBox.getValue());
        if (!filter.equals(filterBox.getValue() == null ? "" : filterBox.getValue())) {
            filterBox.setValue(filter);
        }
        if (filter.isEmpty()) {
            return;
        }
        if (filter.equalsIgnoreCase("&anything_else") || filter.equalsIgnoreCase("anything_else")) {
            return;
        }
        SequentialTaskData list = currentList();
        if (list == null) {
            return;
        }
        int amount = parseAmount();
        int stepIndex = editingStepIndex;
        if (stepIndex < 0 || stepIndex >= SequentialTaskData.maxSteps()) {
            return;
        }
        sendAction(
                new SequentialBufferActionPayload(
                        SequentialBufferActionPayload.ACTION_UPDATE_STEP,
                        editingListIndex,
                        stepIndex,
                        draftKind.ordinal(),
                        amount,
                        filter,
                        draftConcatOrdinal));
        editingStepIndex = -1;
        clearDraftFields();
        subView = SubView.EDIT_LIST;
        rebuildUi();
    }

    private int parseAmount() {
        if (amountBox == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(amountBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private int amountStep() {
        if (minecraft != null && minecraft.hasShiftDown()) {
            return AMOUNT_STEP_SHIFT;
        }
        if (minecraft != null && minecraft.hasControlDown() || minecraft != null && minecraft.hasAltDown()) {
            return AMOUNT_STEP_CTRL_OR_ALT;
        }
        return AMOUNT_STEP_PLAIN;
    }

    private void nudgeAmount(int delta) {
        int next = Math.max(1, parseAmount() + delta);
        if (amountBox != null) {
            amountBox.setValue(Integer.toString(next));
        }
        playClickSound();
    }

    private void convertResource() {
        ItemStack stack = resolveConvertSource();
        if (stack.isEmpty()) {
            return;
        }
        Optional<FluidStack> contained = FluidUtil.getFluidContained(stack);
        if (contained.isPresent() && !contained.get().isEmpty() && contained.get().getFluid() != Fluids.EMPTY) {
            FluidStack fluid = contained.get();
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
            if (id != null && filterBox != null) {
                applyGhostFromFluid(fluid, true);
                if (parseAmount() < 1000 && amountBox != null) {
                    amountBox.setValue("1000");
                }
                playClickSound();
            }
            return;
        }
        if (MekanismChemicalCompat.isLoaded()) {
            Object gas = MekanismChemicalCompat.sampleFromItemStack(stack);
            String gasId = MekanismChemicalCompat.getTypeRegistryName(gas);
            if (gasId != null && !gasId.isBlank() && filterBox != null) {
                applyGhostFromGas(gas, true);
                if (parseAmount() < 1000 && amountBox != null) {
                    amountBox.setValue("1000");
                }
                playClickSound();
            }
        }
    }

    private ItemStack resolveConvertSource() {
        if (!ghostStack.isEmpty()) {
            return ghostStack;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            return carried;
        }
        return resolveExactIdItemFromFilterText();
    }

    private ItemStack resolveExactIdItemFromFilterText() {
        String raw = filterBox == null || filterBox.getValue() == null ? "" : filterBox.getValue().trim();
        return resolveExactIdItem(raw);
    }

    /** Resolves only {@code -registry:id} filters; empty for {@code #}/{@code @}/{@code ?}/{@code &}. */
    private static ItemStack resolveExactIdItem(String filter) {
        if (filter == null) {
            return ItemStack.EMPTY;
        }
        String trimmed = filter.trim();
        if (!trimmed.startsWith("-")) {
            return ItemStack.EMPTY;
        }
        String idFilter = trimmed.substring(1).trim();
        if (idFilter.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            Identifier id = Identifier.parse(idFilter);
            Item item = BuiltInRegistries.ITEM.get(id).map(h -> h.value()).orElse(Items.AIR);
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static FluidStack resolveExactIdFluid(String filter) {
        if (filter == null) {
            return FluidStack.EMPTY;
        }
        String trimmed = filter.trim();
        if (!trimmed.startsWith("-")) {
            return FluidStack.EMPTY;
        }
        try {
            Identifier id = Identifier.parse(trimmed.substring(1).trim());
            Fluid fluid = BuiltInRegistries.FLUID.get(id).map(h -> h.value()).orElse(Fluids.EMPTY);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(fluid, 1000);
        } catch (Exception e) {
            return FluidStack.EMPTY;
        }
    }

    private Object resolveExactIdGas(String filter) {
        if (!MekanismChemicalCompat.isLoaded() || minecraft == null || minecraft.level == null) {
            return MekanismChemicalCompat.emptyStack();
        }
        if (filter == null) {
            return MekanismChemicalCompat.emptyStack();
        }
        String trimmed = filter.trim();
        if (!trimmed.startsWith("-")) {
            return MekanismChemicalCompat.emptyStack();
        }
        return MekanismChemicalCompat.chemicalStackFromIdForDisplay(
                trimmed.substring(1).trim(), 1024L, minecraft.level.registryAccess());
    }

    /** Display icon for filter prefixes ({@code -}/#{@code}/@{@code}/?/{@code &}), mirroring duct ghost preview. */
    private static ItemStack resolveDisplayItem(String filter) {
        return resolveDisplayItem(filter, LIST_ICON_CYCLE_MS);
    }

    private static ItemStack resolveDisplayItem(String filter, long cycleMs) {
        if (filter == null || filter.trim().isEmpty()) {
            return ItemStack.EMPTY;
        }
        filter = filter.trim();
        if (filter.startsWith("-")) {
            return resolveExactIdItem(filter);
        }
        if (filter.startsWith("#")) {
            return getItemForTag(filter.substring(1), cycleMs);
        }
        if (filter.startsWith("@")) {
            return getItemForMod(filter.substring(1), cycleMs);
        }
        if (filter.startsWith("?")) {
            return new ItemStack(Items.KNOWLEDGE_BOOK);
        }
        if (filter.startsWith("&")) {
            String macro = filter.substring(1).trim().toLowerCase();
            if (macro.equals("enchanted") || macro.startsWith("enchanted")) {
                return new ItemStack(Items.DIAMOND_PICKAXE);
            }
            if (macro.equals("damaged") || macro.startsWith("damaged")) {
                ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
                st.setDamageValue(st.getMaxDamage() / 2);
                return st;
            }
            return new ItemStack(Items.KNOWLEDGE_BOOK);
        }
        try {
            Identifier id = Identifier.parse(filter);
            Item item = BuiltInRegistries.ITEM.get(id).map(h -> h.value()).orElse(Items.AIR);
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack getItemForTag(String tagId, long cycleMs) {
        try {
            Identifier loc = Identifier.parse(tagId);
            TagKey<Item> itemTag = ItemTags.create(loc);
            java.util.ArrayList<Item> items = new java.util.ArrayList<>();
            for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(itemTag)) {
                items.add(holder.value());
            }
            if (!items.isEmpty()) {
                long period = Math.max(1L, cycleMs);
                int index = (int) ((System.currentTimeMillis() / period) % items.size());
                return new ItemStack(items.get(index));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack getItemForMod(String modId, long cycleMs) {
        List<Item> modItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && id.getNamespace().startsWith(modId)) {
                modItems.add(item);
            }
        }
        if (!modItems.isEmpty()) {
            long period = Math.max(1L, cycleMs);
            int index = (int) ((System.currentTimeMillis() / period) % modItems.size());
            return new ItemStack(modItems.get(index));
        }
        return ItemStack.EMPTY;
    }

    private static FluidStack resolveDisplayFluid(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return FluidStack.EMPTY;
        }
        String f = filter.trim();
        if (f.startsWith("?") || f.startsWith("&")) {
            return FluidStack.EMPTY;
        }
        if (f.startsWith("-")) {
            return resolveExactIdFluid(f);
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
            if (id != null && id.getNamespace().startsWith(modId) && fluid != Fluids.EMPTY) {
                return new FluidStack(fluid, 1000);
            }
        }
        return FluidStack.EMPTY;
    }

    private Object resolveDisplayGas(String filter) {
        if (!MekanismChemicalCompat.isLoaded() || minecraft == null || minecraft.level == null) {
            return MekanismChemicalCompat.emptyStack();
        }
        if (filter == null || filter.trim().isEmpty()) {
            return MekanismChemicalCompat.emptyStack();
        }
        String f = filter.trim();
        if (f.startsWith("?") || f.startsWith("&")) {
            return MekanismChemicalCompat.emptyStack();
        }
        var reg = minecraft.level.registryAccess();
        if (f.startsWith("-")) {
            return MekanismChemicalCompat.chemicalStackFromIdForDisplay(f.substring(1).trim(), 1024L, reg);
        }
        if (f.startsWith("#")) {
            return MekanismChemicalCompat.firstChemicalInTagForDisplay(f.substring(1), 1024L, reg);
        }
        if (f.startsWith("@")) {
            return MekanismChemicalCompat.firstChemicalInModForDisplay(f.substring(1), 1024L, reg);
        }
        return MekanismChemicalCompat.chemicalStackFromIdForDisplay(f, 1024L, reg);
    }

    private void clearGhostCalibration(boolean updateFilter) {
        ghostStack = ItemStack.EMPTY;
        ghostFluid = FluidStack.EMPTY;
        ghostGas = null;
        filterVariants.clear();
        filterVariantIndex = 0;
        if (updateFilter && filterBox != null) {
            filterBox.setValue("");
        }
    }

    /**
     * When reopening a filter line, fill the ghost from display resolution ({@code -}/#{@code}/@{@code}/?/{@code &})
     * by {@link #draftKind}, rebuild variants, then restore the original line text and sync the variant index when
     * present.
     */
    private void tryPopulateGhostFromExactIdFilterLine(String line) {
        clearGhostCalibration(false);
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        switch (draftKind) {
            case FLUID -> {
                FluidStack fluid = resolveDisplayFluid(trimmed);
                if (!fluid.isEmpty()) {
                    applyGhostFromFluid(fluid, false);
                    restoreFilterLineAndSyncVariant(trimmed);
                }
            }
            case GAS -> {
                Object gas = resolveDisplayGas(trimmed);
                if (gas != null && !MekanismChemicalCompat.isEmptyStack(gas)) {
                    applyGhostFromGas(gas, false);
                    restoreFilterLineAndSyncVariant(trimmed);
                }
            }
            case ITEM -> {
                ItemStack item = resolveDisplayItem(trimmed, LIST_ICON_CYCLE_MS);
                if (!item.isEmpty()) {
                    applyGhostFromItem(item, false);
                    restoreFilterLineAndSyncVariant(trimmed);
                }
            }
        }
    }

    private void restoreFilterLineAndSyncVariant(String originalLine) {
        if (filterBox == null) {
            return;
        }
        filterBox.setValue(originalLine);
        filterBox.setCursorPosition(0);
        filterBox.setHighlightPos(0);
        syncVariantIndexPreservingFilterText();
    }

    /** Match variant index to the filter box text without overwriting non-preset lines. */
    private void syncVariantIndexPreservingFilterText() {
        if (filterBox == null || filterVariants.isEmpty()) {
            filterVariantIndex = 0;
            return;
        }
        String trimmed = filterBox.getValue() == null ? "" : filterBox.getValue().trim();
        for (int i = 0; i < filterVariants.size(); i++) {
            if (filterVariants.get(i).equals(trimmed)) {
                filterVariantIndex = i;
                return;
            }
        }
        filterVariantIndex = 0;
    }

    private void handleGhostSlotClick() {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            clearGhostCalibration(true);
            draftKind = SequenceStepData.Kind.ITEM;
        } else {
            applyGhostFromItem(carried, true);
        }
        playClickSound();
    }

    private void applyGhostFromItem(ItemStack stack, boolean updateFilter) {
        ghostFluid = FluidStack.EMPTY;
        ghostGas = null;
        ghostStack = stack.copyWithCount(1);
        draftKind = SequenceStepData.Kind.ITEM;
        filterVariants.clear();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) {
            filterVariants.add("-" + itemId);
            Item item = stack.getItem();
            var holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
            List<String> itemTags = BuiltInRegistries.ITEM.getTags()
                    .filter(named -> named.contains(holder))
                    .map(named -> named.key().location().toString())
                    .sorted()
                    .toList();
            for (String tagId : itemTags) {
                filterVariants.add("#" + tagId);
            }
            filterVariants.add("@" + itemId.getNamespace());
            boolean enchantedMacro = stack.isEnchanted() || stack.is(Items.ENCHANTED_BOOK);
            if (enchantedMacro) {
                filterVariants.add("&enchanted");
            }
            if (stack.isDamageableItem()) {
                if (stack.isDamaged()) {
                    filterVariants.add("&damaged");
                }
                filterVariants.add("&damaged>0");
                if (stack.isDamaged()) {
                    filterVariants.add("&damaged=" + stack.getDamageValue());
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
                        filterVariants.add("?" + snbt);
                    }
                } catch (Exception ignored) {
                    // omit ? variant when encode fails
                }
            }
        }
        filterVariantIndex = 0;
        if (updateFilter && filterBox != null && !filterVariants.isEmpty()) {
            filterBox.setValue(filterVariants.get(0));
        }
    }

    private void applyGhostFromFluid(FluidStack fluid, boolean updateFilter) {
        ghostStack = ItemStack.EMPTY;
        ghostGas = null;
        ghostFluid = fluid.copy();
        draftKind = SequenceStepData.Kind.FLUID;
        filterVariants.clear();
        Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
        if (fluidId != null) {
            filterVariants.add("-" + fluidId);
            var holder = BuiltInRegistries.FLUID.wrapAsHolder(fluid.getFluid());
            List<String> fluidTags = BuiltInRegistries.FLUID.getTags()
                    .filter(named -> named.contains(holder))
                    .map(named -> named.key().location().toString())
                    .sorted()
                    .toList();
            for (String tagId : fluidTags) {
                filterVariants.add("#" + tagId);
            }
            filterVariants.add("@" + fluidId.getNamespace());
        }
        filterVariantIndex = 0;
        if (updateFilter && filterBox != null && !filterVariants.isEmpty()) {
            filterBox.setValue(filterVariants.get(0));
        }
    }

    private void applyGhostFromGas(Object sample, boolean updateFilter) {
        ghostStack = ItemStack.EMPTY;
        ghostFluid = FluidStack.EMPTY;
        ghostGas = sample;
        draftKind = SequenceStepData.Kind.GAS;
        filterVariants.clear();
        String gasId = MekanismChemicalCompat.getTypeRegistryName(sample);
        if (gasId != null && !gasId.isBlank()) {
            filterVariants.add("-" + gasId);
            try {
                Identifier id = Identifier.parse(gasId);
                filterVariants.add("@" + id.getNamespace());
            } catch (Exception ignored) {
                // keep -id only
            }
        }
        filterVariantIndex = 0;
        if (updateFilter && filterBox != null && !filterVariants.isEmpty()) {
            filterBox.setValue(filterVariants.get(0));
        }
    }

    private void handleGhostIngredientDrop(Object ingredient) {
        if (ingredient == null) {
            clearGhostCalibration(true);
            draftKind = SequenceStepData.Kind.ITEM;
            playClickSound();
            return;
        }
        if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            applyGhostFromFluid(fluidStack, true);
            if (parseAmount() < 1000 && amountBox != null) {
                amountBox.setValue("1000");
            }
            playClickSound();
            return;
        }
        if (ingredient instanceof ItemStack itemStack && !itemStack.isEmpty()) {
            applyGhostFromItem(itemStack, true);
            playClickSound();
            return;
        }
        if (MekanismChemicalCompat.isLoaded() && !MekanismChemicalCompat.isEmptyStack(ingredient)) {
            applyGhostFromGas(ingredient, true);
            if (parseAmount() < 1000 && amountBox != null) {
                amountBox.setValue("1000");
            }
            playClickSound();
        }
    }

    private void cycleFilterVariant(int direction) {
        if (filterVariants.isEmpty()) {
            return;
        }
        filterVariantIndex = Math.floorMod(filterVariantIndex + direction, filterVariants.size());
        if (filterBox != null) {
            filterBox.setValue(filterVariants.get(filterVariantIndex));
        }
        playClickSound();
    }

    private void sendAction(SequentialBufferActionPayload payload) {
        ModNetwork.sendSequentialBufferAction(payload);
        playClickSound();
    }

    private void sendActionSilent(SequentialBufferActionPayload payload) {
        ModNetwork.sendSequentialBufferAction(payload);
    }

    private void initListTitleEditBox() {
        SequentialTaskData list = currentList();
        String current = list == null ? "" : list.customName();
        int boxW = 180;
        int boxH = 12;
        int x = leftPos + (TEXTURE_WIDTH - boxW) / 2;
        int y = topPos + 5;
        listNameBox =
                new EditBox(
                        font,
                        x,
                        y,
                        boxW,
                        boxH,
                        Component.translatable("gui.another_dynamics.sequential_buffer.list_name"));
        listNameBox.setMaxLength(SequentialTaskData.MAX_NAME_LENGTH);
        listNameBox.setBordered(true);
        listNameBox.setTextColor(0xFFFFFFFF);
        listNameBox.setHint(
                Component.translatable(
                                "gui.another_dynamics.sequential_buffer.sequence_list", editingListIndex + 1)
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
        listNameBox.setValue(current);
        listNameBox.setResponder(this::onListNameDraftChanged);
        host.addSequentialWidget(listNameBox);
    }

    private void onListNameDraftChanged(String value) {
        if (editingListIndex < 0 || editingListIndex >= SequentialBufferBlockEntity.sequentialTaskCount()) {
            return;
        }
        SequentialTaskData list = listAt(editingListIndex);
        String applied = value == null ? "" : value;
        String normalized = applied.isBlank() ? "" : applied.trim();
        if (normalized.length() > SequentialTaskData.MAX_NAME_LENGTH) {
            normalized = normalized.substring(0, SequentialTaskData.MAX_NAME_LENGTH);
        }
        if (normalized.equals(list.customName())) {
            return;
        }
        sendActionSilent(
                new SequentialBufferActionPayload(
                        SequentialBufferActionPayload.ACTION_SET_LIST_NAME,
                        editingListIndex,
                        0,
                        0,
                        1,
                        applied,
                        0));
        list.setCustomName(applied);
    }

    private void syncListNameBoxFromBeIfUnfocused() {
        if (listNameBox == null || listNameBox.isFocused()) {
            return;
        }
        SequentialTaskData list = currentList();
        if (list == null) {
            return;
        }
        String name = list.customName();
        if (!name.equals(listNameBox.getValue())) {
            listNameBox.setResponder(s -> {});
            listNameBox.setValue(name);
            listNameBox.setResponder(this::onListNameDraftChanged);
        }
    }

    private void playClickSound() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private Component strictIntakeButtonMessage() {
        boolean on = strictSequentialIntake();
        return Component.translatable(
                on
                        ? "gui.another_dynamics.sequential_buffer.strict_intake.on"
                        : "gui.another_dynamics.sequential_buffer.strict_intake.off");
    }

    private void refreshStrictIntakeButton() {
        if (strictIntakeButton == null || subView != SubView.HUB) {
            return;
        }
        strictIntakeButton.setMessage(strictIntakeButtonMessage());
    }

    private void refreshHubRowState() {
        if (subView != SubView.SEQUENTIAL_TASKS || dynamicButtons.size() < VISIBLE_ROWS * 4) {
            return;
        }
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = hubScroll + i;
            boolean inRange = idx >= 0 && idx < SequentialBufferBlockEntity.sequentialTaskCount();
            SequentialTaskData list = inRange ? listAt(idx) : null;
            boolean hasContent = list != null && list.hasContent();
            int base = i * 4;
            Button output = dynamicButtons.get(base);
            Button enable = dynamicButtons.get(base + 1);
            Button clear = dynamicButtons.get(base + 2);
            Button edit = dynamicButtons.get(base + 3);
            output.visible = clear.visible = enable.visible = edit.visible = inRange;
            if (!inRange) {
                continue;
            }
            enable.active = hasContent;
            boolean on = list.isEnabled();
            enable.setMessage(
                    Component.literal(on ? "E" : "D")
                            .withStyle(Style.EMPTY.withBold(true).withColor(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
            enable.setTooltip(enableToggleTooltip(on, hasContent));
            if (output instanceof ItemIconButton icon) {
                icon.setTooltip(listOutputTooltip(list.outputMode()));
            }
        }
        updateScrollButtonVisibility(0);
    }

    private static Tooltip enableToggleTooltip(boolean enabled, boolean hasContent) {
        Component primary =
                Component.translatable(
                        enabled
                                ? "gui.another_dynamics.sequential_buffer.enabled"
                                : "gui.another_dynamics.sequential_buffer.disabled");
        if (hasContent) {
            return Tooltip.create(primary);
        }
        return Tooltip.create(
                primary
                        .copy()
                        .append("\n")
                        .append(
                                Component.translatable(
                                                "gui.another_dynamics.sequential_buffer.enable_needs_tasks")
                                        .withStyle(ChatFormatting.GRAY)));
    }

    /** Right-side controls: redstone + E/D + clear + edit (see {@code initSequentialTasks}). */
    private static final int HUB_ROW_BUTTON_ZONE_W =
            BUTTON_MARGIN + REDSTONE_SIZE + 3 * ROW_BTN + 3 * ADJACENT_BTN_GAP;

    private @Nullable Component inactiveSequenceListHoverTooltip(int mouseX, int mouseY) {
        if (subView != SubView.SEQUENTIAL_TASKS) {
            return null;
        }
        for (Button b : dynamicButtons) {
            if (b.visible && b.isMouseOver(mouseX, mouseY)) {
                return null;
            }
        }
        int labelW = ENTRY_WIDTH - HUB_ROW_BUTTON_ZONE_W;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = hubScroll + i;
            if (idx < 0 || idx >= SequentialBufferBlockEntity.sequentialTaskCount()) {
                break;
            }
            SequentialTaskData list = listAt(idx);
            if (list.isEnabled()) {
                continue;
            }
            int x = leftPos + ENTRY_X;
            int y = topPos + FIRST_Y + i * ENTRY_HEIGHT;
            // Label/icon only — never over the action-button strip (avoids stacked tooltips).
            if (mouseX >= x && mouseX < x + labelW && mouseY >= y && mouseY < y + ENTRY_HEIGHT) {
                return Component.translatable(
                        "gui.another_dynamics.sequential_buffer.inactive_click_d");
            }
        }
        return null;
    }

    private void refreshEditListRowState() {
        if (subView != SubView.EDIT_LIST) {
            return;
        }
        SequentialTaskData list = currentList();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = stepScroll + i;
            boolean inRange = idx >= 0 && idx < SequentialTaskData.maxSteps();
            if (i < concatButtons.size()) {
                FilterConcatChannelButton cb = concatButtons.get(i);
                cb.visible = inRange;
                cb.active = inRange;
                if (inRange && list != null) {
                    cb.setChannelOrdinal(list.concatAt(idx));
                }
            }
            if (dynamicButtons.size() >= (i + 1) * 2) {
                // Clear + Edit always visible for fixed slots.
                dynamicButtons.get(i * 2).visible = inRange;
                dynamicButtons.get(i * 2 + 1).visible = inRange;
            }
        }
        updateScrollButtonVisibility(1);
    }

    private void refreshGateTooltip() {
        if (gateButton != null) {
            gateButton.setTooltip(gateTooltip());
        }
    }

    private String gateTooltipKey() {
        return "gui.another_dynamics.sequential_buffer.gate." + gate().name().toLowerCase();
    }

    private Tooltip gateTooltip() {
        String key = gateTooltipKey();
        return Tooltip.create(
                Component.translatable(key)
                        .append("\n")
                        .append(Component.translatable(key + ".desc").withStyle(ChatFormatting.GRAY)));
    }

    private String listOutputTooltipKey(SequentialRedstoneMode mode) {
        return "gui.another_dynamics.sequential_buffer.list_output." + mode.name().toLowerCase();
    }

    private Tooltip listOutputTooltip(SequentialRedstoneMode mode) {
        String key = listOutputTooltipKey(mode);
        return Tooltip.create(
                Component.translatable(key)
                        .append("\n")
                        .append(Component.translatable(key + ".desc").withStyle(ChatFormatting.GRAY)));
    }

    private ItemStack gateIconStack() {
        return switch (gate()) {
            case IGNORED -> new ItemStack(Items.GUNPOWDER);
            case LOW -> new ItemStack(Items.REDSTONE);
            case HIGH -> ItemStack.EMPTY;
            case DISABLED -> new ItemStack(Items.BARRIER);
            case AUTO -> new ItemStack(Items.COMPARATOR);
        };
    }

    private ItemStack listOutputIcon(int index) {
        if (index < 0 || index >= SequentialBufferBlockEntity.sequentialTaskCount()) {
            return ItemStack.EMPTY;
        }
        return switch (listAt(index).outputMode()) {
            case DISABLED -> new ItemStack(Items.BARRIER);
            case LOW -> new ItemStack(Items.GUNPOWDER);
            case HIGH -> new ItemStack(Items.REDSTONE);
            case PULSE -> ItemStack.EMPTY;
        };
    }

    private Identifier listOutputOverlay(int index) {
        if (index < 0 || index >= SequentialBufferBlockEntity.sequentialTaskCount()) {
            return null;
        }
        return listAt(index).outputMode() == SequentialRedstoneMode.PULSE ? REDSTONE_GUI : null;
    }

    private int maxHubScroll() {
        return Math.max(0, SequentialBufferBlockEntity.sequentialTaskCount() - VISIBLE_ROWS);
    }

    private int maxStepScroll() {
        return Math.max(0, displayStepCount() - VISIBLE_ROWS);
    }

    private int helpLineStep() {
        return font.lineHeight + 4;
    }

    private int maxHelpScroll() {
        int visible = Math.max(1, HELP_CLIP_BOTTOM - HELP_CONTENT_TOP);
        return Math.max(0, helpContentHeight - visible);
    }

    private void scrollBy(int kind, int delta) {
        if (kind == 0) {
            hubScroll = Mth.clamp(hubScroll + delta, 0, maxHubScroll());
            refreshHubRowState();
        } else if (kind == 1) {
            stepScroll = Mth.clamp(stepScroll + delta, 0, maxStepScroll());
            refreshEditListRowState();
        } else {
            helpScroll = Mth.clamp(helpScroll + delta * helpLineStep(), 0, maxHelpScroll());
            updateScrollButtonVisibility(2);
        }
        playClickSound();
    }

    private void updateScrollButtonVisibility(int kind) {
        int max =
                kind == 0 ? maxHubScroll() : kind == 1 ? maxStepScroll() : maxHelpScroll();
        boolean show = max > 0;
        int value = kind == 0 ? hubScroll : kind == 1 ? stepScroll : helpScroll;
        if (scrollUpButton != null) {
            scrollUpButton.visible = show;
            scrollUpButton.active = value > 0;
        }
        if (scrollDownButton != null) {
            scrollDownButton.visible = show;
            scrollDownButton.active = value < max;
        }
    }

    public void extractBackground(GuiGraphicsExtractor graphics, float partialTick, int mouseX, int mouseY) {
        Identifier bg = subView == SubView.VALID_KEYS ? VALID_KEYS_TEXTURE : TEXTURE;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                bg,
                leftPos,
                topPos,
                0.0F,
                0.0F,
                imageWidth,
                imageHeight,
                TEXTURE_WIDTH,
                TEXTURE_HEIGHT);

        if (subView == SubView.SEQUENTIAL_TASKS || subView == SubView.EDIT_LIST) {
            renderEntryRows(graphics);
            renderListScrollbar(graphics, subView == SubView.SEQUENTIAL_TASKS ? 0 : 1);
        } else if (subView == SubView.STEP_EDIT) {
            renderGhostSlot(graphics);
        } else if (subView == SubView.VALID_KEYS) {
            renderListScrollbar(graphics, 2);
        }
    }

    private void renderEntryRows(GuiGraphicsExtractor graphics) {
        int count =
                subView == SubView.SEQUENTIAL_TASKS
                        ? SequentialBufferBlockEntity.sequentialTaskCount()
                        : displayStepCount();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = (subView == SubView.SEQUENTIAL_TASKS ? hubScroll : stepScroll) + i;
            if (idx < 0 || idx >= count) {
                continue;
            }
            int x = leftPos + ENTRY_X;
            int y = topPos + FIRST_Y + i * ENTRY_HEIGHT;
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    ENTRY_ROW_TEXTURE,
                    x,
                    y,
                    0.0F,
                    0.0F,
                    ENTRY_WIDTH,
                    ENTRY_HEIGHT,
                    ENTRY_WIDTH,
                    ENTRY_HEIGHT);

            boolean inactive =
                    subView == SubView.SEQUENTIAL_TASKS && !listAt(idx).isEnabled();
            if (inactive) {
                graphics.fill(x, y, x + ENTRY_WIDTH, y + ENTRY_HEIGHT, 0x99000000);
            }

            // Match duct filter rows: icon slot inset 3px from entry top-left.
            int slotX = x + 3;
            int slotY = y + 3;
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    SINGLE_SLOT,
                    slotX,
                    slotY,
                    0.0F,
                    0.0F,
                    18,
                    18,
                    18,
                    18);
            if (subView == SubView.SEQUENTIAL_TASKS) {
                renderHubPreviewIcon(graphics, slotX, slotY, listAt(idx));
            } else {
                SequentialTaskData list = currentList();
                SequenceStepData step = null;
                if (list != null && idx < list.steps().size()) {
                    step = list.steps().get(idx);
                }
                renderStepPreviewIcon(graphics, slotX, slotY, step);
            }
        }
    }

    /** EDIT_LIST: preview via display resolution ({@code -}/#{@code}/@{@code}/?/{@code &}). */
    private void renderStepPreviewIcon(
            GuiGraphicsExtractor graphics, int slotX, int slotY, @Nullable SequenceStepData step) {
        if (step == null || step.isEmpty()) {
            return;
        }
        String filter = step.filter() == null ? "" : step.filter().trim();
        if (filter.isEmpty()) {
            return;
        }
        blitResolvedStepIcon(graphics, slotX, slotY, step.kind(), filter, LIST_ICON_CYCLE_MS);
    }

    /**
     * HUB: cycle through non-empty steps that resolve a display preview, timed like duct tag icons.
     */
    private void renderHubPreviewIcon(
            GuiGraphicsExtractor graphics, int slotX, int slotY, SequentialTaskData list) {
        if (list == null) {
            return;
        }
        List<SequenceStepData> previewable = new ArrayList<>();
        for (SequenceStepData step : list.steps()) {
            if (step == null || step.isEmpty()) {
                continue;
            }
            String filter = step.filter() == null ? "" : step.filter().trim();
            if (filter.isEmpty()) {
                continue;
            }
            if (hasResolvableDisplayPreview(step.kind(), filter)) {
                previewable.add(step);
            }
        }
        if (previewable.isEmpty()) {
            return;
        }
        int index = (int) ((System.currentTimeMillis() / HUB_ICON_CYCLE_MS) % previewable.size());
        SequenceStepData step = previewable.get(index);
        blitResolvedStepIcon(graphics, slotX, slotY, step.kind(), step.filter().trim(), HUB_ICON_CYCLE_MS);
    }

    private boolean hasResolvableDisplayPreview(SequenceStepData.Kind kind, String filter) {
        return switch (kind) {
            case FLUID -> !resolveDisplayFluid(filter).isEmpty();
            case GAS -> {
                Object gas = resolveDisplayGas(filter);
                yield gas != null && !MekanismChemicalCompat.isEmptyStack(gas);
            }
            case ITEM -> !resolveDisplayItem(filter, LIST_ICON_CYCLE_MS).isEmpty();
        };
    }

    private void blitResolvedStepIcon(
            GuiGraphicsExtractor graphics,
            int slotX,
            int slotY,
            SequenceStepData.Kind kind,
            String filter,
            long cycleMs) {
        switch (kind) {
            case FLUID -> {
                FluidStack fluid = resolveDisplayFluid(filter);
                if (!fluid.isEmpty()) {
                    GuiFluidStillBlit.blit16(graphics, fluid, slotX + 1, slotY + 1);
                    return;
                }
                ItemStack fallback = resolveDisplayItem(filter, cycleMs);
                if (!fallback.isEmpty()) {
                    graphics.item(fallback, slotX + 1, slotY + 1);
                    graphics.itemDecorations(this.font, fallback, slotX + 1, slotY + 1);
                }
            }
            case GAS -> {
                Object gas = resolveDisplayGas(filter);
                if (gas != null && !MekanismChemicalCompat.isEmptyStack(gas)) {
                    GuiChemicalStillBlit.blit16(graphics, gas, slotX + 1, slotY + 1);
                    return;
                }
                ItemStack fallback = resolveDisplayItem(filter, cycleMs);
                if (!fallback.isEmpty()) {
                    graphics.item(fallback, slotX + 1, slotY + 1);
                    graphics.itemDecorations(this.font, fallback, slotX + 1, slotY + 1);
                }
            }
            case ITEM -> {
                ItemStack item = resolveDisplayItem(filter, cycleMs);
                if (!item.isEmpty()) {
                    graphics.item(item, slotX + 1, slotY + 1);
                    graphics.itemDecorations(this.font, item, slotX + 1, slotY + 1);
                }
            }
        }
    }

    private void renderGhostSlot(GuiGraphicsExtractor graphics) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                SINGLE_SLOT,
                ghostSlotX,
                ghostSlotY,
                0.0F,
                0.0F,
                18,
                18,
                18,
                18);
        if (ghostGas != null && !MekanismChemicalCompat.isEmptyStack(ghostGas)) {
            GuiChemicalStillBlit.blit16(graphics, ghostGas, ghostSlotX + 1, ghostSlotY + 1);
        } else if (!ghostFluid.isEmpty()) {
            GuiFluidStillBlit.blit16(graphics, ghostFluid, ghostSlotX + 1, ghostSlotY + 1);
        } else if (!ghostStack.isEmpty()) {
            graphics.item(ghostStack, ghostSlotX + 1, ghostSlotY + 1);
            graphics.itemDecorations(this.font, ghostStack, ghostSlotX + 1, ghostSlotY + 1);
        }
    }

    private void renderListScrollbar(GuiGraphicsExtractor graphics, int kind) {
        int max = kind == 0 ? maxHubScroll() : kind == 1 ? maxStepScroll() : maxHelpScroll();
        if (max <= 0) {
            return;
        }
        int scroll = kind == 0 ? hubScroll : kind == 1 ? stepScroll : helpScroll;
        int x = leftPos + SCROLLBAR_X_REL;
        int trackY;
        int trackH;
        if (kind == 2) {
            trackY = topPos + HELP_CONTENT_TOP + SCROLL_ARROW + SCROLL_ARROW_TRACK_GAP;
            int trackBottom = topPos + HELP_CLIP_BOTTOM - 4 - SCROLL_ARROW - SCROLL_ARROW_TRACK_GAP;
            trackH = Math.max(SCROLLER_HEIGHT, trackBottom - trackY);
        } else {
            trackY = topPos + SCROLLBAR_Y_REL;
            trackH = SCROLLBAR_HEIGHT;
        }
        graphics.fill(x, trackY, x + SCROLLER_WIDTH, trackY + trackH, SCROLL_TRACK_COLOR);
        int handleRange = Math.max(0, trackH - SCROLLER_HEIGHT);
        int handleY = trackY + (max <= 0 ? 0 : (int) ((double) scroll / max * handleRange));
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                SCROLLER_TEXTURE,
                x,
                handleY,
                0.0F,
                0.0F,
                SCROLLER_WIDTH,
                SCROLLER_HEIGHT,
                SCROLLER_WIDTH,
                SCROLLER_HEIGHT);
    }

    public void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (subView == SubView.SEQUENTIAL_TASKS || subView == SubView.VALID_KEYS) {
            Component title =
                    switch (subView) {
                        case SEQUENTIAL_TASKS -> Component.translatable(
                                "gui.another_dynamics.sequential_buffer.list_of_sequences");
                        case VALID_KEYS -> Component.translatable(
                                "gui.another_dynamics.sequential_buffer.valid_keys");
                        default -> Component.empty();
                    };
            int titleX = (imageWidth - font.width(title)) / 2;
            graphics.text(font, title, titleX, 7, 0xFF404040, false);
        }

        if (subView == SubView.SEQUENTIAL_TASKS) {
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int idx = hubScroll + i;
                if (idx >= SequentialBufferBlockEntity.sequentialTaskCount()) {
                    break;
                }
                SequentialTaskData list = listAt(idx);
                Component label = list.displayName(idx + 1);
                boolean inactive = !list.isEnabled();
                int textX = ENTRY_X + 3 + 18 + 6;
                int maxW = ENTRY_WIDTH - (3 + 18 + 6) - 62;
                String text = label.getString();
                if (font.width(text) > maxW) {
                    text = font.plainSubstrByWidth(text, maxW - font.width("...")) + "...";
                }
                if (inactive) {
                    int nameY = FIRST_Y + i * ENTRY_HEIGHT + 3;
                    graphics.text(font, text, textX, nameY, 0xFFFFFFFF, false);
                    Component warn =
                            Component.translatable(
                                    "gui.another_dynamics.sequential_buffer.inactive_warning");
                    String warnText = warn.getString();
                    if (font.width(warnText) > maxW) {
                        warnText =
                                font.plainSubstrByWidth(warnText, maxW - font.width("...")) + "...";
                    }
                    int warnY = nameY + font.lineHeight + 1;
                    graphics.text(font, warnText, textX, warnY, 0xFFFF5555, false);
                } else {
                    int y = FIRST_Y + i * ENTRY_HEIGHT + (ENTRY_HEIGHT - font.lineHeight) / 2;
                    graphics.text(font, text, textX, y, 0xFF404040, false);
                }
            }
        } else if (subView == SubView.EDIT_LIST) {
            SequentialTaskData list = currentList();
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int idx = stepScroll + i;
                if (idx < 0 || idx >= SequentialTaskData.maxSteps()) {
                    continue;
                }
                if (list == null || idx >= list.steps().size()) {
                    continue;
                }
                SequenceStepData step = list.steps().get(idx);
                if (step == null || step.isEmpty()) {
                    continue;
                }
                String raw = step.filter() == null ? "" : step.filter();
                int textX = ENTRY_X + 3 + 18 + 6;
                int maxW = ENTRY_WIDTH - (3 + 18 + 6) - 48;
                String text = raw;
                if (font.width(text) > maxW) {
                    text = font.plainSubstrByWidth(text, maxW - font.width("...")) + "...";
                }
                float qtyScale = 0.75f;
                int qtyGap = 2;
                int qtyH = Math.round(font.lineHeight * qtyScale);
                int blockH = font.lineHeight + qtyGap + qtyH;
                int blockTop = FIRST_Y + i * ENTRY_HEIGHT + (ENTRY_HEIGHT - blockH) / 2;
                graphics.text(font, text, textX, blockTop, 0xFF404040, false);
                String qty =
                        Component.translatable(
                                        "gui.another_dynamics.sequential_buffer.amount_line",
                                        step.amount())
                                .getString();
                int qtyY = blockTop + font.lineHeight + qtyGap;
                graphics.pose().pushMatrix();
                graphics.pose().scale(qtyScale, qtyScale);
                graphics.text(
                        font,
                        qty,
                        Math.round(textX / qtyScale),
                        Math.round(qtyY / qtyScale),
                        0xFF55FFFF,
                        false);
                graphics.pose().popMatrix();
            }
        } else if (subView == SubView.STEP_EDIT) {
            Component amountLabel = Component.translatable("gui.another_dynamics.sequential_buffer.amount");
            int lw = font.width(amountLabel);
            int labelX = amountEditBoxGuiLeft + (AMOUNT_EDIT_W - lw) / 2;
            int labelY = NUMERIC_ROW_Y - font.lineHeight - AMOUNT_LABEL_ABOVE_GAP;
            graphics.text(font, amountLabel, labelX, labelY, 0xFF404040, false);
        } else if (subView == SubView.VALID_KEYS) {
            renderValidKeysBody(graphics, mouseX, mouseY);
        }
    }

    private void renderValidKeysBody(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        exampleDataList.clear();
        int lineStep = helpLineStep();
        int helpY = HELP_CONTENT_TOP - helpScroll;

        graphics.enableScissor(leftPos + 4, topPos + HELP_CONTENT_TOP, leftPos + imageWidth - 4, topPos + HELP_CLIP_BOTTOM);

        helpY = renderFilterKeySection(
                graphics,
                Component.translatable("gui.another_dynamics.duct_node.transport.item"),
                "gui.another_dynamics.general_filter_text.",
                true,
                true,
                HELP_TEXT_X,
                helpY,
                lineStep,
                mouseX,
                mouseY);
        helpY += lineStep / 2;
        helpY = renderFilterKeySection(
                graphics,
                Component.translatable("gui.another_dynamics.duct_node.transport.fluid"),
                "gui.another_dynamics.fluid_filter_text.",
                true,
                true,
                HELP_TEXT_X,
                helpY,
                lineStep,
                mouseX,
                mouseY);
        helpY += lineStep / 2;
        // Gas: reuse gas_filter_text for id/modid; tag/nbt have no lang keys — literal examples.
        String gp = "gui.another_dynamics.gas_filter_text.";
        graphics.text(
                font,
                Component.translatable("gui.another_dynamics.duct_node.transport.gas"),
                HELP_TEXT_X,
                helpY,
                0xFF404040,
                false);
        helpY += lineStep;
        helpY =
                renderHelpLineWithExample(
                                graphics, gp + "id", gp + "id.example", gp + "id.after", HELP_TEXT_X, helpY, mouseX, mouseY)
                        + lineStep;
        helpY =
                renderHelpLineParts(
                                graphics,
                                "# Filters gas by tag (e.g., ",
                                "#mekanism:waste",
                                ")",
                                HELP_TEXT_X,
                                helpY,
                                mouseX,
                                mouseY)
                        + lineStep;
        helpY =
                renderHelpLineWithExample(
                                graphics,
                                gp + "modid",
                                gp + "modid.example",
                                gp + "modid.after",
                                HELP_TEXT_X,
                                helpY,
                                mouseX,
                                mouseY)
                        + lineStep;
        graphics.text(font, Component.literal("? Filters gas by NBT"), HELP_TEXT_X, helpY, 0xFF404040, false);
        helpY += lineStep;
        helpY =
                renderHelpLineParts(
                                graphics,
                                "  (e.g., ",
                                "?\"mekanism:gas_data\":{}",
                                ")",
                                HELP_TEXT_X,
                                helpY,
                                mouseX,
                                mouseY)
                        + lineStep;

        graphics.disableScissor();

        helpContentHeight = Math.max(0, helpY + helpScroll - HELP_CONTENT_TOP);
        updateScrollButtonVisibility(2);
    }

    private int renderFilterKeySection(
            GuiGraphicsExtractor graphics,
            Component header,
            String prefix,
            boolean includeTag,
            boolean includeNbt,
            int x,
            int y,
            int lineStep,
            int mouseX,
            int mouseY) {
        graphics.text(font, header, x, y, 0xFF404040, false);
        y += lineStep;
        y = renderHelpLineWithExample(
                        graphics, prefix + "id", prefix + "id.example", prefix + "id.after", x, y, mouseX, mouseY)
                + lineStep;
        if (includeTag) {
            y = renderHelpLineWithExample(
                            graphics, prefix + "tag", prefix + "tag.example", prefix + "tag.after", x, y, mouseX, mouseY)
                    + lineStep;
        }
        y = renderHelpLineWithExample(
                        graphics,
                        prefix + "modid",
                        prefix + "modid.example",
                        prefix + "modid.after",
                        x,
                        y,
                        mouseX,
                        mouseY)
                + lineStep;
        if (includeNbt) {
            graphics.text(font, Component.translatable(prefix + "nbt"), x, y, 0xFF404040, false);
            y += lineStep;
            y = renderHelpLineWithExample(
                            graphics,
                            prefix + "nbt.example",
                            prefix + "nbt.example.text",
                            prefix + "nbt.after",
                            x,
                            y,
                            mouseX,
                            mouseY)
                    + lineStep;
        }
        return y;
    }

    private int renderHelpLineWithExample(
            GuiGraphicsExtractor guiGraphics,
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
        return renderHelpLineParts(
                guiGraphics,
                beforeComponent.getString(),
                exampleComponent.getString(),
                afterComponent.getString(),
                x,
                y,
                mouseX,
                mouseY);
    }

    private int renderHelpLineParts(
            GuiGraphicsExtractor guiGraphics,
            String beforeText,
            String exampleText,
            String afterText,
            int x,
            int y,
            int mouseX,
            int mouseY) {
        int beforeWidth = font.width(beforeText);
        guiGraphics.text(font, beforeText, x, y, 0xFF404040, false);
        int exampleX = x + beforeWidth;
        int exampleWidth = font.width(exampleText);
        int sx = leftPos + exampleX;
        int sy = topPos + y;
        boolean hovered =
                mouseX >= sx && mouseX <= sx + exampleWidth && mouseY >= sy && mouseY <= sy + font.lineHeight;
        int exampleColor = hovered ? 0xFF0066FF : 0xFF0066CC;
        guiGraphics.text(font, exampleText, exampleX, y, exampleColor, false);
        exampleDataList.add(new ExampleData(exampleText, exampleX, y, exampleWidth));
        if (afterText != null && !afterText.isEmpty()) {
            guiGraphics.text(font, afterText, exampleX + exampleWidth, y, 0xFF404040, false);
        }
        return y;
    }

    private void renderExampleTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        for (ExampleData exampleData : exampleDataList) {
            int screenX = leftPos + exampleData.x;
            int screenY = topPos + exampleData.y;
            if (mouseX >= screenX
                    && mouseX <= screenX + exampleData.width
                    && mouseY >= screenY
                    && mouseY <= screenY + font.lineHeight) {
                List<Component> tooltip = new ArrayList<>(2);
                tooltip.add(Component.translatable("gui.another_dynamics.general_filter_text.click_to_copy"));
                tooltip.add(Component.translatable("gui.another_dynamics.general_filter_text.paste_hint"));
                guiGraphics.setTooltipForNextFrame(
                        font, tooltip, java.util.Optional.empty(), ItemStack.EMPTY, mouseX, mouseY, null);
                return;
            }
        }
    }

    /** Called after host widgets render; draws titles / help / tooltips. */
    public void extractOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(this.leftPos, this.topPos);
        extractLabels(graphics, mouseX, mouseY);
        graphics.pose().popMatrix();
        if (subView == SubView.VALID_KEYS) {
            renderExampleTooltip(graphics, mouseX, mouseY);
        }
        Component inactiveTip = inactiveSequenceListHoverTooltip(mouseX, mouseY);
        if (inactiveTip != null) {
            graphics.setTooltipForNextFrame(font, inactiveTip, mouseX, mouseY);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (GuiInput.clearEditBoxOnRightClick(mouseX, mouseY, button, filterBox, amountBox, listNameBox)) {
            playClickSound();
            return true;
        }
        if (subView == SubView.VALID_KEYS && button == 0) {
            for (ExampleData exampleData : exampleDataList) {
                int sx = leftPos + exampleData.x;
                int sy = topPos + exampleData.y;
                if (mouseX >= sx
                        && mouseX <= sx + exampleData.width
                        && mouseY >= sy
                        && mouseY <= sy + font.lineHeight
                        && sy >= topPos + HELP_CONTENT_TOP
                        && sy < topPos + HELP_CLIP_BOTTOM) {
                    if (minecraft != null && minecraft.keyboardHandler != null) {
                        minecraft.keyboardHandler.setClipboard(exampleData.example);
                        playClickSound();
                    }
                    return true;
                }
            }
            if (tryBeginScrollDrag(mouseX, mouseY)) {
                return true;
            }
            return false;
        }
        if (subView == SubView.STEP_EDIT
                && button == 0
                && mouseX >= ghostSlotX
                && mouseX < ghostSlotX + 18
                && mouseY >= ghostSlotY
                && mouseY < ghostSlotY + 18) {
            handleGhostSlotClick();
            return true;
        }
        return button == 0 && tryBeginScrollDrag(mouseX, mouseY);
    }

    private boolean tryBeginScrollDrag(double mouseX, double mouseY) {
        if (subView == SubView.SEQUENTIAL_TASKS && maxHubScroll() > 0 && hitScrollTrack(mouseX, mouseY, 0)) {
            draggingScroll = true;
            dragScrollKind = 0;
            applyScrollFromMouse(mouseY, 0);
            return true;
        }
        if (subView == SubView.EDIT_LIST && maxStepScroll() > 0 && hitScrollTrack(mouseX, mouseY, 1)) {
            draggingScroll = true;
            dragScrollKind = 1;
            applyScrollFromMouse(mouseY, 1);
            return true;
        }
        if (subView == SubView.VALID_KEYS && maxHelpScroll() > 0 && hitScrollTrack(mouseX, mouseY, 2)) {
            draggingScroll = true;
            dragScrollKind = 2;
            applyScrollFromMouse(mouseY, 2);
            return true;
        }
        return false;
    }

    private boolean hitScrollTrack(double mouseX, double mouseY, int kind) {
        int x = leftPos + SCROLLBAR_X_REL;
        int trackY;
        int trackH;
        if (kind == 2) {
            trackY = topPos + HELP_CONTENT_TOP + SCROLL_ARROW + SCROLL_ARROW_TRACK_GAP;
            int trackBottom = topPos + HELP_CLIP_BOTTOM - 4 - SCROLL_ARROW - SCROLL_ARROW_TRACK_GAP;
            trackH = Math.max(SCROLLER_HEIGHT, trackBottom - trackY);
        } else {
            trackY = topPos + SCROLLBAR_Y_REL;
            trackH = SCROLLBAR_HEIGHT;
        }
        return mouseX >= x
                && mouseX < x + SCROLLER_WIDTH
                && mouseY >= trackY
                && mouseY < trackY + trackH;
    }

    private void applyScrollFromMouse(double mouseY, int kind) {
        int trackY;
        int trackH;
        if (kind == 2) {
            trackY = topPos + HELP_CONTENT_TOP + SCROLL_ARROW + SCROLL_ARROW_TRACK_GAP;
            int trackBottom = topPos + HELP_CLIP_BOTTOM - 4 - SCROLL_ARROW - SCROLL_ARROW_TRACK_GAP;
            trackH = Math.max(SCROLLER_HEIGHT, trackBottom - trackY);
        } else {
            trackY = topPos + SCROLLBAR_Y_REL;
            trackH = SCROLLBAR_HEIGHT;
        }
        float ratio = Mth.clamp((float) (mouseY - trackY) / Math.max(1, trackH), 0f, 1f);
        int max = kind == 0 ? maxHubScroll() : kind == 1 ? maxStepScroll() : maxHelpScroll();
        int value = Mth.clamp(Math.round(ratio * max), 0, max);
        if (kind == 0) {
            hubScroll = value;
            refreshHubRowState();
        } else if (kind == 1) {
            stepScroll = value;
            refreshEditListRowState();
        } else {
            helpScroll = value;
            updateScrollButtonVisibility(2);
        }
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            applyScrollFromMouse(mouseY, dragScrollKind);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (subView == SubView.VALID_KEYS && maxHelpScroll() > 0) {
            helpScroll =
                    Mth.clamp(helpScroll + (deltaY < 0 ? helpLineStep() : -helpLineStep()), 0, maxHelpScroll());
            updateScrollButtonVisibility(2);
            return true;
        }
        if (subView == SubView.SEQUENTIAL_TASKS && maxHubScroll() > 0) {
            hubScroll = Mth.clamp(hubScroll + (deltaY < 0 ? 1 : -1), 0, maxHubScroll());
            refreshHubRowState();
            return true;
        }
        if (subView == SubView.EDIT_LIST && maxStepScroll() > 0) {
            stepScroll = Mth.clamp(stepScroll + (deltaY < 0 ? 1 : -1), 0, maxStepScroll());
            refreshEditListRowState();
            return true;
        }
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        if (filterBox != null && filterBox.isFocused()) {
            return filterBox.keyPressed(event);
        }
        if (amountBox != null && amountBox.isFocused()) {
            return amountBox.keyPressed(event);
        }
        if (listNameBox != null && listNameBox.isFocused()) {
            return listNameBox.keyPressed(event);
        }
        return false;
    }

    /** X / Esc / inventory: one UI level up, or leave Configure for Settings Copier hub. */
    public void requestBackOrLeave() {
        if (handleBack()) {
            return;
        }
        host.requestSequentialVirtualLeave();
    }

    public boolean charTyped(CharacterEvent event) {
        if (filterBox != null && filterBox.isFocused()) {
            return filterBox.charTyped(event);
        }
        if (amountBox != null && amountBox.isFocused()) {
            return amountBox.charTyped(event);
        }
        return false;
    }

    public boolean hidesPlayerSlots() {
        return subView == SubView.VALID_KEYS;
    }


    public boolean isHubSubView() {
        return subView == SubView.HUB;
    }

    public boolean handleBack() {
        if (subView == SubView.VALID_KEYS) {
            closeValidKeys();
            return true;
        }
        if (subView == SubView.STEP_EDIT) {
            closeStepEdit();
            return true;
        }
        if (subView == SubView.EDIT_LIST) {
            closeEditList();
            return true;
        }
        if (subView == SubView.SEQUENTIAL_TASKS) {
            subView = SubView.HUB;
            rebuildUi();
            return true;
        }
        return false;
    }

    // ===== JEI Ghost Ingredient Integration (IAnDynamicsGhostTarget) =====

    @Nullable
    public IAnDynamicsGhostTarget.IGhostIngredientConsumer getGhostHandler() {
        if (subView != SubView.STEP_EDIT) {
            return null;
        }
        return new IAnDynamicsGhostTarget.IGhostIngredientConsumer() {
            @Override
            @Nullable
            public Object supportedTarget(Object ingredient) {
                if (ingredient instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    return itemStack;
                }
                if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
                    return fluidStack;
                }
                if (MekanismChemicalCompat.isLoaded() && ingredient != null) {
                    if (!MekanismChemicalCompat.isEmptyStack(ingredient)) {
                        return ingredient;
                    }
                }
                return null;
            }

            @Override
            public void accept(Object ingredient) {
                handleGhostIngredientDrop(ingredient);
            }
        };
    }

    @Nullable
    public Rect2i getGhostTargetArea() {
        if (subView != SubView.STEP_EDIT) {
            return null;
        }
        return new Rect2i(ghostSlotX, ghostSlotY, 18, 18);
    }

}
