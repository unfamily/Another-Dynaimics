package net.unfamily.another_dynamics.duct;

import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Gas ("chemical") allow/deny filtering for a node and bank.
 */
public final class DuctGasFilterLogic {
    private DuctGasFilterLogic() {}

    public static boolean passesGasFiltersForBank(DuctFaceNode node, DuctFaceNode.FilterBank bank, Object chemicalStack, ServerLevel level) {
        if (node == null || bank == null) {
            return false;
        }
        if (chemicalStack == null) {
            return false;
        }

        // Deny always blocks.
        List<String> deny = node.bankDenyFilters(bank);
        if (deny != null) {
            for (String line : deny) {
                if (DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, line, level.registryAccess())) {
                    return false;
                }
            }
        }

        // If allow list has any non-empty line, require at least one match.
        List<String> allow = node.bankAllowFilters(bank);
        boolean hasAllow = false;
        if (allow != null) {
            for (String line : allow) {
                if (line != null && !line.trim().isEmpty()) {
                    hasAllow = true;
                    break;
                }
            }
            if (hasAllow) {
                for (String line : allow) {
                    if (DuctGasFilterMatcher.matchesFilterEntry(chemicalStack, line, level.registryAccess())) {
                        return true;
                    }
                }
                return false;
            }
        }

        return true;
    }
}

