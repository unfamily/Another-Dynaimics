package net.unfamily.another_dynamics.duct.filterimport.pipez;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportPreview;
import org.junit.jupiter.api.Test;

class PipezFilterLineConverterTest {

    @Test
    void singleItemNoNbt() {
        CompoundTag channel = channelWithFilters(filterEntry("minecraft:iron_ingot", "SINGLE", false, null));
        FilterImportPreview preview = PipezFilterLineConverter.convert(channel);
        assertEquals(1, preview.mainLines().size());
        assertEquals("-minecraft:iron_ingot", preview.mainLines().getFirst());
    }

    @Test
    void tagEntry() {
        CompoundTag channel = channelWithFilters(filterEntry("c:ingots", "TAG", false, null));
        FilterImportPreview preview = PipezFilterLineConverter.convert(channel);
        assertEquals("#c:ingots", preview.mainLines().getFirst());
    }

    @Test
    void metadataProducesTwoLines() {
        CompoundTag meta = new CompoundTag();
        meta.putInt("actuallyadditions:energy", 250000);
        CompoundTag channel =
                channelWithFilters(filterEntry("actuallyadditions:drill_green", "SINGLE", false, meta));
        FilterImportPreview preview = PipezFilterLineConverter.convert(channel);
        assertEquals(2, preview.mainLines().size());
        assertEquals("-actuallyadditions:drill_green", preview.mainLines().get(0));
        assertTrue(preview.mainLines().get(1).startsWith("?"));
    }

    @Test
    void blacklistUsesDenyAsPrimaryName() {
        CompoundTag channel = channelWithFilters(filterEntry("minecraft:stone", "SINGLE", false, null));
        channel.putByte("filter_mode", (byte) 1);
        FilterImportPreview preview = PipezFilterLineConverter.convert(channel);
        assertEquals(PipezFilterLineConverter.DEFAULT_DENY, preview.defaultPrimaryName());
        assertEquals(PipezFilterLineConverter.DEFAULT_ALLOW, preview.defaultSecondaryName());
    }

    @Test
    void formatDuctFilterLineExactIdGetsMinusPrefix() {
        assertEquals("-minecraft:stone", PipezFilterLineConverter.formatDuctFilterLine("minecraft:stone", "SINGLE"));
        assertEquals("#c:ingots", PipezFilterLineConverter.formatDuctFilterLine("c:ingots", "TAG"));
        assertEquals("-minecraft:stone", PipezFilterLineConverter.formatDuctFilterLine("-minecraft:stone", "SINGLE"));
    }

    @Test
    void invertGoesToSecondary() {
        CompoundTag channel =
                channelWithFilters(filterEntry("minecraft:damaged_anvil", "SINGLE", true, null));
        FilterImportPreview preview = PipezFilterLineConverter.convert(channel);
        assertTrue(preview.mainLines().isEmpty());
        assertEquals(1, preview.invertedLines().size());
        assertEquals("-minecraft:damaged_anvil", preview.invertedLines().getFirst());
        assertTrue(preview.needsSecondCopier());
    }

    private static CompoundTag channelWithFilters(CompoundTag... filters) {
        CompoundTag channel = new CompoundTag();
        channel.putByte("filter_mode", (byte) 0);
        ListTag list = new ListTag();
        for (CompoundTag f : filters) {
            list.add(f);
        }
        channel.put("Filters", list);
        return channel;
    }

    private static CompoundTag filterEntry(String tagPath, String type, boolean invert, CompoundTag metadata) {
        CompoundTag entry = new CompoundTag();
        CompoundTag tag = new CompoundTag();
        tag.putString("tag", tagPath);
        tag.putString("type", type);
        entry.put("Tag", tag);
        entry.putBoolean("Invert", invert);
        entry.putBoolean("ExactMetadata", false);
        if (metadata != null) {
            entry.put("Metadata", metadata);
        }
        return entry;
    }
}
