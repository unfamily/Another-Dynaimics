package net.unfamily.another_dynamics.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.unfamily.another_dynamics.AnotherDynamicsMod;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnotherDynamicsMod.MOD_ID);

    public static final DeferredItem<BlockItem> ITEM_DUCT = ITEMS.register(
            "item_duct",
            () -> new BlockItem(ModBlocks.ITEM_DUCT.get(), new Item.Properties()));

    private ModItems() {}
}
