package net.unfamily.another_dynamics.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.registry.ModMenuTypes;

import java.util.List;

/**
 * Copies duct face <em>configuration</em> via GUI Copy; left-click opens the configurator;
 * shift-right-click on a duct face pastes {@code all} mode only.
 */
public class SettingsCopierItem extends Item {
    private static final String TOOLTIP_ROOT = "item.another_dynamics.settings_copier.tooltip.";

    public SettingsCopierItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    public static Component grayTooltipLine(String translationKey) {
        return Component.translatable(translationKey).withStyle(ChatFormatting.GRAY);
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

    public static void openHubMenu(ServerPlayer player, InteractionHand hand) {
        player.openMenu(
                new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return Component.translatable("gui.another_dynamics.settings_copier.configurator");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                        return new SettingsCopierMenu(id, inv, hand);
                    }
                },
                buf -> buf.writeByte(hand == InteractionHand.OFF_HAND ? 1 : 0));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        SettingsCopierStoreKind mode = SettingsCopierStoreKind.getMode(stack);
        boolean hasData = DuctFaceSettingsSnapshot.hasStoredSettings(stack);
        if (!hasData) {
            addTooltipLines(tooltip, TOOLTIP_ROOT + "empty.", 2);
        } else if (mode == SettingsCopierStoreKind.FILTER) {
            addTooltipLines(tooltip, TOOLTIP_ROOT + "filter.", 3);
        } else {
            addTooltipLines(tooltip, TOOLTIP_ROOT + "all.", 4);
        }
        tooltip.add(grayTooltipLine(TOOLTIP_ROOT + "use_gui"));
    }

    private static void addTooltipLines(List<Component> tooltip, String keyPrefix, int lineCount) {
        for (int i = 0; i < lineCount; i++) {
            tooltip.add(grayTooltipLine(keyPrefix + i));
        }
    }
}
