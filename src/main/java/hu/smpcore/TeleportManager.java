package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Otthonok (/sethome, /home), /back, teleport kérések (/tpa), warpok. Teleport előtt várakozás, mozgásra megszakad. */
public final class TeleportManager implements Listener, TabExecutor {

    private record Request(UUID from, long time) {
    }

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,16}");

    private final SMPCore plugin;
    private final File homesFile;
    private final File warpsFile;
    private final Map<UUID, Map<String, String>> homes = new HashMap<>();
    private final Map<String, String> warps = new LinkedHashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private final Map<UUID, Integer> warming = new HashMap<>();
    private int tokenCounter;

    public TeleportManager(SMPCore plugin) {
        this.plugin = plugin;
        this.homesFile = new File(plugin.getDataFolder(), "homes.yml");
        this.warpsFile = new File(plugin.getDataFolder(), "warps.yml");
        load();
    }

    // ---------- Mentés / betöltés ----------

    private static String ser(Location l) {
        return String.format(Locale.ROOT, "%s;%.2f;%.2f;%.2f;%.1f;%.1f", l.getWorld().getName(),
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    private static Location de(String s) {
        try {
            String[] p = s.split(";");
            World world = Bukkit.getWorld(p[0]);
            if (world == null) {
                return null;
            }
            return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void load() {
        if (homesFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(homesFile);
            ConfigurationSection players = yaml.getConfigurationSection("homes");
            if (players != null) {
                for (String uuid : players.getKeys(false)) {
                    try {
                        Map<String, String> map = new LinkedHashMap<>();
                        ConfigurationSection s = players.getConfigurationSection(uuid);
                        if (s != null) {
                            for (String name : s.getKeys(false)) {
                                map.put(name, s.getString(name));
                            }
                        }
                        homes.put(UUID.fromString(uuid), map);
                    } catch (IllegalArgumentException ignored) {
                        // hibás sor kihagyva
                    }
                }
            }
        }
        if (warpsFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(warpsFile);
            ConfigurationSection s = yaml.getConfigurationSection("warps");
            if (s != null) {
                for (String name : s.getKeys(false)) {
                    warps.put(name, s.getString(name));
                }
            }
        }
    }

    private void saveHomes() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, String>> e : homes.entrySet()) {
            for (Map.Entry<String, String> h : e.getValue().entrySet()) {
                yaml.set("homes." + e.getKey() + "." + h.getKey(), h.getValue());
            }
        }
        write(yaml, homesFile);
    }

    private void saveWarps() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> e : warps.entrySet()) {
            yaml.set("warps." + e.getKey(), e.getValue());
        }
        write(yaml, warpsFile);
    }

    private void write(YamlConfiguration yaml, File file) {
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a(z) " + file.getName() + " fájlt: " + ex.getMessage());
        }
    }

    // ---------- Teleport ----------

    private void teleport(Player player, Location dest) {
        UUID id = player.getUniqueId();
        boolean bypass = player.hasPermission("smpcore.admin");
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("teleport.cooldown-seconds", 5) * 1000L;
        Long last = lastTeleport.get(id);
        if (!bypass && last != null && now - last < cooldown) {
            plugin.send(player, "tp-cooldown",
                    Placeholder.unparsed("seconds", String.valueOf((cooldown - (now - last)) / 1000 + 1)));
            return;
        }
        int warmup = bypass ? 0 : plugin.getConfig().getInt("teleport.warmup-seconds", 3);
        if (warmup <= 0) {
            go(player, dest);
            return;
        }
        int token = ++tokenCounter;
        warming.put(id, token);
        Location start = player.getLocation();
        plugin.send(player, "tp-warmup", Placeholder.unparsed("seconds", String.valueOf(warmup)));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Integer current = warming.get(id);
            if (current == null || current != token) {
                return;
            }
            warming.remove(id);
            if (!player.isOnline()) {
                return;
            }
            Location here = player.getLocation();
            if (!here.getWorld().equals(start.getWorld()) || here.distanceSquared(start) > 1.5) {
                plugin.send(player, "tp-cancelled-move");
                return;
            }
            go(player, dest);
        }, warmup * 20L);
    }

    private void go(Player player, Location dest) {
        UUID id = player.getUniqueId();
        back.put(id, player.getLocation());
        lastTeleport.put(id, System.currentTimeMillis());
        player.teleportAsync(dest);
        plugin.send(player, "tp-done");
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && warming.remove(player.getUniqueId()) != null) {
            plugin.send(player, "tp-cancelled-damage");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        back.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        back.remove(id);
        requests.remove(id);
        warming.remove(id);
        requests.values().removeIf(r -> r.from().equals(id));
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        switch (name) {
            case "sethome" -> setHome(player, args);
            case "home" -> home(player, args);
            case "delhome" -> delHome(player, args);
            case "homes" -> listHomes(player);
            case "back" -> {
                Location b = back.get(player.getUniqueId());
                if (b == null) {
                    plugin.send(player, "back-none");
                } else {
                    teleport(player, b);
                }
            }
            case "tpa" -> tpa(player, args);
            case "tpaccept" -> tpAccept(player);
            case "tpdeny" -> tpDeny(player);
            case "tpcancel" -> tpCancel(player);
            case "warp" -> warp(player, args);
            case "warps" -> listWarps(player);
            case "setwarp" -> setWarp(player, args);
            case "delwarp" -> delWarp(player, args);
            default -> { }
        }
        return true;
    }

    private void setHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "home";
        if (!NAME.matcher(name).matches()) {
            plugin.send(player, "home-invalid-name");
            return;
        }
        Map<String, String> own = homes.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
        int max = plugin.getConfig().getInt("teleport.max-homes", 3);
        if (!own.containsKey(name) && own.size() >= max && !player.hasPermission("smpcore.admin")) {
            plugin.send(player, "home-limit", Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        own.put(name, ser(player.getLocation()));
        saveHomes();
        plugin.send(player, "home-set", Placeholder.unparsed("name", name));
    }

    private void home(Player player, String[] args) {
        Map<String, String> own = homes.getOrDefault(player.getUniqueId(), Map.of());
        if (own.isEmpty()) {
            plugin.send(player, "home-none-set");
            return;
        }
        String name;
        if (args.length > 0) {
            name = args[0].toLowerCase(Locale.ROOT);
        } else if (own.containsKey("home")) {
            name = "home";
        } else if (own.size() == 1) {
            name = own.keySet().iterator().next();
        } else {
            listHomes(player);
            return;
        }
        String data = own.get(name);
        Location loc = data == null ? null : de(data);
        if (loc == null) {
            plugin.send(player, "home-none", Placeholder.unparsed("name", name));
            return;
        }
        teleport(player, loc);
    }

    private void delHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "home";
        Map<String, String> own = homes.get(player.getUniqueId());
        if (own == null || own.remove(name) == null) {
            plugin.send(player, "home-none", Placeholder.unparsed("name", name));
            return;
        }
        saveHomes();
        plugin.send(player, "home-deleted", Placeholder.unparsed("name", name));
    }

    private void listHomes(Player player) {
        Map<String, String> own = homes.getOrDefault(player.getUniqueId(), Map.of());
        if (own.isEmpty()) {
            plugin.send(player, "home-none-set");
            return;
        }
        plugin.send(player, "home-list", Placeholder.unparsed("homes", String.join(", ", own.keySet())),
                Placeholder.unparsed("max", String.valueOf(plugin.getConfig().getInt("teleport.max-homes", 3))));
    }

    private void tpa(Player player, String[] args) {
        if (args.length < 1) {
            plugin.send(player, "tpa-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.send(player, "player-not-found", Placeholder.unparsed("player", args[0]));
            return;
        }
        if (target.equals(player)) {
            plugin.send(player, "tpa-self");
            return;
        }
        requests.put(target.getUniqueId(), new Request(player.getUniqueId(), System.currentTimeMillis()));
        plugin.send(player, "tpa-sent", Placeholder.unparsed("player", target.getName()));
        plugin.send(target, "tpa-received", Placeholder.unparsed("player", player.getName()));
    }

    private Request takeRequest(Player target) {
        Request r = requests.remove(target.getUniqueId());
        if (r == null) {
            plugin.send(target, "tpa-none");
            return null;
        }
        long expire = plugin.getConfig().getLong("teleport.request-expire-seconds", 60) * 1000L;
        if (System.currentTimeMillis() - r.time() > expire) {
            plugin.send(target, "tpa-expired");
            return null;
        }
        return r;
    }

    private void tpAccept(Player player) {
        Request r = takeRequest(player);
        if (r == null) {
            return;
        }
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            plugin.send(player, "tpa-offline");
            return;
        }
        plugin.send(player, "tpa-accepted", Placeholder.unparsed("player", from.getName()));
        plugin.send(from, "tpa-accepted-by", Placeholder.unparsed("player", player.getName()));
        teleport(from, player.getLocation());
    }

    private void tpDeny(Player player) {
        Request r = takeRequest(player);
        if (r == null) {
            return;
        }
        plugin.send(player, "tpa-denied", Placeholder.unparsed("player", plugin.name(r.from())));
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) {
            plugin.send(from, "tpa-denied-by", Placeholder.unparsed("player", player.getName()));
        }
    }

    private void tpCancel(Player player) {
        boolean removed = requests.values().removeIf(r -> r.from().equals(player.getUniqueId()));
        plugin.send(player, removed ? "tpa-cancelled" : "tpa-none");
    }

    private void warp(Player player, String[] args) {
        if (args.length < 1) {
            listWarps(player);
            return;
        }
        String data = warps.get(args[0].toLowerCase(Locale.ROOT));
        Location loc = data == null ? null : de(data);
        if (loc == null) {
            plugin.send(player, "warp-none", Placeholder.unparsed("name", args[0]));
            return;
        }
        teleport(player, loc);
    }

    private void listWarps(Player player) {
        if (warps.isEmpty()) {
            plugin.send(player, "warp-empty");
            return;
        }
        plugin.send(player, "warp-list", Placeholder.unparsed("warps", String.join(", ", warps.keySet())));
    }

    private void setWarp(Player player, String[] args) {
        if (!player.hasPermission("smpcore.admin")) {
            plugin.send(player, "no-permission");
            return;
        }
        if (args.length < 1 || !NAME.matcher(args[0].toLowerCase(Locale.ROOT)).matches()) {
            plugin.send(player, "warp-usage");
            return;
        }
        warps.put(args[0].toLowerCase(Locale.ROOT), ser(player.getLocation()));
        saveWarps();
        plugin.send(player, "warp-set", Placeholder.unparsed("name", args[0].toLowerCase(Locale.ROOT)));
    }

    private void delWarp(Player player, String[] args) {
        if (!player.hasPermission("smpcore.admin")) {
            plugin.send(player, "no-permission");
            return;
        }
        if (args.length < 1 || warps.remove(args[0].toLowerCase(Locale.ROOT)) == null) {
            plugin.send(player, "warp-none", Placeholder.unparsed("name", args.length < 1 ? "?" : args[0]));
            return;
        }
        saveWarps();
        plugin.send(player, "warp-deleted", Placeholder.unparsed("name", args[0]));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) {
            return out;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "home", "delhome" -> {
                if (sender instanceof Player p) {
                    out.addAll(homes.getOrDefault(p.getUniqueId(), Map.of()).keySet());
                }
            }
            case "warp", "delwarp" -> out.addAll(warps.keySet());
            case "tpa" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            default -> { }
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
