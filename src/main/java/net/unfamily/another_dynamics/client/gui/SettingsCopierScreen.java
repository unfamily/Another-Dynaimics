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
import net.unfamily.another_dynamics.duct.DuctGuiLayout;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.network.ModNetwork;
import net.unfamily.another_dynamics.network.SettingsCopierHubActionPayload;

/**
 * Settings copier: hub and virtual universal duct editor in one screen (root layer sync, no nested openMenu).
 */
public final class SettingsCopierScreen extends AbstractUniversalDuctScreen<SettingsCopierMenu> {
    private static final ResourceLocation HUB_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    AnotherDynamicsMod.MOD_ID, "textures/gui/background/settings_copier.png");

    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_Y = 5;
    private static final int CLOSE_BUTTON_X =
            DuctGuiLayout.NODE_TEXTURE_WIDTH - CLOSE_BUTTON_SIZE - 5;
    private static final int TITLE_Y = 7;
    private static final int CENTER_X = 38;
    private static final int ROW1_Y = 32;
    private static final int ROW2_Y = 50;
    private static final int ROW3_Y = 78;
    private static final int RENAME_LABEL_GAP_ABOVE_BOX = 2;
    private static final int BTN_H = 14;
    private static final int ROW_BTN_W = 76;
    private static final int ROW_GAP = 4;
    private static final int CENTER_PANEL_W = ROW_BTN_W * 3 + ROW_GAP * 2;
    private static final int RENAME_BOX_W = CENTER_PANEL_W - 18 - ROW_GAP;
    private static final int RENAME_CONFIRM_W = 18;

    private int cachedRootLayer = -1;
    private Button hubCloseButton;
    private Button configureButton;
    private Button modeButton;
    private Button renameConfirmButton;
    private EditBox renameBox;
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
        if (!virtualBackConfirmPending) {
            refreshVirtualCloseButton();
        }
        super.containerTick();
    }

    @Override
    protected void handleCloseOrBack() {
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
        super.renderBg(graphics, partialTick, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (menu.isHubLayer()) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            renderBg(graphics, partialTick, mouseX, mouseY);
            for (Renderable renderable : renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
            graphics.pose().pushPose();
            graphics.pose().translate(this.leftPos, this.topPos, 0.0F);
            renderLabels(graphics, mouseX, mouseY);
            graphics.pose().popPose();
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (menu.isHubLayer()) {
            int titleWidth = this.font.width(this.title);
            int titleX = (this.imageWidth - titleWidth) / 2;
            graphics.drawString(this.font, this.title, titleX, TITLE_Y, 0x404040, false);
            Component renameCaption =
                    Component.translatable("gui.another_dynamics.settings_copier.rename_section");
            int captionWidth = this.font.width(renameCaption);
            int captionX = (this.imageWidth - captionWidth) / 2;
            int captionY = ROW3_Y - this.font.lineHeight - RENAME_LABEL_GAP_ABOVE_BOX;
            graphics.drawString(this.font, renameCaption, captionX, captionY, 0x404040, false);
            return;
        }
        super.renderLabels(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderSlot(GuiGraphics graphics, Slot slot) {
        if (menu.isHubLayer() && slot.index < SettingsCopierMenu.PLAYER_SLOT_COUNT) {
            return;
        }
        if (menu.isVirtualLayer() && slot.index == menu.openingCopierMenuSlotIndex()) {
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
        if (menu.isHubLayer() && slot.index < SettingsCopierMenu.PLAYER_SLOT_COUNT) {
            return;
        }
        if (menu.isVirtualLayer() && slot.index == menu.openingCopierMenuSlotIndex()) {
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
        return super.charTyped(codePoint, modifiers);
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
                        .bounds(this.leftPos + CENTER_X, this.topPos + ROW1_Y, CENTER_PANEL_W, BTN_H)
                        .build();
        addRenderableWidget(configureButton);

        modeButton =
                Button.builder(modeButtonLabel(), b -> onModeLeftClick())
                        .bounds(this.leftPos + CENTER_X, this.topPos + ROW2_Y, CENTER_PANEL_W, BTN_H)
                        .build();
        addRenderableWidget(modeButton);

        renameBox = new EditBox(this.font, 0, 0, RENAME_BOX_W, BTN_H, Component.empty());
        renameBox.setMaxLength(64);
        ItemStack stack = menu.copierStack(minecraft.player);
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            renameBox.setValue(stack.getHoverName().getString());
        }
        renameBox.setPosition(this.leftPos + CENTER_X, this.topPos + ROW3_Y);
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
                                this.topPos + ROW3_Y,
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

}
