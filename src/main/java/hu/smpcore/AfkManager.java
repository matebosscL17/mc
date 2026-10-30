package hu.smpcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AFK kezelés: tétlen játékos AFK jelzést kap a tablistán, opcionálisan kirúgjuk. /afk kapcsolja. */
public final class AfkManager implements Listener, CommandExecutor {

    private final SMPCore plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public AfkManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            lastActive.put(player.getUniqueId(), now);
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::check, 200L, 200L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : afk) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.playerListName(null);
            }
        }
        afk.clear();
    }

    public boolean isAfk(UUID id) {
        return afk.contains(id);
    }

    private void check() {
        if (!plugin.getConfig().getBoolean("afk.enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long afkAfter = plugin.getConfig().getLong("afk.after-seconds", 300) * 1000L;
        long kickAfter = plugin.getConfig().getLong("afk.kick-minutes", 0) * 60_000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            long idle = now - lastActive.getOrDefault(id, now);
            if (!afk.contains(id) && idle >= afkAfter) {
                setAfk(player, true);
            }
            if (kickAfter > 0 && idle >= kickAfter && !player.hasPermission("smpcore.admin")) {
                player.kick(plugin.msg("afk-kick"));
            }
        }
    }

    private void setAfk(Player player, boolean value) {
        UUID id = player.getUniqueId();
        boolean changed = value ? afk.add(id) : afk.remove(id);
        if (!changed) {
            return;
        }
        player.playerListName(value
                ? plugin.msg("afk-tab", Placeholder.unparsed("player", player.getName()))
                : null);
        if (plugin.getConfig().getBoolean("afk.broadcast", true)) {
            Bukkit.broadcast(plugin.msg(value ? "afk-now" : "afk-back",
                    Placeholder.unparsed("player", player.getName())));
        }
    }

    /** Aktivitás: frissíti az időt, és ha AFK volt, visszahozza. Csak a fő szálról hívható. */
    private void touch(Player player) {
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.contains(player.getUniqueId())) {
            setAfk(player, false);
        }
    }

    // ---------- Események ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastActive.remove(id);
        afk.remove(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() || event.hasChangedOrientation()) {
            touch(event.getPlayer());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().toLowerCase().startsWith("/afk")) {
            touch(event.getPlayer());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> touch(player));
    }

    // ---------- /afk ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (afk.contains(player.getUniqueId())) {
            touch(player);
        } else {
            lastActive.put(player.getUniqueId(), System.currentTimeMillis());
            setAfk(player, true);
        }
        return true;
    }
}
