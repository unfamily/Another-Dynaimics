package net.unfamily.another_dynamics.duct.filterimport;

import java.util.List;

/** Client/server preview of an import before execute. */
public record FilterImportPreview(
        List<String> mainLines,
        List<String> invertedLines,
        List<Integer> mainConcatChannels,
        List<Integer> invertedConcatChannels,
        String defaultPrimaryName,
        String defaultSecondaryName,
        boolean needsSecondCopier) {

    public FilterImportPreview(
            List<String> mainLines,
            List<String> invertedLines,
            String defaultPrimaryName,
            String defaultSecondaryName,
            boolean needsSecondCopier) {
        this(mainLines, invertedLines, List.of(), List.of(), defaultPrimaryName, defaultSecondaryName, needsSecondCopier);
    }

    public boolean isEmpty() {
        return mainLines.isEmpty() && invertedLines.isEmpty();
    }
}
