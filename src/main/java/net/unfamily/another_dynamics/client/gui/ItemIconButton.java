package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Standard NeoForge/vanilla {@link Button} (widget/button sprites) with a centered item or texture icon.
 */
public class ItemIconButton extends Button {
    private final Supplier<ItemStack> iconStack;
    @Nullable
    private final Supplier<ResourceLocation> overlayTexture;
    @Nullable
    private final Runnable onRightPress;

    public ItemIconButton(
            int x,
            int y,
            int size,
            OnPress onPress,
            Supplier<ItemStack> iconStack,
            Component tooltip) {
        this(x, y, size, onPress, iconStack, null, null, tooltip);
    }

    public ItemIconButton(
            int x,
            int y,
            int size,
            OnPress onPress,
            Supplier<ItemStack> iconStack,
            @Nullable Supplier<ResourceLocation> overlayTexture,
            Component tooltip) {
        this(x, y, size, onPress, iconStack, overlayTexture, null, tooltip);
    }

    public ItemIconButton(
            int x,
            int y,
            int size,
            OnPress onPress,
            Supplier<ItemStack> iconStack,
            @Nullable Supplier<ResourceLocation> overlayTexture,
            @Nullable Runnable onRightPress,
            Component tooltip) {
        super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
        this.iconStack = iconStack;
        this.overlayTexture = overlayTexture;
        this.onRightPress = onRightPress;
        if (!tooltip.getString().isEmpty()) {
            setTooltip(Tooltip.create(tooltip));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 1 && onRightPress != null && clicked(mouseX, mouseY)) {
            onRightPress.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        int iconSize = 12;
        int ix = getX() + (getWidth() - iconSize) / 2;
        int iy = getY() + (getHeight() - iconSize) / 2;
        ResourceLocation texture = overlayTexture != null ? overlayTexture.get() : null;
        if (texture != null) {
            graphics.blit(texture, ix, iy, 0, 0, iconSize, iconSize, iconSize, iconSize);
            return;
        }
        ItemStack stack = iconStack.get();
        if (!stack.isEmpty()) {
            graphics.pose().pushPose();
            float scale = iconSize / 16.0f;
            graphics.pose().translate(ix, iy, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.renderItem(stack, 0, 0);
            graphics.pose().popPose();
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
