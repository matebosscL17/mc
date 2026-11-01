package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lobby: /lobby (visszateleportál), /lobby set, /lobby build (Penthouse stílusú lebegő platform építése).
 * A lobby egyben a világ spawn pontja. Az arany nyomólapról /rtp-vel indul a vadonba.
 */
public final class LobbyManager implements Listener, CommandExecutor {

    private record Op(int x, int y, int z, Material material) {
    }

    /** Építési terv: a középponthoz képesti koordinátákkal dolgozunk. */
    private static final class Planner {
        private final int cx;
        private final int cy;
        private final int cz;
        private final List<Op> ops = new ArrayList<>();

        private Planner(int cx, int cy, int cz) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
        }

        private void set(int dx, int dy, int dz, Material material) {
            ops.add(new Op(cx + dx, cy + dy, cz + dz, material));
        }

        private void disc(int radius, int dy, Material material) {
            int limit = radius * radius + radius;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= limit) {
                        set(dx, dy, dz, material);
                    }
                }
            }
        }
    }

    private static final String TAG = "smpcore_lobby";
    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.WATER, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.FIRE,
            Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE,
            Material.BUBBLE_COLUMN, Material.KELP, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.COBWEB);

    private final SMPCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private boolean building;

    public LobbyManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lobby.yml");
        this.data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        startDaylight();
    }

    // ---------- Lobby hely ----------

    /** A mentett lobby hely, vagy null, ha még nincs beállítva. */
    private Location lobby() {
        if (!data.contains("world")) {
            return null;
        }
        World world = Bukkit.getWorld(data.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, data.getDouble("x"), data.getDouble("y"), data.getDouble("z"),
                (float) data.getDouble("yaw"), (float) data.getDouble("pitch"));
    }

    private void saveLobby(Location loc) {
        data.set("world", loc.getWorld().getName());
        data.set("x", loc.getX());
        data.set("y", loc.getY());
        data.set("z", loc.getZ());
        data.set("yaw", (double) loc.getYaw());
        data.set("pitch", (double) loc.getPitch());
        try {
            plugin.getDataFolder().mkdirs();
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a lobby.yml-t: " + ex.getMessage());
        }
    }

    private Location lobbyOrSpawn() {
        Location lobby = lobby();
        return lobby != null ? lobby : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("rtp")) {
            if (args.length > 0 && BIOMES.containsKey(args[0].toLowerCase())) {
                randomTeleport(player, args[0].toLowerCase());
            } else {
                openBiomeMenu(player);
            }
            return true;
        }
        if (args.length == 0) {
            player.teleportAsync(lobbyOrSpawn());
            plugin.send(player, "lobby-tp");
            return true;
        }
        if (!player.hasPermission("smpcore.admin")) {
            plugin.send(player, "no-permission");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> {
                Location here = player.getLocation();
                saveLobby(here);
                here.getWorld().setSpawnLocation(here);
                plugin.send(player, "lobby-set");
            }
            case "build" -> build(player);
            default -> plugin.send(player, "lobby-usage");
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.getConfig().getBoolean("lobby.teleport-on-join", false)) {
            event.getPlayer().teleportAsync(lobbyOrSpawn());
        }
    }

    // ---------- Lobby építés ----------

    private void build(Player admin) {
        if (building) {
            plugin.send(admin, "lobby-building");
            return;
        }
        World world = admin.getWorld();
        if (!world.equals(Bukkit.getWorlds().get(0))) {
            plugin.send(admin, "lobby-wrong-world");
            return;
        }
        int radius = Math.max(12, Math.min(40, plugin.getConfig().getInt("lobby.platform-radius", 24)));
        int cx = admin.getLocation().getBlockX();
        int cz = admin.getLocation().getBlockZ();
        int cy = admin.getLocation().getBlockY() - 1;
        if (cy + 12 >= world.getMaxHeight() || cy - 12 <= world.getMinHeight()) {
            plugin.send(admin, "lobby-height");
            return;
        }

        List<Op> ops = plan(cx, cy, cz, radius);
        Location spawn = new Location(world, cx + 0.5, cy + 1, cz + radius / 2 + 0.5, 180f, 0f);
        building = true;
        plugin.send(admin, "lobby-build-start");

        new BukkitRunnable() {
            private int index = 0;

            @Override
            public void run() {
                int end = Math.min(index + 4000, ops.size());
                for (; index < end; index++) {
                    Op op = ops.get(index);
                    Block block = world.getBlockAt(op.x(), op.y(), op.z());
                    if (op.material().isAir() && block.getType().isAir()) {
                        continue;
                    }
                    block.setType(op.material(), false);
                }
                if (index >= ops.size()) {
                    cancel();
                    finishBuild(admin, world, spawn, cx, cy, cz, radius);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishBuild(Player admin, World world, Location spawn, int cx, int cy, int cz, int radius) {
        building = false;

        for (TextDisplay old : world.getEntitiesByClass(TextDisplay.class)) {
            if (old.getScoreboardTags().contains(TAG)) {
                old.remove();
            }
        }
        text(new Location(world, cx + 0.5, cy + 8.5, cz + 0.5),
                "<gradient:#ffd700:#ff8c00><bold>PENTHOUSE SMP", 5f);
        text(new Location(world, cx + 0.5, cy + 6.5, cz + 0.5), "<gray>Üdv a szerveren!", 2f);
        text(new Location(world, cx + radius / 2 + 0.5, cy + 3.5, cz + 0.5),
                "<gold><bold>INDULÁS A VILÁGBA<newline><gray>Állj az arany nyomólapra!", 2f);

        world.setSpawnLocation(spawn);
        saveLobby(spawn);
        plugin.getLogger().info("Lobby elkészült: " + cx + ", " + cy + ", " + cz);
        if (admin.isOnline()) {
            admin.teleportAsync(spawn);
            plugin.send(admin, "lobby-build-done");
        }
    }

    private void text(Location loc, String miniMessage, float scale) {
        loc.getWorld().spawn(loc, TextDisplay.class, display -> {
            display.text(plugin.parse(miniMessage));
            display.setBillboard(Display.Billboard.CENTER);
            display.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            display.addScoreboardTag(TAG);
        });
    }

    /** A teljes építési terv. Sorrend: felső lap (hogy a repülő építő rá tudjon állni), tér, alsó rész, részletek. */
    private List<Op> plan(int cx, int cy, int cz, int radius) {
        Planner p = new Planner(cx, cy, cz);
        double rimStart = (radius - 1.5) * (radius - 1.5);
        int limit = radius * radius + radius;

        // 1) Felső lap, fekete-fehér sakktábla, arany szegély
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > limit) {
                    continue;
                }
                Material top = d2 > rimStart ? Material.GOLD_BLOCK
                        : (Math.floorMod(dx + dz, 2) == 0 ? Material.QUARTZ_BLOCK : Material.POLISHED_BLACKSTONE);
                p.set(dx, 0, dz, top);
            }
        }

        // 2) Tér kitisztítása a platform fölött
        for (int dy = 1; dy <= 10; dy++) {
            p.disc(radius + 1, dy, Material.AIR);
        }

        // 3) Lebegő sziget alja: lefelé szűkülő kúp
        for (int k = 1; k <= 10; k++) {
            int r = Math.max(2, radius - 2 * k + 1);
            p.disc(r, -k, k % 2 == 0 ? Material.BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
        }

        // 4) Üvegkorlát az arany szegélyen
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= limit && d2 > rimStart) {
                    p.set(dx, 1, dz, Material.GLASS);
                    p.set(dx, 2, dz, Material.GLASS);
                }
            }
        }

        // 5) Oszlopok a korláton, tetejükön fény
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            int x = (int) Math.round((radius - 1) * Math.cos(angle));
            int z = (int) Math.round((radius - 1) * Math.sin(angle));
            for (int dy = 1; dy <= 4; dy++) {
                p.set(x, dy, z, Material.QUARTZ_PILLAR);
            }
            p.set(x, 5, z, Material.SEA_LANTERN);
        }

        // 6) Világító padló
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            int x = (int) Math.round(radius * 2.0 / 3.0 * Math.cos(angle));
            int z = (int) Math.round(radius * 2.0 / 3.0 * Math.sin(angle));
            p.set(x, 0, z, Material.SEA_LANTERN);
        }

        // 7) Középen szökőkút: kvarc medence, víz, oszlop
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= 12) {
                    p.set(dx, -1, dz, Material.SEA_LANTERN);
                    p.set(dx, 0, dz, Material.WATER);
                } else if (d2 <= 20) {
                    p.set(dx, 0, dz, Material.QUARTZ_BLOCK);
                    p.set(dx, 1, dz, Material.QUARTZ_BLOCK);
                }
            }
        }
        p.set(0, 0, 0, Material.GOLD_BLOCK);
        for (int dy = 1; dy <= 3; dy++) {
            p.set(0, dy, 0, Material.QUARTZ_PILLAR);
        }
        p.set(0, 4, 0, Material.SEA_LANTERN);

        // 8) Indító pad: arany szegély, fekete közép, arany nyomólap
        int padX = radius / 2;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
                p.set(padX + dx, 0, dz, edge ? Material.GOLD_BLOCK : Material.POLISHED_BLACKSTONE);
            }
        }
        p.set(padX, 1, 0, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);

        return p.ops;
    }

    // ---------- Véletlen teleport ----------

    @EventHandler
    public void onPad(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || !plugin.getConfig().getBoolean("rtp.pad-enabled", true)) {
            return;
        }
        Location lobby = lobby();
        Location plate = event.getClickedBlock().getLocation();
        if (lobby == null || !lobby.getWorld().equals(plate.getWorld())) {
            return;
        }
        double dx = lobby.getX() - plate.getX();
        double dz = lobby.getZ() - plate.getZ();
        if (dx * dx + dz * dz > 50 * 50) {
            return;
        }
        openBiomeMenu(event.getPlayer());
    }

    private void randomTeleport(Player player, String biomeGroup) {
        if (!plugin.getConfig().getBoolean("rtp.enabled", true)) {
            plugin.send(player, "rtp-disabled");
            return;
        }
        UUID id = player.getUniqueId();
        if (searching.contains(id)) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("rtp.cooldown-seconds", 30) * 1000L;
        Long last = cooldowns.get(id);
        if (last != null && now - last < cooldown && !player.hasPermission("smpcore.admin")) {
            long left = (cooldown - (now - last)) / 1000L + 1;
            plugin.send(player, "rtp-cooldown", Placeholder.unparsed("seconds", String.valueOf(left)));
            return;
        }
        searching.add(id);
        tryRandom(player, plugin.survival().world(), biomeGroup, 0);
    }

    // ---------- Biom választó ----------

    private record BiomeGroup(String label, Material icon, Set<String> biomes) {
    }

    private static final Map<String, BiomeGroup> BIOMES = new LinkedHashMap<>();

    private static void group(String id, String label, Material icon, String... biomes) {
        BIOMES.put(id, new BiomeGroup(label, icon, Set.of(biomes)));
    }

    static {
        group("síkság", "<green>Síkság", Material.GRASS_BLOCK, "plains", "sunflower_plains", "meadow");
        group("erdő", "<dark_green>Erdő", Material.OAK_SAPLING, "forest", "flower_forest", "birch_forest",
                "old_growth_birch_forest", "dark_forest");
        group("tajga", "<dark_aqua>Tajga", Material.SPRUCE_SAPLING, "taiga", "old_growth_pine_taiga",
                "old_growth_spruce_taiga");
        group("havas", "<aqua>Havas vidék", Material.SNOW_BLOCK, "snowy_plains", "snowy_taiga", "ice_spikes",
                "snowy_slopes", "grove");
        group("sivatag", "<yellow>Sivatag", Material.SAND, "desert");
        group("badlands", "<gold>Badlands", Material.TERRACOTTA, "badlands", "eroded_badlands", "wooded_badlands");
        group("szavanna", "<#d4a017>Szavanna", Material.ACACIA_LOG, "savanna", "savanna_plateau",
                "windswept_savanna");
        group("dzsungel", "<#32cd32>Dzsungel", Material.JUNGLE_SAPLING, "jungle", "sparse_jungle", "bamboo_jungle");
        group("mocsár", "<#556b2f>Mocsár", Material.LILY_PAD, "swamp", "mangrove_swamp");
        group("hegység", "<gray>Hegység", Material.STONE, "windswept_hills", "windswept_forest",
                "windswept_gravelly_hills", "stony_peaks", "jagged_peaks", "frozen_peaks");
        group("véletlen", "<light_purple>Véletlen hely", Material.ENDER_PEARL);
    }

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private void openBiomeMenu(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.parse("<gradient:#ffd700:#ff8c00><bold>Hová szeretnél menni?"));
        holder.inventory = inv;
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.displayName(plugin.parse(" "));
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, pane);
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22};
        int i = 0;
        for (Map.Entry<String, BiomeGroup> e : BIOMES.entrySet()) {
            if (i >= slots.length) {
                break;
            }
            ItemStack item = new ItemStack(e.getValue().icon());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(plugin.parse("<!italic>" + e.getValue().label()));
            meta.lore(List.of(plugin.parse("<!italic><gray>Kattints: véletlen helyre teleportálsz"),
                    plugin.parse("<!italic><gray>ebben a biomban.")));
            item.setItemMeta(meta);
            inv.setItem(slots[i++], item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22};
        int index = -1;
        for (int k = 0; k < slots.length; k++) {
            if (slots[k] == event.getSlot()) {
                index = k;
            }
        }
        if (index < 0 || index >= BIOMES.size()) {
            return;
        }
        String id = new ArrayList<>(BIOMES.keySet()).get(index);
        player.closeInventory();
        randomTeleport(player, id);
    }

    // ---------- Mindig nappal a spawnban ----------

    private final Set<UUID> dayLocked = new HashSet<>();

    private void startDaylight() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean inSpawn = plugin.spawn().isProtected(p.getLocation());
                if (inSpawn) {
                    p.setPlayerTime(6000, false);
                    dayLocked.add(p.getUniqueId());
                } else if (dayLocked.remove(p.getUniqueId())) {
                    p.resetPlayerTime();
                }
            }
        }, 40L, 40L);
    }

    private void tryRandom(Player player, World world, String biomeGroup, int attempt) {
        if (!player.isOnline()) {
            searching.remove(player.getUniqueId());
            return;
        }
        if (attempt >= 40) {
            // Végső tartalék: nem hagyjuk üzenettel abba, hanem az égből ejtjük le
            double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double d = ThreadLocalRandom.current().nextDouble(300, 1500);
            Location sky = new Location(world, world.getWorldBorder().getCenter().getX() + Math.cos(a) * d,
                    300, world.getWorldBorder().getCenter().getZ() + Math.sin(a) * d);
            player.teleportAsync(sky).thenAccept(ok -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                searching.remove(player.getUniqueId());
                if (ok && player.isOnline()) {
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                    int ticks = 20 * 45;
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true));
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.RESISTANCE, ticks, 4, false, false, true));
                }
            }));
            return;
        }
        WorldBorder border = world.getWorldBorder();
        double min = plugin.getConfig().getDouble("rtp.min-radius", 300);
        double max = Math.min(plugin.getConfig().getDouble("rtp.max-radius", 3000), border.getSize() / 2 - 16);
        if (max <= min) {
            min = 0;
        }
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double distance = ThreadLocalRandom.current().nextDouble(min, Math.max(min + 1, max));
        int x = (int) Math.floor(border.getCenter().getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(border.getCenter().getZ() + Math.sin(angle) * distance);
        BiomeGroup group = BIOMES.get(biomeGroup);
        if (group != null && !group.biomes().isEmpty()) {
            boolean found = false;
            for (int i = 0; i < 400 && !found; i++) {
                double a = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
                double d = ThreadLocalRandom.current().nextDouble(min, Math.max(min + 1, max));
                int sx = (int) Math.floor(border.getCenter().getX() + Math.cos(a) * d);
                int sz = (int) Math.floor(border.getCenter().getZ() + Math.sin(a) * d);
                if (group.biomes().contains(world.getBiome(sx, 64, sz).getKey().getKey())) {
                    x = sx;
                    z = sz;
                    found = true;
                }
            }
            if (!found) {
                tryRandom(player, world, "véletlen", attempt + 5);
                return;
            }
        }
        final int fx = x;
        final int fz = z;

        world.getChunkAtAsync(fx >> 4, fz >> 4).thenAccept(chunk ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    int y = world.getHighestBlockYAt(fx, fz);
                    Material ground = world.getBlockAt(fx, y, fz).getType();
                    if (y <= world.getMinHeight() || UNSAFE.contains(ground)) {
                        tryRandom(player, world, biomeGroup, attempt + 1);
                        return;
                    }
                    Location target = new Location(world, fx + 0.5, y + 1, fz + 0.5);
                    player.teleportAsync(target).thenAccept(ok -> {
                        searching.remove(player.getUniqueId());
                        if (ok) {
                            cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                            plugin.send(player, "rtp-done",
                                    Placeholder.unparsed("x", String.valueOf(fx)),
                                    Placeholder.unparsed("z", String.valueOf(fz)));
                        }
                    });
                }));
    }
}
