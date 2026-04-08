package net.unfamily.another_dynamics.duct;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.unfamily.another_dynamics.client.DuctItemRenderer;
import net.unfamily.another_dynamics.registry.ModDataComponents;

import java.util.function.Consumer;

/**
 * Single physical item representing multiple logical duct types (via {@link ModDataComponents#DUCT_LOGICAL_ID}).
 */
public final class DuctBlockItem extends BlockItem {
    public DuctBlockItem(Block block, Properties properties) {
        super(block, properties);
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

