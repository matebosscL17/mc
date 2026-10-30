package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;

/** Tablista fejléc és lábléc (szerver neve, online játékosok, ping, TPS, pénz). */
public final class TabManager {

    private final SMPCore plugin;
    private BukkitTask task;

    public TabManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("tab.enabled", true)) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::update, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private String join(String path) {
        List<String> lines = plugin.getConfig().getStringList(path);
        return String.join("<newline>", lines);
    }

    private void update() {
        String header = join("tab.header");
        String footer = join("tab.footer");
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        for (Player player : Bukkit.getOnlinePlayers()) {
            var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                    Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())),
                    Placeholder.unparsed("max", String.valueOf(Bukkit.getMaxPlayers())),
                    Placeholder.unparsed("ping", String.valueOf(player.getPing())),
                    Placeholder.unparsed("tps", String.format(Locale.ROOT, "%.1f", tps)),
                    Placeholder.unparsed("money", plugin.money(plugin.economy().get(player.getUniqueId()))),
                    Placeholder.unparsed("player", player.getName())};
            player.sendPlayerListHeaderAndFooter(plugin.parse(header, resolvers), plugin.parse(footer, resolvers));
        }
    }
}
