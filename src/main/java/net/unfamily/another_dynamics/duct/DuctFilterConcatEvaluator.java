package net.unfamily.another_dynamics.duct;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Concatenated filter lines: same {@link FilterConcatChannel} letter on one list (allow or deny) must all match (AND);
 * groups and standalone lines combine with OR when evaluating {@code matchesAny}.
 */
public final class DuctFilterConcatEvaluator {
    @FunctionalInterface
    public interface LineMatcher {
        boolean matchesLine(int lineIndex, String trimmedLine);
    }

    private DuctFilterConcatEvaluator() {}

    public static boolean matchesAny(List<String> lines, List<Integer> concatChannels, LineMatcher matcher) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        Set<Integer> consumedConcat = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            int ch = FilterConcatChannel.channelAt(concatChannels, i);
            if (ch == 0) {
                if (matcher.matchesLine(i, raw.trim())) {
                    return true;
                }
            } else if (!consumedConcat.contains(ch)) {
                if (matchesConcatGroup(lines, concatChannels, ch, matcher)) {
                    consumedConcat.add(ch);
                    return true;
                }
            }
        }
        return false;
    }

    /** First line index of the first matching unit (standalone line or concat group head). */
    public static int firstMatchingLineIndex(
            List<String> lines, List<Integer> concatChannels, LineMatcher matcher) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        Set<Integer> consumedConcat = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            int ch = FilterConcatChannel.channelAt(concatChannels, i);
            if (ch == 0) {
                if (matcher.matchesLine(i, raw.trim())) {
                    return i;
                }
            } else if (!consumedConcat.contains(ch)) {
                int head = firstIndexOfChannel(lines, concatChannels, ch);
                if (head >= 0 && matchesConcatGroup(lines, concatChannels, ch, matcher)) {
                    consumedConcat.add(ch);
                    return head;
                }
            }
        }
        return -1;
    }

    private static int firstIndexOfChannel(List<String> lines, List<Integer> concatChannels, int channel) {
        for (int i = 0; i < lines.size(); i++) {
            if (FilterConcatChannel.channelAt(concatChannels, i) == channel) {
                String raw = lines.get(i);
                if (raw != null && !raw.trim().isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean matchesConcatGroup(
            List<String> lines, List<Integer> concatChannels, int channel, LineMatcher matcher) {
        boolean anyInGroup = false;
        for (int i = 0; i < lines.size(); i++) {
            if (FilterConcatChannel.channelAt(concatChannels, i) != channel) {
                continue;
            }
            String raw = lines.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            anyInGroup = true;
            if (!matcher.matchesLine(i, raw.trim())) {
                return false;
            }
        }
        return anyInGroup;
    }
}
