package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;

import org.jetbrains.annotations.NotNull;

/**
 * Duct node GUI: upgrades, center controls (stub), right column (redstone, copy, channel), player inventory.
 * Control actions are visual / local stubs only until gameplay is wired.
 */
public final class DuctNodeScreen extends AbstractContainerScreen<DuctNodeMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/background/node.png");
    private static final ResourceLocation MEDIUM_BUTTONS =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/medium_buttons.png");
    private static final ResourceLocation REDSTONE_GUI =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/redstone_gui.png");
    /** From iskautils / Pattern-Crafter slot art (18×18). */
    private static final ResourceLocation SINGLE_SLOT =
            ResourceLocation.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "textures/gui/single_slot.png");

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

    /** Same width for every full-width row button; row-2 middle block (- / edit / +) matches this total width. */
    private static final int ROW_BTN_W = 76;
    private static final int ROW_GAP = 4;

    /** Same size as Pattern Crafter filter letter labels (16×10). */
    private static final int CHANNEL_WIDGET_W = 16;
    private static final int CHANNEL_WIDGET_H = 10;
    private static final int CHANNEL_WIDGET_Y = DuctNodeMenu.SLOT_COPY_Y + 18 + 6;

    private int redstoneButtonScreenX;
    private int redstoneButtonScreenY;
    /** Local stub: 0–4, same icon order as Pattern Crafter / iskautils. */
    private int redstoneModeStub;

    private ChannelLetterButton channelButton;
    private EditBox routingPriorityBox;

    public DuctNodeScreen(DuctNodeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = TEXTURE_WIDTH;
        this.imageHeight = TEXTURE_HEIGHT;
        this.inventoryLabelY = 10_000;
    }

    @Override
    protected void init() {
        super.init();

        redstoneButtonScreenX = this.leftPos + DuctNodeMenu.REDSTONE_GUI_X;
        redstoneButtonScreenY = this.topPos + DuctNodeMenu.REDSTONE_GUI_Y;

        Button close = Button.builder(Component.literal("\u2715"), b -> {
                    playClickSound();
                    onClose();
                })
                .bounds(this.leftPos + CLOSE_BUTTON_X, this.topPos + CLOSE_BUTTON_Y, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE)
                .build();
        addRenderableWidget(close);

        addRenderableWidget(stubButton(CENTER_X, ROW1_Y, ROW_BTN_W, BTN_H, "gui.another_dynamics.duct_node.deny_list"));
        addRenderableWidget(stubButton(
                CENTER_X + ROW_BTN_W + ROW_GAP,
                ROW1_Y,
                ROW_BTN_W,
                BTN_H,
                "gui.another_dynamics.duct_node.list_logic"));
        addRenderableWidget(stubButton(
                CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                ROW1_Y,
                ROW_BTN_W,
                BTN_H,
                "gui.another_dynamics.duct_node.allow_list"));

        int r2x = CENTER_X;
        addRenderableWidget(stubButton(r2x, ROW2_Y, ROW_BTN_W, BTN_H, "gui.another_dynamics.duct_node.routing_mode"));
        r2x += ROW_BTN_W + ROW_GAP;
        int midX = r2x;
        int stepperW = 11;
        int innerGap = 2;
        int editW = ROW_BTN_W - 2 * stepperW - 2 * innerGap;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustRoutingPriority(-1))
                .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, stepperW, BTN_H)
                .build());
        r2x += stepperW + innerGap;
        routingPriorityBox = new EditBox(this.font, this.leftPos + r2x, this.topPos + ROW2_Y, editW, BTN_H, Component.empty());
        routingPriorityBox.setMaxLength(6);
        routingPriorityBox.setValue("0");
        addRenderableWidget(routingPriorityBox);
        r2x += editW + innerGap;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustRoutingPriority(1))
                .bounds(this.leftPos + r2x, this.topPos + ROW2_Y, stepperW, BTN_H)
                .build());
        r2x = midX + ROW_BTN_W;
        r2x += ROW_GAP;
        addRenderableWidget(stubButton(r2x, ROW2_Y, ROW_BTN_W, BTN_H, "gui.another_dynamics.duct_node.dump"));

        addRenderableWidget(stubButton(CENTER_X, ROW3_Y, ROW_BTN_W, BTN_H, "gui.another_dynamics.duct_node.node_mode"));
        addRenderableWidget(stubButton(
                CENTER_X + ROW_BTN_W + ROW_GAP,
                ROW3_Y,
                ROW_BTN_W,
                BTN_H,
                "gui.another_dynamics.duct_node.self_feed"));
        addRenderableWidget(stubButton(
                CENTER_X + 2 * (ROW_BTN_W + ROW_GAP),
                ROW3_Y,
                ROW_BTN_W,
                BTN_H,
                "gui.another_dynamics.duct_node.rendering"));

        int channelX = DuctNodeMenu.SLOT_COPY_X + (18 - CHANNEL_WIDGET_W) / 2;
        channelButton = new ChannelLetterButton(
                this.leftPos + channelX,
                this.topPos + CHANNEL_WIDGET_Y,
                CHANNEL_WIDGET_W,
                CHANNEL_WIDGET_H);
        addRenderableWidget(channelButton);
    }

    private Button stubButton(int guiX, int guiY, int w, int h, String translationKey) {
        return Button.builder(Component.translatable(translationKey), b -> playClickSound())
                .bounds(this.leftPos + guiX, this.topPos + guiY, w, h)
                .build();
    }

    private void adjustRoutingPriority(int delta) {
        playClickSound();
        int v = parsePriority(routingPriorityBox.getValue());
        v = Math.max(0, Math.min(999_999, v + delta));
        routingPriorityBox.setValue(Integer.toString(v));
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
        graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        blitMachineSlotBackgrounds(graphics);

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
        if (button == 0
                && mouseX >= redstoneButtonScreenX
                && mouseX < redstoneButtonScreenX + REDSTONE_BUTTON_SIZE
                && mouseY >= redstoneButtonScreenY
                && mouseY < redstoneButtonScreenY + REDSTONE_BUTTON_SIZE) {
            playClickSound();
            redstoneModeStub = (redstoneModeStub + 1) % 5;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        int titleWidth = this.font.width(this.title);
        int titleX = (this.imageWidth - titleWidth) / 2;
        graphics.drawString(this.font, this.title, titleX, 7, 0x404040, false);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);

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
