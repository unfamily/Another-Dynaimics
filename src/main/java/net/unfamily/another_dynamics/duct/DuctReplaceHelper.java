package net.unfamily.another_dynamics.duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.another_dynamics.Config;
import net.unfamily.another_dynamics.duct.logistics.DuctNetworkCache;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConvertJobs;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConverter;
import net.unfamily.another_dynamics.duct.project.ProjectDuctNetwork;
import net.unfamily.another_dynamics.duct.project.ProjectDuctVisualAdjacency;
import net.unfamily.another_dynamics.registry.ModDataComponents;
import net.unfamily.another_dynamics.registry.ModItems;

/**
 * Handles Shift+right-click replacement of a placed duct with a compatible duct item from the player's hand.
 *
 * <p>Compatibility rules (all must pass):
 * <ol>
 *   <li>The incoming duct's {@link DuctDefinition#enabledTransportKinds()} must be a superset of the
 *       installed duct's kinds — no transport capability may be removed.</li>
 *   <li>For every face: used module slots must fit within the new {@link DuctDefinition#moduleSlotCount()}.</li>
 *   <li>For every face × lane (item/fluid/gas): every filter bank's current entry count must not exceed
 *       the new effective capacity (new transport spec + existing module bonuses).</li>
 * </ol>
 *
 * <p>Triple-Shift on the same block within {@link DuctReplaceArm#ARM_TICKS}: (1) single replace, (2) project
 * ducts reachable via visual pipe links, (3) all compatible definitive ducts in the pipe component.
 */
public final class DuctReplaceHelper {
    private DuctReplaceHelper() {}

    public enum CompatFail {
        UNKNOWN_TYPE,
        INCOMPATIBLE_TYPES,
        MODULE_SLOTS,
        FILTER_SLOTS
    }

    public static boolean isDuctReplacementCandidate(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String id = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (id != null && !id.isEmpty()) {
            return true;
        }
        return stack.getItem() instanceof DuctBlockItem;
    }

