package de.louis.staat;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static de.louis.staat.StaatsPlugin.Access;
import static de.louis.staat.StaatsPlugin.fmt;

/**
 * Staatskasse.
 * Präsident (und staat.admin): voller Zugriff.
 * Kanzler: Einsehen, Abheben und Auszahlen nur bis zum Tageslimit.
 * Alle anderen: dürfen nur einzahlen (spenden).
 */
final class TreasuryCommand implements TabExecutor {

    private final StaatsPlugin plugin;

    TreasuryCommand(StaatsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender);
            case "einzahlen" -> deposit(sender, args);
            case "abheben" -> withdraw(sender, args);
            case "zahlen" -> pay(sender, args);
            case "steuer" -> tax(sender, args);
            default -> sender.sendMessage(plugin.msg("messages.treasury-usage"));
        }
        return true;
    }

    // ------------------------------------------------------------------ info

    private void info(CommandSender sender) {
        Access access = plugin.treasuryAccess(sender);
        if (access == Access.NONE) {
            sender.sendMessage(plugin.msg("messages.no-access"));
            return;
        }
        sender.sendMessage(plugin.msg("messages.treasury-info",
                "{balance}", fmt(plugin.treasury()),
                "{tax}", fmt(plugin.taxAmount()),
                "{minutes}", String.valueOf(plugin.taxIntervalMinutes())));

        if (access == Access.LIMITED && sender instanceof Player p) {
            double remaining = Math.max(0, plugin.dailyLimit() - plugin.spentToday(p.getUniqueId()));
            sender.sendMessage(plugin.msg("messages.treasury-limit-info",
                    "{remaining}", fmt(remaining), "{limit}", fmt(plugin.dailyLimit())));
        }
    }

    // -------------------------------------------------------------- einzahlen

    private void deposit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("messages.players-only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("messages.treasury-usage"));
            return;
        }
        double amount = StaatsPlugin.parseAmount(args[1]);
        if (amount <= 0) {
            sender.sendMessage(plugin.msg("messages.invalid-amount"));
            return;
        }
        if (!plugin.economy().canVerify()) {
            sender.sendMessage(plugin.msg("messages.needs-vault"));
            return;
        }
        if (!plugin.economy().withdraw(p, amount)) {
            sender.sendMessage(plugin.msg("messages.not-enough-wallet"));
            return;
        }

        plugin.addTreasury(amount);
        plugin.saveData();
        plugin.logTx("KASSE EINZAHLEN " + p.getName() + " " + fmt(amount) + " -> " + fmt(plugin.treasury()));
        sender.sendMessage(plugin.msg("messages.treasury-deposited", "{amount}", fmt(amount)));
    }

    // ---------------------------------------------------------------- abheben

    private void withdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("messages.players-only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("messages.treasury-usage"));
            return;
        }
        double amount = StaatsPlugin.parseAmount(args[1]);
        if (amount <= 0) {
            sender.sendMessage(plugin.msg("messages.invalid-amount"));
            return;
        }
        if (!reserve(sender, p, amount)) return;

        if (!plugin.economy().deposit(p, amount)) {
            plugin.addTreasury(amount);
            sender.sendMessage(plugin.msg("messages.economy-failed"));
            return;
        }
        finish(sender, p, amount, "KASSE ABHEBEN " + p.getName() + " " + fmt(amount));
        sender.sendMessage(plugin.msg("messages.treasury-withdrawn", "{amount}", fmt(amount)));
    }

    // ----------------------------------------------------------------- zahlen

    private void pay(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.msg("messages.treasury-usage"));
            return;
        }
        double amount = StaatsPlugin.parseAmount(args[2]);
        if (amount <= 0) {
            sender.sendMessage(plugin.msg("messages.invalid-amount"));
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.msg("messages.unknown-player"));
            return;
        }
        String targetName = target.getName() != null ? target.getName() : args[1];

        Player actor = sender instanceof Player pl ? pl : null;
        if (!reserve(sender, actor, amount)) return;

        if (!plugin.economy().deposit(target, amount)) {
            plugin.addTreasury(amount);
            sender.sendMessage(plugin.msg("messages.economy-failed"));
            return;
        }
        finish(sender, actor, amount, "KASSE ZAHLEN " + sender.getName() + " -> " + targetName + " " + fmt(amount));
        sender.sendMessage(plugin.msg("messages.treasury-paid", "{amount}", fmt(amount), "{player}", targetName));

        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(plugin.msg("messages.treasury-received", "{amount}", fmt(amount)));
        }
    }

    // ----------------------------------------------------------------- steuer

    private void tax(CommandSender sender, String[] args) {
        Access access = plugin.treasuryAccess(sender);
        if (access == Access.NONE) {
            sender.sendMessage(plugin.msg("messages.no-access"));
            return;
        }
        String minutes = String.valueOf(plugin.taxIntervalMinutes());

        if (args.length < 2) {
            sender.sendMessage(plugin.msg("messages.tax-info", "{tax}", fmt(plugin.taxAmount()), "{minutes}", minutes));
            return;
        }
        if (access != Access.FULL) {
            sender.sendMessage(plugin.msg("messages.no-permission"));
            return;
        }
        double value = StaatsPlugin.parseAmount(args[1]);
        if (value < 0) {
            sender.sendMessage(plugin.msg("messages.invalid-amount"));
            return;
        }
        double max = plugin.getConfig().getDouble("tax.max-amount", 20);
        if (value > max) {
            sender.sendMessage(plugin.msg("messages.tax-too-high", "{max}", fmt(max)));
            return;
        }
        plugin.setTaxAmount(value);
        plugin.saveData();
        plugin.logTx("STEUER GESETZT " + fmt(value) + " (durch " + sender.getName() + ")");
        sender.sendMessage(plugin.msg("messages.tax-set", "{tax}", fmt(value)));
    }

    // ---------------------------------------------------------------- Helfer

    /** Prüft Zugriff, Tageslimit und Kassenstand und nimmt den Betrag aus der Kasse. */
    private boolean reserve(CommandSender sender, Player actor, double amount) {
        Access access = plugin.treasuryAccess(sender);
        if (access == Access.NONE) {
            sender.sendMessage(plugin.msg("messages.no-access"));
            return false;
        }
        if (plugin.treasury() < amount) {
            sender.sendMessage(plugin.msg("messages.treasury-too-low"));
            return false;
        }
        if (access == Access.LIMITED) {
            if (actor == null) {
                sender.sendMessage(plugin.msg("messages.no-access"));
                return false;
            }
            double remaining = Math.max(0, plugin.dailyLimit() - plugin.spentToday(actor.getUniqueId()));
            if (amount > remaining) {
                sender.sendMessage(plugin.msg("messages.limit-reached", "{remaining}", fmt(remaining)));
                return false;
            }
        }
        plugin.addTreasury(-amount);
        return true;
    }

    private void finish(CommandSender sender, Player actor, double amount, String logLine) {
        if (plugin.treasuryAccess(sender) == Access.LIMITED && actor != null) {
            plugin.addSpent(actor.getUniqueId(), amount);
        }
        plugin.saveData();
        plugin.logTx(logLine + " -> Kasse " + fmt(plugin.treasury()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        Access access = plugin.treasuryAccess(sender);
        if (args.length == 1) {
            result.add("einzahlen");
            if (access != Access.NONE) result.addAll(List.of("info", "abheben", "zahlen", "steuer"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("zahlen") && access != Access.NONE) {
            Bukkit.getOnlinePlayers().forEach(p -> result.add(p.getName()));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return result.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
