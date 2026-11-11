package fr.chunkhopper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Menu d'amelioration d'un Chunk Hopper. Tout est lu depuis config.yml (section menu). */
public final class UpgradeMenu implements InventoryHolder {

    private static final String STATE_UPGRADE = "upgrade";
    private static final String STATE_NO_MONEY = "upgrade-no-money";
    private static final String STATE_MAX = "max-level";

    private final ChunkHopperPlugin plugin;
    private final UUID worldId;
    private final int x, y, z;
    private Inventory inventory;
    private int displayedLevel;
    /** slot -> nom de la section de config (pour savoir sur quoi on clique). */
    private final Map<Integer, String> slotRoles = new HashMap<>();

    private UpgradeMenu(ChunkHopperPlugin plugin, Block block) {
        this.plugin = plugin;
        this.worldId = block.getWorld().getUID();
        this.x = block.getX();
        this.y = block.getY();
        this.z = block.getZ();
    }

    public static void open(ChunkHopperPlugin plugin, Player player, Block block) {
        UpgradeMenu menu = new UpgradeMenu(plugin, block);
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("menu.rows", 3)));
        Map<String, String> ph = menu.placeholders(player, plugin.levelOf(block));
        Component title = Text.parse(plugin.getConfig().getString("menu.title", "&8Amelioration"), ph);
        menu.inventory = Bukkit.createInventory(menu, rows * 9, title);
        menu.render(player);
        player.openInventory(menu.inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Block block() {
        World world = Bukkit.getWorld(worldId);
        return world == null ? null : world.getBlockAt(x, y, z);
    }

    private Map<String, String> placeholders(Player player, int level) {
        int max = plugin.maxLevel();
        int next = Math.min(level + 1, max);
        ChunkHopperPlugin.LevelData cur = plugin.levelData(level);
        ChunkHopperPlugin.LevelData nxt = plugin.levelData(next);
        double price = level >= max ? 0 : nxt.price();
        double balance = plugin.balance(player);
        Map<String, String> ph = new HashMap<>();
        ph.put("level", String.valueOf(level));
        ph.put("level_roman", Text.roman(level));
        ph.put("next_level", String.valueOf(next));
        ph.put("next_level_roman", Text.roman(next));
        ph.put("max_level", String.valueOf(max));
        ph.put("max_level_roman", Text.roman(max));
        ph.put("speed", String.valueOf(cur.speed()));
        ph.put("next_speed", String.valueOf(nxt.speed()));
        ph.put("price", plugin.formatMoney(price));
        ph.put("balance", plugin.formatMoney(balance));
        ph.put("missing", plugin.formatMoney(Math.max(0, price - balance)));
        return ph;
    }

    /** (Re)dessine tout le menu depuis la config. */
    public void render(Player player) {
        Block block = block();
        int level = block == null ? 0 : plugin.levelOf(block);
        if (level <= 0) {
            player.closeInventory();
            return;
        }
        displayedLevel = level;
        inventory.clear();
        slotRoles.clear();

        ConfigurationSection items = plugin.getConfig().getConfigurationSection("menu.items");
        if (items == null) return;

        int max = plugin.maxLevel();
        double price = level >= max ? 0 : plugin.levelData(level + 1).price();
        String state;
        if (level >= max) state = STATE_MAX;
        else if (plugin.canAfford(player, price)) state = STATE_UPGRADE;
        else state = items.isConfigurationSection(STATE_NO_MONEY) ? STATE_NO_MONEY : STATE_UPGRADE;

        Map<String, String> ph = placeholders(player, level);

        for (String name : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(name);
            if (sec == null || name.equals("filler")) continue;
            boolean isState = name.equals(STATE_UPGRADE) || name.equals(STATE_NO_MONEY) || name.equals(STATE_MAX);
            if (isState && !name.equals(state)) continue;
            // si max-level n'existe pas dans la config, on n'affiche rien a cet endroit
            ItemStack stack = ConfigItems.build(plugin, sec, ph);
            for (int slot : slots(sec)) {
                if (slot < 0 || slot >= inventory.getSize()) continue;
                inventory.setItem(slot, stack.clone());
                // les 3 etats du bouton partagent le role "upgrade"
                slotRoles.put(slot, isState ? STATE_UPGRADE : name);
            }
        }

        ConfigurationSection filler = items.getConfigurationSection("filler");
        if (filler != null && filler.getBoolean("enabled", true)) {
            ItemStack stack = ConfigItems.build(plugin, filler, ph);
            for (int i = 0; i < inventory.getSize(); i++) {
                if (inventory.getItem(i) == null) inventory.setItem(i, stack.clone());
            }
        }
    }

    /** "slot: 13", "slots: [0, 1, 2]" ou "slots: ['0-8', 17]". */
    private static List<Integer> slots(ConfigurationSection sec) {
        List<Integer> out = new ArrayList<>();
        if (sec.contains("slot")) out.add(sec.getInt("slot"));
        for (Object o : sec.getList("slots", List.of())) {
            if (o instanceof Number n) {
                out.add(n.intValue());
            } else if (o instanceof String s) {
                try {
                    if (s.contains("-")) {
                        String[] p = s.split("-", 2);
                        int a = Integer.parseInt(p[0].trim());
                        int b = Integer.parseInt(p[1].trim());
                        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) out.add(i);
                    } else {
                        out.add(Integer.parseInt(s.trim()));
                    }
                } catch (NumberFormatException ignored) {
                    // slot invalide : ignore
                }
            }
        }
        return out;
    }

    public void handleClick(Player player, int slot) {
        String role = slotRoles.get(slot);
        if (role == null) return;
        if (role.equals("close")) {
            player.closeInventory();
        } else if (role.equals(STATE_UPGRADE)) {
            attemptUpgrade(player);
        }
    }

    private void attemptUpgrade(Player player) {
        if (!player.hasPermission("chunkhopper.upgrade")) {
            player.sendMessage(plugin.message("no-permission", Map.of()));
            return;
        }
        Block block = block();
        int level = block == null ? 0 : plugin.levelOf(block);
        if (level <= 0) {
            player.sendMessage(plugin.message("hopper-gone", Map.of()));
            player.closeInventory();
            return;
        }
        // le niveau a change depuis l'affichage (autre joueur) : on rafraichit sans rien debiter
        if (level != displayedLevel) {
            render(player);
            return;
        }
        if (level >= plugin.maxLevel()) {
            player.sendMessage(plugin.message("max-level", Map.of()));
            plugin.playSound(player, "sounds.error");
            render(player);
            return;
        }

        double price = plugin.levelData(level + 1).price();
        if (price > 0) {
            if (!plugin.hasEconomy()) {
                player.sendMessage(plugin.message("no-economy", Map.of()));
                plugin.playSound(player, "sounds.error");
                return;
            }
            if (!plugin.canAfford(player, price) || !plugin.withdraw(player, price)) {
                player.sendMessage(plugin.message("no-money", placeholders(player, level)));
                plugin.playSound(player, "sounds.error");
                return;
            }
        }

        if (!plugin.setLevel(block, level + 1)) {
            // l'amelioration a echoue apres le paiement : on rembourse (pas de depot via Vault ici)
            player.sendMessage(plugin.message("hopper-gone", Map.of()));
            plugin.refund(player, price);
            player.closeInventory();
            return;
        }
        player.sendMessage(plugin.message("upgraded", placeholders(player, level + 1)));
        plugin.playSound(player, "sounds.upgrade");
        render(player);
    }
}
