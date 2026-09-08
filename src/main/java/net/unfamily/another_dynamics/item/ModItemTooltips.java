package net.unfamily.another_dynamics.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.duct.DuctDefinition;
import net.unfamily.another_dynamics.duct.DuctDefinitionRegistry;
import net.unfamily.another_dynamics.duct.DuctIds;
import net.unfamily.another_dynamics.duct.DuctTransportKind;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Shared item tooltip styling: secondary (gray) summaries vs highlighted (gold) tips.
 */
public final class ModItemTooltips {
    private ModItemTooltips() {}

    public static Component secondary(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.GRAY);
    }

    public static Component important(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.GOLD);
    }

    /** One-line transport summary from {@link ModDataComponents#DUCT_LOGICAL_ID} / definition. */
    public static Component ductTransportsLine(ItemStack stack, String fallbackLogicalId) {
        String logicalId = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = fallbackLogicalId != null && !fallbackLogicalId.isEmpty()
                    ? fallbackLogicalId
                    : DuctIds.DEFAULT_LOGICAL_ID;
        }
        List<DuctTransportKind> kinds =
                DuctDefinition.orderedMenuTransportKinds(DuctDefinitionRegistry.getByLogicalId(logicalId));
        MutableComponent joined = Component.empty();
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) {
                joined.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            }
            joined.append(
                    Component.translatable(transportLangKey(kinds.get(i))).withStyle(ChatFormatting.GRAY));
        }
        return Component.translatable("item.another_dynamics.duct.tooltip.transports", joined)
                .withStyle(ChatFormatting.GRAY);
    }

    private static String transportLangKey(DuctTransportKind kind) {
        return switch (kind) {
            case ITEM -> "gui.another_dynamics.duct_node.transport.item";
            case FLUID -> "gui.another_dynamics.duct_node.transport.fluid";
            case GAS -> "gui.another_dynamics.duct_node.transport.gas";
            case ENERGY -> "gui.another_dynamics.duct_node.transport.energy";
            case HEAT -> "gui.another_dynamics.duct_node.transport.heat";
        };
    }
}
