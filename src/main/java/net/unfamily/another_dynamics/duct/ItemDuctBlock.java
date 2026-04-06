package net.unfamily.another_dynamics.duct;

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

public final class ItemDuctBlock extends AbstractDuctBlock {

    public ItemDuctBlock(Properties properties) {
        super(properties);
    }

    @Override
    public DuctNetworkType ductNetworkType() {
        return DuctNetworkType.ITEM;
    }

    @Override
    protected SoundType soundTypeForDuct() {
        return DuctSoundTypes.forLogicalDuct(DuctIds.ITEM_DUCT);
    }

    @Override
    protected VoxelShape shapeForMasks(int pipeMask, int storageMask) {
        return ItemDuctShapes.forMasks(pipeMask, storageMask);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ItemDuctBlockEntity(pos, state);
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
                    ItemDuctBlockEntity duct = (ItemDuctBlockEntity) be;
                    if (level.isClientSide()) {
                        ItemDuctBlockEntity.clientTick(level, pos, blockState, duct);
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
            if (entity instanceof ItemDuctBlockEntity duct) {
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
            if (entity instanceof ItemDuctBlockEntity duct) {
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

    private static void openDuctMenu(ServerPlayer player, ItemDuctBlockEntity duct, Direction clickedFace) {
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
