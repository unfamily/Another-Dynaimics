package net.unfamily.another_dynamics.duct;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import java.util.function.Consumer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConverter;
import net.unfamily.another_dynamics.item.SettingsCopierItem;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Single physical item representing multiple logical duct types (via {@link ModDataComponents#DUCT_LOGICAL_ID}).
 */
public final class DuctBlockItem extends BlockItem {
    private static final String OPAQUE_TOOLTIP_ROOT = "item.another_dynamics.duct.opaque.desc.";

    private final String defaultLogicalId;

    public DuctBlockItem(Block block, Properties properties) {
        this(block, properties, DuctIds.DEFAULT_LOGICAL_ID);
    }

    public DuctBlockItem(Block block, Properties properties, String defaultLogicalId) {
        super(block, properties);
        this.defaultLogicalId =
                defaultLogicalId != null && !defaultLogicalId.isEmpty()
                        ? DuctIds.normalize(defaultLogicalId)
                        : DuctIds.DEFAULT_LOGICAL_ID;
    }

    /**
     * Item rendering uses {@link net.unfamily.another_dynamics.client.DuctItemSpecialRenderer}
     * via {@code assets/another_dynamics/items/*.json} special model definitions.
     */

    public String getDefaultLogicalId() {
        return defaultLogicalId;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() != null && context.getPlayer().isSecondaryUseActive()) {
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof AbstractDuctBlock) {
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity instanceof DuctBlockEntity ductBE) {
                    ItemStack stack = context.getItemInHand();
                    if (DuctReplaceHelper.isSameDuctType(ductBE, stack)) {
                        return super.useOn(context);
                    }
                    String newLogicalId = DuctReplaceHelper.logicalIdFromReplacementItem(stack);
                    if (newLogicalId == null) {
                        newLogicalId = defaultLogicalId;
                    }
                    if (level.isClientSide()) {
                        return InteractionResult.SUCCESS;
                    }
                    ductBE.refreshFromWorld();
                    return DuctReplaceHelper.tryReplace(
                            context.getPlayer(), level, pos, ductBE, newLogicalId, context.getHand());
                }
            } else if (state.getBlock() instanceof ProjectDuctBlock) {
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                return ProjectDuctConverter.tryConvertNetwork(
                        context.getPlayer(), level, pos, context.getItemInHand(), context.getHand());
            }
        }
        return super.useOn(context);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        stack.set(ModDataComponents.DUCT_LOGICAL_ID.get(), defaultLogicalId);
        return stack;
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        for (int i = 0; i < 3; i++) {
            tooltip.accept(SettingsCopierItem.grayTooltipLine(OPAQUE_TOOLTIP_ROOT + i));
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        String logicalId = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        var def = DuctDefinitionRegistry.getByLogicalId(logicalId).orElse(null);
        if (def != null && def.translationKey().isPresent()) {
            return Component.translatable(def.translationKey().get());
        }
        return super.getName(stack);
    }
}

