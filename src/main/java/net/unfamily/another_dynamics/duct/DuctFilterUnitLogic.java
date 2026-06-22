package net.unfamily.another_dynamics.duct;

import java.util.List;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.Nullable;

/** Ordered filter units (standalone line or concat AND group) for destination-aware routing. */
public final class DuctFilterUnitLogic {
    private DuctFilterUnitLogic() {}

    public record FilterUnit(int headIndex, List<Integer> lineIndices) {}

    public record UnitBinding(
            @Nullable DuctDirectionalEndpoint endpoint, boolean ignoreChannel, boolean anyFace) {}

    public record MatchedAllowUnit(FilterUnit unit, UnitBinding binding) {}

    public static List<FilterUnit> enumerateUnits(List<String> lines, List<Integer> concatChannels) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<FilterUnit> units = new java.util.ArrayList<>();
        java.util.HashSet<Integer> consumedConcat = new java.util.HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            int ch = FilterConcatChannel.channelAt(concatChannels, i);
            if (ch == 0) {
                units.add(new FilterUnit(i, List.of(i)));
            } else if (!consumedConcat.contains(ch)) {
                consumedConcat.add(ch);
                java.util.ArrayList<Integer> indices = new java.util.ArrayList<>();
                for (int j = 0; j < lines.size(); j++) {
                    if (FilterConcatChannel.channelAt(concatChannels, j) != ch) {
                        continue;
                    }
                    String r = lines.get(j);
                    if (r != null && !r.trim().isEmpty()) {
                        indices.add(j);
                    }
                }
                if (!indices.isEmpty()) {
                    units.add(new FilterUnit(indices.getFirst(), List.copyOf(indices)));
                }
            }
        }
        return units;
    }

    @Nullable
    public static UnitBinding unitBinding(
            FilterUnit unit,
            List<DuctDirectionalEndpoint> remoteNodes,
            List<Boolean> ignoreChannelFlags) {
        return unitBinding(unit, remoteNodes, ignoreChannelFlags, null);
    }

    /** Returns consistent binding for a unit, or null when concat lines disagree on endpoint/ignore flags. */
    @Nullable
    public static UnitBinding unitBinding(
            FilterUnit unit,
            List<DuctDirectionalEndpoint> remoteNodes,
            List<Boolean> ignoreChannelFlags,
            @Nullable List<Boolean> anyFaceFlags) {
        DuctDirectionalEndpoint endpoint = null;
        boolean ignore = false;
        boolean anyFace = false;
        boolean seenBound = false;
        for (int idx : unit.lineIndices()) {
            DuctDirectionalEndpoint ep = DuctFilterRemoteNodeLogic.endpointAt(remoteNodes, idx);
            boolean ig = DuctFilterRemoteNodeLogic.ignoreChannelAt(ignoreChannelFlags, idx);
            boolean af = DuctFilterRemoteNodeLogic.anyFaceAt(anyFaceFlags, idx);
            if (ep == null) {
                if (seenBound) {
                    return null;
                }
                continue;
            }
            if (!seenBound) {
                endpoint = ep;
                ignore = ig;
                anyFace = af;
                seenBound = true;
            } else if (!java.util.Objects.equals(endpoint, ep) || ignore != ig || anyFace != af) {
                return null;
            }
        }
        return new UnitBinding(endpoint, ignore, anyFace);
    }

    public static boolean unitTextMatches(
            FilterUnit unit,
            List<String> lines,
            List<Integer> concatChannels,
            BiPredicate<Integer, String> lineMatches) {
        for (int idx : unit.lineIndices()) {
            String raw = lines.get(idx);
            if (raw == null || raw.trim().isEmpty()) {
                return false;
            }
            if (!lineMatches.test(idx, raw.trim())) {
                return false;
            }
        }
        return true;
    }

    /** True when bound endpoint matches counterparty, or unit is unbound. */
    public static boolean unitDestinationMatches(
            UnitBinding binding, @Nullable DuctDirectionalEndpoint counterparty) {
        if (binding.endpoint() == null) {
            return true;
        }
        if (counterparty == null) {
            return false;
        }
        if (binding.anyFace()) {
            return binding.endpoint().pos().equals(counterparty.pos());
        }
        return binding.endpoint().matches(counterparty.pos(), counterparty.face());
    }

    /**
     * Sequential allow/deny: walks units in list order; first unit whose text matches and destination gate passes
     * decides the bank outcome, then global precedence applies.
     */
    public static boolean evaluateSequentialPrecedence(
            boolean denyOverridesAllow,
            List<String> allowFilters,
            List<String> denyFilters,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> allowRemote,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowIgnoreChannel,
            List<Boolean> denyIgnoreChannel,
            @Nullable DuctDirectionalEndpoint counterparty,
            BiPredicate<Integer, String> allowLineMatches,
            BiPredicate<Integer, String> denyLineMatches) {
        return evaluateSequentialPrecedence(
                denyOverridesAllow,
                allowFilters,
                denyFilters,
                allowConcat,
                denyConcat,
                allowRemote,
                denyRemote,
                allowIgnoreChannel,
                denyIgnoreChannel,
                null,
                null,
                counterparty,
                allowLineMatches,
                denyLineMatches);
    }

    public static boolean evaluateSequentialPrecedence(
            boolean denyOverridesAllow,
            List<String> allowFilters,
            List<String> denyFilters,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> allowRemote,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowIgnoreChannel,
            List<Boolean> denyIgnoreChannel,
            @Nullable List<Boolean> allowAnyFace,
            @Nullable List<Boolean> denyAnyFace,
            @Nullable DuctDirectionalEndpoint counterparty,
            BiPredicate<Integer, String> allowLineMatches,
            BiPredicate<Integer, String> denyLineMatches) {
        boolean hasAllow = hasNonEmptyLine(allowFilters);
        boolean hasDeny = hasNonEmptyLine(denyFilters);
        if (!hasAllow && !hasDeny) {
            return true;
        }
        boolean allowHit =
                findFirstFullyMatchingUnit(
                        allowFilters,
                        allowConcat,
                        allowRemote,
                        allowIgnoreChannel,
                        allowAnyFace,
                        counterparty,
                        allowLineMatches);
        boolean denyHit =
                findFirstFullyMatchingUnit(
                        denyFilters,
                        denyConcat,
                        denyRemote,
                        denyIgnoreChannel,
                        denyAnyFace,
                        counterparty,
                        denyLineMatches);
        if (denyOverridesAllow) {
            if (denyHit) {
                return false;
            }
            if (hasAllow && !allowHit) {
                return false;
            }
            return true;
        }
        if (hasAllow && allowHit) {
            return true;
        }
        if (denyHit) {
            return false;
        }
        if (hasAllow && !allowHit) {
            return false;
        }
        return true;
    }

    /** First allow unit whose filter text matches (used by extract/retrieve schedulers). */
    @Nullable
    public static MatchedAllowUnit firstMatchingAllowUnit(
            List<String> allowFilters,
            List<Integer> allowConcat,
            List<DuctDirectionalEndpoint> allowRemote,
            List<Boolean> allowIgnoreChannel,
            BiPredicate<Integer, String> allowLineMatches) {
        for (FilterUnit unit : enumerateUnits(allowFilters, allowConcat)) {
            UnitBinding binding = unitBinding(unit, allowRemote, allowIgnoreChannel);
            if (binding == null) {
                continue;
            }
            if (!unitTextMatches(unit, allowFilters, allowConcat, allowLineMatches)) {
                continue;
            }
            return new MatchedAllowUnit(unit, binding);
        }
        return null;
    }

    public static boolean denyBlocksAllowUnit(
            FilterUnit allowUnit,
            List<String> denyFilters,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> denyIgnoreChannel,
            @Nullable DuctDirectionalEndpoint counterparty,
            BiPredicate<Integer, String> denyLineMatches) {
        return denyBlocksAllowUnit(
                allowUnit,
                denyFilters,
                denyConcat,
                denyRemote,
                denyIgnoreChannel,
                null,
                counterparty,
                denyLineMatches);
    }

    public static boolean denyBlocksAllowUnit(
            FilterUnit allowUnit,
            List<String> denyFilters,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> denyIgnoreChannel,
            @Nullable List<Boolean> denyAnyFace,
            @Nullable DuctDirectionalEndpoint counterparty,
            BiPredicate<Integer, String> denyLineMatches) {
        for (FilterUnit du : enumerateUnits(denyFilters, denyConcat)) {
            UnitBinding db = unitBinding(du, denyRemote, denyIgnoreChannel, denyAnyFace);
            if (db == null) {
                continue;
            }
            if (!unitTextMatches(du, denyFilters, denyConcat, denyLineMatches)) {
                continue;
            }
            if (!unitDestinationMatches(db, counterparty)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean findFirstFullyMatchingUnit(
            List<String> lines,
            List<Integer> concat,
            List<DuctDirectionalEndpoint> remote,
            List<Boolean> ignoreChannel,
            @Nullable List<Boolean> anyFaceFlags,
            @Nullable DuctDirectionalEndpoint counterparty,
            BiPredicate<Integer, String> lineMatches) {
        for (FilterUnit unit : enumerateUnits(lines, concat)) {
            UnitBinding binding = unitBinding(unit, remote, ignoreChannel, anyFaceFlags);
            if (binding == null) {
                continue;
            }
            if (!unitTextMatches(unit, lines, concat, lineMatches)) {
                continue;
            }
            if (!unitDestinationMatches(binding, counterparty)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean hasNonEmptyLine(List<String> lines) {
        if (lines == null) {
            return false;
        }
        for (String s : lines) {
            if (s != null && !s.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
