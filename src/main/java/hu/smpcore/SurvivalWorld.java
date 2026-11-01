package hu.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * A túlélő világ külön dimenzió: a spawn/lobby az alapértelmezett világban van,
 * a túlélés egy új, normálisan generált világban (+ saját Nether és End).
 */
public final class SurvivalWorld implements Listener {

    private final SMPCore plugin;

    public SurvivalWorld(SMPCore plugin) {
        this.plugin = plugin;
    }

    private String baseName() {
        return plugin.getConfig().getString("survival.world-name", "survival");
    }

    /** A túlélő világ, vagy az alap világ, ha még nincs/ki van kapcsolva. */
    public World world() {
        World w = Bukkit.getWorld(baseName());
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    /** Létrehozza (vagy betölti) a világokat. Szerverindulás után egyszer kell hívni. */
    public void create() {
        if (!plugin.getConfig().getBoolean("survival.enabled", true)) {
            return;
        }
        String name = baseName();
        World overworld = new WorldCreator(name).environment(World.Environment.NORMAL).createWorld();
        new WorldCreator(name + "_nether").environment(World.Environment.NETHER).createWorld();
        new WorldCreator(name + "_the_end").environment(World.Environment.THE_END).createWorld();
        if (overworld != null) {
            double border = plugin.getConfig().getDouble("setup.world-border", 0);
            if (border > 0) {
                overworld.getWorldBorder().setCenter(0, 0);
                overworld.getWorldBorder().setSize(border);
            }
            overworld.setDifficulty(Bukkit.getWorlds().get(0).getDifficulty());
            plugin.getLogger().info("Túlélő világ kész: " + name);
        }
    }

    private World named(String suffix) {
        return Bukkit.getWorld(baseName() + suffix);
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        World from = event.getFrom().getWorld();
        String base = baseName();
        World overworld = named("");
        World nether = named("_nether");
        World end = named("_the_end");
        if (overworld == null || nether == null || end == null) {
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (from.equals(overworld)) {
                Location f = event.getFrom();
                event.setTo(new Location(nether, f.getX() / 8.0, Math.max(32, Math.min(120, f.getY())),
                        f.getZ() / 8.0, f.getYaw(), f.getPitch()));
            } else if (from.equals(nether)) {
                Location f = event.getFrom();
                event.setTo(new Location(overworld, f.getX() * 8.0, f.getY(), f.getZ() * 8.0,
                        f.getYaw(), f.getPitch()));
            }
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from.equals(overworld)) {
                Location platform = new Location(end, 100.5, 49, 0.5, 90f, 0f);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        end.getBlockAt(100 + dx, 48, dz).setType(Material.OBSIDIAN);
                        for (int dy = 49; dy <= 51; dy++) {
                            end.getBlockAt(100 + dx, dy, dz).setType(Material.AIR);
                        }
                    }
                }
                event.setTo(platform);
            } else if (from.equals(end)) {
                Location back = player.getRespawnLocation();
                event.setTo(back != null ? back : overworld.getSpawnLocation());
            }
        }
        if (from.getName().startsWith(base)) {
            event.setCanCreatePortal(true);
        }
    }
}
