package net.unfamily.another_dynamics.duct;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.unfamily.another_dynamics.registry.ModDataComponents;

/**
 * Single physical item representing multiple logical duct types (via {@link ModDataComponents#DUCT_LOGICAL_ID}).
 */
public final class DuctBlockItem extends BlockItem {
    public DuctBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        // Must be explicit so the stack always serializes with a component payload (visible in /give, JEI, etc.).
        stack.set(ModDataComponents.DUCT_LOGICAL_ID.get(), DuctIds.DEFAULT_LOGICAL_ID);
        return stack;
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

