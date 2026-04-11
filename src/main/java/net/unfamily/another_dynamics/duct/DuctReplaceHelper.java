package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.another_dynamics.duct.module.DuctModuleEffects;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

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
 */
public final class DuctReplaceHelper {
    private DuctReplaceHelper() {}

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
    public static ItemInteractionResult tryReplace(
            Player player,
            Level level,
            BlockPos pos,
            DuctBlockEntity ductBE,
            String newLogicalId,
            InteractionHand hand) {

        String currentLogicalId = ductBE.getLogicalDuctId();

        // Normalize and validate the incoming logical id.
        newLogicalId = DuctIds.normalize(newLogicalId);
        if (newLogicalId == null || newLogicalId.isEmpty()) {
            newLogicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }

        // Replacing with the same type is a no-op; treat as success so vanilla placement is still blocked.
        if (currentLogicalId.equals(newLogicalId)) {
            return ItemInteractionResult.SUCCESS;
        }

        Optional<DuctDefinition> newDefOpt = DuctDefinitionRegistry.getByLogicalId(newLogicalId);
        if (newDefOpt.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("another_dynamics.duct_replace.unknown_type"), true);
            return ItemInteractionResult.FAIL;
        }
        DuctDefinition newDef = newDefOpt.get();

        Optional<DuctDefinition> currentDefOpt = ductBE.ductDefinition();

        // --- Compatibility check 1: transport kinds ---
        EnumSet<DuctTransportKind> newKinds = newDef.enabledTransportKinds();
        EnumSet<DuctTransportKind> currentKinds = currentDefOpt
                .map(DuctDefinition::enabledTransportKinds)
                .orElse(EnumSet.of(DuctTransportKind.ITEM));

        if (!newKinds.containsAll(currentKinds)) {
            player.displayClientMessage(
                    Component.translatable("another_dynamics.duct_replace.incompatible_types"), true);
            return ItemInteractionResult.FAIL;
        }

        // --- Compatibility check 2 & 3: per-face module slots and filter banks ---
        int newModuleSlotCount = newDef.moduleSlotCount();
        for (Direction face : Direction.values()) {
            DuctFaceLanes lanes = ductBE.getFaceLanes(face);

            // Check module slots
            int usedModuleSlots = countUsedModuleSlots(lanes);
            if (usedModuleSlots > newModuleSlotCount) {
                player.displayClientMessage(
                        Component.translatable("another_dynamics.duct_replace.module_slots_lost"), true);
                return ItemInteractionResult.FAIL;
            }

            // Module bonuses stay identical since the installed modules don't change.
            DuctModuleEffects.FilterSlotBonuses bonuses = DuctModuleEffects.filterSlotBonuses(ductBE, face);
            NodeMode nodeMode = lanes.nodeMode;

            // Check item lane filters
            if (newKinds.contains(DuctTransportKind.ITEM)) {
                DuctItemTransportSpec newItemSpec = newDef.itemTransportOrFallback();
                if (!itemFiltersCompatible(lanes.item, newItemSpec, nodeMode, bonuses)) {
                    player.displayClientMessage(
                            Component.translatable("another_dynamics.duct_replace.filter_slots_lost"), true);
                    return ItemInteractionResult.FAIL;
                }
            }

            // Check fluid lane filters
            if (newKinds.contains(DuctTransportKind.FLUID)) {
                DuctFluidTransportSpec newFluidSpec = newDef.fluidTransportOrFallback();
                if (!fluidFiltersCompatible(lanes.fluid, newFluidSpec, nodeMode, bonuses)) {
                    player.displayClientMessage(
                            Component.translatable("another_dynamics.duct_replace.filter_slots_lost"), true);
                    return ItemInteractionResult.FAIL;
                }
            }

            // Check gas lane filters
            if (newKinds.contains(DuctTransportKind.GAS)) {
                DuctGasTransportSpec newGasSpec = newDef.gasTransportOrFallback();
                if (!gasFiltersCompatible(lanes.gas, newGasSpec, nodeMode, bonuses)) {
                    player.displayClientMessage(
                            Component.translatable("another_dynamics.duct_replace.filter_slots_lost"), true);
                    return ItemInteractionResult.FAIL;
                }
            }
        }

        // --- All checks passed: perform the replacement ---

        // Build the drop item for the old duct before changing the id.
        ItemStack oldDuctItem = new ItemStack(level.getBlockState(pos).getBlock().asItem());
        oldDuctItem.set(ModDataComponents.DUCT_LOGICAL_ID.get(), currentLogicalId);

        // Swap the logical id (this also resizes module slot handlers and marks dirty).
        ductBE.setLogicalDuctId(newLogicalId);

        // Clamp filter sizes to the new transport specs (may shrink lists that barely fit, but we
        // already verified nothing is actually lost above).
        ductBE.clampFaceFiltersToSpec();

        // Force an immediate sync packet to all clients watching this chunk so the new logical id
        // and model data reach the client before the next chunk tick.
        BlockState currentState = level.getBlockState(pos);
        level.blockEntityChanged(pos);
        level.sendBlockUpdated(pos, currentState, currentState, 3);

        // Consume one item from the player's hand.
        ItemStack handStack = player.getItemInHand(hand);
        if (!player.getAbilities().instabuild) {
            handStack.shrink(1);
        }

        // Return the old duct to the player or drop it.
        if (!player.getInventory().add(oldDuctItem)) {
            Block.popResource(level, pos, oldDuctItem);
        }

        // Play a placement sound as tactile feedback.
        level.playSound(
                null,
                pos,
                SoundEvents.IRON_TRAPDOOR_CLOSE,
                SoundSource.BLOCKS,
                0.5f,
                1.2f);

        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.translatable("another_dynamics.duct_replace.success"), true);
        }

        return ItemInteractionResult.CONSUME;
    }

    // --- Helpers ---

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

    /**
     * Returns true when the item lane's current filter entry counts all fit within the new spec's
     * effective capacities (base + existing module bonuses).
     */
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

    /**
     * Checks that none of a node's filter lists exceed the given capacity thresholds.
     *
     * @param legacyA capacity for the legacy root allow list
     * @param legacyD capacity for the legacy root deny list
     * @param bankA   capacity for each multi-bank allow list (EXTRACTOR/RETRIEVER/FILTER)
     * @param bankD   capacity for each multi-bank deny list
     */
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

    /** Returns true when the list's occupied entries (non-blank strings) fit within {@code capacity}. */
    private static boolean fitsIn(List<String> list, int capacity) {
        long nonEmpty = list.stream().filter(s -> s != null && !s.isBlank()).count();
        return nonEmpty <= capacity;
    }
}
