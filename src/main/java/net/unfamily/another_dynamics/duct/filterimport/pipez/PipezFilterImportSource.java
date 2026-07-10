package net.unfamily.another_dynamics.duct.filterimport.pipez;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportChannel;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportPreview;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportSource;

/** Pipez upgrade import via {@code pipez:item|fluid|gas} data components. */
public final class PipezFilterImportSource implements FilterImportSource {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("pipez", "upgrade");
    public static final PipezFilterImportSource INSTANCE = new PipezFilterImportSource();

    private PipezFilterImportSource() {}

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public boolean canImport(ItemStack stack, HolderLookup.Provider registries) {
        if (!ModList.get().isLoaded("pipez")) {
            return false;
        }
        return PipezStackComponents.isPipezUpgrade(stack)
                && PipezStackComponents.hasAnyPipezChannel(stack, registries);
    }

    @Override
    public List<FilterImportChannel> channels(ItemStack stack, HolderLookup.Provider registries) {
        List<FilterImportChannel> out = new ArrayList<>();
        for (FilterImportChannel ch : FilterImportChannel.values()) {
            if (PipezStackComponents.readChannelData(stack, ch, registries) != null) {
                out.add(ch);
            }
        }
        return out;
    }

    @Override
    public FilterImportPreview preview(
            ItemStack stack, FilterImportChannel channel, HolderLookup.Provider registries) {
        var data = PipezStackComponents.readChannelData(stack, channel, registries);
        if (data == null) {
            return new FilterImportPreview(List.of(), List.of(), "", "", false);
        }
        return PipezFilterLineConverter.convert(data);
    }
}
