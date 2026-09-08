package net.unfamily.another_dynamics.item;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.registry.ModDataComponents;

public class RemoteNodeSelectorItem extends Item {
    public RemoteNodeSelectorItem(Properties properties) {
        super(properties);
    }

    @Nullable
    public static DuctDirectionalEndpoint getEndpoint(ItemStack stack) {
        return stack.get(ModDataComponents.REMOTE_NODE_ENDPOINT.get());
    }

    public static void bindEndpoint(ItemStack stack, DuctDirectionalEndpoint endpoint, Player player) {
        stack.set(ModDataComponents.REMOTE_NODE_ENDPOINT.get(), endpoint);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("item.another_dynamics.remote_node_selector.message.set"), true);
        } else {
            player.sendSystemMessage(Component.translatable("item.another_dynamics.remote_node_selector.message.set"));
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null) {
            return InteractionResult.PASS;
        }
        DuctDirectionalEndpoint endpoint =
                DuctDirectionalEndpoint.fromBlockInteraction(
                        level, context.getClickedPos(), context.getClickedFace());
        if (endpoint == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        bindEndpoint(stack, endpoint, player);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(ModItemTooltips.secondary("item.another_dynamics.remote_node_selector.tooltip.summary"));
        DuctDirectionalEndpoint endpoint = getEndpoint(stack);
        if (endpoint == null) {
            tooltip.accept(
                    Component.translatable("item.another_dynamics.remote_node_selector.tooltip.unbound")
                            .withStyle(ChatFormatting.AQUA));
            return;
        }
        BlockPos p = endpoint.pos();
        tooltip.accept(
                Component.translatable(
                                "item.another_dynamics.remote_node_selector.tooltip.bound",
                                p.getX(),
                                p.getY(),
                                p.getZ(),
                                endpoint.face().getSerializedName())
                        .withStyle(ChatFormatting.AQUA));
    }
}
