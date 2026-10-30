package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Toplista hologramok a spawnnál: /holo create [playtime|kills|deaths|balance], /holo remove, /holo list. */
public final class HologramManager implements CommandExecutor {

    private static final String TAG = "smpcore_holo";
    private static final Set<String> TYPES = Set.of("playtime", "kills", "deaths", "balance");

    private final SMPCore plugin;
    private final StatsManager stats;
    private final File file;
    /** hologram entitás UUID -> típus */
    private final Map<UUID, String> holograms = new LinkedHashMap<>();
    private BukkitTask task;

    public HologramManager(SMPCore plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.file = new File(plugin.getDataFolder(), "holograms.yml");
        load();
    }

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 100L, 200L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("holograms")) {
            try {
                holograms.put(UUID.fromString((String) m.get("uuid")), (String) m.get("type"));
            } catch (Exception ex) {
                plugin.getLogger().warning("Hibás hologram a holograms.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
    }

    private void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<UUID, String> e : holograms.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", e.getKey().toString());
            m.put("type", e.getValue());
            list.add(m);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("holograms", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a holograms.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Szöveg ----------

    private static String rankColor(int rank) {
        return switch (rank) {
            case 1 -> "<gold><bold>";
            case 2 -> "<#c0c0c0><bold>";
            case 3 -> "<#cd7f32><bold>";
            default -> "<white>";
        };
    }

    private String content(String type) {
        List<Map.Entry<UUID, Long>> list = stats.ranking(type);
        if (list == null) {
            list = new ArrayList<>();
        }
        String line = "<dark_gray><strikethrough>                                  ";
        StringBuilder text = new StringBuilder();
        text.append("<gradient:#ffd700:#ff8c00><bold>✦ PENTHOUSE SMP ✦</bold></gradient><newline>");
        text.append(plugin.cfg("messages.holo-title-" + type)).append("<newline>");
        text.append(line).append("<newline>");
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : list) {
            if (rank > 10) {
                break;
            }
            String marker = rank <= 3 ? "<yellow>★ " : "<gray>" + rank + ". ";
            text.append(marker).append(rankColor(rank)).append(plugin.name(entry.getKey()))
                    .append(" <dark_gray>» <green>").append(stats.formatValue(type, entry.getValue()))
                    .append("<newline>");
            rank++;
        }
        if (list.isEmpty()) {
            text.append("<gray><italic>Még nincs adat.<newline>");
        }
        text.append(line).append("<newline><dark_gray><italic>Részletek: <gray>/top");
        return text.toString();
    }

    private void updateAll() {
        boolean changed = false;
        for (Map.Entry<UUID, String> entry : new ArrayList<>(holograms.entrySet())) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof TextDisplay display) {
                display.text(plugin.parse(content(entry.getValue())));
            }
        }
        if (changed) {
            save();
        }
    }

    // ---------- Parancs ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (args.length == 0) {
            plugin.send(player, "holo-usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "remove" -> remove(player);
            case "list" -> {
                for (Map.Entry<UUID, String> e : holograms.entrySet()) {
                    plugin.send(player, "holo-list-line",
                            Placeholder.unparsed("type", e.getValue()),
                            Placeholder.unparsed("id", e.getKey().toString().substring(0, 8)));
                }
            }
            default -> plugin.send(player, "holo-usage");
        }
        return true;
    }

    private void create(Player player, String[] args) {
        String type = args.length > 1 ? args[1].toLowerCase() : "";
        if (!TYPES.contains(type)) {
            plugin.send(player, "holo-usage");
            return;
        }
        Location loc = player.getLocation().add(0, 1.8, 0);
        TextDisplay display = loc.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.text(plugin.parse(content(type)));
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(1.4f, 1.4f, 1.4f), new AxisAngle4f()));
            d.addScoreboardTag(TAG);
        });
        holograms.put(display.getUniqueId(), type);
        save();
        plugin.send(player, "holo-created");
    }

    private void remove(Player player) {
        TextDisplay nearest = null;
        double best = 16;
        for (Map.Entry<UUID, String> entry : holograms.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof TextDisplay display && display.getWorld().equals(player.getWorld())) {
                double d = display.getLocation().distanceSquared(player.getLocation());
                if (d < best) {
                    best = d;
                    nearest = display;
                }
            }
        }
        if (nearest == null) {
            plugin.send(player, "holo-none-near");
            return;
        }
        holograms.remove(nearest.getUniqueId());
        nearest.remove();
        save();
        plugin.send(player, "holo-removed");
    }
}
