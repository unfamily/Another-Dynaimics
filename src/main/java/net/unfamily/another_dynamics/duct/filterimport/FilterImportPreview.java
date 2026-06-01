package net.unfamily.another_dynamics.duct.filterimport;

import java.util.List;

/** Client/server preview of an import before execute. */
public record FilterImportPreview(
        List<String> mainLines,
        List<String> invertedLines,
        String defaultPrimaryName,
        String defaultSecondaryName,
        boolean needsSecondCopier) {

    public boolean isEmpty() {
        return mainLines.isEmpty() && invertedLines.isEmpty();
    }
}
