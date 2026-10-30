package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Oldalsó (sidebar) scoreboard: szerver név, pénz, ölés/halál, játszott idő, online játékosok.
 * Játékosonként külön scoreboard, másodpercenként frissül. /sidebar kapcsolja ki-be.
 */
public final class SidebarManager implements Listener, CommandExecutor {

    private static final int MAX_LINES = 15;
    private static final String OBJECTIVE = "smpcore";

    private final SMPCore plugin;
    private final StatsManager stats;
    private final File file;
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BukkitTask task;

    public SidebarManager(SMPCore plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.file = new File(plugin.getDataFolder(), "sidebar-hidden.yml");
        loadHidden();
    }

    // ---------- Be/kikapcsolás tárolása ----------

    private void loadHidden() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String s : yaml.getStringList("hidden")) {
            try {
                hidden.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // hibás sor
            }
        }
    }

    private void saveHidden() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID id : hidden) {
            list.add(id.toString());
        }
        yaml.set("hidden", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a sidebar-hidden.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Életciklus ----------

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 20L);
    }

    public void reload() {
        start();
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (boards.containsKey(player.getUniqueId())) {
                    player.setScoreboard(manager.getMainScoreboard());
                }
            }
        }
        boards.clear();
    }

    // ---------- Scoreboard építés és frissítés ----------

    private List<String> lines() {
        List<String> lines = plugin.getConfig().getStringList("sidebar.lines");
        return lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
    }

    /** Minden sorhoz egyedi, láthatatlan bejegyzés kell (§0, §1, ...). */
    private static String entry(int index) {
        return "§" + Integer.toHexString(index);
    }

    private Scoreboard build(int lineCount) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return null;
        }
        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY,
                plugin.parse(plugin.cfg("sidebar.title")));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < lineCount; i++) {
            Team team = board.registerNewTeam("line" + i);
            team.addEntry(entry(i));
            objective.getScore(entry(i)).setScore(lineCount - i);
        }
        return board;
    }

    private void show(Player player) {
        if (hidden.contains(player.getUniqueId())
                || !plugin.getConfig().getBoolean("sidebar.enabled", true)) {
            return;
        }
        Scoreboard board = build(lines().size());
        if (board == null) {
            return;
        }
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        update(player);
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        UUID id = player.getUniqueId();
        Scoreboard board = boards.get(id);
        if (board == null) {
            return;
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective != null) {
            objective.displayName(plugin.parse(plugin.cfg("sidebar.title")));
        }
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("money", plugin.money(plugin.economy().get(id))),
                Placeholder.unparsed("kills", String.valueOf(stats.kills(id))),
                Placeholder.unparsed("deaths", String.valueOf(stats.deaths(id))),
                Placeholder.unparsed("playtime", StatsManager.formatTime(stats.playMillis(id))),
                Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())),
                Placeholder.unparsed("max", String.valueOf(Bukkit.getMaxPlayers())));

        List<String> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            Team team = board.getTeam("line" + i);
            if (team != null) {
                team.prefix(plugin.parse(lines.get(i), resolver));
            }
        }
    }

    // ---------- Események ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        show(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    // ---------- /sidebar ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        UUID id = player.getUniqueId();
        if (hidden.remove(id)) {
            saveHidden();
            show(player);
            plugin.send(player, "sidebar-on");
        } else {
            hidden.add(id);
            saveHidden();
            boards.remove(id);
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
            plugin.send(player, "sidebar-off");
        }
        return true;
    }
}
