#!/usr/bin/env python3
"""Mechanical 26.x GUI API migrations for AbstractUniversalDuctScreen."""

from pathlib import Path
import re

TARGET = Path(
    "/home/unfamily/IdeaProjects/Another-Dynaimics-26.1.2/src/main/java"
    "/net/unfamily/another_dynamics/client/gui/AbstractUniversalDuctScreen.java"
)


def main() -> None:
    text = TARGET.read_text(encoding="utf-8")

    text = text.replace("import net.minecraft.Util;\n", "import net.minecraft.util.Util;\n")

    if "import net.minecraft.client.input.CharacterEvent;" not in text:
        text = text.replace(
            "import net.minecraft.client.Minecraft;\n",
            "import net.minecraft.client.Minecraft;\n"
            "import net.minecraft.client.input.CharacterEvent;\n"
            "import net.minecraft.client.input.KeyEvent;\n"
            "import net.minecraft.client.input.MouseButtonEvent;\n"
            "import net.minecraft.client.input.MouseButtonInfo;\n",
        )

    text = text.replace(
        "        super(menu, playerInventory, title);\n"
        "        this.imageWidth = TEXTURE_WIDTH;\n"
        "        this.imageHeight = TEXTURE_HEIGHT;\n",
        "        super(menu, playerInventory, title, TEXTURE_WIDTH, TEXTURE_HEIGHT);\n",
    )

    replacements = [
        ("graphics.drawString(", "graphics.text("),
        ("guiGraphics.drawString(", "guiGraphics.text("),
        ("graphics.renderItem(", "graphics.item("),
        ("guiGraphics.renderItem(", "guiGraphics.item("),
        ("graphics.renderItemDecorations(", "graphics.itemDecorations("),
        ("guiGraphics.renderItemDecorations(", "guiGraphics.itemDecorations("),
        ("graphics.pose().pushPose()", "graphics.pose().pushMatrix()"),
        ("graphics.pose().popPose()", "graphics.pose().popMatrix()"),
        ("guiGraphics.pose().pushPose()", "guiGraphics.pose().pushMatrix()"),
        ("guiGraphics.pose().popPose()", "guiGraphics.pose().popMatrix()"),
        ("hasShiftDown()", "minecraft != null && minecraft.hasShiftDown()"),
        ("renderTransparentBackground(", "extractTransparentBackground("),
        ("renderBlurredBackground(", "extractBlurredBackground("),
    ]
    for old, new in replacements:
        text = text.replace(old, new)

    # Remove 3-arg translate (Matrix3x2f is 2D); z-order uses nextStratum instead.
    text = re.sub(
        r"graphics\.pose\(\)\.translate\(this\.leftPos, this\.topPos, 0\.0F\);",
        "graphics.pose().translate(this.leftPos, this.topPos);",
        text,
    )
    text = re.sub(
        r"graphics\.pose\(\)\.translate\(0, 0, \d+\);\s*\n",
        "graphics.nextStratum();\n",
        text,
    )

    text = text.replace(
        "    protected void renderBg(\n",
        "    public void extractBackground(\n",
    )
    text = text.replace(
        "    protected void renderLabels(\n",
        "    protected void extractLabels(\n",
    )
    text = text.replace(
        "    protected void renderSlot(\n",
        "    protected void extractSlot(\n",
    )

    # extractSlot gains mouse coordinates in 26.x
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

    text = text.replace(
        "        super.renderSlot(graphics, slot);",
        "        super.extractSlot(graphics, slot, mouseX, mouseY);",
    )

    # Drop renderSlotHighlight override (private in 26.x); HOW_TO_USE skips slots in extractSlot.
    text = re.sub(
        r"\n    @Override\n"
        r"    protected void renderSlotHighlight\(\n"
        r"        @NotNull GuiGraphicsExtractor guiGraphics,\n"
        r"        @NotNull Slot slot,\n"
        r"        int mouseX,\n"
        r"        int mouseY,\n"
        r"        float partialTick\n"
        r"    \) \{[^}]+\}\n",
        "\n",
        text,
        count=1,
        flags=re.DOTALL,
    )

    # renderBackground -> extractBackground (HOW_TO_USE overlay path)
    text = text.replace(
        "    public void renderBackground(\n",
        "    @Override\n    public void extractBackground(\n",
    )
    # Remove duplicate @Override if present
    text = text.replace(
        "    @Override\n    @Override\n    public void extractBackground(\n",
        "    @Override\n    public void extractBackground(\n",
    )

    # Remove custom render() — merged into extractRenderState below
    render_override = re.search(
        r"\n    @Override\n    public void render\(\n.*?\n    \}\n\n    // ===== JEI",
        text,
        flags=re.DOTALL,
    )
    if render_override:
        text = text[: render_override.start()] + "\n" + text[render_override.end() - len("\n    // ===== JEI") :]

    if "public void extractRenderState(" not in text:
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
                int cx = mouseX - 8;
                int cy = mouseY - 8;
                graphics.item(carried, cx, cy);
                graphics.itemDecorations(this.font, carried, cx, cy);
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
        marker = "    // ===== JEI Ghost Ingredient Integration"
        text = text.replace(marker, insert + marker)

    # Registry Optional holders
    text = text.replace(
        "Item item = BuiltInRegistries.ITEM.get(id);",
        "Item item = BuiltInRegistries.ITEM.get(id).map(h -> h.value()).orElse(Items.AIR);",
    )
    text = text.replace(
        "return new ItemStack(BuiltInRegistries.ITEM.get(id));",
        "return BuiltInRegistries.ITEM.get(id).map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY);",
    )
    text = text.replace(
        "Fluid fluid = BuiltInRegistries.FLUID.get(id);",
        "Fluid fluid = BuiltInRegistries.FLUID.get(id).map(h -> h.value()).orElse(Fluids.EMPTY);",
    )

    # extractBackground must call super first for normal screens
    text = text.replace(
        "    public void extractBackground(\n"
        "        @NotNull GuiGraphicsExtractor graphics,\n"
        "        float partialTick,\n"
        "        int mouseX,\n"
        "        int mouseY\n"
        "    ) {",
        "    @Override\n    public void extractBackground(\n"
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

    # Remove old renderBackground block body start if duplicated
    text = re.sub(
        r"        if \(subView == SubView\.HOW_TO_USE\) \{\n"
        r"            if \(minecraft != null && minecraft\.level != null\) \{\n"
        r"                extractTransparentBackground\(graphics\);\n"
        r"                extractBlurredBackground\(partialTick\);\n",
        "        if (subView == SubView.HOW_TO_USE && false) {\n",
        text,
        count=1,
    )

    TARGET.write_text(text, encoding="utf-8")
    print("Patched", TARGET)


if __name__ == "__main__":
    main()
