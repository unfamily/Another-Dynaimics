package net.unfamily.another_dynamics.inventory;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.unfamily.another_dynamics.AnotherDynamicsMod;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctTransportKind;

/**
 * Verbose filter mirror / sync / push tracing. Enable with {@code dev.filterSyncDebug} in common config.
 */
public final class FilterSyncDebugLog {
    private FilterSyncDebugLog() {}

    public static boolean enabled() {
        return Config.FILTER_SYNC_DEBUG.get();
    }

    public static void log(String side, String event, String detail) {
        if (!enabled()) {
            return;
        }
        AnotherDynamicsMod.LOGGER.info("[FILTER-DBG][{}] {} {}", side, event, detail);
    }

    public static String transportKindName(int ordinal) {
        DuctTransportKind[] kinds = DuctTransportKind.values();
        if (ordinal < 0 || ordinal >= kinds.length) {
            return "tk?" + ordinal;
        }
        return kinds[ordinal].name() + "(" + ordinal + ")";
    }

    public static String bankName(int ordinal) {
        DuctFaceNode.FilterBank[] banks = DuctFaceNode.FilterBank.values();
        if (ordinal < 0 || ordinal >= banks.length) {
            return "bank?" + ordinal;
        }
        return banks[ordinal].name() + "(" + ordinal + ")";
    }

    public static String posFace(BlockPos pos, Direction face) {
        return "pos=" + pos + " face=" + face;
    }

    public static String listPreview(List<String> list) {
        if (list == null) {
            return "null";
        }
        if (list.isEmpty()) {
            return "[] (n=0)";
        }
        StringBuilder sb = new StringBuilder("[");
        int shown = Math.min(6, list.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String s = list.get(i);
            if (s == null) {
                sb.append("null");
            } else if (s.isEmpty()) {
                sb.append("\"\"");
            } else {
                sb.append(s.length() > 48 ? s.substring(0, 48) + "…" : s);
            }
        }
        if (list.size() > shown) {
            sb.append(", …+").append(list.size() - shown);
        }
        sb.append("] (n=").append(list.size()).append(")");
        return sb.toString();
    }

    public static String pushState(int transportKindOrdinal, boolean hydrated, boolean dirty, int bankMask) {
        return "tk="
                + transportKindName(transportKindOrdinal)
                + " hydrated="
                + hydrated
                + " dirty="
                + dirty
                + " bankMask=0b"
                + Integer.toBinaryString(bankMask);
    }

    public static String caller(int stackDepth) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int idx = stackDepth;
        if (idx < st.length) {
            StackTraceElement e = st[idx];
            return e.getClassName() + "#" + e.getMethodName() + ":" + e.getLineNumber();
        }
        return "?";
    }

    public static void clientReceiveSync(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            boolean skippedDirty) {
        if (!enabled()) {
            return;
        }
        log(
                "CLIENT",
                skippedDirty ? "RECEIVE_SYNC_SKIP_DIRTY" : "RECEIVE_SYNC_APPLY",
                posFace(pos, face)
                        + " "
                        + transportKindName(transportKindOrdinal)
                        + " "
                        + bankName(filterBankOrdinal)
                        + " allow="
                        + listPreview(allow)
                        + " deny="
                        + listPreview(deny)
                        + " from="
                        + caller(4));
    }

    public static void clientPush(
            String menuType,
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny,
            boolean editingAllowList) {
        if (!enabled()) {
            return;
        }
        log(
                "CLIENT",
                "PUSH",
                menuType
                        + " "
                        + posFace(pos, face)
                        + " "
                        + transportKindName(transportKindOrdinal)
                        + " "
                        + bankName(filterBankOrdinal)
                        + " allow="
                        + listPreview(allow)
                        + " deny="
                        + listPreview(deny)
                        + " allowCtx="
                        + editingAllowList
                        + " from="
                        + caller(4));
    }

    public static void clientPushState(String menuType, int transportKindOrdinal, boolean hydrated, boolean dirty, int bankMask, String reason) {
        if (!enabled()) {
            return;
        }
        log(
                "CLIENT",
                "STATE",
                menuType + " " + pushState(transportKindOrdinal, hydrated, dirty, bankMask) + " reason=" + reason);
    }

    public static void clientScreenAction(String action, int transportKindOrdinal, boolean hydrated, boolean dirty, String extra) {
        if (!enabled()) {
            return;
        }
        log(
                "CLIENT",
                "SCREEN",
                action
                        + " tk="
                        + transportKindName(transportKindOrdinal)
                        + " hydrated="
                        + hydrated
                        + " dirty="
                        + dirty
                        + (extra != null ? " " + extra : "")
                        + " from="
                        + caller(4));
    }

    public static void serverApply(
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allowIn,
            List<String> denyIn,
            List<String> storedAllow,
            List<String> storedDeny,
            String context) {
        if (!enabled()) {
            return;
        }
        log(
                "SERVER",
                "APPLY",
                context
                        + " "
                        + posFace(pos, face)
                        + " "
                        + transportKindName(transportKindOrdinal)
                        + " "
                        + bankName(filterBankOrdinal)
                        + " allowIn="
                        + listPreview(allowIn)
                        + " denyIn="
                        + listPreview(denyIn)
                        + " storedAllow="
                        + listPreview(storedAllow)
                        + " storedDeny="
                        + listPreview(storedDeny));
    }

    public static void serverSyncSend(
            String context,
            BlockPos pos,
            Direction face,
            int transportKindOrdinal,
            int filterBankOrdinal,
            List<String> allow,
            List<String> deny) {
        if (!enabled()) {
            return;
        }
        log(
                "SERVER",
                "SYNC_SEND",
                context
                        + " "
                        + posFace(pos, face)
                        + " "
                        + transportKindName(transportKindOrdinal)
                        + " "
                        + bankName(filterBankOrdinal)
                        + " allow="
                        + listPreview(allow)
                        + " deny="
                        + listPreview(deny));
    }

    public static void serverPacket(String packet, String detail) {
        if (!enabled()) {
            return;
        }
        log("SERVER", packet, detail);
    }

    public static void clientPacket(String packet, String detail) {
        if (!enabled()) {
            return;
        }
        log("CLIENT", packet, detail);
    }
}
