package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Heti toplista: a hét végén a játékidő, ölés és pénzszerzés top 3-a jutalmat (pénz, kulcs) kap. */
public final class WeeklyManager implements Listener {

    public static final List<String> TYPES = List.of("playtime", "kills", "balance");

    private final SMPCore plugin;
    private final StatsManager stats;
    private final File file;
    private long weekStart;
    private final Map<String, Map<UUID, Long>> baseline = new HashMap<>();
    /** offline nyertesek kulcsai: játékos -> lista ("crate:db") */
    private final Map<UUID, List<String>> pending = new LinkedHashMap<>();
    private BukkitTask task;

    public WeeklyManager(SMPCore plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.file = new File(plugin.getDataFolder(), "weekly.yml");
        load();
    }

    // ---------- Mentés / betöltés ----------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        weekStart = yaml.getLong("week-start", 0);
        for (String type : TYPES) {
            Map<UUID, Long> map = new HashMap<>();
            var section = yaml.getConfigurationSection("baseline." + type);
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        map.put(UUID.fromString(key), section.getLong(key));
                    } catch (IllegalArgumentException ignored) {
                        // hibás sor kihagyva
                    }
                }
            }
            baseline.put(type, map);
        }
        var pend = yaml.getConfigurationSection("pending");
        if (pend != null) {
            for (String key : pend.getKeys(false)) {
                try {
                    pending.put(UUID.fromString(key), new ArrayList<>(pend.getStringList(key)));
                } catch (IllegalArgumentException ignored) {
                    // hibás sor kihagyva
                }
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("week-start", weekStart);
        for (String type : TYPES) {
            for (Map.Entry<UUID, Long> e : baseline.getOrDefault(type, Map.of()).entrySet()) {
                yaml.set("baseline." + type + "." + e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<UUID, List<String>> e : pending.entrySet()) {
            yaml.set("pending." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a weekly.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Időzítés ----------

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::check, 200L, 1200L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("weekly.enabled", true);
    }

    private long periodMillis() {
        return Math.max(1, plugin.getConfig().getLong("weekly.days", 7)) * 86_400_000L;
    }

    private void check() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (weekStart == 0) {
            newWeek(now);
            return;
        }
        if (now - weekStart >= periodMillis()) {
            endWeek();
            newWeek(now);
        }
    }

    private void newWeek(long now) {
        weekStart = now;
        for (String type : TYPES) {
            Map<UUID, Long> map = new HashMap<>();
            List<Map.Entry<UUID, Long>> list = stats.ranking(type);
            if (list != null) {
                for (Map.Entry<UUID, Long> e : list) {
                    map.put(e.getKey(), e.getValue());
                }
            }
            baseline.put(type, map);
        }
        save();
    }

    public String timeLeft() {
        if (weekStart == 0) {
            return "-";
        }
        long left = Math.max(0, weekStart + periodMillis() - System.currentTimeMillis());
        long hours = left / 3_600_000L;
        return (hours / 24) + " nap " + (hours % 24) + " óra";
    }

    /** Heti gyarapodás szerint rendezett lista (csak a pozitívak). */
    public List<Map.Entry<UUID, Long>> ranking(String type) {
        List<Map.Entry<UUID, Long>> all = stats.ranking(type);
        List<Map.Entry<UUID, Long>> list = new ArrayList<>();
        if (all == null) {
            return list;
        }
        Map<UUID, Long> base = baseline.getOrDefault(type, Map.of());
        for (Map.Entry<UUID, Long> e : all) {
            long gain = e.getValue() - base.getOrDefault(e.getKey(), 0L);
            if (gain > 0) {
                list.add(Map.entry(e.getKey(), gain));
            }
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list;
    }

    // ---------- Jutalmak ----------

    private List<Map<?, ?>> rewardList(String type) {
        return plugin.getConfig().getMapList("weekly.rewards." + type);
    }

    /** A jutalom szöveges leírása a GUI-hoz (helyezés 1-től). */
    public String describe(String type, int place) {
        List<Map<?, ?>> list = rewardList(type);
        if (place > list.size()) {
            return "-";
        }
        Map<?, ?> m = list.get(place - 1);
        StringBuilder sb = new StringBuilder();
        Object money = m.get("money");
        if (money instanceof Number n && n.longValue() > 0) {
            sb.append(plugin.money(n.longValue()));
        }
        Object key = m.get("key");
        if (key != null) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(key).append(" kulcs");
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private void endWeek() {
        for (String type : TYPES) {
            List<Map.Entry<UUID, Long>> top = ranking(type);
            List<Map<?, ?>> rewards = rewardList(type);
            for (int i = 0; i < Math.min(3, Math.min(top.size(), rewards.size())); i++) {
                UUID id = top.get(i).getKey();
                Map<?, ?> reward = rewards.get(i);
                long money = reward.get("money") instanceof Number n ? n.longValue() : 0;
                if (money > 0) {
                    plugin.economy().add(id, money);
                }
                if (reward.get("key") != null) {
                    String entry = reward.get("key") + ":1";
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) {
                        deliver(online, entry);
                    } else {
                        pending.computeIfAbsent(id, k -> new ArrayList<>()).add(entry);
                    }
                }
                Bukkit.broadcast(plugin.msg("weekly-winner",
                        Placeholder.unparsed("type", typeName(type)),
                        Placeholder.unparsed("place", String.valueOf(i + 1)),
                        Placeholder.unparsed("player", plugin.name(id)),
                        Placeholder.unparsed("reward", describe(type, i + 1))));
            }
        }
        save();
    }

    private void deliver(Player player, String entry) {
        String[] parts = entry.split(":");
        int amount = 1;
        try {
            amount = Integer.parseInt(parts[1]);
        } catch (RuntimeException ignored) {
            // marad 1
        }
        plugin.crates().giveKey(player, parts[0], amount);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        List<String> list = pending.remove(event.getPlayer().getUniqueId());
        if (list == null) {
            return;
        }
        for (String entry : list) {
            deliver(event.getPlayer(), entry);
        }
        save();
    }

    public static String typeName(String type) {
        return switch (type) {
            case "playtime" -> "Játékidő";
            case "kills" -> "Ölések";
            case "deaths" -> "Halálok";
            default -> "Pénz";
        };
    }
}
