package net.unfamily.another_dynamics.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.unfamily.another_dynamics.duct.AbstractDuctBlock;
import net.unfamily.another_dynamics.duct.DuctBlock;
import net.minecraft.nbt.CompoundTag;
import net.unfamily.another_dynamics.duct.settings.DuctFaceSettingsSnapshot;
import net.unfamily.another_dynamics.registry.ModDataComponents;
import net.unfamily.another_dynamics.registry.ModItems;

import java.util.List;

/** Copies duct face node settings from the GUI copy slot; paste with shift-click on a storage node face. */
public class SettingsCopierItem extends Item {
    public SettingsCopierItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockState(pos).getBlock() instanceof AbstractDuctBlock)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        BlockHitResult hit =
                new BlockHitResult(context.getClickLocation(), context.getClickedFace(), pos, false);
        return DuctBlock.attemptSettingsCopierPaste(level, pos, player, stack, hit).result();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (DuctFaceSettingsSnapshot.hasStoredSettings(stack)) {
            tooltip.add(Component.translatable("item.another_dynamics.settings_copier.has_data"));
        } else {
            tooltip.add(Component.translatable("item.another_dynamics.settings_copier.empty"));
        }
    }

    /** Client copy-slot decoration: always uses the "full" ({@code settings_copier_1}) item model. */
    public static ItemStack copySlotDisplayStack() {
        ItemStack stack = new ItemStack(ModItems.SETTINGS_COPIER.get());
        CompoundTag marker = new CompoundTag();
        marker.putInt("Fmt", DuctFaceSettingsSnapshot.FORMAT_VERSION);
        stack.set(ModDataComponents.DUCT_FACE_SETTINGS, marker);
        return stack;
    }
}
