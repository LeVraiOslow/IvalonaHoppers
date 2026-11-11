package fr.chunkhopper;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class ChunkHopperPlugin extends JavaPlugin implements TabExecutor {

    public record ChunkKey(UUID world, int x, int z) {}
    public record BlockPos(int x, int y, int z) {}
    /** speed = nombre d'items deplaces par transfert ; price = cout pour atteindre ce niveau. */
    public record LevelData(int speed, double price) {}

    private NamespacedKey key;
    private NamespacedKey levelKey;
    private int maxPerChunk;
    private boolean keepLevelOnBreak;
    private int maxLevel = 1;
    private final TreeMap<Integer, LevelData> levels = new TreeMap<>();
    private final Set<InventoryType> transferTargets = EnumSet.noneOf(InventoryType.class);
    private VaultHook vault;

    /** Index des Chunk Hoppers (avec leur niveau) dans les chunks charges. */
    private final Map<ChunkKey, Map<BlockPos, Integer>> hoppers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "chunk_hopper");
        levelKey = new NamespacedKey(this, "level");
        loadSettings();

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            vault = new VaultHook(this);
        } else {
            getLogger().warning("Vault est absent : les ameliorations payantes sont desactivees.");
        }

        getServer().getPluginManager().registerEvents(new HopperListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        PluginCommand cmd = getCommand("chunkhopper");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunk(chunk);
            }
        }

        long interval = Math.max(1L, getConfig().getLong("collect-interval-ticks", 5L));
        getServer().getScheduler().runTaskTimer(this, this::collectAll, interval, interval);
    }

    /** Lit la config (appele au demarrage et par /chunkhopper reload). */
    private void loadSettings() {
        maxPerChunk = getConfig().getInt("max-per-chunk", 0);
        keepLevelOnBreak = getConfig().getBoolean("keep-level-on-break", true);

        levels.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("levels");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(k);
                if (s == null) continue;
                try {
                    int lvl = Integer.parseInt(k);
                    if (lvl < 1) continue;
                    levels.put(lvl, new LevelData(Math.max(1, s.getInt("speed", 1)), Math.max(0, s.getDouble("price", 0))));
                } catch (NumberFormatException ignored) {
                    getLogger().warning("Niveau invalide dans la config : " + k);
                }
            }
        }
        levels.putIfAbsent(1, new LevelData(1, 0));
        maxLevel = 1;
        while (levels.containsKey(maxLevel + 1)) maxLevel++;

        transferTargets.clear();
        for (String name : getConfig().getStringList("upgraded-transfer-targets")) {
            try {
                transferTargets.add(InventoryType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Type d'inventaire invalide : " + name);
            }
        }
    }

    // ------------------------------------------------------------------ item

    public NamespacedKey key() {
        return key;
    }

    public NamespacedKey levelKey() {
        return levelKey;
    }

    public boolean keepLevelOnBreak() {
        return keepLevelOnBreak;
    }

    public ItemStack createItem(int amount) {
        return createItem(amount, 1);
    }

    public ItemStack createItem(int amount, int level) {
        int lvl = Math.max(1, Math.min(level, maxLevel));
        Map<String, String> ph = Map.of(
                "level", String.valueOf(lvl),
                "level_roman", Text.roman(lvl),
                "speed", String.valueOf(levelData(lvl).speed()));

        ItemStack stack = new ItemStack(Material.HOPPER, amount);
        stack.editMeta(meta -> {
            meta.displayName(Text.parse(getConfig().getString("item.name", "<gold>Chunk Hopper"), ph));
            List<Component> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("item.lore")) {
                lore.add(Text.parse(line, ph));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, lvl);
        });
        return stack;
    }

    public boolean isChunkHopperItem(ItemStack stack) {
        return stack != null
                && stack.getType() == Material.HOPPER
                && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Niveau stocke dans l'item (1 si absent, ex. anciens Chunk Hoppers). */
    public int itemLevel(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return 1;
        Integer lvl = stack.getItemMeta().getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return clampLevel(lvl == null ? 1 : lvl);
    }

    /** Niveau stocke dans le hopper pose (1 si absent). */
    public int readLevel(Hopper hopper) {
        Integer lvl = hopper.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return clampLevel(lvl == null ? 1 : lvl);
    }

    private int clampLevel(int lvl) {
        return Math.max(1, Math.min(lvl, maxLevel));
    }

    public Component message(String path, Map<String, String> placeholders) {
        return Text.parse(getConfig().getString("messages." + path, path), placeholders);
    }

    // ---------------------------------------------------------------- levels

    public int maxLevel() {
        return maxLevel;
    }

    public LevelData levelData(int level) {
        LevelData data = levels.get(level);
        return data != null ? data : levels.get(1);
    }

    public boolean isTransferTarget(InventoryType type) {
        return transferTargets.contains(type);
    }

    /** Pose le niveau du hopper (PDC + index). Faux si ce n'est pas un Chunk Hopper. */
    public boolean setLevel(Block block, int level) {
        if (!(block.getState() instanceof Hopper hopper)) return false;
        if (!hopper.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return false;
        int lvl = clampLevel(level);
        hopper.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, lvl);
        hopper.update();
        register(block, lvl);
        return true;
    }

    /** Niveau du Chunk Hopper a cet endroit, 0 si ce n'en est pas un. */
    public int levelOf(Block block) {
        return levelAt(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public int levelAt(Location loc) {
        if (loc.getWorld() == null) return 0;
        return levelAt(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private int levelAt(UUID world, int x, int y, int z) {
        Map<BlockPos, Integer> map = hoppers.get(new ChunkKey(world, x >> 4, z >> 4));
        if (map == null) return 0;
        Integer lvl = map.get(new BlockPos(x, y, z));
        return lvl == null ? 0 : lvl;
    }

    // --------------------------------------------------------------- economy

    public boolean hasEconomy() {
        return vault != null && vault.available();
    }

    public boolean canAfford(Player player, double price) {
        if (price <= 0) return true;
        return hasEconomy() && vault.has(player, price);
    }

    public boolean withdraw(Player player, double price) {
        return hasEconomy() && vault.withdraw(player, price);
    }

    public void refund(Player player, double price) {
        if (price > 0 && hasEconomy()) vault.deposit(player, price);
    }

    public double balance(Player player) {
        return hasEconomy() ? vault.balance(player) : 0;
    }

    public String formatMoney(double amount) {
        if (hasEconomy()) return vault.format(amount);
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    public void playSound(Player player, String path) {
        String sound = getConfig().getString(path, "");
        if (sound == null || sound.isBlank()) return;
        player.playSound(player.getLocation(), sound, 1f, 1f);
    }

    // ----------------------------------------------------------------- index

    private static ChunkKey keyOf(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    private static ChunkKey keyOf(Block block) {
        return new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
    }

    public void scanChunk(Chunk chunk) {
        Map<BlockPos, Integer> found = new HashMap<>();
        for (BlockState state : chunk.getTileEntities(b -> b.getType() == Material.HOPPER, false)) {
            if (state instanceof Hopper hopper
                    && hopper.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                found.put(new BlockPos(state.getX(), state.getY(), state.getZ()), readLevel(hopper));
            }
        }
        if (found.isEmpty()) {
            hoppers.remove(keyOf(chunk));
        } else {
            hoppers.put(keyOf(chunk), found);
        }
    }

    public void forgetChunk(Chunk chunk) {
        hoppers.remove(keyOf(chunk));
    }

    public boolean canPlaceIn(Block block) {
        if (maxPerChunk <= 0) return true;
        Map<BlockPos, Integer> map = hoppers.get(keyOf(block));
        return map == null || map.size() < maxPerChunk;
    }

    public void register(Block block, int level) {
        hoppers.computeIfAbsent(keyOf(block), k -> new HashMap<>())
                .put(new BlockPos(block.getX(), block.getY(), block.getZ()), level);
    }

    public void unregister(Block block) {
        ChunkKey ck = keyOf(block);
        Map<BlockPos, Integer> map = hoppers.get(ck);
        if (map == null) return;
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        map.remove(pos);
        lastTransfer.remove(pos);
        pendingTransfers.remove(pos);
        if (map.isEmpty()) hoppers.remove(ck);
    }

    // -------------------------------------------------------------- transfer

    /** Delai minimal (ticks) entre deux transferts d'un meme hopper, comme le cooldown vanilla. */
    private static final long TRANSFER_COOLDOWN_TICKS = 8L;

    /** Hoppers dont un transfert est deja programme (evite les doublons). */
    private final Set<BlockPos> pendingTransfers = new HashSet<>();
    /** Dernier tick (temps de jeu du monde) ou un hopper a transfere. */
    private final Map<BlockPos, Long> lastTransfer = new HashMap<>();

    /**
     * Programme un transfert de {@code budget} items au tick suivant.
     *
     * Pourquoi differer ? Pendant InventoryMoveItemEvent, Paper reduit temporairement le slot
     * du hopper a 1 item (le vrai nombre est restaure APRES l'annulation de l'evenement).
     * Lire ou modifier le slot dans l'evenement fausse donc les quantites : on deplacait 1 item
     * puis on supprimait tout le slot. En attendant le tick suivant, le slot a retrouve son
     * contenu reel et le transfert est exact.
     */
    public void scheduleTransfer(Inventory source, Inventory destination, int budget) {
        Location loc = source.getLocation();
        if (loc == null || loc.getWorld() == null) return;
        World world = loc.getWorld();
        BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        if (pendingTransfers.contains(pos)) return;
        long now = world.getGameTime();
        Long last = lastTransfer.get(pos);
        if (last != null && now - last < TRANSFER_COOLDOWN_TICKS) return;
        pendingTransfers.add(pos);

        getServer().getScheduler().runTask(this, () -> {
            pendingTransfers.remove(pos);
            // Le hopper doit toujours exister et etre un Chunk Hopper.
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
            if (world.getBlockAt(pos.x(), pos.y(), pos.z()).getType() != Material.HOPPER) return;
            if (levelAt(world.getUID(), pos.x(), pos.y(), pos.z()) <= 0) return;

            lastTransfer.put(pos, world.getGameTime());
            transfer(source, destination, budget);
        });
    }

    /** Deplace jusqu'a {@code budget} items de source vers destination, sans jamais en perdre. */
    public void transfer(Inventory source, Inventory destination, int budget) {
        for (int i = 0; i < source.getSize() && budget > 0; i++) {
            ItemStack stack = source.getItem(i);
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;

            int have = stack.getAmount();
            int take = Math.min(budget, have);
            ItemStack toMove = stack.clone();
            toMove.setAmount(take);

            Map<Integer, ItemStack> left = destination.addItem(toMove);
            int leftover = 0;
            for (ItemStack rest : left.values()) leftover += rest.getAmount();
            int moved = take - leftover;
            if (moved <= 0) continue;

            // On retire exactement ce qui est entre dans la destination, rien de plus.
            int remaining = have - moved;
            if (remaining <= 0) {
                source.setItem(i, null);
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(remaining);
                source.setItem(i, rest);
            }
            budget -= moved;
        }
    }

    // ------------------------------------------------------------ collecting

    private void collectAll() {
        Iterator<Map.Entry<ChunkKey, Map<BlockPos, Integer>>> iterator = hoppers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, Map<BlockPos, Integer>> entry = iterator.next();
            ChunkKey ck = entry.getKey();
            World world = getServer().getWorld(ck.world());
            if (world == null || !world.isChunkLoaded(ck.x(), ck.z())) {
                iterator.remove();
                continue;
            }
            Chunk chunk = world.getChunkAt(ck.x(), ck.z(), false);
            if (!chunk.isEntitiesLoaded()) continue;

            // Recupere les inventaires des hoppers actifs et nettoie ceux qui n'existent plus.
            List<Inventory> inventories = new ArrayList<>();
            entry.getValue().keySet().removeIf(pos -> {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                if (block.getType() != Material.HOPPER) return true;
                // Comme un hopper vanilla : verrouille par redstone = ne collecte pas.
                if (block.getBlockData() instanceof org.bukkit.block.data.type.Hopper data && !data.isEnabled()) {
                    return false;
                }
                if (block.getState(false) instanceof Hopper hopper) {
                    inventories.add(hopper.getInventory());
                }
                return false;
            });
            if (entry.getValue().isEmpty()) {
                iterator.remove();
                continue;
            }
            if (inventories.isEmpty()) continue;

            for (Entity entity : chunk.getEntities()) {
                if (!(entity instanceof Item item) || !item.isValid()) continue;

                ItemStack original = item.getItemStack();
                ItemStack remaining = original.clone();
                for (Inventory inventory : inventories) {
                    Map<Integer, ItemStack> left = inventory.addItem(remaining);
                    if (left.isEmpty()) {
                        remaining = null;
                        break;
                    }
                    remaining = left.get(0);
                }

                if (remaining == null) {
                    item.remove();
                } else if (remaining.getAmount() != original.getAmount()) {
                    item.setItemStack(remaining);
                }
            }
        }
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chunkhopper.reload")) return true;
            reloadConfig();
            loadSettings();
            sender.sendMessage(message("reloaded", Map.of()));
            return true;
        }

        if (!sender.hasPermission("chunkhopper.give")) return true;
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(message("usage", Map.of()));
            return true;
        }
        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(message("unknown-player", Map.of()));
            return true;
        }
        int amount = 1;
        int level = 1;
        try {
            if (args.length >= 3) amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            if (args.length >= 4) level = clampLevel(Integer.parseInt(args[3]));
        } catch (NumberFormatException e) {
            sender.sendMessage(message("usage", Map.of()));
            return true;
        }
        target.getInventory().addItem(createItem(amount, level)).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(message("given", Map.of(
                "amount", String.valueOf(amount),
                "player", target.getName(),
                "level", String.valueOf(level),
                "level_roman", Text.roman(level))));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("chunkhopper.give")) subs.add("give");
            if (sender.hasPermission("chunkhopper.reload")) subs.add("reload");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (!sender.hasPermission("chunkhopper.give") || !args[0].equalsIgnoreCase("give")) return List.of();
        if (args.length == 2) {
            return getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3) return List.of("1", "16", "64");
        if (args.length == 4) {
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= maxLevel; i++) out.add(String.valueOf(i));
            return out;
        }
        return List.of();
    }
}
