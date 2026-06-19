package net.unfamily.another_dynamics.duct.filterimport;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.unfamily.another_dynamics.inventory.SettingsCopierMenu;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.network.ModNetwork;

/** Server-side filter import into one or two settings copiers. */
public final class FilterImportService {
    private FilterImportService() {}

    public static FilterImportResult execute(
            ServerPlayer player,
            SettingsCopierMenu menu,
            InteractionHand hand,
            FilterImportChannel channel,
            String primaryName,
            String secondaryName) {
        ItemStack sourceUpgrade = menu.getImportSourceStack();
        ItemStack secondCopierSlot = menu.getImportSecondCopierStack();
        var registries = player.level().registryAccess();
        var preview =
                FilterImportRegistry.preview(sourceUpgrade, channel, registries).orElse(null);
        if (preview == null) {
            return FilterImportResult.NO_ADAPTER;
        }
        if (preview.isEmpty()) {
            return FilterImportResult.EMPTY_LIST;
        }
        if (preview.needsSecondCopier()
                && (secondCopierSlot == null
                        || secondCopierSlot.isEmpty()
                        || !(secondCopierSlot.getItem() instanceof SettingsCopierItem))) {
            return FilterImportResult.MISSING_SECOND_COPIER;
        }

        ItemStack primary = player.getItemInHand(hand);
        if (primary.isEmpty() || !(primary.getItem() instanceof SettingsCopierItem)) {
            return FilterImportResult.NO_ADAPTER;
        }

        ItemStack writtenPrimary =
                FilterImportCopierWriter.writeFilterCopier(
                        primary,
                        preview.mainLines(),
                        preview.mainConcatChannels(),
                        preview.mainRemoteNodes(),
                        primaryName,
                        channel,
                        registries);
        if (writtenPrimary.isEmpty()) {
            return FilterImportResult.NO_ADAPTER;
        }
        player.setItemInHand(hand, writtenPrimary);

        if (preview.needsSecondCopier()) {
            ItemStack writtenSecondary =
                    FilterImportCopierWriter.writeFilterCopier(
                            secondCopierSlot,
                            preview.invertedLines(),
                            preview.invertedConcatChannels(),
                            preview.invertedRemoteNodes(),
                            secondaryName,
                            channel,
                            registries);
            if (writtenSecondary.isEmpty()) {
                return FilterImportResult.NO_ADAPTER;
            }
            menu.setImportSecondCopierStack(writtenSecondary);
        }

        ModNetwork.sendSettingsCopierStackSync(player, writtenPrimary);
        return FilterImportResult.SUCCESS;
    }
}
