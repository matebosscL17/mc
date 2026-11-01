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
    /** kijelölt védett téglalapok a fő világban (minX, maxX, minZ, maxZ); ha üres, a sugár számít */
    private final java.util.List<int[]> regions = new java.util.ArrayList<>();
    private final java.util.Map<java.util.UUID, int[]> selection = new java.util.HashMap<>();

    public SpawnManager(SMPCore plugin) {
        this.plugin = plugin;
        this.zoneFile = new File(plugin.getDataFolder(), "spawnzone.yml");
        if (zoneFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(zoneFile);
            for (String line : yaml.getStringList("regions")) {
                try {
                    String[] p = line.split(",");
                    regions.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                            Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())});
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Hibás sor a spawnzone.yml-ben: " + line);
                }
            }
        }
    }

    private void saveRegions() {
        YamlConfiguration yaml = new YamlConfiguration();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int[] r : regions) {
            lines.add(r[0] + "," + r[1] + "," + r[2] + "," + r[3]);
        }
        yaml.set("regions", lines);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(zoneFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a spawnzone.yml-t: " + ex.getMessage());
        }
    }

    public void addRegion(int x1, int z1, int x2, int z2) {
        regions.add(new int[]{Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2)});
        saveRegions();
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
        if (!regions.isEmpty()) {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            for (int[] r : regions) {
                if (x >= r[0] && x <= r[1] && z >= r[2] && z <= r[3]) {
                    return true;
                }
            }
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
                    addRegion(sel[0], sel[1], sel[2], sel[3]);
                    selection.remove(player.getUniqueId());
                    plugin.send(player, "zone-set", Placeholder.unparsed("size",
                            (Math.abs(sel[0] - sel[2]) + 1) + " x " + (Math.abs(sel[1] - sel[3]) + 1)));
                }
            }
            case "set", "add" -> {
                if (args.length < 5) {
                    plugin.send(player, "zone-usage");
                    return true;
                }
                try {
                    int x1 = Integer.parseInt(args[1]);
                    int z1 = Integer.parseInt(args[2]);
                    int x2 = Integer.parseInt(args[3]);
                    int z2 = Integer.parseInt(args[4]);
                    addRegion(x1, z1, x2, z2);
                    plugin.send(player, "zone-set", Placeholder.unparsed("size",
                            (Math.abs(x1 - x2) + 1) + " x " + (Math.abs(z1 - z2) + 1)));
                } catch (NumberFormatException ex) {
                    plugin.send(player, "zone-usage");
                }
            }
            case "undo" -> {
                if (!regions.isEmpty()) {
                    regions.remove(regions.size() - 1);
                    saveRegions();
                }
                plugin.send(player, "zone-undone");
            }
            case "clear" -> {
                regions.clear();
                selection.remove(player.getUniqueId());
                saveRegions();
                plugin.send(player, "zone-cleared");
            }
            case "show" -> show(player);
            case "info" -> {
                if (regions.isEmpty()) {
                    plugin.send(player, "zone-radius", Placeholder.unparsed("radius",
                            String.valueOf(plugin.getConfig().getDouble("spawn-protection.radius", 24))));
                }
                for (int[] r : regions) {
                    plugin.send(player, "zone-info", Placeholder.unparsed("x1", String.valueOf(r[0])),
                            Placeholder.unparsed("z1", String.valueOf(r[2])),
                            Placeholder.unparsed("x2", String.valueOf(r[1])),
                            Placeholder.unparsed("z2", String.valueOf(r[3])));
                }
            }
            default -> plugin.send(player, "zone-usage");
        }
        return true;
    }

    /** A védett terület szélét mutatja részecskékkel (csak a közeli szakaszt, pár másodpercig). */
    private void show(Player player) {
        java.util.List<int[]> rs = new java.util.ArrayList<>(regions);
        double radius = plugin.getConfig().getDouble("spawn-protection.radius", 24);
        Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        final int[] ticks = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || ticks[0]++ >= 16) {
                task.cancel();
                return;
            }
            double y = player.getLocation().getY() + 1;
            double px = player.getLocation().getX();
            double pz = player.getLocation().getZ();
            if (!rs.isEmpty()) {
                for (int[] r : rs) {
                    for (int i = -48; i <= 48; i += 3) {
                        double[][] pts = {{Math.floor(px) + i, r[2]}, {Math.floor(px) + i, r[3] + 1},
                                {r[0], Math.floor(pz) + i}, {r[1] + 1, Math.floor(pz) + i}};
                        for (double[] p : pts) {
                            boolean onEdge = p[0] >= r[0] && p[0] <= r[1] + 1 && p[1] >= r[2] && p[1] <= r[3] + 1;
                            if (onEdge) {
                                player.spawnParticle(Particle.END_ROD, p[0], y, p[1], 1, 0, 0, 0, 0);
                            }
                        }
                    }
                }
            } else {
                for (int a = 0; a < 360; a += 6) {
                    double rad = Math.toRadians(a);
                    double x = spawn.getX() + Math.cos(rad) * radius;
                    double z = spawn.getZ() + Math.sin(rad) * radius;
                    if (Math.abs(x - px) <= 48 && Math.abs(z - pz) <= 48) {
                        player.spawnParticle(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                    }
                }
            }
        }, 0L, 10L);
    }

    private boolean bypass(Player player) {
        return player.hasPermission("smpcore.admin") && player.getGameMode() == org.bukkit.GameMode.CREATIVE;
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
