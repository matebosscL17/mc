package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Területvédelem chunkonként: /claim megvesz egy chunkot (pénzért), csak te és a megbízott játékosok építhetnek,
 * nyithatnak ládát, bánthatják az állataidat. Robbanás, tűz és víz/láva sem megy át a védett területre.
 */
public final class ClaimManager implements Listener, TabExecutor {

    private final SMPCore plugin;
    private final File file;
    /** "világ;cx;cz" -> tulajdonos */
    private final Map<String, UUID> claims = new LinkedHashMap<>();
    /** tulajdonos -> megbízott játékosok (minden chunkjára érvényes) */
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
    private final Map<UUID, int[]> lastChunk = new HashMap<>();

    public ClaimManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
        load();
    }

    // ---------- Mentés / betöltés ----------

    private static String key(String world, int cx, int cz) {
        return world + ";" + cx + ";" + cz;
    }

    private static String key(Location loc) {
        return key(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("claims")) {
            try {
                claims.put(key((String) m.get("world"), ((Number) m.get("x")).intValue(),
                        ((Number) m.get("z")).intValue()), UUID.fromString((String) m.get("owner")));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Hibás claim a claims.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
        ConfigurationSection t = yaml.getConfigurationSection("trust");
        if (t != null) {
            for (String owner : t.getKeys(false)) {
                try {
                    Set<UUID> set = new HashSet<>();
                    for (String s : t.getStringList(owner)) {
                        set.add(UUID.fromString(s));
                    }
                    trusted.put(UUID.fromString(owner), set);
                } catch (IllegalArgumentException ignored) {
                    // hibás sor kihagyva
                }
            }
        }
    }

    public void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, UUID> e : claims.entrySet()) {
            String[] p = e.getKey().split(";");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", p[0]);
            m.put("x", Integer.parseInt(p[1]));
            m.put("z", Integer.parseInt(p[2]));
            m.put("owner", e.getValue().toString());
            list.add(m);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("claims", list);
        for (Map.Entry<UUID, Set<UUID>> e : trusted.entrySet()) {
            List<String> ids = new ArrayList<>();
            e.getValue().forEach(u -> ids.add(u.toString()));
            yaml.set("trust." + e.getKey(), ids);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a claims.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Lekérdezések ----------

    private boolean enabled() {
        return plugin.getConfig().getBoolean("claims.enabled", true);
    }

    private UUID ownerAt(Location loc) {
        return loc.getWorld() == null ? null : claims.get(key(loc));
    }

    private boolean canBuild(Player player, Location loc) {
        if (!enabled()) {
            return true;
        }
        UUID owner = ownerAt(loc);
        if (owner == null || owner.equals(player.getUniqueId()) || player.hasPermission("smpcore.admin")) {
            return true;
        }
        return trusted.getOrDefault(owner, Set.of()).contains(player.getUniqueId());
    }

    private void deny(Player player, Location loc) {
        UUID owner = ownerAt(loc);
        player.sendActionBar(plugin.msg("claim-denied",
                Placeholder.unparsed("owner", owner == null ? "?" : plugin.name(owner))));
    }

    private int count(UUID owner) {
        int n = 0;
        for (UUID u : claims.values()) {
            if (u.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    // ---------- Védelem ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Action action = event.getAction();
        boolean trample = action == Action.PHYSICAL && block.getType() == Material.FARMLAND;
        boolean use = action == Action.RIGHT_CLICK_BLOCK && block.getType().isInteractable()
                && !event.getPlayer().isSneaking();
        if ((trample || use) && !canBuild(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
            if (use) {
                deny(event.getPlayer(), block.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Monster || entity instanceof Player) {
            return;
        }
        if (!canBuild(event.getPlayer(), entity.getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer(), entity.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!canBuild(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer(), event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Monster || victim instanceof Player) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player player && !canBuild(player, victim.getLocation())) {
            event.setCancelled(true);
            deny(player, victim.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (remover instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            remover = shooter;
        }
        if (remover instanceof Player player && !canBuild(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && !canBuild(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!canBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (enabled()) {
            event.blockList().removeIf(b -> ownerAt(b.getLocation()) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (enabled()) {
            event.blockList().removeIf(b -> ownerAt(b.getLocation()) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (enabled() && ownerAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!enabled() || ownerAt(event.getBlock().getLocation()) == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !canBuild(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobGrief(EntityChangeBlockEvent event) {
        Entity e = event.getEntity();
        boolean griefer = e instanceof Enderman || e instanceof Ravager || e instanceof Wither
                || e instanceof Silverfish;
        if (enabled() && griefer && ownerAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (!enabled()) {
            return;
        }
        UUID to = ownerAt(event.getToBlock().getLocation());
        if (to == null) {
            return;
        }
        UUID from = ownerAt(event.getBlock().getLocation());
        if (!to.equals(from)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!enabled()) {
            return;
        }
        UUID pistonOwner = ownerAt(event.getBlock().getLocation());
        List<Block> targets = new ArrayList<>();
        targets.add(event.getBlock().getRelative(event.getDirection()));
        for (Block b : event.getBlocks()) {
            targets.add(b);
            targets.add(b.getRelative(event.getDirection()));
        }
        for (Block b : targets) {
            UUID owner = ownerAt(b.getLocation());
            if (owner != null && !owner.equals(pistonOwner)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!enabled()) {
            return;
        }
        UUID pistonOwner = ownerAt(event.getBlock().getLocation());
        for (Block b : event.getBlocks()) {
            UUID owner = ownerAt(b.getLocation());
            if (owner != null && !owner.equals(pistonOwner)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---------- Belépés / kilépés jelzés ----------

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!enabled() || !event.hasChangedBlock()) {
            return;
        }
        Location to = event.getTo();
        int cx = to.getBlockX() >> 4;
        int cz = to.getBlockZ() >> 4;
        Player player = event.getPlayer();
        int[] last = lastChunk.get(player.getUniqueId());
        if (last != null && last[0] == cx && last[1] == cz) {
            return;
        }
        lastChunk.put(player.getUniqueId(), new int[]{cx, cz});
        UUID owner = claims.get(key(to.getWorld().getName(), cx, cz));
        UUID before = null;
        if (last != null) {
            before = claims.get(key(event.getFrom().getWorld().getName(), last[0], last[1]));
        }
        if (owner != null && !owner.equals(before)) {
            player.sendActionBar(plugin.msg("claim-enter", Placeholder.unparsed("owner", plugin.name(owner))));
        } else if (owner == null && before != null) {
            player.sendActionBar(plugin.msg("claim-wild"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastChunk.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (!enabled()) {
            plugin.send(player, "claim-disabled");
            return true;
        }
        if (command.getName().equalsIgnoreCase("unclaim")) {
            unclaim(player);
            return true;
        }
        String sub = args.length == 0 ? "claim" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "claim", "buy" -> claim(player);
            case "unclaim" -> unclaim(player);
            case "trust" -> trust(player, args, true);
            case "untrust" -> trust(player, args, false);
            case "info" -> info(player);
            case "list" -> list(player);
            case "show" -> show(player);
            default -> plugin.send(player, "claim-usage");
        }
        return true;
    }

    private void claim(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        String k = key(player.getLocation());
        List<String> worlds = plugin.getConfig().getStringList("claims.worlds");
        if (!worlds.isEmpty() && !worlds.contains(player.getWorld().getName())) {
            plugin.send(player, "claim-world");
            return;
        }
        UUID existing = claims.get(k);
        if (existing != null) {
            plugin.send(player, existing.equals(player.getUniqueId()) ? "claim-yours" : "claim-taken",
                    Placeholder.unparsed("owner", plugin.name(existing)));
            return;
        }
        World main = Bukkit.getWorlds().get(0);
        if (player.getWorld().equals(main)) {
            Location spawn = main.getSpawnLocation();
            double radius = plugin.getConfig().getDouble("spawn-protection.radius", 30) + 16;
            double dx = player.getLocation().getX() - spawn.getX();
            double dz = player.getLocation().getZ() - spawn.getZ();
            if (dx * dx + dz * dz <= radius * radius) {
                plugin.send(player, "claim-near-spawn");
                return;
            }
        }
        boolean admin = player.hasPermission("smpcore.admin");
        int max = plugin.getConfig().getInt("claims.max-per-player", 9);
        if (!admin && count(player.getUniqueId()) >= max) {
            plugin.send(player, "claim-limit", Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        long price = admin ? 0 : plugin.getConfig().getLong("claims.price", 2000);
        if (price > 0 && !plugin.economy().withdraw(player.getUniqueId(), price)) {
            plugin.send(player, "not-enough-money", Placeholder.unparsed("amount", plugin.money(price)));
            return;
        }
        claims.put(k, player.getUniqueId());
        save();
        plugin.send(player, "claim-done", Placeholder.unparsed("x", String.valueOf(chunk.getX())),
                Placeholder.unparsed("z", String.valueOf(chunk.getZ())),
                Placeholder.unparsed("price", plugin.money(price)));
        show(player);
    }

    private void unclaim(Player player) {
        String k = key(player.getLocation());
        UUID owner = claims.get(k);
        if (owner == null) {
            plugin.send(player, "claim-not-claimed");
            return;
        }
        boolean admin = player.hasPermission("smpcore.admin");
        if (!owner.equals(player.getUniqueId()) && !admin) {
            plugin.send(player, "claim-taken", Placeholder.unparsed("owner", plugin.name(owner)));
            return;
        }
        claims.remove(k);
        long refund = plugin.getConfig().getLong("claims.price", 2000)
                * plugin.getConfig().getInt("claims.refund-percent", 50) / 100;
        if (refund > 0) {
            plugin.economy().add(owner, refund);
        }
        save();
        plugin.send(player, "claim-removed", Placeholder.unparsed("refund", plugin.money(Math.max(0, refund))));
    }

    private void trust(Player player, String[] args, boolean add) {
        if (args.length < 2) {
            plugin.send(player, "claim-usage");
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[1]);
        if (target == null || target.getUniqueId().equals(player.getUniqueId())) {
            plugin.send(player, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        Set<UUID> set = trusted.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        if (add) {
            set.add(target.getUniqueId());
        } else {
            set.remove(target.getUniqueId());
        }
        save();
        plugin.send(player, add ? "claim-trusted" : "claim-untrusted", Placeholder.unparsed("player", args[1]));
    }

    private void info(Player player) {
        UUID owner = claims.get(key(player.getLocation()));
        if (owner == null) {
            plugin.send(player, "claim-not-claimed");
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID u : trusted.getOrDefault(owner, Set.of())) {
            names.add(plugin.name(u));
        }
        plugin.send(player, "claim-info", Placeholder.unparsed("owner", plugin.name(owner)),
                Placeholder.unparsed("trusted", names.isEmpty() ? "-" : String.join(", ", names)));
    }

    private void list(Player player) {
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, UUID> e : claims.entrySet()) {
            if (e.getValue().equals(player.getUniqueId())) {
                String[] p = e.getKey().split(";");
                if (n < 20) {
                    if (n > 0) {
                        sb.append(", ");
                    }
                    sb.append("(").append(Integer.parseInt(p[1]) * 16 + 8).append(", ")
                            .append(Integer.parseInt(p[2]) * 16 + 8).append(")");
                }
                n++;
            }
        }
        plugin.send(player, "claim-list", Placeholder.unparsed("count", String.valueOf(n)),
                Placeholder.unparsed("max", String.valueOf(plugin.getConfig().getInt("claims.max-per-player", 9))),
                Placeholder.unparsed("places", n == 0 ? "-" : sb.toString()));
    }

    /** A chunk határát jelzi részecskékkel pár másodpercig (csak a játékos látja). */
    private void show(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        int x0 = chunk.getX() * 16;
        int z0 = chunk.getZ() * 16;
        double baseY = player.getLocation().getY();
        final int[] ticks = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || ticks[0]++ >= 12) {
                task.cancel();
                return;
            }
            for (int i = 0; i <= 16; i += 2) {
                for (double dy = -1; dy <= 3; dy += 2) {
                    double y = baseY + dy;
                    player.spawnParticle(Particle.END_ROD, x0 + i, y, z0, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.END_ROD, x0 + i, y, z0 + 16, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.END_ROD, x0, y, z0 + i, 1, 0, 0, 0, 0);
                    player.spawnParticle(Particle.END_ROD, x0 + 16, y, z0 + i, 1, 0, 0, 0, 0);
                }
            }
        }, 0L, 10L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("unclaim")) {
            return out;
        }
        if (args.length == 1) {
            out.addAll(List.of("claim", "unclaim", "trust", "untrust", "info", "list", "show"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
