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

final class RankCommand implements TabExecutor {

    private final StaatsPlugin plugin;

    RankCommand(StaatsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                send(sender, "messages.own-rank", p.getName(), plugin.rankOf(p.getUniqueId()));
            } else {
                sender.sendMessage(plugin.msg("messages.rank-usage"));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("info") && args.length >= 2) {
            OfflinePlayer target = plugin.findPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.msg("messages.unknown-player"));
                return true;
            }
            String name = target.getName() != null ? target.getName() : args[1];
            send(sender, "messages.other-rank", name, plugin.rankOf(target.getUniqueId()));
            return true;
        }

        if (sub.equals("set") && args.length >= 3) {
            if (!sender.hasPermission("staat.admin")) {
                sender.sendMessage(plugin.msg("messages.no-permission"));
                return true;
            }
            OfflinePlayer target = plugin.findPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.msg("messages.unknown-player"));
                return true;
            }
            String key = args[2].toLowerCase(Locale.ROOT);
            if (!plugin.rankExists(key)) {
                sender.sendMessage(plugin.msg("messages.unknown-rank", "{ranks}", String.join(", ", plugin.rankKeys())));
                return true;
            }
            String name = target.getName() != null ? target.getName() : args[1];
            plugin.setRank(sender, target, name, key);
            return true;
        }

        sender.sendMessage(plugin.msg("messages.rank-usage"));
        return true;
    }

    private void send(CommandSender to, String path, String player, String key) {
        to.sendMessage(plugin.msg(path, "{player}", player, "{color}", plugin.colorTag(key), "{rank}", plugin.display(key)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.add("info");
            if (sender.hasPermission("staat.admin")) result.add("set");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("set"))) {
            Bukkit.getOnlinePlayers().forEach(p -> result.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set") && sender.hasPermission("staat.admin")) {
            result.addAll(plugin.rankKeys());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return result.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
