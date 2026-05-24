package net.unfamily.another_dynamics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.network.SettingsCopierOpenHubPayload;

/** Client-side item interactions for {@link SettingsCopierItem} (left-click has no server hook in vanilla). */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SettingsCopierClientEvents {
    private SettingsCopierClientEvents() {}

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        Player player = event.getEntity();
        if (player.isShiftKeyDown()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof SettingsCopierItem)) {
            return;
        }
        if (player.containerMenu instanceof SettingsCopierMenu) {
            return;
        }
        InteractionHand hand = event.getHand();
        PacketDistributor.sendToServer(new SettingsCopierOpenHubPayload(hand == InteractionHand.MAIN_HAND ? 0 : 1));
    }
}
