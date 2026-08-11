package net.unfamily.another_dynamics.inventory;

import java.util.ArrayList;
import java.util.List;

import net.unfamily.another_dynamics.duct.DuctDirectionalEndpoint;
import net.unfamily.another_dynamics.duct.DuctFaceNode;
import net.unfamily.another_dynamics.duct.DuctFilterRemoteNodeLogic;

import org.jetbrains.annotations.Nullable;

/** Client-side filter list mirrors for one transport kind (three filter banks). */
final class ClientFilterLaneMirror {
    private final List<String> allowFiltersExtractor = new ArrayList<>();
    private final List<String> denyFiltersExtractor = new ArrayList<>();
    private final List<String> allowFiltersRetriever = new ArrayList<>();
    private final List<String> denyFiltersRetriever = new ArrayList<>();
    private final List<String> allowFiltersFilter = new ArrayList<>();
    private final List<String> denyFiltersFilter = new ArrayList<>();
    private final List<Integer> allowCapsExtractor = new ArrayList<>();
    private final List<Integer> allowCapsExtractorLimit = new ArrayList<>();
    private final List<Integer> allowCapsRetriever = new ArrayList<>();
    private final List<Integer> allowCapsFilter = new ArrayList<>();
    private final List<Integer> allowCapsFilterKeep = new ArrayList<>();
    private final List<Integer> allowConcatExtractor = new ArrayList<>();
    private final List<Integer> denyConcatExtractor = new ArrayList<>();
    private final List<Integer> allowConcatRetriever = new ArrayList<>();
    private final List<Integer> denyConcatRetriever = new ArrayList<>();
    private final List<Integer> allowConcatFilter = new ArrayList<>();
    private final List<Integer> denyConcatFilter = new ArrayList<>();
    private final List<@Nullable DuctDirectionalEndpoint> allowRemoteExtractor = new ArrayList<>();
    private final List<@Nullable DuctDirectionalEndpoint> denyRemoteExtractor = new ArrayList<>();
    private final List<@Nullable DuctDirectionalEndpoint> allowRemoteRetriever = new ArrayList<>();
    private final List<@Nullable DuctDirectionalEndpoint> denyRemoteRetriever = new ArrayList<>();
    private final List<@Nullable DuctDirectionalEndpoint> allowRemoteFilter = new ArrayList<>();
    private final List<@Nullable DuctDirectionalEndpoint> denyRemoteFilter = new ArrayList<>();
    private final List<Boolean> allowRemoteIgnoreChannelExtractor = new ArrayList<>();
    private final List<Boolean> denyRemoteIgnoreChannelExtractor = new ArrayList<>();
    private final List<Boolean> allowRemoteIgnoreChannelRetriever = new ArrayList<>();
    private final List<Boolean> denyRemoteIgnoreChannelRetriever = new ArrayList<>();
    private final List<Boolean> allowRemoteIgnoreChannelFilter = new ArrayList<>();
    private final List<Boolean> denyRemoteIgnoreChannelFilter = new ArrayList<>();
    private final List<Boolean> allowRemoteAnyFaceExtractor = new ArrayList<>();
    private final List<Boolean> denyRemoteAnyFaceExtractor = new ArrayList<>();
    private final List<Boolean> allowRemoteAnyFaceRetriever = new ArrayList<>();
    private final List<Boolean> denyRemoteAnyFaceRetriever = new ArrayList<>();
    private final List<Boolean> allowRemoteAnyFaceFilter = new ArrayList<>();
    private final List<Boolean> denyRemoteAnyFaceFilter = new ArrayList<>();
    private boolean denyOverridesAllowExtractor = true;
    private boolean denyOverridesAllowRetriever = true;
    private boolean denyOverridesAllowFilter = true;

