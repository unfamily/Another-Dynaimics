package net.unfamily.another_dynamics.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(ModItemTooltips.secondary(summaryKey));
    }
}
