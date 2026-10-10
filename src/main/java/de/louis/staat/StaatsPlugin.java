package de.louis.staat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StaatsPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public enum Access { NONE, LIMITED, FULL }

    record Limit(String date, double amount) {}

    private final Map<UUID, String> ranks = new ConcurrentHashMap<>();
    private final Map<UUID, Double> bank = new HashMap<>();
    private final Map<UUID, Limit> limits = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private double treasury;
    private Double taxOverride;
    private File dataFile;
    private File logFile;
    private EconomyBridge economy;

    // ------------------------------------------------------------ Lebenszyklus

    @Override
    public void onEnable() {
        saveDefaultConfig();
        economy = new EconomyBridge(this);
        dataFile = new File(getDataFolder(), "data.yml");
        logFile = new File(getDataFolder(), "transactions.log");
        loadData();

        register("rang", new RankCommand(this));
        register("bank", new BankCommand(this));
        register("staatskasse", new TreasuryCommand(this));
        Bukkit.getPluginManager().registerEvents(this, this);

        startTasks();
    }

    @Override
    public void onDisable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        saveData();
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand c = getCommand(name);
        if (c != null) {
            c.setExecutor(executor);
            c.setTabCompleter(executor);
        }
    }

    EconomyBridge economy() {
        return economy;
    }

    // ------------------------------------------------------------------ Daten

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void loadData() {
        ranks.clear();
        bank.clear();
        limits.clear();
        treasury = 0;
        taxOverride = null;

        File source = dataFile;
        boolean legacy = false;
        if (!dataFile.exists()) {
            // Ränge aus dem alten RankSystem übernehmen
            File old = new File(getDataFolder().getParentFile(), "RankSystem/data.yml");
            if (!old.exists()) return;
            source = old;
            legacy = true;
        }

        YamlConfiguration y = YamlConfiguration.loadConfiguration(source);

        ConfigurationSection s = y.getConfigurationSection("players");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                UUID u = parseUuid(k);
                if (u != null) ranks.put(u, s.getString(k, defaultRank()));
            }
        }
        s = y.getConfigurationSection("bank");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                UUID u = parseUuid(k);
                if (u != null) bank.put(u, s.getDouble(k));
            }
        }
        treasury = y.getDouble("treasury.balance", 0);
        if (y.isSet("tax.amount")) taxOverride = y.getDouble("tax.amount");

        s = y.getConfigurationSection("limits");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                UUID u = parseUuid(k);
                if (u != null) limits.put(u, new Limit(s.getString(k + ".date", ""), s.getDouble(k + ".amount")));
            }
        }

        if (legacy) saveData();
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        ranks.forEach((u, r) -> y.set("players." + u, r));
        bank.forEach((u, b) -> y.set("bank." + u, b));
        y.set("treasury.balance", treasury);
        if (taxOverride != null) y.set("tax.amount", taxOverride);
        limits.forEach((u, l) -> {
            y.set("limits." + u + ".date", l.date());
            y.set("limits." + u + ".amount", l.amount());
        });
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("data.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    void logTx(String line) {
        try {
            getDataFolder().mkdirs();
            Files.writeString(logFile.toPath(), LocalDateTime.now() + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    // --------------------------------------------------------------- Konten

    double bank(UUID u) {
        return bank.getOrDefault(u, 0.0);
    }

    void setBank(UUID u, double value) {
        bank.put(u, round2(value));
    }

    double treasury() {
        return treasury;
    }

    void addTreasury(double delta) {
        treasury = round2(treasury + delta);
    }

    double taxAmount() {
        return taxOverride != null ? taxOverride : getConfig().getDouble("tax.amount", 2);
    }

    void setTaxAmount(double v) {
        taxOverride = round2(v);
    }

    int taxIntervalMinutes() {
        return Math.max(1, getConfig().getInt("tax.interval-minutes", 10));
    }

    double dailyLimit() {
        return getConfig().getDouble("treasury.chancellor-daily-limit", 200);
    }

    double spentToday(UUID u) {
        Limit l = limits.get(u);
        return (l != null && l.date().equals(LocalDate.now().toString())) ? l.amount() : 0;
    }

    void addSpent(UUID u, double amount) {
        limits.put(u, new Limit(LocalDate.now().toString(), round2(spentToday(u) + amount)));
    }

    // ----------------------------------------------------------------- Ränge

    String defaultRank() {
        return getConfig().getString("default-rank", "zivilist").toLowerCase(Locale.ROOT);
    }

    String rankOf(UUID u) {
        return ranks.getOrDefault(u, defaultRank());
    }

    boolean rankExists(String key) {
        return getConfig().isConfigurationSection("ranks." + key);
    }

    List<String> rankKeys() {
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }

    String display(String key) {
        return getConfig().getString("ranks." + key + ".display", key);
    }

    String colorTag(String key) {
        return "<" + getConfig().getString("ranks." + key + ".color", "white") + ">";
    }

    Access treasuryAccess(CommandSender s) {
        if (s.hasPermission("staat.admin")) return Access.FULL;
        if (s instanceof Player p) {
            String r = rankOf(p.getUniqueId());
            if (r.equals("praesident")) return Access.FULL;
            if (r.equals("kanzler")) return Access.LIMITED;
        }
        return Access.NONE;
    }

    void setRank(CommandSender sender, OfflinePlayer target, String targetName, String key) {
        if (getConfig().getStringList("unique-ranks").contains(key)) {
            for (Map.Entry<UUID, String> e : new ArrayList<>(ranks.entrySet())) {
                if (e.getValue().equals(key) && !e.getKey().equals(target.getUniqueId())) {
                    ranks.remove(e.getKey());
                    Player old = Bukkit.getPlayer(e.getKey());
                    if (old != null) {
                        old.sendMessage(msg("messages.rank-lost", "{rank}", display(defaultRank())));
                    }
                }
            }
        }

        if (key.equals(defaultRank())) {
            ranks.remove(target.getUniqueId());
        } else {
            ranks.put(target.getUniqueId(), key);
        }
        saveData();
        logTx("RANG " + targetName + " -> " + key + " (durch " + sender.getName() + ")");

        sender.sendMessage(msg("messages.rank-set",
                "{player}", targetName, "{color}", colorTag(key), "{rank}", display(key)));
        if (getConfig().getBoolean("messages.broadcast-enabled", true)) {
            Bukkit.broadcast(msg("messages.broadcast", "{player}", targetName, "{rank}", display(key)));
        }
    }

    OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    // ----------------------------------------------------------------- Chat

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!getConfig().getBoolean("chat-prefix", true)) return;
        String key = rankOf(event.getPlayer().getUniqueId());
        Component prefix = MM.deserialize(colorTag(key) + "[" + display(key) + "]<reset> ");

        event.renderer(ChatRenderer.viewerUnaware((source, name, message) ->
                prefix.append(name)
                        .append(Component.text(" » ", NamedTextColor.GRAY))
                        .append(message)));
    }

    // ----------------------------------------------------------------- Tasks

    private void startTasks() {
        FileConfiguration c = getConfig();
        if (c.getBoolean("salary.enabled", true)) {
            schedule(c.getInt("salary.interval-minutes", 5), this::paySalaries);
        }
        if (c.getBoolean("tax.enabled", true)) {
            schedule(taxIntervalMinutes(), this::collectTax);
        }
        if (c.getDouble("bank.interest-percent", 0.5) > 0) {
            schedule(c.getInt("bank.interest-interval-minutes", 60), this::payInterest);
        }
    }

    private void schedule(int minutes, Runnable r) {
        long period = Math.max(1, minutes) * 60L * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, r, period, period));
    }

    private void paySalaries() {
        boolean fromTreasury = getConfig().getBoolean("salary.from-treasury", false);
        boolean changed = false;

        for (Player p : Bukkit.getOnlinePlayers()) {
            String key = rankOf(p.getUniqueId());
            double amount = getConfig().getDouble("ranks." + key + ".payout", 0);
            if (amount <= 0) continue;

            if (fromTreasury) {
                if (treasury < amount) {
                    p.sendMessage(msg("messages.salary-treasury-empty"));
                    continue;
                }
                addTreasury(-amount);
                changed = true;
            }

            if (economy.deposit(p, amount)) {
                p.sendMessage(msg("messages.salary", "{amount}", fmt(amount), "{rank}", display(key)));
            } else if (fromTreasury) {
                addTreasury(amount);
            }
        }
        if (changed) saveData();
    }

    private void collectTax() {
        double amount = taxAmount();
        if (amount <= 0) return;
        List<String> taxed = getConfig().getStringList("tax.ranks");

        double collected = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!taxed.contains(rankOf(p.getUniqueId()))) continue;
            if (economy.withdraw(p, amount)) {
                collected += amount;
                p.sendMessage(msg("messages.tax-paid", "{amount}", fmt(amount)));
            } else {
                p.sendMessage(msg("messages.tax-failed", "{amount}", fmt(amount)));
            }
        }
        if (collected > 0) {
            addTreasury(collected);
            saveData();
            logTx("STEUER +" + fmt(collected) + " -> Staatskasse " + fmt(treasury));
        }
    }

    private void payInterest() {
        double pct = getConfig().getDouble("bank.interest-percent", 0.5);
        double cap = getConfig().getDouble("bank.max-interest-per-payout", 50);
        boolean changed = false;

        for (Map.Entry<UUID, Double> e : bank.entrySet()) {
            double interest = Math.min(cap, round2(e.getValue() * pct / 100.0));
            if (interest < 0.01) continue;
            e.setValue(round2(e.getValue() + interest));
            changed = true;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.sendMessage(msg("messages.interest", "{amount}", fmt(interest)));
        }
        if (changed) saveData();
    }

    // --------------------------------------------------------------- Helfer

    Component msg(String path, String... replacements) {
        String text = getConfig().getString(path, "");
        text = text.replace("{prefix}", getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return MM.deserialize(text);
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Gibt -1 bei ungültiger Eingabe zurück. */
    static double parseAmount(String s) {
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 1_000_000_000) return -1;
            return round2(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
