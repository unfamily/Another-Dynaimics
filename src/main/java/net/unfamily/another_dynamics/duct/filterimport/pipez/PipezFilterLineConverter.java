package net.unfamily.another_dynamics.duct.filterimport.pipez;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.unfamily.another_dynamics.duct.filterimport.FilterImportPreview;

/**
 * Converts Pipez {@code Filters} NBT into duct filter line strings (id / {@code #tag} / {@code ?nbt} pairs).
 */
public final class PipezFilterLineConverter {
    /** Matches {@code gui.another_dynamics.settings_copier.import.default_allow|default_deny}. */
    public static final String DEFAULT_ALLOW = "Pipez imported Allow";
    public static final String DEFAULT_DENY = "Pipez imported Deny";

    private PipezFilterLineConverter() {}

    public static FilterImportPreview convert(CompoundTag channelData) {
        List<String> main = new ArrayList<>();
        List<String> inverted = new ArrayList<>();
        List<Integer> mainConcat = new ArrayList<>();
        List<Integer> invertedConcat = new ArrayList<>();
        boolean whitelist = isWhitelist(channelData);
        int mainChannel = 1;
        int invertedChannel = 1;
        if (channelData.contains("Filters")) {
            ListTag filters = channelData.getListOrEmpty("Filters");
            for (int i = 0; i < filters.size(); i++) {
                if (filters.get(i) instanceof CompoundTag entry) {
                    List<String> lines = linesForFilter(entry);
                    if (lines.isEmpty()) {
                        continue;
                    }
                    boolean invert = entry.getBooleanOr("Invert", false);
                    if (invert) {
                        for (String line : lines) {
                            inverted.add(line);
                            invertedConcat.add(invertedChannel);
                        }
                        invertedChannel++;
                    } else {
                        for (String line : lines) {
                            main.add(line);
                            mainConcat.add(mainChannel);
                        }
                        mainChannel++;
                    }
                }
            }
        }
        String primaryName = whitelist ? DEFAULT_ALLOW : DEFAULT_DENY;
        String secondaryName = whitelist ? DEFAULT_DENY : DEFAULT_ALLOW;
        return new FilterImportPreview(
                main,
                inverted,
                mainConcat,
                invertedConcat,
                primaryName,
                secondaryName,
                !inverted.isEmpty());
    }

    private static boolean isWhitelist(CompoundTag channelData) {
        if (channelData.contains("filter_mode")) {
            return channelData.getByteOr("filter_mode", (byte) 0) == 0;
        }
        if (channelData.contains("filter_mode")) {
            return channelData.getIntOr("filter_mode", 0) == 0;
        }
        return true;
    }

    static List<String> linesForFilter(CompoundTag entry) {
        List<String> out = new ArrayList<>();
        String idLine = tagLine(entry);
        if (idLine == null) {
            return out;
        }
        out.add(idLine);
        CompoundTag metadata = entry.contains("Metadata")
                ? entry.getCompoundOrEmpty("Metadata")
                : null;
        if (metadata != null && !metadata.isEmpty()) {
            String nbtSub = metadata.toString();
            if (!nbtSub.isEmpty()) {
                out.add("?" + nbtSub);
            }
        }
        return out;
    }

    @Nullable
    private static String tagLine(CompoundTag entry) {
        if (!entry.contains("Tag")) {
            return null;
        }
        CompoundTag tag = entry.getCompoundOrEmpty("Tag");
        String path = tag.contains("tag") ? tag.getStringOr("tag", "") : "";
        if (path.isEmpty()) {
            return null;
        }
        String type = tag.contains("type") ? tag.getStringOr("type", "") : "SINGLE";
        return formatDuctFilterLine(path, type);
    }

    /**
     * Maps Pipez tag paths to duct filter syntax: exact ids use {@code -id} (same as ghost-slot presets in
     * {@link net.unfamily.another_dynamics.client.gui.AbstractUniversalDuctScreen}), tags use {@code #tag}.
     */
    static String formatDuctFilterLine(String path, String type) {
        if (path.isEmpty()) {
            return path;
        }
        if ("TAG".equalsIgnoreCase(type)) {
            return path.startsWith("#") ? path : "#" + path;
        }
        if (path.startsWith("#")
                || path.startsWith("@")
                || path.startsWith("&")
                || path.startsWith("?")
                || path.startsWith("-")) {
            return path;
        }
        Identifier.tryParse(path);
        return "-" + path;
    }
}
