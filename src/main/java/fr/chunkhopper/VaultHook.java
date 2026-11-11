package fr.chunkhopper;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Toutes les references a Vault sont ici : la classe n'est chargee que si Vault est present. */
final class VaultHook {

    private final JavaPlugin plugin;

    VaultHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    boolean available() {
        return economy() != null;
    }

    boolean has(Player player, double amount) {
        Economy eco = economy();
        return eco != null && eco.has(player, amount);
    }

    boolean withdraw(Player player, double amount) {
        Economy eco = economy();
        return eco != null && eco.withdrawPlayer(player, amount).transactionSuccess();
    }

    void deposit(Player player, double amount) {
        Economy eco = economy();
        if (eco != null) eco.depositPlayer(player, amount);
    }

    double balance(Player player) {
        Economy eco = economy();
        return eco == null ? 0 : eco.getBalance(player);
    }

    String format(double amount) {
        Economy eco = economy();
        return eco == null ? String.valueOf(amount) : eco.format(amount);
    }
}
