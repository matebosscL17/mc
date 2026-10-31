package hu.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/** Automatikus tippek/hirdetések a chatben megadott időközönként (egymás után körbe). */
public final class AnnounceManager {

    private final SMPCore plugin;
    private BukkitTask task;
    private int index;

    public AnnounceManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("announce.enabled", true)) {
            return;
        }
        long ticks = Math.max(30, plugin.getConfig().getLong("announce.interval-seconds", 300)) * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        List<String> lines = plugin.getConfig().getStringList("announce.messages");
        if (lines.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        String line = lines.get(index % lines.size());
        index++;
        Bukkit.broadcast(plugin.parse(plugin.cfg("announce.prefix") + line));
    }
}
