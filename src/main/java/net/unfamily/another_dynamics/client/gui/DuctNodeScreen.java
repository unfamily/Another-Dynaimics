package net.unfamily.another_dynamics.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.unfamily.another_dynamics.inventory.DuctNodeMenu;

/** World duct node GUI. */
public final class DuctNodeScreen extends AbstractUniversalDuctScreen<DuctNodeMenu> {
    public DuctNodeScreen(DuctNodeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }
}
