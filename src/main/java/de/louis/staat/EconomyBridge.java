package de.louis.staat;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Verbindung zum Wirtschafts-Plugin.
 * Bevorzugt Vault (per Reflection, daher keine Abhängigkeit beim Bauen),
 * alternativ Konsolenbefehle aus der config.yml.
 */
final class EconomyBridge {

    private final StaatsPlugin plugin;
    private Object provider;
    private Method mBalance;
    private Method mWithdraw;
    private Method mDeposit;
    private Method mSuccess;

    EconomyBridge(StaatsPlugin plugin) {
        this.plugin = plugin;
    }

    private String mode() {
        return plugin.getConfig().getString("economy.mode", "auto").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean tryVault() {
        if (provider != null) return true;
        try {
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration((Class) eco);
            if (rsp == null) return false;
            Object p = rsp.getProvider();
            Method balance = eco.getMethod("getBalance", OfflinePlayer.class);
            Method withdraw = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Method deposit = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Class<?> resp = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            Method success = resp.getMethod("transactionSuccess");
            mBalance = balance;
            mWithdraw = withdraw;
            mDeposit = deposit;
            mSuccess = success;
            provider = p;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            provider = null;
            return false;
        }
    }

    private boolean usingVault() {
        return !mode().equals("command") && tryVault();
    }

    /** true, wenn der Kontostand geprüft werden kann (nur mit Vault). */
    boolean canVerify() {
        return usingVault();
    }

    /** Nimmt Geld vom Spieler. Mit Vault wird vorher der Kontostand geprüft. */
    boolean withdraw(OfflinePlayer p, double amount) {
        if (amount <= 0) return false;
        if (usingVault()) {
            try {
                double bal = ((Number) mBalance.invoke(provider, p)).doubleValue();
                if (bal < amount) return false;
                Object r = mWithdraw.invoke(provider, p, amount);
                return (Boolean) mSuccess.invoke(r);
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("Vault-Abbuchung fehlgeschlagen: " + e.getMessage());
                return false;
            }
        }
        if (mode().equals("vault")) return false;
        return runCommand("economy.take-command", p, amount);
    }

    /** Gibt dem Spieler Geld. */
    boolean deposit(OfflinePlayer p, double amount) {
        if (amount <= 0) return false;
        if (usingVault()) {
            try {
                Object r = mDeposit.invoke(provider, p, amount);
                return (Boolean) mSuccess.invoke(r);
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("Vault-Gutschrift fehlgeschlagen: " + e.getMessage());
                return false;
            }
        }
        if (mode().equals("vault")) return false;
        return runCommand("economy.give-command", p, amount);
    }

    /** Kontostand oder -1, wenn nicht ermittelbar. */
    double balance(OfflinePlayer p) {
        if (!usingVault()) return -1;
        try {
            return ((Number) mBalance.invoke(provider, p)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private boolean runCommand(String path, OfflinePlayer p, double amount) {
        String name = p.getName();
        String template = plugin.getConfig().getString(path, "");
        if (name == null || template.isBlank()) return false;
        String cmd = template
                .replace("{player}", name)
                .replace("{amount}", StaatsPlugin.fmt(amount));
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }
}
