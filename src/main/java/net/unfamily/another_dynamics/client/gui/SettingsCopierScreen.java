package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.world.inventory.Slot;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.client.SettingsCopierClient;
import net.unfamily.another_dynamics.duct.DuctGuiLayout;
import net.unfamily.another_dynamics.duct.DuctMenuSync;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportPreview;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportRegistry;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.network.SettingsCopierHubActionPayload;
import net.unfamily.another_dynamics.duct.filterimport.external.ExternalUpgradeFilterImportSource;

/**
 * Settings copier: hub and virtual universal duct editor in one screen (root layer sync, no nested openMenu).
 */
public final class SettingsCopierScreen extends AbstractUniversalDuctScreen<SettingsCopierMenu> {
    private static final ResourceLocation HUB_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/background/settings_copier.png");
    /** Same panel as virtual universal duct editor ({@link AbstractUniversalDuctScreen}). */
    private static final ResourceLocation VIRTUAL_NODE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/background/node.png");

    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_Y = 5;
    private static final int CLOSE_BUTTON_X =
            DuctGuiLayout.NODE_TEXTURE_WIDTH - CLOSE_BUTTON_SIZE - 5;
    private static final int TITLE_Y = 7;
    private static final int CENTER_X = 38;
    private static final int BTN_H = 14;
    /** Hub: Configure → Mode → Import (last), with vertical spacing between rows. */
    private static final int HUB_BTN_V_GAP = 8;
    private static final int HUB_ROW1_Y = 24;
    private static final int HUB_ROW2_Y = HUB_ROW1_Y + BTN_H + HUB_BTN_V_GAP;
    private static final int HUB_ROW3_Y = HUB_ROW2_Y + BTN_H + HUB_BTN_V_GAP;
    /** Below Import button: gap + rename caption line + {@link #RENAME_LABEL_GAP_ABOVE_BOX} before the edit box. */
    private static final int HUB_RENAME_SECTION_GAP = 22;
    private static final int HUB_RENAME_Y = HUB_ROW3_Y + BTN_H + HUB_RENAME_SECTION_GAP;
    private static final int RENAME_LABEL_GAP_ABOVE_BOX = 2;
    private static final int ROW_BTN_W = 76;
    private static final int ROW_GAP = 4;
    private static final int CENTER_PANEL_W = ROW_BTN_W * 3 + ROW_GAP * 2;
    private static final int RENAME_BOX_W = CENTER_PANEL_W - 18 - ROW_GAP;
    private static final int RENAME_CONFIRM_W = 18;
    private int cachedRootLayer = -1;
    private Button hubCloseButton;
    private Button importFilterHubButton;
    private Button configureButton;
    private Button modeButton;
    private Button renameConfirmButton;
    private EditBox renameBox;
    private Button importCloseButton;
    private Button importBackButton;
    private Button importChannelButton;
    private Button importExecuteButton;
    private EditBox importPrimaryNameBox;
    private EditBox importSecondaryNameBox;
    private int importChannelIndex;
    private boolean importNamesAutoFill = true;
    private ItemStack lastImportSourceForAutofill = ItemStack.EMPTY;
    private boolean modeConfirmPending;
    private boolean virtualBackConfirmPending;

    public SettingsCopierScreen(SettingsCopierMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = DuctGuiLayout.NODE_TEXTURE_WIDTH;
        this.imageHeight = DuctGuiLayout.NODE_TEXTURE_HEIGHT;
        this.titleLabelY = 10_000;
        this.inventoryLabelY = 10_000;
    }

    @Override
    protected boolean showsSettingsCopierColumn() {
        return false;
    }

    @Override
    protected boolean showsChannelLetterControl(
            boolean hubLayer, boolean filterList, boolean advancedFiltering, boolean bufferLimits) {
        if (!menu.isVirtualLayer() || menu.getSyncData().get(DuctMenuSync.MENU_VIEW_LAYER) == 0) {
            return false;
        }
        return !hubLayer || filterList || advancedFiltering || bufferLimits;
    }

    @Override
    protected boolean useSettingsCopierHubNavigation() {
        return menu.isVirtualLayer();
    }

