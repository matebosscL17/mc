package hu.smpcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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

/** Rangok (prefix a chatben és a tablistán), formázott chat, privát üzenet (/msg, /reply). */
public final class RankManager implements Listener, CommandExecutor, TabCompleter {

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, String> assigned = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastMessage = new HashMap<>();

    public RankManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ranks.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                assigned.put(UUID.fromString(key), section.getString(key));
            } catch (IllegalArgumentException ignored) {
                // hibás sor kihagyva
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : assigned.entrySet()) {
            yaml.set("players." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a ranks.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Rang lekérdezés ----------

    private ConfigurationSection rankSection(String id) {
        return plugin.getConfig().getConfigurationSection("ranks.list." + id);
    }

    public String rankId(Player player) {
        String id = assigned.get(player.getUniqueId());
        if (id != null && rankSection(id) != null) {
            return id;
        }
        if (player.hasPermission("smpcore.admin")) {
            String fallback = plugin.getConfig().getString("ranks.admin-fallback", "admin");
            if (rankSection(fallback) != null) {
                return fallback;
            }
        }
        return null;
    }

    public String prefix(Player player) {
        String id = rankId(player);
        if (id == null) {
            return plugin.getConfig().getString("ranks.default", "<gray>Játékos");
        }
        return rankSection(id).getString("prefix", id);
    }

    private String nameColor(Player player) {
        String id = rankId(player);
        return id == null ? "<white>" : rankSection(id).getString("name", "<white>");
    }

    private String textColor(Player player) {
        String id = rankId(player);
        return id == null ? "<white>" : rankSection(id).getString("text", "<white>");
    }

    public Component tabName(Player player) {
        return plugin.parse("<prefix> <color><player>",
                Placeholder.parsed("prefix", prefix(player)),
                Placeholder.parsed("color", nameColor(player)),
                Placeholder.unparsed("player", player.getName()));
    }

    public void refreshTab(Player player) {
        player.playerListName(tabName(player));
    }

    // ---------- Chat ----------

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) {
            return;
        }
        event.renderer((source, sourceDisplayName, message, viewer) -> plugin.parse(
                plugin.cfg("chat.format"),
                Placeholder.parsed("prefix", prefix(source)),
                Placeholder.parsed("name-color", nameColor(source)),
                Placeholder.parsed("text-color", textColor(source)),
                Placeholder.unparsed("player", source.getName()),
                Placeholder.component("message", message)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshTab(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "rank" -> rank(sender, args);
            case "msg" -> msg(sender, args);
            case "reply" -> reply(sender, args);
            default -> { }
        }
        return true;
    }

    private void rank(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length == 0) {
            plugin.send(sender, "rank-usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                ConfigurationSection section = plugin.getConfig().getConfigurationSection("ranks.list");
                plugin.send(sender, "rank-list-header");
                if (section != null) {
                    for (String id : section.getKeys(false)) {
                        plugin.send(sender, "rank-list-line", Placeholder.unparsed("id", id),
                                Placeholder.parsed("prefix", section.getString(id + ".prefix", id)));
                    }
                }
            }
            case "set" -> {
                if (args.length < 3) {
                    plugin.send(sender, "rank-usage");
                    return;
                }
                OfflinePlayer target = plugin.findPlayer(args[1]);
                String id = args[2].toLowerCase(Locale.ROOT);
                if (target == null) {
                    plugin.send(sender, "player-not-found", Placeholder.unparsed("player", args[1]));
                    return;
                }
                if (rankSection(id) == null) {
                    plugin.send(sender, "rank-unknown", Placeholder.unparsed("id", id));
                    return;
                }
                assigned.put(target.getUniqueId(), id);
                save();
                Player online = target.getPlayer();
                if (online != null) {
                    refreshTab(online);
                }
                plugin.send(sender, "rank-set", Placeholder.unparsed("player", args[1]),
                        Placeholder.parsed("prefix", rankSection(id).getString("prefix", id)));
            }
            case "remove" -> {
                if (args.length < 2) {
                    plugin.send(sender, "rank-usage");
                    return;
                }
                OfflinePlayer target = plugin.findPlayer(args[1]);
                if (target == null) {
                    plugin.send(sender, "player-not-found", Placeholder.unparsed("player", args[1]));
                    return;
                }
                assigned.remove(target.getUniqueId());
                save();
                Player online = target.getPlayer();
                if (online != null) {
                    refreshTab(online);
                }
                plugin.send(sender, "rank-removed", Placeholder.unparsed("player", args[1]));
            }
            default -> plugin.send(sender, "rank-usage");
        }
    }

    private void msg(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.send(player, "msg-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || target.equals(player)) {
            plugin.send(player, "player-not-found", Placeholder.unparsed("player", args[0]));
            return;
        }
        deliver(player, target, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
    }

    private void reply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (args.length < 1) {
            plugin.send(player, "msg-usage");
            return;
        }
        UUID last = lastMessage.get(player.getUniqueId());
        Player target = last == null ? null : Bukkit.getPlayer(last);
        if (target == null) {
            plugin.send(player, "msg-nobody");
            return;
        }
        deliver(player, target, String.join(" ", args));
    }

    private void deliver(Player from, Player to, String text) {
        plugin.send(from, "msg-to", Placeholder.unparsed("player", to.getName()),
                Placeholder.unparsed("message", text));
        plugin.send(to, "msg-from", Placeholder.unparsed("player", from.getName()),
                Placeholder.unparsed("message", text));
        lastMessage.put(from.getUniqueId(), to.getUniqueId());
        lastMessage.put(to.getUniqueId(), from.getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("rank")) {
            if (args.length == 1) {
                out.addAll(List.of("set", "remove", "list"));
            } else if (args.length == 2) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                ConfigurationSection section = plugin.getConfig().getConfigurationSection("ranks.list");
                if (section != null) {
                    out.addAll(section.getKeys(false));
                }
            }
        } else if (name.equals("msg") && args.length == 1) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
