package net.unfamily.another_dynamics.item;

import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/** Block item with a single gray summary tooltip line. */
public final class SummaryTooltipBlockItem extends BlockItem {
    private final String summaryKey;

    public SummaryTooltipBlockItem(Block block, Properties properties, String summaryKey) {
        super(block, properties);
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
