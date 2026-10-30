package hu.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/** Kezdőcsomag első belépéskor és védett spawn terület (építés és PvP tiltva). */
public final class SpawnManager implements Listener {

    private final SMPCore plugin;

    public SpawnManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    // ---------- Kezdőcsomag ----------

    @EventHandler
    public void onFirstJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore() || !plugin.getConfig().getBoolean("starter-kit.enabled", true)) {
            return;
        }
        for (String entry : plugin.getConfig().getStringList("starter-kit.items")) {
            String[] parts = entry.split(":");
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("Ismeretlen tárgy a starter-kit configban: " + entry);
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // marad 1
                }
            }
            for (ItemStack left : player.getInventory().addItem(new ItemStack(material, amount)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        plugin.send(player, "kit-given");
    }

    // ---------- Spawn védelem ----------

    private boolean inZone(Location loc) {
        if (!plugin.getConfig().getBoolean("spawn-protection.enabled", true) || loc.getWorld() == null) {
            return false;
        }
        World main = Bukkit.getWorlds().get(0);
        if (!loc.getWorld().equals(main)) {
            return false;
        }
        Location spawn = main.getSpawnLocation();
        double radius = plugin.getConfig().getDouble("spawn-protection.radius", 24);
        double vertical = plugin.getConfig().getDouble("spawn-protection.vertical", 40);
        if (vertical > 0 && Math.abs(loc.getY() - spawn.getY()) > vertical) {
            return false;
        }
        double dx = loc.getX() - spawn.getX();
        double dz = loc.getZ() - spawn.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean bypass(Player player) {
        return player.hasPermission("smpcore.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (inZone(event.getBlock().getLocation()) && !bypass(player)) {
            event.setCancelled(true);
            player.sendActionBar(plugin.msg("spawn-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (inZone(event.getBlock().getLocation()) && !bypass(player)) {
            event.setCancelled(true);
            player.sendActionBar(plugin.msg("spawn-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (plugin.getConfig().getBoolean("spawn-protection.pvp", false)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            damager = shooter;
        }
        if (damager instanceof Player attacker && inZone(victim.getLocation())) {
            event.setCancelled(true);
            attacker.sendActionBar(plugin.msg("spawn-no-pvp"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> inZone(block.getLocation()));
    }
}
