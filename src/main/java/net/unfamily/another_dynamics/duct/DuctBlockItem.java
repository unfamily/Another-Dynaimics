package net.unfamily.another_dynamics.duct;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.unfamily.another_dynamics.client.DuctItemRenderer;
import net.unfamily.another_dynamics.duct.project.ProjectDuctBlock;
import net.unfamily.another_dynamics.duct.project.ProjectDuctConverter;
import net.unfamily.another_dynamics.item.ModItemTooltips;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import java.util.List;
import java.util.function.Consumer;

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
     * Routes item rendering through {@link DuctItemRenderer} (a custom
     * {@link BlockEntityWithoutLevelRenderer}) so that the texture is driven by
     * {@link ModDataComponents#DUCT_LOGICAL_ID} rather than a static baked model.
     * This is the same mechanism used by GeckoLib's {@code GeoItemRenderer}.
     */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private DuctItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.renderer == null) {
                    this.renderer = new DuctItemRenderer();
                }
                return this.renderer;
            }
        });
    }

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
                                    context.getPlayer(), level, pos, ductBE, newLogicalId, context.getHand())
                            .result();
                }
            } else if (state.getBlock() instanceof ProjectDuctBlock) {
                if (level.isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                return ProjectDuctConverter.tryConvertNetwork(
                                context.getPlayer(), level, pos, context.getItemInHand(), context.getHand())
                        .result();
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
            ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(ModItemTooltips.secondary("item.another_dynamics.duct.tooltip.summary"));
        tooltip.add(ModItemTooltips.ductTransportsLine(stack, defaultLogicalId));
        for (int i = 0; i < 3; i++) {
            tooltip.add(ModItemTooltips.important(OPAQUE_TOOLTIP_ROOT + i));
        }
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        String logicalId = stack.get(ModDataComponents.DUCT_LOGICAL_ID.get());
        if (logicalId == null || logicalId.isEmpty()) {
            logicalId = DuctIds.DEFAULT_LOGICAL_ID;
        }
        var def = DuctDefinitionRegistry.getByLogicalId(logicalId).orElse(null);
        if (def != null && def.translationKey().isPresent()) {
            return def.translationKey().get();
        }
        return super.getDescriptionId(stack);
    }
}

