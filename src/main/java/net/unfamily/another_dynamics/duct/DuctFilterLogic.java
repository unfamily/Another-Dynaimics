package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Item filter precedence ({@link DuctFaceNode#denyOverridesAllow} per bank).
 * <p><strong>Material-lane family:</strong> keep in sync with {@link DuctFluidFilterLogic} and {@link DuctGasFilterLogic}
 * (and universal item/fluid/gas lanes).
 */
public final class DuctFilterLogic {
    private DuctFilterLogic() {}

    public static boolean passesItemFilters(DuctFaceNode node, ItemStack stack, Level level) {
        return passesItemFilters(node, stack, level, null);
    }

    public static boolean passesItemFilters(
            DuctFaceNode node, ItemStack stack, Level level, @Nullable DuctDirectionalEndpoint counterparty) {
        return passesItemFiltersWithConcat(
                node.denyOverridesAllow,
                node.allowFilters,
                node.denyFilters,
                node.allowConcatChannels,
                node.denyConcatChannels,
                node.allowRemoteNodes,
                node.denyRemoteNodes,
                node.allowRemoteIgnoreChannel,
                node.denyRemoteIgnoreChannel,
                node.allowRemoteAnyFace,
                node.denyRemoteAnyFace,
                stack,
                level,
                counterparty);
    }

    /** Evaluate allow/deny banks with concat groups and optional per-line destination bindings. */
    public static boolean passesItemFiltersWithConcat(
            boolean denyOverridesAllow,
            List<String> allowFilters,
            List<String> denyFilters,
            List<Integer> allowConcat,
            List<Integer> denyConcat,
            List<DuctDirectionalEndpoint> allowRemote,
            List<DuctDirectionalEndpoint> denyRemote,
            List<Boolean> allowIgnoreChannel,
            List<Boolean> denyIgnoreChannel,
            ItemStack stack,
            Level level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        return passesItemFiltersWithConcat(
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
                stack,
                level,
                counterparty);
    }

    public static boolean passesItemFiltersWithConcat(
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
            ItemStack stack,
            Level level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();

        if (counterparty != null) {
            return DuctFilterUnitLogic.evaluateSequentialPrecedence(
                    denyOverridesAllow,
                    allowFilters,
                    denyFilters,
                    allowConcat,
                    denyConcat,
                    allowRemote,
                    denyRemote,
                    allowIgnoreChannel,
                    denyIgnoreChannel,
                    allowAnyFace,
                    denyAnyFace,
                    counterparty,
                    (i, trimmed) ->
                            matchesLineWithConcat(
                                    allowFilters,
                                    allowConcat,
                                    allowRemote,
                                    counterparty,
                                    i,
                                    trimmed,
                                    stack,
                                    item,
                                    itemId,
                                    itemIdStr,
                                    itemModId,
                                    reg,
                                    false),
                    (i, trimmed) ->
                            matchesLineWithConcat(
                                    denyFilters,
                                    denyConcat,
                                    denyRemote,
                                    counterparty,
                                    i,
                                    trimmed,
                                    stack,
                                    item,
                                    itemId,
                                    itemIdStr,
                                    itemModId,
                                    reg,
                                    false));
        }

        boolean hasA =
                DuctFilterRemoteNodeLogic.hasAnyApplicableNonEmpty(allowFilters, allowRemote, counterparty);
        boolean hasD =
                DuctFilterRemoteNodeLogic.hasAnyApplicableNonEmpty(denyFilters, denyRemote, counterparty);
        if (!hasA && !hasD) {
            return true;
        }
        boolean A =
                hasA
                        && matchesAny(
                                allowFilters,
                                allowConcat,
                                allowRemote,
                                counterparty,
                                stack,
                                item,
                                itemId,
                                itemIdStr,
                                itemModId,
                                reg);
        boolean D =
                hasD
                        && matchesAny(
                                denyFilters,
                                denyConcat,
                                denyRemote,
                                counterparty,
                                stack,
                                item,
                                itemId,
                                itemIdStr,
                                itemModId,
                                reg);
        return applyPrecedence(denyOverridesAllow, hasA, hasD, A, D);
    }

    public static boolean itemMatchesAllowUnit(
            DuctFaceNode.FilterBank bank,
            DuctFaceNode node,
            DuctFilterUnitLogic.FilterUnit unit,
            ItemStack stack,
            Level level) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();
        List<String> allow = node.bankAllowFilters(bank);
        List<Integer> concat = node.bankAllowConcatChannels(bank);
        return DuctFilterUnitLogic.unitTextMatches(
                unit,
                allow,
                concat,
                (i, trimmed) ->
                        DuctFilterMatcher.matchesFilterEntry(
                                stack, item, itemId, itemIdStr, itemModId, trimmed, reg));
    }

    public static boolean itemMatchesDenyForAllowUnit(
            DuctFaceNode.FilterBank bank,
            DuctFaceNode node,
            DuctFilterUnitLogic.FilterUnit allowUnit,
            ItemStack stack,
            Level level,
            @Nullable DuctDirectionalEndpoint counterparty) {
        if (stack.isEmpty()) {
            return false;
        }
        HolderLookup.Provider reg = level.registryAccess();
        Item item = stack.getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String itemIdStr = itemId.toString();
        String itemModId = itemId.getNamespace();
        return DuctFilterUnitLogic.denyBlocksAllowUnit(
                allowUnit,
                node.bankDenyFilters(bank),
                node.bankDenyConcatChannels(bank),
                node.bankDenyRemoteNodes(bank),
                node.bankDenyRemoteIgnoreChannel(bank),
                counterparty,
                (i, trimmed) ->
                        DuctFilterMatcher.matchesFilterEntry(
                                stack, item, itemId, itemIdStr, itemModId, trimmed, reg));
    }

    private static boolean applyPrecedence(
            boolean denyOverridesAllow, boolean hasA, boolean hasD, boolean A, boolean D) {
        if (denyOverridesAllow) {
            if (D) {
                return false;
            }
            if (hasA && !A) {
                return false;
            }
            return true;
        }
        if (hasA && A) {
            return true;
        }
        if (D) {
            return false;
        }
        if (hasA && !A) {
            return false;
        }
        return true;
    }

    private static boolean matchesLineWithConcat(
            List<String> entries,
            List<Integer> concatChannels,
            List<DuctDirectionalEndpoint> destinations,
            @Nullable DuctDirectionalEndpoint counterparty,
            int index,
            String trimmed,
            ItemStack stack,
            Item item,
            ResourceLocation itemId,
            String itemIdStr,
            String itemModId,
            HolderLookup.Provider registries,
            boolean checkRemote) {
        if (checkRemote
                && !DuctFilterRemoteNodeLogic.lineApplicable(index, destinations, counterparty)) {
            return false;
        }
        return DuctFilterMatcher.matchesFilterEntry(
                stack, item, itemId, itemIdStr, itemModId, trimmed, registries);
    }

    private static boolean matchesAny(
            List<String> entries,
            List<Integer> concatChannels,
            List<DuctDirectionalEndpoint> destinations,
            @Nullable DuctDirectionalEndpoint counterparty,
            ItemStack stack,
            Item item,
            ResourceLocation itemId,
            String itemIdStr,
            String itemModId,
            HolderLookup.Provider registries) {
        return DuctFilterConcatEvaluator.matchesAny(
                entries,
                concatChannels,
                (i, trimmed) ->
                        matchesLineWithConcat(
                                entries,
                                concatChannels,
                                destinations,
                                counterparty,
                                i,
                                trimmed,
                                stack,
                                item,
                                itemId,
                                itemIdStr,
                                itemModId,
                                registries,
                                true));
    }

    public static int listHash(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String field : list) {
            sb.append(field != null ? field : "");
            sb.append('|');
        }
        return sb.toString().hashCode();
    }
}
