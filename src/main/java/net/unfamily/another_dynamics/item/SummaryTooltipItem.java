package net.unfamily.another_dynamics.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Item with a single gray summary tooltip line. */
public final class SummaryTooltipItem extends Item {
    private final String summaryKey;

    public SummaryTooltipItem(Properties properties, String summaryKey) {
        super(properties);
        this.summaryKey = summaryKey;
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(ModItemTooltips.secondary(summaryKey));
    }
}
