package hu.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SMPCore extends JavaPlugin implements Listener, CommandExecutor {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Location> lastDeaths = new HashMap<>();

    private Economy economy;
    private GraveManager graves;
    private ShopManager shop;
    private BountyManager bounties;
    private DailyManager daily;
    private StatsManager stats;
    private SidebarManager sidebar;
    private JobsManager jobs;
    private AuctionManager auctions;
    private SpawnManager spawn;
    private AfkManager afk;
    private BackupManager backup;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        economy = new Economy(this);
        graves = new GraveManager(this);
        shop = new ShopManager(this);
        bounties = new BountyManager(this);
        daily = new DailyManager(this);
        stats = new StatsManager(this);
        sidebar = new SidebarManager(this, stats);
        jobs = new JobsManager(this);
        auctions = new AuctionManager(this);
        spawn = new SpawnManager(this);
        afk = new AfkManager(this);
        backup = new BackupManager(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(graves, this);
        pm.registerEvents(shop, this);
        pm.registerEvents(bounties, this);
        pm.registerEvents(stats, this);
        pm.registerEvents(sidebar, this);
        pm.registerEvents(jobs, this);
        pm.registerEvents(auctions, this);
        pm.registerEvents(spawn, this);
        pm.registerEvents(afk, this);

        for (String name : List.of("rules", "discord", "death", "smpcore")) {
            bind(name, this);
        }
        EconomyCommands eco = new EconomyCommands(this);
        for (String name : List.of("balance", "pay", "baltop", "eco")) {
            bind(name, eco);
        }
        bind("shop", shop);
        bind("sell", shop);
        bind("bounty", bounties);
        bind("daily", daily);
        bind("top", stats);
        bind("graves", graves);
        bind("sidebar", sidebar);
        bind("jobs", jobs);
        bind("ah", auctions);
        bind("afk", afk);
        bind("backup", backup);
        sidebar.start();
        afk.start();
        backup.start();

        // 5 percenként mentés
        getServer().getScheduler().runTaskTimer(this, this::saveAll, 6000L, 6000L);
        getLogger().info("SMPCore elindult.");
    }

    @Override
    public void onDisable() {
        backup.stop();
        afk.stop();
        saveAll();
    }

    private void saveAll() {
        economy.save();
        stats.save();
        jobs.save();
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
        } else {
            getLogger().warning("Hiányzó parancs a plugin.yml-ben: " + name);
        }
    }

    // ---------- Közös segédfüggvények (a többi osztály is használja) ----------

    public Economy economy() {
        return economy;
    }

    public Component parse(String text, TagResolver... resolvers) {
        return mm.deserialize(text == null ? "" : text, resolvers);
    }

    /** Nyers szöveg a configból (teljes útvonallal). */
    public String cfg(String path) {
        return getConfig().getString(path, "");
    }

    /** A config "messages." része alapján készít üzenetet. */
    public Component msg(String key, TagResolver... resolvers) {
        String text = getConfig().getString("messages." + key,
                "<red>[hiányzó üzenet: " + key + "]");
        return parse(text, resolvers);
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(msg(key, resolvers));
    }

    public String money(long amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', ' ')
                + " " + getConfig().getString("economy.currency", "$");
    }

    /** Pozitív egész összeg, hibánál -1. */
    public long parseAmount(String text) {
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Online vagy már ismert (cache-elt) játékos név alapján, különben null. */
    public OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    public String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }

    private TagResolver coordResolvers(Location loc) {
        return TagResolver.resolver(
                Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(loc.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())),
                Placeholder.unparsed("world", loc.getWorld() == null ? "?" : loc.getWorld().getName()));
    }

    // ---------- Események ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        TagResolver who = Placeholder.unparsed("player", player.getName());

        economy.ensure(player.getUniqueId());

        if (getConfig().getBoolean("join-quit.enabled", true)) {
            String path = player.hasPlayedBefore() ? "join-quit.join" : "join-quit.first-join";
            event.joinMessage(parse(cfg(path), who));
        }

        if (getConfig().getBoolean("welcome-title.enabled", true)) {
            player.showTitle(Title.title(
                    parse(cfg("welcome-title.title"), who),
                    parse(cfg("welcome-title.subtitle"), who)));
        }

        getServer().getScheduler().runTaskLater(this, () -> daily.hint(player), 80L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (getConfig().getBoolean("join-quit.enabled", true)) {
            TagResolver who = Placeholder.unparsed("player", event.getPlayer().getName());
            event.quitMessage(parse(cfg("join-quit.quit"), who));
        }
        lastDeaths.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Location loc = victim.getLocation();
        lastDeaths.put(victim.getUniqueId(), loc.clone());

        if (getConfig().getBoolean("death-coords.enabled", true)) {
            victim.sendMessage(parse(cfg("death-coords.message"), coordResolvers(loc)));
        }

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)
                && getConfig().getBoolean("player-heads.enabled", true)) {
            double chance = getConfig().getDouble("player-heads.chance", 1.0);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(victim);
                meta.displayName(parse(cfg("player-heads.name"),
                        Placeholder.unparsed("player", victim.getName())));
                head.setItemMeta(meta);
                // Külön dobjuk le, hogy ne kerüljön a sírba
                loc.getWorld().dropItemNaturally(loc, head);
            }
        }
    }

    // ---------- Parancsok (rules, discord, death, smpcore) ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "rules" -> {
                for (String line : getConfig().getStringList("rules.lines")) {
                    sender.sendMessage(parse(line));
                }
            }
            case "discord" -> sender.sendMessage(parse(cfg("discord.message")));
            case "death" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "player-only");
                    return true;
                }
                Location loc = lastDeaths.get(player.getUniqueId());
                if (loc == null) {
                    player.sendMessage(parse(cfg("death-coords.command-none")));
                } else {
                    player.sendMessage(parse(cfg("death-coords.message"), coordResolvers(loc)));
                }
            }
            case "smpcore" -> {
                if (!sender.hasPermission("smpcore.admin")) {
                    send(sender, "no-permission");
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    shop.reload();
                    sidebar.reload();
                    jobs.reload();
                    backup.start();
                    send(sender, "reloaded");
                } else {
                    send(sender, "usage-smpcore");
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