    List<String> allowFilters(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowFiltersExtractor;
            case RETRIEVER -> allowFiltersRetriever;
            case FILTER -> allowFiltersFilter;
        };
    }

    List<String> denyFilters(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyFiltersExtractor;
            case RETRIEVER -> denyFiltersRetriever;
            case FILTER -> denyFiltersFilter;
        };
    }

    boolean denyOverridesAllow(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyOverridesAllowExtractor;
            case RETRIEVER -> denyOverridesAllowRetriever;
            case FILTER -> denyOverridesAllowFilter;
        };
    }

    void setDenyOverridesAllow(DuctFaceNode.FilterBank bank, boolean value) {
        switch (bank) {
            case EXTRACTOR -> denyOverridesAllowExtractor = value;
            case RETRIEVER -> denyOverridesAllowRetriever = value;
            case FILTER -> denyOverridesAllowFilter = value;
        }
    }

    List<Integer> allowCaps(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowCapsExtractor;
            case RETRIEVER -> allowCapsRetriever;
            case FILTER -> allowCapsFilter;
        };
    }

    List<Integer> filterKeepCaps() {
        return allowCapsFilterKeep;
    }

    List<Integer> extractorLimitCaps() {
        return allowCapsExtractorLimit;
    }

    /** Secondary caps: FILTER Keep or EXTRACTOR Insert Limit. */
    @Nullable
    List<Integer> allowCaps2(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case FILTER -> allowCapsFilterKeep;
            case EXTRACTOR -> allowCapsExtractorLimit;
            case RETRIEVER -> null;
        };
    }

    List<Integer> allowConcat(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowConcatExtractor;
            case RETRIEVER -> allowConcatRetriever;
            case FILTER -> allowConcatFilter;
        };
    }

    List<Integer> denyConcat(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyConcatExtractor;
            case RETRIEVER -> denyConcatRetriever;
            case FILTER -> denyConcatFilter;
        };
    }

    List<@Nullable DuctDirectionalEndpoint> allowRemote(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowRemoteExtractor;
            case RETRIEVER -> allowRemoteRetriever;
            case FILTER -> allowRemoteFilter;
        };
    }

    List<@Nullable DuctDirectionalEndpoint> denyRemote(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyRemoteExtractor;
            case RETRIEVER -> denyRemoteRetriever;
            case FILTER -> denyRemoteFilter;
        };
    }

    List<Boolean> allowRemoteIgnoreChannel(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowRemoteIgnoreChannelExtractor;
            case RETRIEVER -> allowRemoteIgnoreChannelRetriever;
            case FILTER -> allowRemoteIgnoreChannelFilter;
        };
    }

    List<Boolean> denyRemoteIgnoreChannel(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyRemoteIgnoreChannelExtractor;
            case RETRIEVER -> denyRemoteIgnoreChannelRetriever;
            case FILTER -> denyRemoteIgnoreChannelFilter;
        };
    }

    List<Boolean> allowRemoteAnyFace(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> allowRemoteAnyFaceExtractor;
            case RETRIEVER -> allowRemoteAnyFaceRetriever;
            case FILTER -> allowRemoteAnyFaceFilter;
        };
    }

    List<Boolean> denyRemoteAnyFace(DuctFaceNode.FilterBank bank) {
        return switch (bank) {
            case EXTRACTOR -> denyRemoteAnyFaceExtractor;
            case RETRIEVER -> denyRemoteAnyFaceRetriever;
            case FILTER -> denyRemoteAnyFaceFilter;
        };
    }

    void clampSizes(int maxAllow, int maxDeny) {
        clampList(allowFiltersExtractor, maxAllow);
        clampList(denyFiltersExtractor, maxDeny);
        clampList(allowFiltersRetriever, maxAllow);
        clampList(denyFiltersRetriever, maxDeny);
        clampList(allowFiltersFilter, maxAllow);
        clampList(denyFiltersFilter, maxDeny);
        clampIntList(allowCapsExtractor, maxAllow);
        clampIntList(allowCapsExtractorLimit, maxAllow);
        clampIntList(allowCapsRetriever, maxAllow);
        clampIntList(allowCapsFilter, maxAllow);
        clampIntList(allowCapsFilterKeep, maxAllow);
        clampIntList(allowConcatExtractor, maxAllow);
        clampIntList(denyConcatExtractor, maxDeny);
        clampIntList(allowConcatRetriever, maxAllow);
        clampIntList(denyConcatRetriever, maxDeny);
        clampIntList(allowConcatFilter, maxAllow);
        clampIntList(denyConcatFilter, maxDeny);
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteExtractor, maxAllow);
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteExtractor, maxDeny);
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteRetriever, maxAllow);
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteRetriever, maxDeny);
        DuctFilterRemoteNodeLogic.syncToLineSize(allowRemoteFilter, maxAllow);
        DuctFilterRemoteNodeLogic.syncToLineSize(denyRemoteFilter, maxDeny);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteIgnoreChannelExtractor, maxAllow);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteIgnoreChannelExtractor, maxDeny);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteIgnoreChannelRetriever, maxAllow);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteIgnoreChannelRetriever, maxDeny);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteIgnoreChannelFilter, maxAllow);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteIgnoreChannelFilter, maxDeny);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceExtractor, maxAllow);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceExtractor, maxDeny);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceRetriever, maxAllow);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceRetriever, maxDeny);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(allowRemoteAnyFaceFilter, maxAllow);
        DuctFilterRemoteNodeLogic.syncIgnoreChannelToLineSize(denyRemoteAnyFaceFilter, maxDeny);
    }

    private static void clampList(List<String> list, int max) {
        while (list.size() < max) {
            list.add("");
        }
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }

    private static void clampIntList(List<Integer> list, int max) {
        while (list.size() < max) {
            list.add(0);
        }
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }
}
