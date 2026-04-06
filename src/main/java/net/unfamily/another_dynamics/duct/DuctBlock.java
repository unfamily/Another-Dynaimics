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
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Physical duct block. Hybrid ducts can join <strong>multiple</strong> {@link DuctNetworkType} graphs at the same time;
 * this build registers {@link DuctNetworkType#ITEM} only—add further types to {@link #ductNetworkTypes()} when implemented.
 * Block id in saves remains {@code item_duct} ({@link net.unfamily.another_dynamics.registry.ModBlocks#ITEM_DUCT}).
 */
public final class DuctBlock extends AbstractDuctBlock {

    public DuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public EnumSet<DuctNetworkType> ductNetworkTypes() {
        return EnumSet.of(DuctNetworkType.ITEM);
    }

    @Override
    protected SoundType soundTypeForDuct() {
        return DuctSoundTypes.forLogicalDuct(DuctIds.ITEM_DUCT);
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
        return type == ModBlockEntities.ITEM_DUCT.get()
                ? (level, pos, blockState, be) -> {
                    DuctBlockEntity duct = (DuctBlockEntity) be;
                    if (level.isClientSide()) {
                        DuctBlockEntity.clientTick(level, pos, blockState, duct);
                    } else if (level instanceof ServerLevel sl) {
                        duct.serverTickPipe(sl);
                    }
                }
                : null;
    }

    private static Optional<Direction> nodeFaceFromHitLocation(BlockPos pos, BlockHitResult hit, DuctBlockEntity duct) {
        Vec3 l = hit.getLocation();
        double lx = l.x - pos.getX();
        double ly = l.y - pos.getY();
        double lz = l.z - pos.getZ();
        return DuctShapes.resolveStorageNodeFace(duct.getPipeMask(), duct.getVisualStorageMask(), lx, ly, lz);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof DuctBlockEntity duct) {
            Optional<Direction> face = nodeFaceFromHitLocation(pos, hit, duct);
            if (face.isEmpty()) {
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
            Optional<Direction> face = nodeFaceFromHitLocation(pos, hitResult, duct);
            if (face.isEmpty()) {
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
                });
    }
}
