package net.unfamily.another_dynamics.duct;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.registry.ModBlockEntities;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Physical duct block. Hybrid ducts can join <strong>multiple</strong> {@link DuctNetworkType} graphs at the same time;
 * this build registers {@link DuctNetworkType#ITEM} only—add further types to {@link #ductNetworkTypes()} when implemented.
 * Block id in saves is {@code duct} ({@link net.unfamily.another_dynamics.registry.ModBlocks#DUCT}).
 */
public class DuctBlock extends AbstractDuctBlock {

    public DuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            if (level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof DuctBlockEntity be) {
                be.dropAllStalledItems(sl);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        // Physical block is universal; actual network membership is resolved from the BlockEntity's logical id
        // via DuctConnectable.isSameNetwork(level, pos, type).
        return EnumSet.allOf(DuctNetworkType.class);
    }

    @Override
    protected SoundType soundTypeForDuct() {
        return DuctSoundTypes.forLogicalDuct(DuctIds.DEFAULT_LOGICAL_ID);
    }

    @Override
    protected VoxelShape shapeForMasks(int pipeMask, int storageMask) {
        return DuctShapes.forMasks(pipeMask, storageMask);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DuctBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTicker(type);
    }

    @Nullable
    private static <T extends BlockEntity> BlockEntityTicker<T> createTicker(BlockEntityType<T> type) {
        return type == ModBlockEntities.DUCT.get()
                ? (level, pos, blockState, be) -> {
                    if (!level.isClientSide() && level instanceof ServerLevel sl) {
                        ((DuctBlockEntity) be).serverTickPipe(sl);
                    }
                }
                : null;
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof DuctBlockEntity be) {
            stack.set(ModDataComponents.DUCT_LOGICAL_ID.get(), be.getLogicalDuctId());
        }
        return stack;
    }

    private static Optional<Direction> nodeFaceFromHitLocation(BlockPos pos, BlockHitResult hit, DuctBlockEntity duct) {
        Vec3 l = hit.getLocation();
        double lx = l.x - pos.getX();
        double ly = l.y - pos.getY();
        double lz = l.z - pos.getZ();
        int settingsMask = duct.getSettingsFaceMask();
        Optional<Direction> fromShape =
                DuctShapes.resolveStorageNodeFace(duct.getPipeMask(), settingsMask, lx, ly, lz);
        if (fromShape.isPresent()) {
            return fromShape;
        }
        Direction hitFace = hit.getDirection();
        if ((settingsMask & (1 << hitFace.ordinal())) != 0) {
            return Optional.of(hitFace);
        }
        return Optional.empty();
    }

    private static double[] localHit(BlockPos pos, BlockHitResult hit) {
        Vec3 l = hit.getLocation();
        return new double[] {l.x - pos.getX(), l.y - pos.getY(), l.z - pos.getZ()};
    }

    /**
     * Shift+click paste from a Settings Copier. {@link ItemInteractionResult#PASS_TO_DEFAULT_BLOCK_INTERACTION}
     * when the copier is empty or no storage/settings face was targeted.
     */
    public static ItemInteractionResult attemptSettingsCopierPaste(
            Level level,
            BlockPos pos,
            Player player,
            ItemStack usedStack,
            BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof DuctBlockEntity duct)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        Optional<ItemStack> copierOpt = DuctFaceSettingsSnapshot.findCopierWithData(player, usedStack);
        if (copierOpt.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        Optional<Direction> nodeFace = nodeFaceFromHitLocation(pos, hit, duct);
        if (nodeFace.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (level instanceof ServerLevel sl) {
            ItemStack copier = copierOpt.get();
            var data = DuctFaceSettingsSnapshot.readFromCopier(copier);
            if (data.isPresent()
                    && DuctFaceSettingsSnapshot.apply(
                            duct, nodeFace.get(), data.get(), sl.registryAccess(), player)) {
                SettingsCopierFeedback.notifyPasted(player);
                return ItemInteractionResult.CONSUME;
            }
            SettingsCopierFeedback.notifyPasteFailed(player);
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof DuctBlockEntity duct) {
            if (!level.isClientSide()) {
                duct.refreshFromWorld();
            }
            Optional<Direction> face = nodeFaceFromHitLocation(pos, hit, duct);
            if (face.isEmpty()) {
                return InteractionResult.PASS;
            }
            if (player.isShiftKeyDown()) {
                if (level.isClientSide()) {
                    return duct.hasAnyStallOnFace(face.get()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
                }
                if (level instanceof ServerLevel sl) {
                    return duct.tryShiftClearStalledOnFace(sl, face.get(), player, InteractionHand.MAIN_HAND)
                            ? InteractionResult.CONSUME
                            : InteractionResult.PASS;
                }
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                openDuctMenu(serverPlayer, duct, face.get());
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof DuctBlockEntity duct) {
            double[] loc = localHit(pos, hitResult);
            double lx = loc[0];
            double ly = loc[1];
            double lz = loc[2];
            if (!level.isClientSide()) {
                duct.refreshFromWorld();
            }
            if (player.isShiftKeyDown() && stack.getItem() instanceof SettingsCopierItem) {
                ItemInteractionResult copierResult =
                        attemptSettingsCopierPaste(level, pos, player, stack, hitResult);
                if (copierResult != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                    return copierResult;
                }
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            Optional<Direction> nodeFace = nodeFaceFromHitLocation(pos, hitResult, duct);
            if (player.isShiftKeyDown() && nodeFace.isPresent() && duct.hasAnyStallOnFace(nodeFace.get())) {
                if (level.isClientSide()) {
                    return ItemInteractionResult.SUCCESS;
                }
                if (level instanceof ServerLevel sl) {
                    return duct.tryShiftClearStalledOnFace(sl, nodeFace.get(), player, hand)
                            ? ItemInteractionResult.CONSUME
                            : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                }
            }
            if (DuctWrenchTags.isWrench(stack)) {
                Optional<Direction> wFace =
                        DuctShapes.resolveWrenchDisconnectFace(
                                duct.getPipeMask(), duct.getVisualStorageMask(), lx, ly, lz);
                if (wFace.isPresent()) {
                    if (level.isClientSide()) {
                        return ItemInteractionResult.SUCCESS;
                    }
                    duct.applyWrenchDisconnect(level, wFace.get());
                    return ItemInteractionResult.CONSUME;
                }
                if (!player.isShiftKeyDown()
                        && DuctShapes.canReconnectFromCoreHit(
                                duct.getPipeMask(),
                                duct.getVisualStorageMask(),
                                duct.getUserDisconnectedFaceMask(),
                                lx,
                                ly,
                                lz,
                                hitResult.getDirection())) {
                    if (level.isClientSide()) {
                        return ItemInteractionResult.SUCCESS;
                    }
                    duct.tryReconnectFace(level, hitResult.getDirection());
                    return ItemInteractionResult.CONSUME;
                }
            }
            if (player.isShiftKeyDown()) {
                if (!DuctReplaceHelper.isDuctReplacementCandidate(stack)) {
                    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                }
                if (DuctReplaceHelper.isSameDuctType(duct, stack)) {
                    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                }
                if (level.isClientSide()) {
                    return ItemInteractionResult.SUCCESS;
                }
                String newLogicalId = DuctReplaceHelper.logicalIdFromReplacementItem(stack);
                if (newLogicalId == null) {
                    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                }
                return DuctReplaceHelper.tryReplace(player, level, pos, duct, newLogicalId, hand);
            }
            Optional<Direction> face = nodeFaceFromHitLocation(pos, hitResult, duct);
            if (face.isEmpty()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!player.isShiftKeyDown()
                    && duct.faceHasStalledFluidOrGas(face.get())
                    && duct.heldItemCanExtractStalledMedia(stack)) {
                if (level.isClientSide()) {
                    return ItemInteractionResult.SUCCESS;
                }
                if (level instanceof ServerLevel sl
                        && duct.tryExtractStalledMediaToHand(sl, face.get(), player, hand)) {
                    return ItemInteractionResult.CONSUME;
                }
            }
            if (player.isShiftKeyDown()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (level.isClientSide()) {
                return ItemInteractionResult.SUCCESS;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                openDuctMenu(serverPlayer, duct, face.get());
            }
            return ItemInteractionResult.CONSUME;
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    private static void openDuctMenu(ServerPlayer player, DuctBlockEntity duct, Direction clickedFace) {
        duct.prepareMenuOpenState();
        // Must run before constructing {@link net.unfamily.another_dynamics.inventory.DuctMenuActiveLaneSlots}:
        // menu slot count comes from the datapack definition, while face {@code moduleSlots} may still be the
        // previous size until resized (otherwise {@code broadcastChanges} throws "Slot N not in valid range").
        duct.ensureFaceLaneModuleSlotCapacitiesMatchDefinition();
        player.openMenu(
                new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return duct.getScreenTitle();
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int containerId, Inventory inv, Player p) {
                        return new DuctNodeMenu(containerId, inv, duct, clickedFace);
                    }
                },
                buf -> {
                    buf.writeBlockPos(duct.getBlockPos());
                    buf.writeByte(clickedFace.ordinal());
                    buf.writeBoolean(duct.ductAlwaysOpaqueRendering());
                    buf.writeUtf(duct.getLogicalDuctId());
                    buf.writeByte(duct.menuUiLayer());
                    buf.writeByte(duct.moduleSlotCountForMenu());
                });
    }
}
