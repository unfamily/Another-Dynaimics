#!/usr/bin/env python3
"""Patch input/render method signatures in AbstractUniversalDuctScreen for MC 26.x."""

from pathlib import Path
import re

TARGET = Path(
    "/home/unfamily/IdeaProjects/Another-Dynaimics-26.1.2/src/main/java"
    "/net/unfamily/another_dynamics/client/gui/AbstractUniversalDuctScreen.java"
)


def ensure_imports(text: str) -> str:
    if "import net.minecraft.client.input.MouseButtonEvent;" not in text:
        text = text.replace(
            "import net.minecraft.client.Minecraft;\n",
            "import net.minecraft.client.Minecraft;\n"
            "import net.minecraft.client.input.CharacterEvent;\n"
            "import net.minecraft.client.input.KeyEvent;\n"
            "import net.minecraft.client.input.MouseButtonEvent;\n"
            "import net.minecraft.client.input.MouseButtonInfo;\n"
            "import net.minecraft.client.renderer.RenderPipelines;\n",
        )
    return text


def patch_mouse_clicked(text: str) -> str:
    old = (
        "    @Override\n"
        "    public boolean mouseClicked(double mouseX, double mouseY, int button) {"
    )
    new = (
        "    @Override\n"
        "    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {\n"
        "        double mouseX = event.x();\n"
        "        double mouseY = event.y();\n"
        "        int button = event.button();"
    )
    text = text.replace(old, new)
    text = text.replace(
        "return super.mouseClicked(mouseX, mouseY, button);",
        "return super.mouseClicked(event, doubleClick);",
    )
    text = text.replace(
        "protected boolean delegateContainerMouseClicked(double mouseX, double mouseY, int button) {\n"
        "        return super.mouseClicked(mouseX, mouseY, button);",
        "protected boolean delegateContainerMouseClicked(MouseButtonEvent event, boolean doubleClick) {\n"
        "        return super.mouseClicked(event, doubleClick);",
    )
    return text


def patch_mouse_released(text: str) -> str:
    old = "    @Override\n    public boolean mouseReleased(double mouseX, double mouseY, int button) {"
    new = (
        "    @Override\n"
        "    public boolean mouseReleased(MouseButtonEvent event) {\n"
        "        double mouseX = event.x();\n"
        "        double mouseY = event.y();\n"
        "        int button = event.button();"
    )
    text = text.replace(old, new)
    return text.replace(
        "return super.mouseReleased(mouseX, mouseY, button);",
        "return super.mouseReleased(event);",
    )


def patch_mouse_dragged(text: str) -> str:
    old = (
        "    @Override\n"
        "    public boolean mouseDragged(\n"
        "        double mouseX,\n"
        "        double mouseY,\n"
        "        int button,\n"
        "        double dragX,\n"
        "        double dragY\n"
        "    ) {"
    )
    new = (
        "    @Override\n"
        "    public boolean mouseDragged(\n"
        "        MouseButtonEvent event,\n"
        "        double dragX,\n"
        "        double dragY\n"
        "    ) {\n"
        "        double mouseX = event.x();\n"
        "        double mouseY = event.y();\n"
        "        int button = event.button();"
    )
    text = text.replace(old, new)
    return text.replace(
        "return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);",
        "return super.mouseDragged(event, dragX, dragY);",
    )


def patch_key_pressed(text: str) -> str:
    old = "    @Override\n    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {"
    new = (
        "    @Override\n"
        "    public boolean keyPressed(KeyEvent event) {\n"
        "        int keyCode = event.key();\n"
        "        int scanCode = event.scancode();\n"
        "        int modifiers = event.modifiers();"
    )
    text = text.replace(old, new)
    text = text.replace(
        "protected boolean delegateContainerKeyPressed(int keyCode, int scanCode, int modifiers) {\n"
        "        return super.keyPressed(keyCode, scanCode, modifiers);",
        "protected boolean delegateContainerKeyPressed(KeyEvent event) {\n"
        "        return super.keyPressed(event);",
    )
    text = text.replace(
        "minecraft.options.keyInventory.matches(keyCode, scanCode);",
        "minecraft.options.keyInventory.matches(event);",
    )
    return text.replace(
        "return super.keyPressed(keyCode, scanCode, modifiers);",
        "return super.keyPressed(event);",
    )


def patch_char_typed(text: str) -> str:
    old = "    @Override\n    public boolean charTyped(char codePoint, int modifiers) {"
    new = (
        "    @Override\n"
        "    public boolean charTyped(CharacterEvent event) {\n"
        "        char codePoint = (char) event.codepoint();"
    )
    text = text.replace(old, new)
    return text.replace(
        "return super.charTyped(codePoint, modifiers);",
        "return super.charTyped(event);",
    )


def patch_route_how_to_use(text: str) -> str:
    old = """            boolean handled = switch (op) {
                case MOUSE_CLICK -> child.mouseClicked(mouseX, mouseY, button);
                case MOUSE_RELEASE -> child.mouseReleased(
                    mouseX,
                    mouseY,
                    button
                );
                case MOUSE_DRAG -> child.mouseDragged(
                    mouseX,
                    mouseY,
                    button,
                    dragX,
                    dragY
                );
                case KEY -> child.keyPressed(keyCode, scanCode, modifiers);
                case CHAR -> child.charTyped(codePoint, modifiers);
            };"""
    new = """            boolean handled = switch (op) {
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
            };"""
    return text.replace(old, new)


