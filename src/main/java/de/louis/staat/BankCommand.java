package de.louis.staat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class BankCommand implements TabExecutor {

    private final StaatsPlugin plugin;

    BankCommand(StaatsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("messages.players-only"));
            return true;
        }

        String sub = args.length == 0 ? "kontostand" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "kontostand", "konto" -> balance(p);
            case "einzahlen" -> deposit(p, args);
            case "abheben" -> withdraw(p, args);
            default -> p.sendMessage(plugin.msg("messages.bank-usage"));
        }
        return true;
    }

    private void balance(Player p) {
        p.sendMessage(plugin.msg("messages.bank-balance", "{amount}", StaatsPlugin.fmt(plugin.bank(p.getUniqueId()))));
        double wallet = plugin.economy().balance(p);
        if (wallet >= 0) {
            p.sendMessage(plugin.msg("messages.bank-wallet", "{amount}", StaatsPlugin.fmt(wallet)));
        }
    }

    private void deposit(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(plugin.msg("messages.bank-usage"));
            return;
        }
        double amount = StaatsPlugin.parseAmount(args[1]);
        if (amount <= 0) {
            p.sendMessage(plugin.msg("messages.invalid-amount"));
            return;
        }
        if (!plugin.economy().canVerify()) {
            p.sendMessage(plugin.msg("messages.needs-vault"));
            return;
        }
        if (!plugin.economy().withdraw(p, amount)) {
            p.sendMessage(plugin.msg("messages.not-enough-wallet"));
            return;
        }

        UUID u = p.getUniqueId();
        plugin.setBank(u, plugin.bank(u) + amount);
        plugin.saveData();
        plugin.logTx("BANK EINZAHLEN " + p.getName() + " " + StaatsPlugin.fmt(amount));
        p.sendMessage(plugin.msg("messages.bank-deposited",
                "{amount}", StaatsPlugin.fmt(amount), "{balance}", StaatsPlugin.fmt(plugin.bank(u))));
    }

    private void withdraw(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(plugin.msg("messages.bank-usage"));
            return;
        }
        double amount = StaatsPlugin.parseAmount(args[1]);
        if (amount <= 0) {
            p.sendMessage(plugin.msg("messages.invalid-amount"));
            return;
        }

        UUID u = p.getUniqueId();
        double bal = plugin.bank(u);
        if (amount > bal) {
            p.sendMessage(plugin.msg("messages.not-enough-bank"));
            return;
        }

        plugin.setBank(u, bal - amount);
        if (!plugin.economy().deposit(p, amount)) {
            plugin.setBank(u, bal);
            p.sendMessage(plugin.msg("messages.economy-failed"));
            return;
        }
        plugin.saveData();
        plugin.logTx("BANK ABHEBEN " + p.getName() + " " + StaatsPlugin.fmt(amount));
        p.sendMessage(plugin.msg("messages.bank-withdrawn",
                "{amount}", StaatsPlugin.fmt(amount), "{balance}", StaatsPlugin.fmt(plugin.bank(u))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("kontostand", "einzahlen", "abheben").stream()
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
