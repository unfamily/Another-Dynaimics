package net.unfamily.another_dynamics.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.unfamily.another_dynamics.duct.AbstractDuctBlock;
import net.unfamily.another_dynamics.duct.DuctBlock;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.duct.settings.SettingsCopierStoreKind;

import java.util.List;

/** Copies duct face node settings via GUI Save; paste full settings with shift-click on a duct face (all mode only). */
public class SettingsCopierItem extends Item {
    private static final String TOOLTIP_ROOT = "item.another_dynamics.settings_copier.tooltip.";

    public SettingsCopierItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof AbstractDuctBlock)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        BlockHitResult hit =
                new BlockHitResult(context.getClickLocation(), context.getClickedFace(), pos, false);
        return DuctBlock.attemptSettingsCopierPaste(level, pos, player, stack, hit).result();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (!DuctFaceSettingsSnapshot.hasStoredSettings(stack)) {
            addTooltipLines(tooltip, TOOLTIP_ROOT + "empty.", 2);
        } else if (DuctFaceSettingsSnapshot.getStoreKind(stack) == SettingsCopierStoreKind.FILTER) {
            addTooltipLines(tooltip, TOOLTIP_ROOT + "filter.", 3);
        } else {
            addTooltipLines(tooltip, TOOLTIP_ROOT + "all.", 4);
        }
        tooltip.add(
                Component.translatable(TOOLTIP_ROOT + "air_gui").withStyle(ChatFormatting.GRAY));
    }

    private static void addTooltipLines(List<Component> tooltip, String keyPrefix, int lineCount) {
        for (int i = 0; i < lineCount; i++) {
            tooltip.add(Component.translatable(keyPrefix + i));
        }
    }
}
