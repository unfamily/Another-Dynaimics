package net.unfamily.another_dynamics.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * Advanced remote node face picker: N/S/E/W/U/D, or * (any face at bound block). Left-click cycles forward,
 * right-click backward. * without a bound endpoint is inactive (any counterparty). Uses vanilla button sprites.
 */
public final class RemoteNodeFaceButton extends Button {
    public record FaceSelection(@Nullable Direction face, boolean anyFace) {}

    private static final int ANY_ORDINAL = Direction.values().length;

    private boolean destinationBound;
    private @Nullable Direction face;
    private boolean anyFace;
    private final Consumer<FaceSelection> onSelectionChanged;

    public RemoteNodeFaceButton(
            int x, int y, int width, int height, Consumer<FaceSelection> onSelectionChanged) {
        super(x, y, width, height, Component.literal("*"), b -> {}, DEFAULT_NARRATION);
        this.onSelectionChanged = onSelectionChanged;
        refreshTooltip();
    }

    public void setSelection(boolean bound, @Nullable Direction value, boolean anyFaceAtPos) {
        destinationBound = bound;
        face = value;
        anyFace = bound && anyFaceAtPos;
        setMessage(Component.literal(labelFor(destinationBound, face, anyFace)));
        refreshTooltip();
    }

    public static String labelFor(boolean bound, @Nullable Direction value, boolean anyFaceAtPos) {
        if (!bound || anyFaceAtPos) {
            return "*";
        }
        return switch (value) {
            case NORTH -> "N";
            case SOUTH -> "S";
            case EAST -> "E";
            case WEST -> "W";
            case UP -> "U";
            case DOWN -> "D";
            case null -> "*";
        };
    }

    private int selectionOrdinal() {
        if (!destinationBound) {
            return ANY_ORDINAL;
        }
        if (anyFace) {
            return ANY_ORDINAL;
        }
        return face != null ? face.ordinal() : 0;
    }

    private void applyOrdinal(int ordinal) {
        if (ordinal >= ANY_ORDINAL) {
            anyFace = true;
            if (face == null) {
                face = Direction.NORTH;
            }
        } else {
            anyFace = false;
            face = Direction.values()[ordinal];
        }
        setMessage(Component.literal(labelFor(destinationBound, face, anyFace)));
    }

    private void refreshTooltip() {
        if (!destinationBound) {
            setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "gui.another_dynamics.duct_node.remote_node.face_any.tooltip")));
        } else if (anyFace) {
            setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "gui.another_dynamics.duct_node.remote_node.face_any_at_pos.tooltip")));
        } else {
            setTooltip(
                    Tooltip.create(
                            Component.translatable(
                                    "gui.another_dynamics.duct_node.remote_node.face_cycle.tooltip")));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && (button == 0 || button == 1) && clicked(mouseX, mouseY) && destinationBound) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            int total = ANY_ORDINAL + 1;
            int idx = selectionOrdinal();
            if (button == 0) {
                idx = (idx + 1) % total;
            } else {
                idx = (idx - 1 + total) % total;
            }
            applyOrdinal(idx);
            refreshTooltip();
            onSelectionChanged.accept(new FaceSelection(face, anyFace));
            return true;
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
