package net.unfamily.another_dynamics;

import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Temporary beta nag: action-bar spam when a tester holds Pipez pipe items in main/off hand. Remove
 * this class (or set {@link #ENABLED} to {@code false}) before release.
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 * REMOVE COMPLETY BEFORE RELEASE
 */
@EventBusSubscriber(modid = AnotherDynamicsMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class BetaTesterPipezNag {
    /** Set to {@code false} or delete this class for the final release build. */
    private static final boolean ENABLED = false;

    private static final int INTERVAL_TICKS = 10;

    private static final TextColor NAG_TEXT_COLOR = TextColor.fromRgb(0x8B0000);

    private static final Set<ResourceLocation> NAG_PIPE_ITEMS = Set.of(
            ResourceLocation.fromNamespaceAndPath("pipez", "item_pipe"),
            ResourceLocation.fromNamespaceAndPath("pipez", "fluid_pipe"),
            ResourceLocation.fromNamespaceAndPath("pipez", "energy_pipe"),
            ResourceLocation.fromNamespaceAndPath("pipez", "universal_pipe"),
            ResourceLocation.fromNamespaceAndPath("pipez", "gas_pipe"));

    private BetaTesterPipezNag() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer) || player.level().isClientSide()) {
            return;
        }
        if (player.tickCount % INTERVAL_TICKS != 0 || !holdingNagPipeItem(player)) {
            return;
        }
        serverPlayer.displayClientMessage(nagMessage(), true);
    }

    private static Component nagMessage() {
        Component modName =
                Component.translatable("another_dynamics.beta.pipez_nag.mod_name")
                        .withStyle(
                                s -> s.withColor(NAG_TEXT_COLOR)
                                        .withBold(true)
                                        .withUnderlined(true));
        return Component.translatable("another_dynamics.beta.pipez_nag", modName)
                .withStyle(s -> s.withColor(NAG_TEXT_COLOR).withBold(true));
    }

    /** Only main hand and off hand; pipes elsewhere in inventory are ignored. */
    private static boolean holdingNagPipeItem(Player player) {
        return isNagPipeItem(player.getMainHandItem()) || isNagPipeItem(player.getOffhandItem());
    }

    private static boolean isNagPipeItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return NAG_PIPE_ITEMS.contains(
                stack.getItem().builtInRegistryHolder().key().location());
    }
}
