package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /bind: blokkot vagy NPC-t (pl. villagert) parancshoz köt. Jobb klikkre a játékos lefuttatja a parancsot,
 * így egy kész spawn térkép boltjai, aukciós pultja stb. működővé tehetők.
 */
public final class BindManager implements Listener, CommandExecutor {

    private final SMPCore plugin;
    private final File file;
    private final Map<String, String> blocks = new LinkedHashMap<>();
    private final Map<UUID, String> entities = new LinkedHashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public BindManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "binds.yml");
        load();
    }

    private static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    // ---------- Mentés / betöltés ----------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("blocks")) {
            try {
                blocks.put(key((String) m.get("world"), ((Number) m.get("x")).intValue(),
                        ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue()),
                        (String) m.get("command"));
            } catch (Exception ex) {
                plugin.getLogger().warning("Hibás bind a binds.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
        for (Map<?, ?> m : yaml.getMapList("entities")) {
            try {
                entities.put(UUID.fromString((String) m.get("uuid")), (String) m.get("command"));
            } catch (Exception ex) {
                plugin.getLogger().warning("Hibás NPC bind a binds.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
    }

    private void save() {
        List<Map<String, Object>> blockList = new ArrayList<>();
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            String[] p = e.getKey().split(";");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", p[0]);
            m.put("x", Integer.parseInt(p[1]));
            m.put("y", Integer.parseInt(p[2]));
            m.put("z", Integer.parseInt(p[3]));
            m.put("command", e.getValue());
            blockList.add(m);
        }
        List<Map<String, Object>> entityList = new ArrayList<>();
        for (Map.Entry<UUID, String> e : entities.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", e.getKey().toString());
            m.put("command", e.getValue());
            entityList.add(m);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blocks", blockList);
        yaml.set("entities", entityList);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a binds.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Használat ----------

    private void run(Player player, String command) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < 800) {
            return;
        }
        lastUse.put(player.getUniqueId(), now);
        player.performCommand(command);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        String command = blocks.get(key(event.getClickedBlock()));
        if (command != null) {
            event.setCancelled(true);
            run(event.getPlayer(), command);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String command = entities.get(event.getRightClicked().getUniqueId());
        if (command != null) {
            event.setCancelled(true);
            run(event.getPlayer(), command);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (entities.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocks.containsKey(key(event.getBlock()))) {
            event.setCancelled(true);
            plugin.send(event.getPlayer(), "bind-protected");
        }
    }

    // ---------- Parancs ----------

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
        if (args.length == 0) {
            plugin.send(player, "bind-usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> list(player);
            case "remove" -> remove(player);
            case "npc" -> npc(player, args);
            default -> bind(player, String.join(" ", args));
        }
        return true;
    }

    private static String clean(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private void bind(Player player, String command) {
        String cmd = clean(command);
        Block block = player.getTargetBlockExact(6);
        if (block != null) {
            blocks.put(key(block), cmd);
            save();
            plugin.send(player, "bind-set", Placeholder.unparsed("command", cmd));
            return;
        }
        Entity entity = player.getTargetEntity(6);
        if (entity != null && !(entity instanceof Player)) {
            makeNpc(entity);
            entities.put(entity.getUniqueId(), cmd);
            save();
            plugin.send(player, "bind-set", Placeholder.unparsed("command", cmd));
            return;
        }
        plugin.send(player, "look-at-block");
    }

    private void makeNpc(Entity entity) {
        entity.setInvulnerable(true);
        entity.setPersistent(true);
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setSilent(true);
            living.setRemoveWhenFarAway(false);
        }
    }

    private void remove(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block != null && blocks.remove(key(block)) != null) {
            save();
            plugin.send(player, "bind-removed");
            return;
        }
        Entity entity = player.getTargetEntity(6);
        if (entity != null && entities.remove(entity.getUniqueId()) != null) {
            save();
            plugin.send(player, "bind-removed");
            return;
        }
        plugin.send(player, "look-at-block");
    }

    private void list(Player player) {
        plugin.send(player, "bind-list-header");
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            plugin.send(player, "bind-list-line",
                    Placeholder.unparsed("where", e.getKey().replace(';', ' ')),
                    Placeholder.unparsed("command", e.getValue()));
        }
        for (Map.Entry<UUID, String> e : entities.entrySet()) {
            plugin.send(player, "bind-list-line",
                    Placeholder.unparsed("where", "NPC " + e.getKey().toString().substring(0, 8)),
                    Placeholder.unparsed("command", e.getValue()));
        }
    }

    /** /bind npc [név] [parancs...]: villager NPC a helyeden, amire kattintva lefut a parancs. */
    private void npc(Player player, String[] args) {
        if (args.length < 3) {
            plugin.send(player, "bind-usage");
            return;
        }
        String name = args[1].replace('_', ' ');
        String cmd = clean(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
        Location loc = player.getLocation();
        Villager villager = loc.getWorld().spawn(loc, Villager.class, v -> {
            v.customName(plugin.parse("<gold><bold>" + name));
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
        });
        entities.put(villager.getUniqueId(), cmd);
        save();
        plugin.send(player, "bind-npc", Placeholder.unparsed("command", cmd));
    }
}
