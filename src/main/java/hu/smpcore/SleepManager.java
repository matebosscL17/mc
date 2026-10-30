package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Alvás-szavazás: ha a játékosok megadott százaléka alszik, az éjszaka átugrik (nem kell mindenkinek aludni). */
public final class SleepManager implements Listener {

    private final SMPCore plugin;
    private final Set<UUID> sleeping = new HashSet<>();
    private boolean skipping;

    public SleepManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("sleep.enabled", true);
    }

    @EventHandler
    public void onEnter(PlayerBedEnterEvent event) {
        if (!enabled() || event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
            return;
        }
        sleeping.add(event.getPlayer().getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> evaluate(event.getPlayer().getWorld(),
                event.getPlayer().getName()));
    }

    @EventHandler
    public void onLeave(PlayerBedLeaveEvent event) {
        sleeping.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sleeping.remove(event.getPlayer().getUniqueId());
    }

    private void evaluate(World world, String lastName) {
        if (skipping) {
            return;
        }
        int eligible = 0;
        int asleep = 0;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || plugin.afk().isAfk(p.getUniqueId())) {
                continue;
            }
            eligible++;
            if (sleeping.contains(p.getUniqueId())) {
                asleep++;
            }
        }
        if (eligible == 0) {
            return;
        }
        int percent = plugin.getConfig().getInt("sleep.percent", 50);
        int needed = Math.max(1, (int) Math.ceil(eligible * percent / 100.0));
        for (Player p : world.getPlayers()) {
            p.sendActionBar(plugin.msg("sleep-progress", Placeholder.unparsed("player", lastName),
                    Placeholder.unparsed("asleep", String.valueOf(asleep)),
                    Placeholder.unparsed("needed", String.valueOf(needed))));
        }
        if (asleep >= needed) {
            skipping = true;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                skipping = false;
                world.setTime(0);
                world.setStorm(false);
                world.setThundering(false);
                for (Player p : world.getPlayers()) {
                    p.sendMessage(plugin.msg("sleep-skipped"));
                }
            }, 60L);
        }
    }
}
