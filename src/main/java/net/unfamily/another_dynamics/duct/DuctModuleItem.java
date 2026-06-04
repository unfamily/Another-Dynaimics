package net.unfamily.another_dynamics.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.unfamily.another_dynamics.client.gui.ModuleUpgradeTooltip;
import net.unfamily.another_dynamics.duct.module.DuctModuleHelper;
import net.unfamily.another_dynamics.duct.module.ModuleDefinitionRegistry;

/** Physical item for a duct module; declaration id is stored in {@link net.unfamily.another_dynamics.registry.ModDataComponents#DUCT_MODULE_DECLARATION}. */
public final class DuctModuleItem extends Item {
    public DuctModuleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof DuctBlockEntity)) {
            return InteractionResult.PASS;
        }
        return DuctBlock.attemptShiftModuleEquip(
                context.getLevel(),
                context.getClickedPos(),
                context.getPlayer(),
                context.getItemInHand(),
                context.getHand(),
                context.getClickedFace(),
                context.getClickLocation());
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        DuctModuleHelper.resolvedDeclarationId(stack)
                .flatMap(ModuleDefinitionRegistry::get)
                .ifPresent(def -> {
                    List<Component> lines = new ArrayList<>();
                    ModuleUpgradeTooltip.appendStatLines(def, lines);
                    tooltipComponents.addAll(lines);
                });
    }
}