    @Override
    @org.jetbrains.annotations.Nullable
    protected net.minecraft.network.chat.Component settingsCopierVirtualMainTitle() {
        if (!menu.isVirtualLayer() || minecraft == null || minecraft.player == null) {
            return null;
        }
        if (menu.storeKind(minecraft.player) == SettingsCopierStoreKind.FILTER) {
            return null;
        }
        return Component.translatable("gui.another_dynamics.settings_copier.virtual_node");
    }

    /** {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen} centers the panel. */
    private void layoutScreenCenter() {
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
    }

    @Override
    protected void init() {
        modeConfirmPending = false;
        virtualBackConfirmPending = false;
        if (menu.isHubLayer()) {
            layoutScreenCenter();
            initHubWidgets();
            cachedRootLayer = SettingsCopierMenu.ROOT_HUB;
            return;
        }
        if (menu.isImportLayer()) {
            layoutScreenCenter();
            initImportWidgets();
            cachedRootLayer = SettingsCopierMenu.ROOT_IMPORT;
            return;
        }
        super.init();
        cachedRootLayer = SettingsCopierMenu.ROOT_VIRTUAL;
        bootstrapSettingsCopierInitialSubview();
        refreshVirtualCloseButton();
    }

    @Override
    public void containerTick() {
        int root = menu.rootLayerValue();
        if (root != cachedRootLayer) {
            cachedRootLayer = root;
            modeConfirmPending = false;
            virtualBackConfirmPending = false;
            this.clearWidgets();
            this.init();
        }
        if (menu.isHubLayer()) {
            if (!modeConfirmPending) {
                refreshModeButtonLabel();
            }
            return;
        }
        if (menu.isImportLayer()) {
            refreshImportUi();
            return;
        }
        if (!virtualBackConfirmPending) {
            refreshVirtualCloseButton();
        }
        super.containerTick();
    }

    @Override
    protected void handleCloseOrBack() {
        if (menu.isImportLayer()) {
            playClickSound();
            ModNetwork.sendSettingsCopierHubAction(SettingsCopierHubActionPayload.ACTION_BACK_FROM_IMPORT);
            return;
        }
        if (menu.isVirtualLayer() && virtualBackConfirmPending) {
            playClickSound();
            cancelVirtualBackConfirm();
            return;
        }
        if (menu.isVirtualLayer() && isMainChromeSubView()) {
            playClickSound();
            if (isSettingsCopierVirtualDetailLayer()) {
                returnToVirtualTransportHub();
            } else {
                ModNetwork.sendSettingsCopierReturnToHub();
            }
            return;
        }
        super.handleCloseOrBack();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (menu.isHubLayer()) {
            graphics.blit(
                    HUB_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0,
                    0,
                    this.imageWidth,
                    this.imageHeight,
                    DuctGuiLayout.NODE_TEXTURE_WIDTH,
                    DuctGuiLayout.NODE_TEXTURE_HEIGHT);
            return;
        }
        if (menu.isImportLayer()) {
            graphics.blit(
                    VIRTUAL_NODE_TEXTURE,
                    this.leftPos,
                    this.topPos,
                    0,
                    0,
                    this.imageWidth,
                    this.imageHeight,
                    DuctGuiLayout.NODE_TEXTURE_WIDTH,
                    DuctGuiLayout.NODE_TEXTURE_HEIGHT);
            blitImportSlotFrames(graphics);
            return;
        }
        super.renderBg(graphics, partialTick, mouseX, mouseY);
    }

