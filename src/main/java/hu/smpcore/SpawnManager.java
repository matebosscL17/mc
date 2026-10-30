package hu.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Enderman;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
public final class SpawnManager implements Listener, CommandExecutor {

    private final SMPCore plugin;
    private final File zoneFile;
    /** kijelölt védett téglalap (a fő világban), vagy null, ha nincs: ekkor a sugár számít */
    private int[] region; // minX, maxX, minZ, maxZ
    private final java.util.Map<java.util.UUID, int[]> selection = new java.util.HashMap<>();

    public SpawnManager(SMPCore plugin) {
        this.plugin = plugin;
        this.zoneFile = new File(plugin.getDataFolder(), "spawnzone.yml");
        if (zoneFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(zoneFile);
            if (yaml.contains("min-x")) {
                region = new int[]{yaml.getInt("min-x"), yaml.getInt("max-x"),
                        yaml.getInt("min-z"), yaml.getInt("max-z")};
            }
        }
    }

    private void saveRegion() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (region != null) {
            yaml.set("min-x", region[0]);
            yaml.set("max-x", region[1]);
            yaml.set("min-z", region[2]);
            yaml.set("max-z", region[3]);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(zoneFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a spawnzone.yml-t: " + ex.getMessage());
        }
    }

    /** Védett terület-e ez a hely (kívülről is hívható). */
    public boolean isProtected(Location loc) {
        return inZone(loc);
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
        if (plugin.getConfig().getBoolean("spawn-protection.whole-world", false)) {
            return true;
        }
        if (region != null) {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            return x >= region[0] && x <= region[1] && z >= region[2] && z <= region[3];
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

    // ---------- /spawnzone ----------

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
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "pos1", "pos2" -> {
                int[] sel = selection.computeIfAbsent(player.getUniqueId(), k -> new int[]{0, 0, 0, 0, 0});
                int i = sub.equals("pos1") ? 0 : 2;
                sel[i] = player.getLocation().getBlockX();
                sel[i + 1] = player.getLocation().getBlockZ();
                sel[4] |= sub.equals("pos1") ? 1 : 2;
                plugin.send(player, "zone-pos", Placeholder.unparsed("pos", sub),
                        Placeholder.unparsed("x", String.valueOf(sel[i])),
                        Placeholder.unparsed("z", String.valueOf(sel[i + 1])));
                if (sel[4] == 3) {
                    region = new int[]{Math.min(sel[0], sel[2]), Math.max(sel[0], sel[2]),
                            Math.min(sel[1], sel[3]), Math.max(sel[1], sel[3])};
                    saveRegion();
                    plugin.send(player, "zone-set", Placeholder.unparsed("size",
                            (region[1] - region[0] + 1) + " x " + (region[3] - region[2] + 1)));
                }
            }
            case "clear" -> {
                region = null;
                selection.remove(player.getUniqueId());
                saveRegion();
                plugin.send(player, "zone-cleared");
            }
            case "show" -> show(player);
            case "info" -> {
                if (region == null) {
                    plugin.send(player, "zone-radius", Placeholder.unparsed("radius",
                            String.valueOf(plugin.getConfig().getDouble("spawn-protection.radius", 24))));
                } else {
                    plugin.send(player, "zone-info", Placeholder.unparsed("x1", String.valueOf(region[0])),
                            Placeholder.unparsed("z1", String.valueOf(region[2])),
                            Placeholder.unparsed("x2", String.valueOf(region[1])),
                            Placeholder.unparsed("z2", String.valueOf(region[3])));
                }
            }
            default -> plugin.send(player, "zone-usage");
        }
        return true;
    }

    /** A védett terület szélét mutatja részecskékkel (csak a közeli szakaszt, pár másodpercig). */
    private void show(Player player) {
        int[] r = region;
        World world = player.getWorld();
        double radius = plugin.getConfig().getDouble("spawn-protection.radius", 24);
        Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        final int[] ticks = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || ticks[0]++ >= 12) {
                task.cancel();
                return;
            }
            double y = player.getLocation().getY() + 1;
            double px = player.getLocation().getX();
            double pz = player.getLocation().getZ();
            if (r != null) {
                for (int i = -40; i <= 40; i += 3) {
                    for (double[] p : new double[][]{{px + i, r[2]}, {px + i, r[3] + 1}, {r[0], pz + i}, {r[1] + 1, pz + i}}) {
                        boolean inside = p[0] >= r[0] - 1 && p[0] <= r[1] + 2 && p[1] >= r[2] - 1 && p[1] <= r[3] + 2;
                        if (inside && Math.abs(p[0] - px) <= 40 && Math.abs(p[1] - pz) <= 40) {
                            player.spawnParticle(Particle.END_ROD, p[0], y, p[1], 1, 0, 0, 0, 0);
                        }
                    }
                }
            } else {
                for (int a = 0; a < 360; a += 6) {
                    double rad = Math.toRadians(a);
                    double x = spawn.getX() + Math.cos(rad) * radius;
                    double z = spawn.getZ() + Math.sin(rad) * radius;
                    if (Math.abs(x - px) <= 40 && Math.abs(z - pz) <= 40) {
                        player.spawnParticle(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                    }
                }
            }
        }, 0L, 10L);
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
        if (damager instanceof Player attacker && (inZone(victim.getLocation()) || inZone(attacker.getLocation()))) {
            event.setCancelled(true);
            attacker.sendActionBar(plugin.msg("spawn-no-pvp"));
        }
    }

    /** A spawn területén senki nem sebződik (a világ alá esést és a /kill-t kivéve). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.getConfig().getBoolean("spawn-protection.damage", false)
                || !(event.getEntity() instanceof Player player) || !inZone(player.getLocation())) {
            return;
        }
        switch (event.getCause()) {
            case VOID, KILL, SUICIDE, WORLD_BORDER -> { }
            default -> event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && inZone(player.getLocation())
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (inZone(event.getBlock().getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(plugin.msg("spawn-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (inZone(event.getBlock().getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(plugin.msg("spawn-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (remover instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            remover = shooter;
        }
        if (inZone(event.getEntity().getLocation())
                && !(remover instanceof Player player && bypass(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (inZone(event.getRightClicked().getLocation()) && !bypass(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.FARMLAND
                && inZone(event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (inZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (inZone(event.getBlock().getLocation())
                && (event.getPlayer() == null || !bypass(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobGrief(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player) && inZone(event.getBlock().getLocation())
                && !(event.getEntity() instanceof org.bukkit.entity.FallingBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> inZone(block.getLocation()));
    }
}
