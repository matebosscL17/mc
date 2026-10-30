package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sírkő rendszer: halálkor a tárgyak egy játékos-fej blokkba kerülnek a halál helyén.
 * Jobb klikk a fejre = kinyitás. Az első pár percben csak a tulajdonos nyithatja.
 */
public final class GraveManager implements Listener, CommandExecutor {

    private record Grave(String world, int x, int y, int z, UUID owner, long created,
                         int exp, List<ItemStack> items) {
    }

    private final SMPCore plugin;
    private final File file;
    private final Map<String, Grave> graves = new HashMap<>();

    public GraveManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "graves.yml");
        load();
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private static String key(Grave g) {
        return g.world() + ";" + g.x() + ";" + g.y() + ";" + g.z();
    }

    // ---------- Mentés / betöltés ----------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("graves")) {
            try {
                List<ItemStack> items = new ArrayList<>();
                for (Object o : (List<?>) m.get("items")) {
                    items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode((String) o)));
                }
                Grave grave = new Grave(
                        (String) m.get("world"),
                        ((Number) m.get("x")).intValue(),
                        ((Number) m.get("y")).intValue(),
                        ((Number) m.get("z")).intValue(),
                        UUID.fromString((String) m.get("owner")),
                        ((Number) m.get("created")).longValue(),
                        ((Number) m.get("exp")).intValue(),
                        items);
                graves.put(key(grave), grave);
            } catch (Exception ex) {
                plugin.getLogger().warning("Hibás sír a graves.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
    }

    private void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Grave g : graves.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", g.world());
            m.put("x", g.x());
            m.put("y", g.y());
            m.put("z", g.z());
            m.put("owner", g.owner().toString());
            m.put("created", g.created());
            m.put("exp", g.exp());
            List<String> items = new ArrayList<>();
            for (ItemStack item : g.items()) {
                items.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
            m.put("items", items);
            list.add(m);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("graves", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a graves.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Halál ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("graves.enabled", true) || event.getKeepInventory()) {
            return;
        }
        Player player = event.getEntity();
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack item : event.getDrops()) {
            if (item != null && !item.getType().isAir()) {
                drops.add(item.clone());
            }
        }
        if (drops.isEmpty()) {
            return;
        }
        Block spot = findSpot(player.getLocation().getBlock());
        if (spot == null) {
            return; // nincs hely sírnak (pl. void), a tárgyak normálisan leesnek
        }

        spot.setType(Material.PLAYER_HEAD);
        BlockState state = spot.getState();
        if (state instanceof Skull skull) {
            skull.setOwningPlayer(player);
            skull.update();
        }

        int exp = 0;
        if (plugin.getConfig().getBoolean("graves.keep-exp", true)) {
            exp = event.getDroppedExp();
            event.setDroppedExp(0);
        }
        event.getDrops().clear();

        Grave grave = new Grave(spot.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ(),
                player.getUniqueId(), System.currentTimeMillis(), exp, drops);
        graves.put(key(grave), grave);
        save();

        plugin.send(player, "grave-created",
                Placeholder.unparsed("x", String.valueOf(spot.getX())),
                Placeholder.unparsed("y", String.valueOf(spot.getY())),
                Placeholder.unparsed("z", String.valueOf(spot.getZ())),
                Placeholder.unparsed("world", spot.getWorld().getName()),
                Placeholder.unparsed("minutes",
                        String.valueOf(plugin.getConfig().getLong("graves.protected-minutes", 5))));
    }

    /** Szabad blokk a halál helyén vagy kicsit fölötte. */
    private Block findSpot(Block start) {
        World world = start.getWorld();
        if (start.getY() <= world.getMinHeight()) {
            return null;
        }
        for (int dy = 0; dy <= 5; dy++) {
            Block candidate = start.getRelative(0, dy, 0);
            if (candidate.getY() >= world.getMaxHeight()) {
                break;
            }
            if ((candidate.isEmpty() || candidate.isReplaceable()) && !graves.containsKey(key(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    // ---------- Kinyitás ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Grave grave = graves.get(key(block));
        if (grave == null) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        long protectMs = plugin.getConfig().getLong("graves.protected-minutes", 5) * 60_000L;
        boolean owner = player.getUniqueId().equals(grave.owner());
        long elapsed = System.currentTimeMillis() - grave.created();
        if (!owner && elapsed < protectMs && !player.hasPermission("smpcore.admin")) {
            long minutesLeft = (protectMs - elapsed) / 60_000L + 1;
            plugin.send(player, "grave-protected", Placeholder.unparsed("minutes", String.valueOf(minutesLeft)));
            return;
        }

        graves.remove(key(grave));
        block.setType(Material.AIR);
        for (ItemStack item : player.getInventory().addItem(grave.items().toArray(new ItemStack[0])).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        if (grave.exp() > 0) {
            player.giveExp(grave.exp());
        }
        save();
        plugin.send(player, "grave-claimed");
    }

    // ---------- Védelem ----------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (graves.containsKey(key(event.getBlock()))) {
            event.setCancelled(true);
            plugin.send(event.getPlayer(), "grave-break");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> graves.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> graves.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (graves.containsKey(key(b))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (graves.containsKey(key(b))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---------- /graves ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        boolean any = false;
        for (Grave g : graves.values()) {
            if (!g.owner().equals(player.getUniqueId())) {
                continue;
            }
            if (!any) {
                plugin.send(player, "graves-header");
                any = true;
            }
            repairMarker(g);
            plugin.send(player, "graves-line",
                    Placeholder.unparsed("x", String.valueOf(g.x())),
                    Placeholder.unparsed("y", String.valueOf(g.y())),
                    Placeholder.unparsed("z", String.valueOf(g.z())),
                    Placeholder.unparsed("world", g.world()));
        }
        if (!any) {
            plugin.send(player, "graves-none");
        }
        return true;
    }

    /** Ha a fej valamiért eltűnt (pl. /fill), visszatesszük, ha a hely szabad. */
    private void repairMarker(Grave g) {
        World world = plugin.getServer().getWorld(g.world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(g.x(), g.y(), g.z());
        if (block.getType() != Material.PLAYER_HEAD && (block.isEmpty() || block.isReplaceable())) {
            block.setType(Material.PLAYER_HEAD);
            BlockState state = block.getState();
            if (state instanceof Skull skull) {
                skull.setOwningPlayer(plugin.getServer().getOfflinePlayer(g.owner()));
                skull.update();
            }
        }
    }
}
