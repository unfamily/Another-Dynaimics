package net.unfamily.another_dynamics.duct;

import java.util.List;

/**
 * Shared allow-bank checks for stall entry-first routing (item / fluid / gas).
 */
public final class DuctStallAllowBank {
    private DuctStallAllowBank() {}

    public static boolean hasNonEmptyAllowLines(List<String> lines, int cap) {
        int n = Math.min(lines.size(), cap);
        for (int i = 0; i < n; i++) {
            String s = lines.get(i);
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasStallRoutableAllowBank(
            List<String> allow, int allowCap, List<DuctDirectionalEndpoint> remote) {
        if (hasNonEmptyAllowLines(allow, allowCap)) {
            return true;
        }
        int n = Math.min(remote.size(), allowCap);
        for (int i = 0; i < n; i++) {
            if (remote.get(i) != null) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOnlyBoundAllowBank(
            List<String> allow, int allowCap, List<DuctDirectionalEndpoint> remote) {
        return hasStallRoutableAllowBank(allow, allowCap, remote) && !hasNonEmptyAllowLines(allow, allowCap);
    }
}
