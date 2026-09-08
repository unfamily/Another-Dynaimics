package net.unfamily.another_dynamics.item;

import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** Item with a single gray summary tooltip line. */
public final class SummaryTooltipItem extends Item {
    private final String summaryKey;

    public SummaryTooltipItem(Properties properties, String summaryKey) {
        super(properties);
        this.summaryKey = summaryKey;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> tooltip,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(ModItemTooltips.secondary(summaryKey));
    }
}