    private void blitImportSlotFrames(GuiGraphics graphics) {
        Slot source = menu.getSlot(SettingsCopierMenu.IMPORT_SOURCE_SLOT);
        if (source.isActive()) {
            int fx = this.leftPos + source.x;
            int fy = this.topPos + source.y;
            SettingsCopierClient.blitModuleSlotFrame(graphics, fx, fy);
            if (source.getItem().isEmpty()) {
                SettingsCopierClient.blitModuleGhostIcon(
                        graphics,
                        fx + SettingsCopierClient.MODULE_SLOT_CONTENT_DX,
                        fy + SettingsCopierClient.MODULE_SLOT_CONTENT_DY);
            }
        }
        Slot second = menu.getSlot(SettingsCopierMenu.IMPORT_SECOND_COPIER_SLOT);
        if (second.isActive()) {
            SettingsCopierClient.blitSlotFrame(graphics, this.leftPos + second.x, this.topPos + second.y);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (menu.isHubLayer()) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            renderBg(graphics, partialTick, mouseX, mouseY);
            for (Slot slot : menu.slots) {
                if (slot.isActive()) {
                    renderSlot(graphics, slot);
                }
            }
            for (Renderable renderable : renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
            graphics.pose().pushPose();
            graphics.pose().translate(this.leftPos, this.topPos, 0.0F);
            renderLabels(graphics, mouseX, mouseY);
            graphics.pose().popPose();
            renderTooltip(graphics, mouseX, mouseY);
            return;
        }
        if (menu.isImportLayer()) {
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (menu.isImportLayer()) {
            Component importTitle =
                    Component.translatable("gui.another_dynamics.settings_copier.importing");
            int titleWidth = this.font.width(importTitle);
            int titleX = (this.imageWidth - titleWidth) / 2;
            graphics.drawString(this.font, importTitle, titleX, TITLE_Y, 0x404040, false);
            Component primaryCaption =
                    Component.translatable("gui.another_dynamics.settings_copier.import.name_primary");
            graphics.drawString(
                    this.font,
                    primaryCaption,
                    SettingsCopierMenu.IMPORT_PRIMARY_NAME_X,
                    SettingsCopierMenu.IMPORT_LABEL_Y,
                    0x404040,
                    false);
            if (menu.clientImportNeedsSecondCopier()) {
                Component secondaryCaption =
                        Component.translatable("gui.another_dynamics.settings_copier.import.name_secondary");
                graphics.drawString(
                        this.font,
                        secondaryCaption,
                        SettingsCopierMenu.importSecondaryNameX(this.imageWidth),
                        SettingsCopierMenu.IMPORT_LABEL_Y,
                        0x404040,
                        false);
            }
            return;
        }
        if (menu.isHubLayer()) {
            int titleWidth = this.font.width(this.title);
            int titleX = (this.imageWidth - titleWidth) / 2;
            graphics.drawString(this.font, this.title, titleX, TITLE_Y, 0x404040, false);
            Component renameCaption =
                    Component.translatable("gui.another_dynamics.settings_copier.rename_section");
            int captionWidth = this.font.width(renameCaption);
            int captionX = (this.imageWidth - captionWidth) / 2;
            int captionY = HUB_RENAME_Y - this.font.lineHeight - RENAME_LABEL_GAP_ABOVE_BOX;
            graphics.drawString(this.font, renameCaption, captionX, captionY, 0x404040, false);
            return;
        }
        super.renderLabels(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (menu.isHubLayer()
                && slot.index < SettingsCopierMenu.PLAYER_SLOT_START + SettingsCopierMenu.PLAYER_SLOT_COUNT) {
            return;
        }
        if (menu.isImportLayer()) {
            if (!slot.isActive()) {
                return;
            }
            if (slot.index < SettingsCopierMenu.PLAYER_SLOT_START) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    int ix = slot.x + SettingsCopierClient.MODULE_SLOT_CONTENT_DX;
                    int iy = slot.y + SettingsCopierClient.MODULE_SLOT_CONTENT_DY;
                    graphics.renderItem(stack, ix, iy);
                    graphics.renderItemDecorations(this.font, stack, ix, iy);
                }
                return;
            }
            super.renderSlot(graphics, slot);
            return;
        }
        if (menu.isVirtualLayer() && slot.index == lockedCopierMenuSlotIndex()) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, slot.x, slot.y);
                graphics.renderItemDecorations(this.font, stack, slot.x, slot.y);
            }
            return;
        }
        super.renderSlot(graphics, slot);
    }

    @Override
    protected void renderSlotHighlight(
            GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, float partialTick) {
        if (menu.isHubLayer() && slot.index < SettingsCopierMenu.PLAYER_SLOT_START + SettingsCopierMenu.PLAYER_SLOT_COUNT) {
            return;
        }
        if (menu.isImportLayer() && !slot.isActive()) {
            return;
        }
        if (menu.isImportLayer() && slot.index < SettingsCopierMenu.PLAYER_SLOT_START) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(
                    SettingsCopierClient.MODULE_SLOT_CONTENT_DX,
                    SettingsCopierClient.MODULE_SLOT_CONTENT_DY,
                    0.0F);
            super.renderSlotHighlight(guiGraphics, slot, mouseX, mouseY, partialTick);
            guiGraphics.pose().popPose();
            return;
        }
        if (menu.isVirtualLayer() && slot.index == lockedCopierMenuSlotIndex()) {
            return;
        }
        super.renderSlotHighlight(guiGraphics, slot, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.isHubLayer()) {
            if (button == 1 && modeConfirmPending && modeButton != null && modeButton.isMouseOver(mouseX, mouseY)) {
                cancelModeConfirm();
                return true;
            }
            if (button == 1 && renameBox != null && renameBox.isMouseOver(mouseX, mouseY)) {
                playClickSound();
                renameBox.setValue("");
                renameBox.setFocused(true);
                return true;
            }
            if (renameBox != null) {
                if (button == 0 && renameBox.isMouseOver(mouseX, mouseY)) {
                    renameBox.setFocused(true);
                    renameBox.mouseClicked(mouseX, mouseY, button);
                    return true;
                }
                if (button == 0 && !renameBox.isMouseOver(mouseX, mouseY)) {
                    unfocusRename();
                }
            }
            return deliverMouseToWidgets(mouseX, mouseY, button);
        }
        if (menu.isImportLayer()) {
            if (importPrimaryNameBox != null && importPrimaryNameBox.isMouseOver(mouseX, mouseY)) {
                importPrimaryNameBox.setFocused(true);
                importNamesAutoFill = false;
                return importPrimaryNameBox.mouseClicked(mouseX, mouseY, button);
            }
            if (importSecondaryNameBox != null
                    && importSecondaryNameBox.visible
                    && importSecondaryNameBox.isMouseOver(mouseX, mouseY)) {
                importSecondaryNameBox.setFocused(true);
                importNamesAutoFill = false;
                return importSecondaryNameBox.mouseClicked(mouseX, mouseY, button);
            }
            if (button == 0) {
                if (importPrimaryNameBox != null) {
                    importPrimaryNameBox.setFocused(false);
                }
                if (importSecondaryNameBox != null) {
                    importSecondaryNameBox.setFocused(false);
                }
            }
            if (deliverMouseToWidgets(mouseX, mouseY, button)) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (menu.isVirtualLayer() && isMainChromeSubView()) {
            if (isOverCloseButton(mouseX, mouseY)) {
                if (button == 1) {
                    playClickSound();
                    onVirtualBackRightClick();
                    return true;
                }
                if (button == 0 && virtualBackConfirmPending) {
                    playClickSound();
                    cancelVirtualBackConfirm();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (menu.isHubLayer()) {
            if (renameBox != null && renameBox.isFocused()) {
                if (renameBox.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            if (modeConfirmPending && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                cancelModeConfirm();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                onClose();
                return true;
            }
            return false;
        }
        if (menu.isImportLayer()) {
            if (importPrimaryNameBox != null && importPrimaryNameBox.isFocused()) {
                return importPrimaryNameBox.keyPressed(keyCode, scanCode, modifiers);
            }
            if (importSecondaryNameBox != null
                    && importSecondaryNameBox.visible
                    && importSecondaryNameBox.isFocused()) {
                return importSecondaryNameBox.keyPressed(keyCode, scanCode, modifiers);
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                playClickSound();
                ModNetwork.sendSettingsCopierHubAction(
                        SettingsCopierHubActionPayload.ACTION_BACK_FROM_IMPORT);
                return true;
            }
            return false;
        }
        if (menu.isVirtualLayer() && virtualBackConfirmPending) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                cancelVirtualBackConfirm();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (menu.isHubLayer()) {
            if (renameBox != null && renameBox.isFocused()) {
                return renameBox.charTyped(codePoint, modifiers);
            }
            return false;
        }
        if (menu.isImportLayer()) {
            if (importPrimaryNameBox != null && importPrimaryNameBox.isFocused()) {
                return importPrimaryNameBox.charTyped(codePoint, modifiers);
            }
            if (importSecondaryNameBox != null
                    && importSecondaryNameBox.visible
                    && importSecondaryNameBox.isFocused()) {
                return importSecondaryNameBox.charTyped(codePoint, modifiers);
            }
            return false;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private int lockedCopierMenuSlotIndex() {
        int idx = menu.openingCopierMenuSlotIndex();
        return idx >= 0 ? idx + SettingsCopierMenu.PLAYER_SLOT_START : -1;
    }

    private void initHubWidgets() {
        hubCloseButton =
                Button.builder(Component.literal("\u2715"), b -> onClose())
                        .bounds(
                                this.leftPos + CLOSE_BUTTON_X,
                                this.topPos + CLOSE_BUTTON_Y,
                                CLOSE_BUTTON_SIZE,
                                CLOSE_BUTTON_SIZE)
                        .build();
        addRenderableWidget(hubCloseButton);

        configureButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.settings_copier.configure"),
                                b -> {
                                    if (renameBox != null && renameBox.isFocused()) {
                                        return;
                                    }
                                    playClickSound();
                                    ModNetwork.sendSettingsCopierHubAction(
                                            SettingsCopierHubActionPayload.ACTION_CONFIGURE);
                                })
                        .bounds(this.leftPos + CENTER_X, this.topPos + HUB_ROW1_Y, CENTER_PANEL_W, BTN_H)
                        .build();
        addRenderableWidget(configureButton);

        modeButton =
                Button.builder(modeButtonLabel(), b -> onModeLeftClick())
                        .bounds(this.leftPos + CENTER_X, this.topPos + HUB_ROW2_Y, CENTER_PANEL_W, BTN_H)
                        .build();
        addRenderableWidget(modeButton);

        importFilterHubButton =
                Button.builder(
                                Component.translatable(
                                        "gui.another_dynamics.settings_copier.import_filter"),
                                b -> {
                                    if (renameBox != null && renameBox.isFocused()) {
                                        return;
                                    }
                                    playClickSound();
                                    ModNetwork.sendSettingsCopierHubAction(
                                            SettingsCopierHubActionPayload.ACTION_ENTER_IMPORT);
                                })
                        .bounds(
                                this.leftPos + CENTER_X,
                                this.topPos + HUB_ROW3_Y,
                                CENTER_PANEL_W,
                                BTN_H)
                        .build();
        importFilterHubButton.active = ExternalUpgradeFilterImportSource.isCompanionModLoaded();
        addRenderableWidget(importFilterHubButton);

        renameBox = new EditBox(this.font, 0, 0, RENAME_BOX_W, BTN_H, Component.empty());
        renameBox.setMaxLength(64);
        ItemStack stack = menu.copierStack(minecraft.player);
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            renameBox.setValue(stack.getHoverName().getString());
        }
        renameBox.setPosition(this.leftPos + CENTER_X, this.topPos + HUB_RENAME_Y);
        addRenderableWidget(renameBox);

        renameConfirmButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.settings_copier.rename_confirm"),
                                b -> {
                                    if (renameBox != null) {
                                        playClickSound();
                                        applyRenameFromBox();
                                    }
                                })
                        .bounds(
                                this.leftPos + CENTER_X + RENAME_BOX_W + ROW_GAP,
                                this.topPos + HUB_RENAME_Y,
                                RENAME_CONFIRM_W,
                                BTN_H)
                        .build();
        addRenderableWidget(renameConfirmButton);
    }

    private Component modeButtonLabel() {
        if (modeConfirmPending) {
            return Component.translatable("gui.another_dynamics.settings_copier.mode_warn")
                    .withStyle(net.minecraft.ChatFormatting.RED);
        }
        SettingsCopierStoreKind mode = SettingsCopierStoreKind.getMode(menu.copierStack(minecraft.player));
        return Component.translatable(
                mode == SettingsCopierStoreKind.FILTER
                        ? "gui.another_dynamics.settings_copier.mode_filter"
                        : "gui.another_dynamics.settings_copier.mode_all");
    }

    private void refreshModeButtonLabel() {
        if (modeButton != null) {
            modeButton.setMessage(modeButtonLabel());
        }
    }

    private void onModeLeftClick() {
        if (renameBox != null && renameBox.isFocused()) {
            return;
        }
        playClickSound();
        if (!modeConfirmPending) {
            modeConfirmPending = true;
            refreshModeButtonLabel();
            return;
        }
        modeConfirmPending = false;
        ModNetwork.sendSettingsCopierHubAction(SettingsCopierHubActionPayload.ACTION_MODE_TOGGLE);
        refreshModeButtonLabel();
    }

    private void cancelModeConfirm() {
        if (!modeConfirmPending) {
            return;
        }
        playClickSound();
        modeConfirmPending = false;
        refreshModeButtonLabel();
    }

    private void onVirtualBackRightClick() {
        if (!virtualBackConfirmPending) {
            virtualBackConfirmPending = true;
            refreshVirtualCloseButton();
            return;
        }
        virtualBackConfirmPending = false;
        ModNetwork.sendSettingsCopierReturnToHub();
    }

    private void cancelVirtualBackConfirm() {
        if (!virtualBackConfirmPending) {
            return;
        }
        virtualBackConfirmPending = false;
        refreshVirtualCloseButton();
    }

    private void refreshVirtualCloseButton() {
        if (closeButton == null) {
            return;
        }
        if (virtualBackConfirmPending) {
            closeButton.setMessage(
                    Component.translatable("gui.another_dynamics.settings_copier.back_warn")
                            .withStyle(net.minecraft.ChatFormatting.RED));
        } else {
            closeButton.setMessage(Component.literal("\u2715"));
        }
    }

    private boolean isOverCloseButton(double mouseX, double mouseY) {
        int x = this.leftPos + CLOSE_BUTTON_X;
        int y = this.topPos + CLOSE_BUTTON_Y;
        return mouseX >= x
                && mouseX < x + CLOSE_BUTTON_SIZE
                && mouseY >= y
                && mouseY < y + CLOSE_BUTTON_SIZE;
    }

    private void applyRenameFromBox() {
        if (renameBox == null) {
            return;
        }
        String trimmed = renameBox.getValue().trim();
        renameBox.setValue(trimmed);
        ModNetwork.sendSettingsCopierHubAction(SettingsCopierHubActionPayload.ACTION_RENAME, trimmed);
        renameBox.setFocused(false);
    }

    private void unfocusRename() {
        if (renameBox != null) {
            renameBox.setFocused(false);
        }
    }

    private void playClickSound() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private void initImportWidgets() {
        importChannelIndex = 0;
        importNamesAutoFill = true;

        importCloseButton =
                Button.builder(Component.literal("\u2715"), b -> handleCloseOrBack())
                        .bounds(
                                this.leftPos + CLOSE_BUTTON_X,
                                this.topPos + CLOSE_BUTTON_Y,
                                CLOSE_BUTTON_SIZE,
                                CLOSE_BUTTON_SIZE)
                        .build();
        addRenderableWidget(importCloseButton);

        importChannelButton =
                Button.builder(Component.literal("Item"), b -> cycleImportChannel())
                        .bounds(
                                this.leftPos + SettingsCopierMenu.importChannelButtonX(this.imageWidth),
                                this.topPos + SettingsCopierMenu.IMPORT_CHANNEL_Y,
                                SettingsCopierMenu.IMPORT_CHANNEL_W,
                                SettingsCopierMenu.IMPORT_BTN_H)
                        .build();
        addRenderableWidget(importChannelButton);

        importBackButton =
                Button.builder(
                                Component.translatable("gui.another_dynamics.settings_copier.back_to_hub"),
                                b -> {
                                    playClickSound();
                                    ModNetwork.sendSettingsCopierHubAction(
                                            SettingsCopierHubActionPayload.ACTION_BACK_FROM_IMPORT);
                                })
                        .bounds(
                                this.leftPos + SettingsCopierMenu.importBackButtonX(this.imageWidth),
                                this.topPos + SettingsCopierMenu.IMPORT_BACK_Y,
                                SettingsCopierMenu.IMPORT_BACK_W,
                                SettingsCopierMenu.IMPORT_BTN_H)
                        .build();
        addRenderableWidget(importBackButton);

        importPrimaryNameBox =
                new EditBox(
                        this.font,
                        0,
                        0,
                        SettingsCopierMenu.IMPORT_NAME_W,
                        SettingsCopierMenu.IMPORT_BTN_H,
                        Component.empty());
        importPrimaryNameBox.setMaxLength(64);
        importPrimaryNameBox.setPosition(
                this.leftPos + SettingsCopierMenu.IMPORT_PRIMARY_NAME_X,
                this.topPos + SettingsCopierMenu.IMPORT_PRIMARY_NAME_Y);
        addRenderableWidget(importPrimaryNameBox);

        importSecondaryNameBox =
                new EditBox(
                        this.font,
                        0,
                        0,
                        SettingsCopierMenu.IMPORT_NAME_W,
                        SettingsCopierMenu.IMPORT_BTN_H,
                        Component.empty());
        importSecondaryNameBox.setMaxLength(64);
        importSecondaryNameBox.setPosition(
                this.leftPos + SettingsCopierMenu.importSecondaryNameX(this.imageWidth),
                this.topPos + SettingsCopierMenu.IMPORT_PRIMARY_NAME_Y);
        addRenderableWidget(importSecondaryNameBox);

        importExecuteButton =
                Button.builder(
                                Component.translatable(
                                        "gui.another_dynamics.settings_copier.import.execute"),
                                b -> executeImport())
                        .bounds(
                                this.leftPos + CENTER_X,
                                this.topPos + SettingsCopierMenu.IMPORT_EXECUTE_Y,
                                CENTER_PANEL_W,
                                SettingsCopierMenu.IMPORT_BTN_H)
                        .build();
        addRenderableWidget(importExecuteButton);

        refreshImportUi();
    }

    private void cycleImportChannel() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack source = menu.getImportSourceStack();
        var channels =
                FilterImportRegistry.channels(source, minecraft.player.level().registryAccess());
        if (channels.size() <= 1) {
            return;
        }
        playClickSound();
        importChannelIndex = (importChannelIndex + 1) % channels.size();
        importNamesAutoFill = true;
        ModNetwork.sendFilterImportChannel(channels.get(importChannelIndex).ordinal());
        refreshImportUi();
    }

    private void refreshImportUi() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        var registries = minecraft.player.level().registryAccess();
        ItemStack source = menu.getImportSourceStack();
        if (!ItemStack.matches(source, lastImportSourceForAutofill)
                || source.getItem() != lastImportSourceForAutofill.getItem()) {
            lastImportSourceForAutofill = source.copy();
            importNamesAutoFill = true;
            importChannelIndex = 0;
        }
        var channels = FilterImportRegistry.channels(source, registries);
        if (channels.isEmpty()) {
            menu.setClientImportNeedsSecondCopier(false);
            if (importChannelButton != null) {
                importChannelButton.setMessage(
                        Component.translatable(
                                "gui.another_dynamics.settings_copier.import_channel.item"));
                importChannelButton.active = false;
            }
            if (importSecondaryNameBox != null) {
                importSecondaryNameBox.visible = false;
            }
            if (importExecuteButton != null) {
                importExecuteButton.active = false;
            }
            return;
        }
        if (importChannelIndex >= channels.size()) {
            importChannelIndex = 0;
        }
        FilterImportChannel channel = channels.get(importChannelIndex);
        if (importChannelButton != null) {
            importChannelButton.setMessage(channelLabel(channel));
            importChannelButton.active = channels.size() > 1;
        }
        FilterImportPreview preview =
                FilterImportRegistry.preview(source, channel, registries).orElse(null);
        boolean needsSecond = preview != null && preview.needsSecondCopier();
        menu.setClientImportNeedsSecondCopier(needsSecond);
        if (importSecondaryNameBox != null) {
            importSecondaryNameBox.visible = needsSecond;
        }
        if (importNamesAutoFill && preview != null) {
            if (importPrimaryNameBox != null) {
                importPrimaryNameBox.setValue(preview.defaultPrimaryName());
            }
            if (importSecondaryNameBox != null && needsSecond) {
                importSecondaryNameBox.setValue(preview.defaultSecondaryName());
            }
        }
        if (importExecuteButton != null) {
            importExecuteButton.active = preview != null && !preview.isEmpty();
        }
    }

    private static Component channelLabel(FilterImportChannel channel) {
        return switch (channel) {
            case ITEM ->
                    Component.translatable("gui.another_dynamics.settings_copier.import_channel.item");
            case FLUID ->
                    Component.translatable("gui.another_dynamics.settings_copier.import_channel.fluid");
            case GAS ->
                    Component.translatable("gui.another_dynamics.settings_copier.import_channel.gas");
        };
    }

    private void executeImport() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack source = menu.getImportSourceStack();
        var channels =
                FilterImportRegistry.channels(source, minecraft.player.level().registryAccess());
        if (channels.isEmpty() || importChannelIndex >= channels.size()) {
            return;
        }
        playClickSound();
        String primary = importPrimaryNameBox != null ? importPrimaryNameBox.getValue().trim() : "";
        String secondary =
                importSecondaryNameBox != null && importSecondaryNameBox.visible
                        ? importSecondaryNameBox.getValue().trim()
                        : "";
        ModNetwork.sendFilterImportExecute(channels.get(importChannelIndex).ordinal(), primary, secondary);
    }

}
