package fr.chunkhopper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Lien optionnel avec RoseStacker (par reflexion : aucune dependance a la compilation).
 *
 * RoseStacker garde la vraie taille d'un tas d'items (ex. 1000) dans ses propres donnees ;
 * l'ItemStack de l'entite ne reflete pas toujours cette quantite. Pour ne rien perdre, on lit
 * et on modifie donc la taille du tas via l'API de RoseStacker.
 */
public final class RoseStackerHook {

    private final ChunkHopperPlugin plugin;
    private final Object api;
    private final Method getStackedItem;
    private final Method getStackSize;
    private final Method setStackSize;
    private final Method removeItemStack;

    private RoseStackerHook(ChunkHopperPlugin plugin, Object api, Method getStackedItem, Method getStackSize,
                            Method setStackSize, Method removeItemStack) {
        this.plugin = plugin;
        this.api = api;
        this.getStackedItem = getStackedItem;
        this.getStackSize = getStackSize;
        this.setStackSize = setStackSize;
        this.removeItemStack = removeItemStack;
    }

    /** Retourne le hook, ou null si RoseStacker est absent / son API inattendue. */
    public static RoseStackerHook create(ChunkHopperPlugin plugin) {
        Plugin rose = Bukkit.getPluginManager().getPlugin("RoseStacker");
        if (rose == null || !rose.isEnabled()) return null;
        try {
            ClassLoader cl = rose.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("dev.rosewood.rosestacker.api.RoseStackerAPI", true, cl);
            Class<?> stackedItemClass = Class.forName("dev.rosewood.rosestacker.stack.StackedItem", true, cl);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method getStackedItem = apiClass.getMethod("getStackedItem", Item.class);
            Method getStackSize = stackedItemClass.getMethod("getStackSize");
            Method setStackSize = stackedItemClass.getMethod("setStackSize", int.class);
            Method removeItemStack = apiClass.getMethod("removeItemStack", stackedItemClass);
            plugin.getLogger().info("RoseStacker detecte : les tas d'items sont geres correctement.");
            return new RoseStackerHook(plugin, api, getStackedItem, getStackSize, setStackSize, removeItemStack);
        } catch (Throwable t) {
            plugin.getLogger().warning("RoseStacker detecte mais son API est inaccessible : " + t);
            return null;
        }
    }

    /** Vraie quantite de l'entite (taille du tas RoseStacker, sinon quantite de l'ItemStack). */
    public int amountOf(Item item) {
        Object stacked = stackedOf(item);
        if (stacked != null) {
            try {
                return Math.max(0, (int) getStackSize.invoke(stacked));
            } catch (Throwable t) {
                warn(t);
            }
        }
        return item.getItemStack().getAmount();
    }

    /** Fixe la quantite restante de l'entite apres un ramassage partiel. */
    public void setAmount(Item item, int amount) {
        Object stacked = stackedOf(item);
        if (stacked != null) {
            try {
                setStackSize.invoke(stacked, amount);
                return;
            } catch (Throwable t) {
                warn(t);
            }
        }
        org.bukkit.inventory.ItemStack stack = item.getItemStack();
        stack.setAmount(amount);
        item.setItemStack(stack);
    }

    /** Supprime completement l'entite (et son tas RoseStacker). */
    public void remove(Item item) {
        Object stacked = stackedOf(item);
        if (stacked != null) {
            try {
                removeItemStack.invoke(api, stacked);
                return;
            } catch (Throwable t) {
                warn(t);
            }
        }
        item.remove();
    }

    private Object stackedOf(Item item) {
        try {
            return getStackedItem.invoke(api, item);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    private boolean warned;

    private void warn(Throwable t) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning("Erreur avec l'API RoseStacker (repli sur le comportement vanilla) : " + t);
    }
}
