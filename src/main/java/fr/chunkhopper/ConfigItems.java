package fr.chunkhopper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;

/**
 * Construit un ItemStack depuis une section de config.
 * "item" accepte un Material (HOPPER, GOLD_INGOT...) ou un item Nexo (nexo:mon_item).
 */
public final class ConfigItems {

    private ConfigItems() {}

    public static ItemStack build(ChunkHopperPlugin plugin, ConfigurationSection sec, Map<String, String> ph) {
        String id = sec.getString("item", sec.getString("material", "STONE"));
        ItemStack stack = resolve(plugin, id);
        stack.setAmount(Math.max(1, Math.min(64, sec.getInt("amount", 1))));

        stack.editMeta(meta -> {
            if (sec.contains("name")) {
                meta.displayName(Text.parse(sec.getString("name", ""), ph));
            }
            if (sec.contains("lore")) {
                List<Component> lore = new ArrayList<>();
                for (String line : sec.getStringList("lore")) {
                    lore.add(Text.parse(line, ph));
                }
                meta.lore(lore);
            }
            if (sec.getBoolean("glow", false)) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (sec.getBoolean("hide-flags", true)) {
                meta.addItemFlags(ItemFlag.values());
            }
        });
        return stack;
    }

    private static ItemStack resolve(ChunkHopperPlugin plugin, String id) {
        if (id.toLowerCase(Locale.ROOT).startsWith("nexo:")) {
            ItemStack nexo = nexoItem(id.substring(5));
            if (nexo != null) return nexo;
            plugin.getLogger().log(Level.WARNING,
                    "Item Nexo introuvable ou Nexo absent : " + id + " (BARRIER utilise a la place)");
            return new ItemStack(Material.BARRIER);
        }
        Material material = Material.matchMaterial(id);
        if (material == null || !material.isItem()) {
            plugin.getLogger().log(Level.WARNING, "Material invalide dans la config : " + id);
            return new ItemStack(Material.BARRIER);
        }
        return new ItemStack(material);
    }

    /** Acces a Nexo par reflexion : aucune dependance de compilation. */
    private static ItemStack nexoItem(String nexoId) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) return null;
        try {
            Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems");
            Object builder = api.getMethod("itemFromId", String.class).invoke(null, nexoId);
            if (builder == null) return null;
            Object built = builder.getClass().getMethod("build").invoke(builder);
            return built instanceof ItemStack stack ? stack.clone() : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
