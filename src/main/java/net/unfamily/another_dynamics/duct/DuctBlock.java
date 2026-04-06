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
import net.minecraft.world.phys.shapes.VoxelShape;

import net.unfamily.another_dynamics.inventory.DuctNodeMenu;
import net.unfamily.another_dynamics.registry.ModBlockEntities;

import org.jetbrains.annotations.Nullable;

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

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof DuctBlockEntity duct) {
                Direction face = hit.getDirection();
                if ((duct.getStorageMask() & (1 << face.ordinal())) == 0) {
                    return InteractionResult.sidedSuccess(level.isClientSide());
                }
                openDuctMenu(serverPlayer, duct, face);
                return InteractionResult.CONSUME;
            }
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
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof DuctBlockEntity duct) {
                Direction face = hitResult.getDirection();
                if ((duct.getStorageMask() & (1 << face.ordinal())) == 0) {
                    return ItemInteractionResult.sidedSuccess(level.isClientSide());
                }
                openDuctMenu(serverPlayer, duct, face);
                return ItemInteractionResult.CONSUME;
            }
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