    @org.jetbrains.annotations.Nullable
    public static String logicalIdFromReplacementItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        String id = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (id != null && !id.isEmpty()) {
            return DuctIds.normalize(id);
        }
        if (stack.getItem() instanceof DuctBlockItem ductItem) {
            id = ductItem.getDefaultInstance().get(ModDataComponents.DUCT_LOGICAL_ID.get());
            if (id != null && !id.isEmpty()) {
                return DuctIds.normalize(id);
            }
        }
        return null;
    }

    public static boolean isSameDuctType(DuctBlockEntity duct, ItemStack stack) {
        String incoming = logicalIdFromReplacementItem(stack);
        return incoming != null && duct.getLogicalDuctId().equals(incoming);
    }

    /**
     * Shift+duct item on a definitive duct: single replace, or armed stage 2/3 mass actions.
     * Same-type clicks are accepted while {@link DuctReplaceArm} matches so the triple-Shift chain works.
     */
    public static InteractionResult handleShiftReplace(
            Player player,
            Level level,
            BlockPos pos,
            DuctBlockEntity ductBE,
            ItemStack stack,
            InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        if (ProjectDuctConvertJobs.hasActiveJob(serverPlayer, serverLevel)
                || DuctReplaceJobs.hasActiveJob(serverPlayer, serverLevel)) {
            actionBar(serverPlayer, Component.translatable("another_dynamics.duct_replace.in_progress"));
            return InteractionResult.CONSUME;
        }

        String newLogicalId = logicalIdFromReplacementItem(stack);
        if (newLogicalId == null || newLogicalId.isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        newLogicalId = DuctIds.normalize(newLogicalId);

        DuctReplaceArm.Arm arm = DuctReplaceArm.matching(serverPlayer, serverLevel, pos, newLogicalId);
        if (arm != null && arm.stage() == 1) {
            return runStage2ProjectConvert(serverPlayer, serverLevel, pos, newLogicalId);
        }
        if (arm != null && arm.stage() == 2) {
            return runStage3MassReplace(serverPlayer, serverLevel, pos, ductBE, newLogicalId, hand);
        }

        String currentLogicalId = ductBE.getLogicalDuctId();
        // Already the target type: skip single-block replace; first Shift starts stage 2 (project), second does stage 3.
        if (currentLogicalId.equals(newLogicalId)) {
            return runStage2ProjectConvert(serverPlayer, serverLevel, pos, newLogicalId);
        }

        InteractionResult result = tryReplace(player, level, pos, ductBE, newLogicalId, hand);
        if (result == InteractionResult.CONSUME) {
            DuctReplaceArm.arm(serverPlayer, serverLevel, pos, newLogicalId, 1);
            actionBar(serverPlayer, Component.translatable("another_dynamics.duct_replace.arm_project_hint"));
        }
        return result;
    }

    private static InteractionResult runStage2ProjectConvert(
            ServerPlayer player, ServerLevel level, BlockPos anchor, String logicalId) {
        int maxJob = Config.projectDuctConvertMaxPerJob();
        List<BlockPos> discovered = discoverReachableProjectDucts(level, anchor, maxJob + 1);
        boolean moreBeyondJob = discovered.size() > maxJob;
        List<BlockPos> targets = moreBeyondJob ? discovered.subList(0, maxJob) : discovered;

        if (targets.isEmpty()) {
            actionBar(player, Component.translatable("another_dynamics.duct_replace.no_project_ducts"));
            DuctReplaceArm.arm(player, level, anchor, logicalId, 2);
            actionBar(player, Component.translatable("another_dynamics.duct_replace.arm_definitive_hint"));
            return InteractionResult.CONSUME;
        }

        if (!player.isCreative()) {
            ItemStack template = ModItems.createDuctStack(logicalId);
            if (ProjectDuctConverter.countMatchingItems(player, template) <= 0) {
                actionBar(player, Component.translatable(
                                "another_dynamics.project_duct.convert.partial", 0, targets.size()));
                return InteractionResult.FAIL;
            }
        }

        ProjectDuctConvertJobs.startAndRunFirstBatch(
                player, level, anchor, logicalId, targets, moreBeyondJob);
        long jobSlack =
                (long) Math.ceil((double) targets.size() / Math.max(1, Config.projectDuctConvertBatchSize()))
                        * Config.projectDuctConvertTickInterval();
        DuctReplaceArm.armUntil(
                player,
                level,
                anchor,
                logicalId,
                2,
                level.getGameTime() + DuctReplaceArm.ARM_TICKS + jobSlack);
        actionBar(player, Component.translatable("another_dynamics.duct_replace.arm_definitive_hint"));
        return InteractionResult.CONSUME;
    }

    private static InteractionResult runStage3MassReplace(
            ServerPlayer player,
            ServerLevel level,
            BlockPos anchor,
            DuctBlockEntity anchorBe,
            String logicalId,
            InteractionHand hand) {
        DuctReplaceArm.clear(player);
        int maxJob = Config.projectDuctConvertMaxPerJob();
        // Cheap candidate collect only (no isCompatible); compatibility is time-sliced in the job.
        List<BlockPos> candidates = collectDefinitiveCandidates(level, anchor, anchorBe, logicalId, maxJob + 1);
        if (candidates.isEmpty()) {
            actionBar(player, Component.translatable("another_dynamics.duct_replace.no_compatible_ducts"));
            return InteractionResult.CONSUME;
        }
        boolean started =
                DuctReplaceJobs.startDiscovering(player, level, anchor, logicalId, candidates, maxJob, hand);
        return started || DuctReplaceJobs.hasActiveJob(player, level)
                ? InteractionResult.CONSUME
                : InteractionResult.FAIL;
    }

    /**
     * Project ducts reachable from {@code start} walking visual pipe links through project and definitive ducts.
     */
    public static List<BlockPos> discoverReachableProjectDucts(Level level, BlockPos start, int maxCollect) {
        List<BlockPos> out = new ArrayList<>();
        if (level == null || start == null || maxCollect <= 0) {
            return out;
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && out.size() < maxCollect) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                if (!ProjectDuctVisualAdjacency.isVisualPipeLink(level, current, direction)) {
                    continue;
                }
                BlockPos neighbor = current.relative(direction);
                if (!seen.add(neighbor)) {
                    continue;
                }
                BlockState neighborState = level.getBlockState(neighbor);
                if (ProjectDuctNetwork.isProjectDuct(neighborState.getBlock())) {
                    out.add(neighbor.immutable());
                    queue.addLast(neighbor);
                    if (out.size() >= maxCollect) {
                        break;
                    }
                } else if (neighborState.getBlock() instanceof DuctConnectable) {
                    queue.addLast(neighbor);
                }
            }
        }
        return out;
    }

    /**
     * Network members with a different logical id than {@code targetLogicalId} (no filter/module compatibility yet).
     * Caps at {@code maxCollect} positions for the discovering job.
     */
    public static List<BlockPos> collectDefinitiveCandidates(
            ServerLevel level,
            BlockPos anchor,
            DuctBlockEntity anchorBe,
            String targetLogicalId,
            int maxCollect) {
        LinkedHashSet<BlockPos> union = new LinkedHashSet<>();
        EnumSet<DuctNetworkType> nets = EnumSet.noneOf(DuctNetworkType.class);
        anchorBe
                .ductDefinition()
                .map(DuctDefinition::enabledTransportKinds)
                .orElse(EnumSet.of(DuctTransportKind.ITEM))
                .forEach(
                        kind -> {
                            switch (kind) {
                                case ITEM -> nets.add(DuctNetworkType.ITEM);
                                case FLUID -> nets.add(DuctNetworkType.FLUID);
                                case GAS -> nets.add(DuctNetworkType.GAS);
                                case ENERGY -> nets.add(DuctNetworkType.ENERGY);
                                case HEAT -> nets.add(DuctNetworkType.HEAT);
                            }
                        });
        if (nets.isEmpty()) {
            nets.add(DuctNetworkType.ITEM);
        }
        for (DuctNetworkType net : nets) {
            union.addAll(DuctNetworkCache.connectedDucts(level, anchor, net));
        }
        List<BlockPos> out = new ArrayList<>();
        String target = DuctIds.normalize(targetLogicalId);
        for (BlockPos pos : union) {
            if (out.size() >= maxCollect) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                continue;
            }
            if (be.getLogicalDuctId().equals(target)) {
                continue;
            }
            out.add(pos.immutable());
        }
        return out;
    }

    /** Full sync discovery (compatibility included). Prefer {@link #collectDefinitiveCandidates} + job slices. */
    public static List<BlockPos> discoverCompatibleDefinitiveTargets(
            ServerLevel level,
            BlockPos anchor,
            DuctBlockEntity anchorBe,
            String targetLogicalId,
            int maxCollect) {
        List<BlockPos> out = new ArrayList<>();
        String target = DuctIds.normalize(targetLogicalId);
        for (BlockPos pos : collectDefinitiveCandidates(level, anchor, anchorBe, targetLogicalId, maxCollect)) {
            if (out.size() >= maxCollect) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof DuctBlockEntity be)) {
                continue;
            }
            if (isCompatible(be, target).isPresent()) {
                continue;
            }
            out.add(pos.immutable());
        }
        return out;
    }

    /** Empty = compatible; otherwise the first failure reason. */
    public static Optional<CompatFail> isCompatible(DuctBlockEntity ductBE, String newLogicalId) {
        newLogicalId = DuctIds.normalize(newLogicalId);
        if (newLogicalId == null || newLogicalId.isEmpty()) {
            newLogicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        Optional<DuctDefinition> newDefOpt = DuctDefinitionRegistry.getByLogicalId(newLogicalId);
        if (newDefOpt.isEmpty()) {
            return Optional.of(CompatFail.UNKNOWN_TYPE);
        }
        DuctDefinition newDef = newDefOpt.get();
        Optional<DuctDefinition> currentDefOpt = ductBE.ductDefinition();
        EnumSet<DuctTransportKind> newKinds = newDef.enabledTransportKinds();
        EnumSet<DuctTransportKind> currentKinds =
                currentDefOpt.map(DuctDefinition::enabledTransportKinds).orElse(EnumSet.of(DuctTransportKind.ITEM));
        if (!newKinds.containsAll(currentKinds)) {
            return Optional.of(CompatFail.INCOMPATIBLE_TYPES);
        }
        int newModuleSlotCount = newDef.moduleSlotCount();
        for (Direction face : Direction.values()) {
            DuctFaceLanes lanes = ductBE.getFaceLanes(face);
            if (countUsedModuleSlots(lanes) > newModuleSlotCount) {
                return Optional.of(CompatFail.MODULE_SLOTS);
            }
            DuctModuleEffects.FilterSlotBonuses bonuses = DuctModuleEffects.filterSlotBonuses(ductBE, face);
            NodeMode nodeMode = lanes.nodeMode;
            if (newKinds.contains(DuctTransportKind.ITEM)) {
                if (!itemFiltersCompatible(lanes.item, newDef.itemTransportOrFallback(), nodeMode, bonuses)) {
                    return Optional.of(CompatFail.FILTER_SLOTS);
                }
            }
            if (newKinds.contains(DuctTransportKind.FLUID)) {
                if (!fluidFiltersCompatible(lanes.fluid, newDef.fluidTransportOrFallback(), nodeMode, bonuses)) {
                    return Optional.of(CompatFail.FILTER_SLOTS);
                }
            }
            if (newKinds.contains(DuctTransportKind.GAS)) {
                if (!gasFiltersCompatible(lanes.gas, newDef.gasTransportOrFallback(), nodeMode, bonuses)) {
                    return Optional.of(CompatFail.FILTER_SLOTS);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Entry point called from {@link DuctBlock#useItemOn} on the server side only.
     *
     * @param player     the player performing the action
     * @param level      the server level
     * @param pos        position of the existing duct
     * @param ductBE     block entity of the existing duct
     * @param newLogicalId logical id carried by the item in the player's hand
     * @param hand       the hand holding the replacement item
     * @return the interaction result to return to the engine
     */
    public static InteractionResult tryReplace(
            Player player,
            Level level,
            BlockPos pos,
            DuctBlockEntity ductBE,
            String newLogicalId,
            InteractionHand hand) {

        String currentLogicalId = ductBE.getLogicalDuctId();

        newLogicalId = DuctIds.normalize(newLogicalId);
        if (newLogicalId == null || newLogicalId.isEmpty()) {
            newLogicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }

        if (currentLogicalId.equals(newLogicalId)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        Optional<CompatFail> fail = isCompatible(ductBE, newLogicalId);
        if (fail.isPresent()) {
            actionBar(player, Component.translatable(failMessageKey(fail.get())));
            return InteractionResult.FAIL;
        }

        if (!performReplace(player, level, pos, ductBE, currentLogicalId, newLogicalId, hand, true)) {
            return InteractionResult.FAIL;
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Swap logical id + inventory exchange. Returns false if the duct is already the target type.
     *
     * @param announceSuccess when true, shows the single-replace success toast (skipped for mass jobs / arm flow)
     */
    /**
     * Swap logical id + inventory exchange. Returns false if the duct is already the target type.
     *
     * @param announceSuccess when true, shows the single-replace success toast (skipped for mass jobs / arm flow)
     * @param deferVisualSync when true, skips per-block {@code sendBlockUpdated}/sound (batch job syncs quietly)
     */
    public static boolean performReplace(
            Player player,
            Level level,
            BlockPos pos,
            DuctBlockEntity ductBE,
            String currentLogicalId,
            String newLogicalId,
            InteractionHand hand,
            boolean announceSuccess) {
        return performReplace(
                player, level, pos, ductBE, currentLogicalId, newLogicalId, hand, announceSuccess, false);
    }

    public static boolean performReplace(
            Player player,
            Level level,
            BlockPos pos,
            DuctBlockEntity ductBE,
            String currentLogicalId,
            String newLogicalId,
            InteractionHand hand,
            boolean announceSuccess,
            boolean deferVisualSync) {
        newLogicalId = DuctIds.normalize(newLogicalId);
        currentLogicalId = DuctIds.normalize(currentLogicalId);
        if (currentLogicalId.equals(newLogicalId)) {
            return false;
        }
        if (isCompatible(ductBE, newLogicalId).isPresent()) {
            return false;
        }

        // Capture old item before changing logical id (survival give-back only).
        ItemStack oldDuctItem = ItemStack.EMPTY;
        if (!player.getAbilities().instabuild) {
            oldDuctItem = new ItemStack(level.getBlockState(pos).getBlock().asItem());
            oldDuctItem.set(ModDataComponents.DUCT_LOGICAL_ID.get(), currentLogicalId);
        }

        ductBE.setLogicalDuctId(newLogicalId);
        ductBE.clampFaceFiltersToSpec();

        BlockState currentState = level.getBlockState(pos);
        level.blockEntityChanged(pos);
        if (!deferVisualSync) {
            level.sendBlockUpdated(pos, currentState, currentState, 3);
        }

        ItemStack handStack = player.getItemInHand(hand);
        if (!player.getAbilities().instabuild) {
            handStack.shrink(1);
            if (!oldDuctItem.isEmpty()) {
                if (!player.getInventory().add(oldDuctItem)) {
                    Block.popResource(level, pos, oldDuctItem);
                }
            }
        }

        if (!deferVisualSync) {
            level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.5f, 1.2f);
        }

        if (announceSuccess && player instanceof ServerPlayer sp) {
            actionBar(sp, Component.translatable("another_dynamics.duct_replace.success"));
        }
        return true;
    }

    private static String failMessageKey(CompatFail fail) {
        return switch (fail) {
            case UNKNOWN_TYPE -> "another_dynamics.duct_replace.unknown_type";
            case INCOMPATIBLE_TYPES -> "another_dynamics.duct_replace.incompatible_types";
            case MODULE_SLOTS -> "another_dynamics.duct_replace.module_slots_lost";
            case FILTER_SLOTS -> "another_dynamics.duct_replace.filter_slots_lost";
        };
    }

    private static int countUsedModuleSlots(DuctFaceLanes lanes) {
        var handler = lanes.moduleSlots;
        int count = 0;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean itemFiltersCompatible(
            DuctFaceNode node,
            DuctItemTransportSpec newSpec,
            NodeMode nodeMode,
            DuctModuleEffects.FilterSlotBonuses bonuses) {
        int legacyA = Math.max(0, newSpec.filterAllowSlots() + bonuses.item().allowAdd());
        int legacyD = Math.max(0, newSpec.filterDenySlots() + bonuses.item().denyAdd());
        int bankA = DuctModuleEffects.effectiveItemAllowBank(newSpec, nodeMode, bonuses);
        int bankD = DuctModuleEffects.effectiveItemDenyBank(newSpec, nodeMode, bonuses);
        return checkAllBanks(node, legacyA, legacyD, bankA, bankD);
    }

    private static boolean fluidFiltersCompatible(
            DuctFaceNode node,
            DuctFluidTransportSpec newSpec,
            NodeMode nodeMode,
            DuctModuleEffects.FilterSlotBonuses bonuses) {
        int legacyA = Math.max(0, newSpec.filterAllowSlots() + bonuses.fluid().allowAdd());
        int legacyD = Math.max(0, newSpec.filterDenySlots() + bonuses.fluid().denyAdd());
        int bankA = DuctModuleEffects.effectiveFluidAllowBank(newSpec, nodeMode, bonuses);
        int bankD = DuctModuleEffects.effectiveFluidDenyBank(newSpec, nodeMode, bonuses);
        return checkAllBanks(node, legacyA, legacyD, bankA, bankD);
    }

    private static boolean gasFiltersCompatible(
            DuctFaceNode node,
            DuctGasTransportSpec newSpec,
            NodeMode nodeMode,
            DuctModuleEffects.FilterSlotBonuses bonuses) {
        int legacyA = Math.max(0, newSpec.filterAllowSlots() + bonuses.gas().allowAdd());
        int legacyD = Math.max(0, newSpec.filterDenySlots() + bonuses.gas().denyAdd());
        int bankA = DuctModuleEffects.effectiveGasAllowBank(newSpec, nodeMode, bonuses);
        int bankD = DuctModuleEffects.effectiveGasDenyBank(newSpec, nodeMode, bonuses);
        return checkAllBanks(node, legacyA, legacyD, bankA, bankD);
    }

    private static boolean checkAllBanks(
            DuctFaceNode node, int legacyA, int legacyD, int bankA, int bankD) {
        return fitsIn(node.allowFilters, legacyA)
                && fitsIn(node.denyFilters, legacyD)
                && fitsIn(node.allowFiltersExtractor, bankA)
                && fitsIn(node.denyFiltersExtractor, bankD)
                && fitsIn(node.allowFiltersRetriever, bankA)
                && fitsIn(node.denyFiltersRetriever, bankD)
                && fitsIn(node.allowFiltersFilter, bankA)
                && fitsIn(node.denyFiltersFilter, bankD);
    }

    private static boolean fitsIn(List<String> list, int capacity) {
        long nonEmpty = list.stream().filter(s -> s != null && !s.isBlank()).count();
        return nonEmpty <= capacity;
    }

    private static void actionBar(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true);
        } else {
            player.sendSystemMessage(message);
        }
    }
}
