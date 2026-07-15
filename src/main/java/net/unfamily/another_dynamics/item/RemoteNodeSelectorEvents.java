package net.unfamily.another_dynamics.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;

/** Destination binding: physical block + clicked face, never duct blocks. */
public final class RemoteNodeSelectorEvents {
    private RemoteNodeSelectorEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getEntity().getItemInHand(event.getHand());
        if (!(held.getItem() instanceof RemoteNodeSelectorItem)) {
            return;
        }
        DuctDirectionalEndpoint endpoint =
                DuctDirectionalEndpoint.fromBlockInteraction(
                        event.getLevel(), event.getPos(), event.getFace());
        if (endpoint == null) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            event.setUseItem(TriState.TRUE);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        RemoteNodeSelectorItem.bindEndpoint(held, endpoint, event.getEntity());
        event.setUseItem(TriState.TRUE);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
