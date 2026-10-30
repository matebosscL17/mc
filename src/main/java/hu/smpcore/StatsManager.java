package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Saját statisztika (ölés, halál, játszott idő) és a /top toplisták. */
public final class StatsManager implements Listener, CommandExecutor {

    private static final int KILLS = 0;
    private static final int DEATHS = 1;
    private static final int PLAY_MS = 2;

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, long[]> data = new HashMap<>();
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    public StatsManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                data.put(id, new long[]{
                        yaml.getLong(key + ".kills"),
                        yaml.getLong(key + ".deaths"),
                        yaml.getLong(key + ".play")});
            } catch (IllegalArgumentException ignored) {
                // hibás sor
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, long[]> e : data.entrySet()) {
            String key = e.getKey().toString();
            yaml.set(key + ".kills", e.getValue()[KILLS]);
            yaml.set(key + ".deaths", e.getValue()[DEATHS]);
            yaml.set(key + ".play", playMillis(e.getKey()));
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a stats.yml-t: " + ex.getMessage());
        }
    }

    private long[] stats(UUID id) {
        return data.computeIfAbsent(id, k -> new long[3]);
    }

    public java.util.Set<UUID> players() {
        return new java.util.HashSet<>(data.keySet());
    }

    public long kills(UUID id) {
        return stats(id)[KILLS];
    }

    public long deaths(UUID id) {
        return stats(id)[DEATHS];
    }

    public long playMillis(UUID id) {
        long base = stats(id)[PLAY_MS];
        Long start = sessionStart.get(id);
        return start == null ? base : base + (System.currentTimeMillis() - start);
    }

    // ---------- Események ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        stats(id);
        sessionStart.put(id, System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long start = sessionStart.remove(id);
        if (start != null) {
            stats(id)[PLAY_MS] += System.currentTimeMillis() - start;
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        stats(victim.getUniqueId())[DEATHS]++;
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            stats(killer.getUniqueId())[KILLS]++;
        }
    }

    // ---------- Toplisták ----------

    /** Rendezett lista (csökkenő) a megadott típushoz, vagy null, ha ismeretlen a típus. */
    public List<Map.Entry<UUID, Long>> ranking(String type) {
        List<Map.Entry<UUID, Long>> list = new ArrayList<>();
        switch (type) {
            case "playtime" -> {
                for (UUID id : data.keySet()) {
                    list.add(Map.entry(id, playMillis(id)));
                }
            }
            case "kills" -> {
                for (Map.Entry<UUID, long[]> e : data.entrySet()) {
                    list.add(Map.entry(e.getKey(), e.getValue()[KILLS]));
                }
            }
            case "deaths" -> {
                for (Map.Entry<UUID, long[]> e : data.entrySet()) {
                    list.add(Map.entry(e.getKey(), e.getValue()[DEATHS]));
                }
            }
            case "balance" -> list.addAll(plugin.economy().all().entrySet());
            default -> {
                return null;
            }
        }
        list.removeIf(e -> e.getValue() <= 0);
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list;
    }

    public String formatValue(String type, long value) {
        return switch (type) {
            case "playtime" -> formatTime(value);
            case "balance" -> plugin.money(value);
            default -> String.valueOf(value);
        };
    }

    // ---------- /top ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String type = args.length > 0 ? args[0].toLowerCase() : "playtime";
        List<Map.Entry<UUID, Long>> list = ranking(type);
        if (list == null) {
            plugin.send(sender, "top-usage");
            return true;
        }
        if (sender instanceof Player player) {
            plugin.tops().open(player, type, args.length > 1 && args[1].toLowerCase().startsWith("h"));
            return true;
        }

        plugin.send(sender, "top-title-" + type);
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : list) {
            if (rank > 10) {
                break;
            }
            plugin.send(sender, "top-line",
                    Placeholder.unparsed("rank", String.valueOf(rank)),
                    Placeholder.unparsed("player", plugin.name(entry.getKey())),
                    Placeholder.unparsed("value", formatValue(type, entry.getValue())));
            rank++;
        }
        return true;
    }

    public static String formatTime(long millis) {
        long minutes = millis / 60_000L;
        return (minutes / 60) + " ó " + (minutes % 60) + " p";
    }
}
