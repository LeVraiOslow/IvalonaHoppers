package fr.chunkhopper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

public final class HopperListener implements Listener {

    private final ChunkHopperPlugin plugin;

    public HopperListener(ChunkHopperPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.HOPPER) return;

        if (!plugin.isChunkHopperItem(event.getItemInHand())) {
            plugin.unregister(block); // hopper normal pose a une ancienne position
            return;
        }
        if (!plugin.canPlaceIn(block)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("max-reached", Map.of()));
            return;
        }
        int level = plugin.itemLevel(event.getItemInHand());
        if (block.getState() instanceof Hopper hopper) {
            hopper.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
            hopper.getPersistentDataContainer().set(plugin.levelKey(), PersistentDataType.INTEGER, level);
            hopper.update();
        }
        plugin.register(block, level);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        plugin.unregister(event.getBlock());
    }

    /** Le hopper casse redonne un Chunk Hopper (et pas un hopper vanilla), avec son niveau. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        if (!(event.getBlockState() instanceof Hopper hopper)) return;
        if (!hopper.getPersistentDataContainer().has(plugin.key(), PersistentDataType.BYTE)) return;
        int level = plugin.keepLevelOnBreak() ? plugin.readLevel(hopper) : 1;
        for (Item item : event.getItems()) {
            if (item.getItemStack().getType() == Material.HOPPER) {
                item.setItemStack(plugin.createItem(1, level));
                break;
            }
        }
    }

    /** Ouvre le menu d'amelioration (maj + clic droit, main vide, selon la config). */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.HOPPER) return;
        if (plugin.levelOf(block) <= 0) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("chunkhopper.upgrade")) return;

        boolean plainClick = "RIGHT_CLICK".equalsIgnoreCase(plugin.getConfig().getString("menu.trigger", "SNEAK_RIGHT_CLICK"));
        boolean open = plainClick
                ? !player.isSneaking()
                : player.isSneaking() && player.getInventory().getItemInMainHand().getType().isAir();
        if (!open) return;

        event.setCancelled(true);
        UpgradeMenu.open(plugin, player, block);
    }

    /**
     * Vitesse de transfert : pour un Chunk Hopper ameliore, on remplace le transfert vanilla
     * (1 item) par un transfert de N items vers le conteneur situe en dessous / en face.
     * Le cooldown vanilla (8 ticks) est conserve car l'evenement est annule.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        if (source.getType() != InventoryType.HOPPER) return;

        Location loc = source.getLocation();
        if (loc == null) return;
        int level = plugin.levelAt(loc);
        if (level <= 0) return;

        int speed = plugin.levelData(level).speed();
        if (speed <= 1) return; // niveau 1 : comportement vanilla

        Inventory destination = event.getDestination();
        if (!plugin.isTransferTarget(destination.getType())) return;

        event.setCancelled(true);
        plugin.scheduleTransfer(source, destination, speed);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.scanChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.forgetChunk(event.getChunk());
    }
}