def patch_deliver_mouse(text: str) -> str:
    old = (
        "            if (child.isMouseOver(mouseX, mouseY) && child.mouseClicked(mouseX, mouseY, button)) {"
    )
    new = (
        "            if (child.isMouseOver(mouseX, mouseY)\n"
        "                    && child.mouseClicked(\n"
        "                            new MouseButtonEvent(\n"
        "                                    mouseX, mouseY, new MouseButtonInfo(button, 0)),\n"
        "                            false)) {"
    )
    return text.replace(old, new)


def patch_render_methods(text: str) -> str:
    text = text.replace("protected void renderBg(", "public void extractBackground(")
    text = text.replace("protected void renderLabels(", "protected void extractLabels(")
    text = text.replace("protected void renderSlot(", "protected void extractSlot(")
    text = text.replace("super.renderSlot(graphics, slot);", "super.extractSlot(graphics, slot, mouseX, mouseY);")

    # extractSlot signature
    text = text.replace(
        "    protected void extractSlot(\n"
        "        @NotNull GuiGraphicsExtractor graphics,\n"
        "        @NotNull Slot slot\n"
        "    ) {",
        "    protected void extractSlot(\n"
        "        @NotNull GuiGraphicsExtractor graphics,\n"
        "        @NotNull Slot slot,\n"
        "        int mouseX,\n"
        "        int mouseY\n"
        "    ) {",
    )

    # Remove broken renderBackground override; merge into extractBackground via renderBg rename
    text = re.sub(
        r"\n    /\*\*\n     \* \"Valid keys\".*?\n    \}\n\n    private boolean routeHowToUseInputToWidgetsOnly",
        "\n\n    private boolean routeHowToUseInputToWidgetsOnly",
        text,
        count=1,
        flags=re.DOTALL,
    )

    # Fix extractBackground signature (was renderBg)
    text = text.replace(
        "    public void extractBackground(\n"
        "        @NotNull GuiGraphicsExtractor graphics,\n"
        "        float partialTick,\n"
        "        int mouseX,\n"
        "        int mouseY\n"
        "    ) {",
        "    @Override\n"
        "    public void extractBackground(\n"
        "        GuiGraphicsExtractor graphics,\n"
        "        int mouseX,\n"
        "        int mouseY,\n"
        "        float partialTick\n"
        "    ) {\n"
        "        if (subView == SubView.HOW_TO_USE) {\n"
        "            if (minecraft != null && minecraft.level != null) {\n"
        "                extractTransparentBackground(graphics);\n"
        "                extractBlurredBackground(graphics);\n"
        "            } else {\n"
        "                super.extractBackground(graphics, mouseX, mouseY, partialTick);\n"
        "            }\n"
        "        } else {\n"
        "            super.extractBackground(graphics, mouseX, mouseY, partialTick);\n"
        "        }\n",
    )

    # Remove renderSlotHighlight override block
    text = re.sub(
        r"\n    @Override\n"
        r"    protected void renderSlotHighlight\(\n.*?\n    \}\n\n    @Override\n"
        r"    protected void extractSlot\(",
        "\n\n    @Override\n    protected void extractSlot(",
        text,
        count=1,
        flags=re.DOTALL,
    )

    # Replace custom render() with extractRenderState + extractTooltip
    render_block = re.search(
        r"\n    @Override\n    public void render\(\n.*?\n    \}\n\n    // ===== JEI",
        text,
        flags=re.DOTALL,
    )
    if render_block:
        insert = """
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
                graphics.renderTooltip(this.font, copier, mouseX, mouseY);
            } else {
                graphics.renderComponentTooltip(
                        this.font,
                        List.of(
                                Component.translatable(
                                        "gui.another_dynamics.duct_node.copy_slot.tooltip.line1"),
                                Component.translatable(
                                        "gui.another_dynamics.duct_node.copy_slot.tooltip.line2")),
                        mouseX,
                        mouseY);
            }
        }
    }

"""
        text = text[: render_block.start()] + insert + text[render_block.end() - len("\n    // ===== JEI") :]

    # z-order layering
    text = re.sub(
        r"graphics\.pose\(\)\.translate\(0, 0, \d+\);\s*\n",
        "graphics.nextStratum();\n",
        text,
    )
    text = text.replace(
        "graphics.pose().translate(this.leftPos, this.topPos, 0.0F);",
        "graphics.pose().translate(this.leftPos, this.topPos);",
    )

    return text


def main() -> None:
    text = TARGET.read_text(encoding="utf-8")
    text = ensure_imports(text)
    text = patch_mouse_clicked(text)
    text = patch_mouse_released(text)
    text = patch_mouse_dragged(text)
    text = patch_key_pressed(text)
    text = patch_char_typed(text)
    text = patch_route_how_to_use(text)
    text = patch_deliver_mouse(text)
    text = patch_render_methods(text)
    TARGET.write_text(text, encoding="utf-8")
    print("Patched input/render methods in", TARGET)


if __name__ == "__main__":
    main()
