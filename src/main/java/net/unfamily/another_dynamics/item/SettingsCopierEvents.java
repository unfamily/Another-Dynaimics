package net.unfamily.another_dynamics.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;

/**
 * Left-click on blocks with the settings copier (same pattern as iska_utils swiss wrench /
 * structure placer): cancel mining and open the hub on the server.
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SettingsCopierEvents {
    private SettingsCopierEvents() {}

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
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
        event.setCanceled(true);
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (player instanceof ServerPlayer sp) {
            SettingsCopierItem.openHubMenu(sp, event.getHand());
        }
    }
}
