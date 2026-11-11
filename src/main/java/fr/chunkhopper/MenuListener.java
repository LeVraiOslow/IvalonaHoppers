package fr.chunkhopper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class MenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof UpgradeMenu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        if (event.getWhoClicked() instanceof Player player) {
            menu.handleClick(player, event.getSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof UpgradeMenu) {
            event.setCancelled(true);
        }
    }
}
