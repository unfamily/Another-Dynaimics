package net.unfamily.another_dynamics.duct.filterimport.external;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportPreview;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportSource;

/** Filter import from optional companion upgrade data components (runtime-only integration). */
public final class ExternalUpgradeFilterImportSource implements FilterImportSource {
    /** Companion mod namespace checked at runtime when present in the pack. */
    public static final String COMPANION_NAMESPACE = "pipez";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(AnotherDynamicsMod.MOD_ID, "external_upgrade");
    public static final ExternalUpgradeFilterImportSource INSTANCE = new ExternalUpgradeFilterImportSource();

    private ExternalUpgradeFilterImportSource() {}

    public static boolean isCompanionModLoaded() {
        return ModList.get().isLoaded(COMPANION_NAMESPACE);
    }

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public boolean canImport(ItemStack stack, HolderLookup.Provider registries) {
        if (!isCompanionModLoaded()) {
            return false;
        }
        return ExternalUpgradeStackComponents.isUpgradeItem(stack)
                && ExternalUpgradeStackComponents.hasAnyChannelData(stack, registries);
    }

    @Override
    public List<FilterImportChannel> channels(ItemStack stack, HolderLookup.Provider registries) {
        List<FilterImportChannel> out = new ArrayList<>();
        for (FilterImportChannel ch : FilterImportChannel.values()) {
            if (ExternalUpgradeStackComponents.readChannelData(stack, ch, registries) != null) {
                out.add(ch);
            }
        }
        return out;
    }

    @Override
    public FilterImportPreview preview(
            ItemStack stack, FilterImportChannel channel, HolderLookup.Provider registries) {
        var data = ExternalUpgradeStackComponents.readChannelData(stack, channel, registries);
        if (data == null) {
            return new FilterImportPreview(List.of(), List.of(), "", "", false);
        }
        return ExternalFilterLineConverter.convert(data);
    }
}
