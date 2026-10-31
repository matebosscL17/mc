package hu.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Lidded;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ládák (crate-ek): a világban kijelölt blokkra kulccsal jobb klikkelve pörgős nyitás, bal klikk = nyeremények.
 * A kulcs valódi tárgy, adható, cserélhető, megvehető. Minden a configban állítható.
 */
public final class CrateManager implements Listener, CommandExecutor {

    private record Reward(int weight, Material icon, String name, long money, String item,
                          String command, boolean broadcast) {
    }

    private static final class UiHolder implements InventoryHolder {
        private Inventory inventory;
        private String buyCrate;
        private int buySlot = -1;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final String TAG = "smpcore_crate";

    private final SMPCore plugin;
    private final File file;
    private final NamespacedKey keyTag;
    /** blokk kulcs -> láda azonosító */
    private final Map<String, String> placed = new LinkedHashMap<>();
    private BukkitTask particleTask;
    private double angle;
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

    public CrateManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "crates.yml");
        this.keyTag = new NamespacedKey(plugin, "crate_key");
        load();
    }

    // ---------- Mentés / betöltés ----------

    private static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("placed")) {
            try {
                String world = (String) m.get("world");
                int x = ((Number) m.get("x")).intValue();
                int y = ((Number) m.get("y")).intValue();
                int z = ((Number) m.get("z")).intValue();
                String crate = (String) m.get("crate");
                placed.put(key(world, x, y, z), crate);
            } catch (Exception ex) {
                plugin.getLogger().warning("Hibás láda a crates.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
    }

    private void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, String> e : placed.entrySet()) {
            String[] p = e.getKey().split(";");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", p[0]);
            m.put("x", Integer.parseInt(p[1]));
            m.put("y", Integer.parseInt(p[2]));
            m.put("z", Integer.parseInt(p[3]));
            m.put("crate", e.getValue());
            list.add(m);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("placed", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a crates.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Config ----------

    private ConfigurationSection crateSection(String id) {
        return plugin.getConfig().getConfigurationSection("crates." + id);
    }

    private String crateName(String id) {
        ConfigurationSection s = crateSection(id);
        return s == null ? id : s.getString("name", id);
    }

    private static long num(Object o, long def) {
        return o instanceof Number n ? n.longValue() : def;
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private List<Reward> rewards(String id) {
        List<Reward> list = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("crates." + id + ".rewards")) {
            Material icon = Material.matchMaterial(str(m.get("icon"), "CHEST"));
            if (icon == null) {
                icon = Material.CHEST;
            }
            list.add(new Reward((int) Math.max(1, num(m.get("weight"), 1)), icon,
                    str(m.get("name"), icon.name()), num(m.get("money"), 0),
                    str(m.get("item"), ""), str(m.get("command"), ""),
                    Boolean.TRUE.equals(m.get("broadcast"))));
        }
        return list;
    }

    private Reward pick(List<Reward> list) {
        int total = 0;
        for (Reward r : list) {
            total += r.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Reward r : list) {
            roll -= r.weight();
            if (roll < 0) {
                return r;
            }
        }
        return list.get(list.size() - 1);
    }

    // ---------- Kulcs ----------

    private ItemStack keyItem(String id, int amount) {
        ConfigurationSection s = crateSection(id);
        Material material = Material.matchMaterial(s == null ? "TRIPWIRE_HOOK"
                : s.getString("key-material", "TRIPWIRE_HOOK"));
        if (material == null || material.isAir()) {
            material = Material.TRIPWIRE_HOOK;
        }
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.parse(s == null ? id : s.getString("key-name", "<!italic><yellow>Kulcs")));
        meta.lore(List.of(plugin.msg("crate-key-lore", Placeholder.parsed("crate", crateName(id)))));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isKey(ItemStack item, String id) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String stored = item.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
        return id.equals(stored);
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    /** Kulcs átadása (pl. heti jutalomként). */
    public void giveKey(Player player, String id, int amount) {
        if (crateSection(id) == null) {
            plugin.getLogger().warning("Ismeretlen láda a jutalomban: " + id);
            return;
        }
        giveItem(player, keyItem(id, amount));
        plugin.send(player, "crate-key-received",
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.parsed("crate", crateName(id)));
    }

    // ---------- Nyitás ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        if (block == null || event.getHand() != EquipmentSlot.HAND
                || (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK)) {
            return;
        }
        String crate = placed.get(key(block));
        if (crate == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (action == Action.LEFT_CLICK_BLOCK || player.isSneaking()) {
            preview(player, crate);
        } else {
            open(player, crate, block);
        }
    }

    private void open(Player player, String id, Block block) {
        List<Reward> rewards = rewards(id);
        if (rewards.isEmpty()) {
            plugin.send(player, "crate-unknown", Placeholder.unparsed("crate", id));
            return;
        }
        if (opening.contains(player.getUniqueId())) {
            plugin.send(player, "crate-busy");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isKey(hand, id)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            plugin.send(player, "crate-no-key", Placeholder.parsed("crate", crateName(id)));
            return;
        }
        int left = hand.getAmount() - 1;
        if (left <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            ItemStack rest = hand.clone();
            rest.setAmount(left);
            player.getInventory().setItemInMainHand(rest);
        }

        Reward result = pick(rewards);
        grant(player, result); // a nyeremény azonnal megvan, az animáció csak látvány
        animate(player, id, rewards, result, block);
    }

    private void grant(Player player, Reward reward) {
        if (reward.money() > 0) {
            plugin.economy().add(player.getUniqueId(), reward.money());
        }
        if (!reward.item().isEmpty()) {
            String[] parts = reward.item().split(":");
            Material material = Material.matchMaterial(parts[0].trim());
            if (material != null && !material.isAir()) {
                int amount = 1;
                if (parts.length > 1) {
                    try {
                        amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                        // marad 1
                    }
                }
                giveItem(player, new ItemStack(material, amount));
            } else {
                plugin.getLogger().warning("Ismeretlen tárgy a láda nyereményében: " + reward.item());
            }
        }
        if (!reward.command().isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.command().replace("%player%", player.getName()));
        }
    }

    private ItemStack icon(Reward reward) {
        ItemStack item = new ItemStack(reward.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.parse("<!italic>" + reward.name()));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private static final Material[] RAINBOW = {
            Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE};

    private double percent(List<Reward> list, Reward reward) {
        int total = 0;
        for (Reward r : list) {
            total += r.weight();
        }
        return 100.0 * reward.weight() / Math.max(1, total);
    }

    private static Material rarityPane(double pct) {
        if (pct <= 5) {
            return Material.ORANGE_STAINED_GLASS_PANE;
        }
        if (pct <= 15) {
            return Material.PURPLE_STAINED_GLASS_PANE;
        }
        if (pct <= 35) {
            return Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        }
        return Material.LIME_STAINED_GLASS_PANE;
    }

    private static String rarityLabel(double pct) {
        if (pct <= 5) {
            return "<gold><bold>LEGENDÁS";
        }
        if (pct <= 15) {
            return "<light_purple><bold>EPIKUS";
        }
        if (pct <= 35) {
            return "<aqua><bold>RITKA";
        }
        return "<green><bold>KÖZÖNSÉGES";
    }

    private void celebrate(Block block, double pct) {
        Location loc = block.getLocation().add(0.5, 1.2, 0.5);
        Color main = pct <= 5 ? Color.ORANGE : pct <= 15 ? Color.PURPLE : pct <= 35 ? Color.AQUA : Color.LIME;
        FireworkEffect.Type type = pct <= 15 ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.BALL;
        Firework fw = loc.getWorld().spawn(loc, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(type).withColor(main, Color.WHITE)
                    .withFade(Color.YELLOW).trail(true).flicker(true).build());
            f.setFireworkMeta(meta);
        });
        fw.detonate();
    }

    private void animate(Player player, String id, List<Reward> rewards, Reward result, Block block) {
        UUID uuid = player.getUniqueId();
        opening.add(uuid);
        if (block.getState() instanceof Lidded lid) {
            lid.open();
        }
        double pct = percent(rewards, result);

        UiHolder holder = new UiHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.parse(crateName(id)));
        holder.inventory = inv;
        player.openInventory(inv);

        int frames = 32;
        List<Reward> sequence = new ArrayList<>();
        for (int i = 0; i < frames + 8; i++) {
            sequence.add(pick(rewards));
        }
        sequence.set(frames - 1 + 4, result); // az utolsó képkockán a középső helyen a nyeremény áll

        Location center = block.getLocation().add(0.5, 1.3, 0.5);
        long time = 0;
        for (int f = 0; f < frames; f++) {
            time += f < 18 ? 2 : (f < 26 ? 4 : 7);
            int frame = f;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (int i = 0; i < 9; i++) {
                    Material pane = RAINBOW[(frame + i) % RAINBOW.length];
                    inv.setItem(i, filler(pane));
                    inv.setItem(18 + i, filler(pane));
                    inv.setItem(9 + i, icon(sequence.get(frame + i)));
                }
                inv.setItem(4, pointer(Material.HOPPER, "▼"));
                inv.setItem(22, pointer(Material.END_ROD, "▲"));
                center.getWorld().spawnParticle(Particle.END_ROD, center, 8, 0.35, 0.3, 0.35, 0.03);
                if (player.isOnline()) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f,
                            0.6f + 1.4f * frame / frames);
                }
            }, time);
        }

        long finish = time + 12;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Material pane = rarityPane(pct);
            for (int i = 0; i < 27; i++) {
                inv.setItem(i, filler(pane));
            }
            ItemStack won = icon(result);
            ItemMeta meta = won.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            meta.lore(List.of(plugin.parse("<!italic>" + rarityLabel(pct))));
            won.setItemMeta(meta);
            inv.setItem(13, won);
            celebrate(block, pct);
            if (player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
        }, finish);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            opening.remove(uuid);
            if (block.getState() instanceof Lidded lid) {
                lid.close();
            }
            if (!player.isOnline()) {
                return;
            }
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof UiHolder) {
                player.closeInventory();
            }
            plugin.send(player, "crate-won", Placeholder.parsed("reward", result.name()));
            if (result.broadcast()) {
                Bukkit.broadcast(plugin.msg("crate-broadcast",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.parsed("reward", result.name()),
                        Placeholder.parsed("crate", crateName(id))));
            }
        }, finish + 45);
    }

    private ItemStack pointer(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.parse("<!italic><yellow><bold>" + name));
        item.setItemMeta(meta);
        return item;
    }

    private void preview(Player player, String id) {
        List<Reward> rewards = rewards(id);
        if (rewards.isEmpty()) {
            plugin.send(player, "crate-unknown", Placeholder.unparsed("crate", id));
            return;
        }
        List<Reward> sorted = new ArrayList<>(rewards);
        sorted.sort(Comparator.comparingInt(Reward::weight)); // a legritkább elöl
        int rows = Math.min(6, (sorted.size() + 6) / 7 + 2);
        int size = rows * 9;
        UiHolder holder = new UiHolder();
        Inventory inv = Bukkit.createInventory(holder, size, plugin.parse(crateName(id)));
        holder.inventory = inv;
        ItemStack border = filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) {
            inv.setItem(i, border);
        }
        int index = 0;
        for (Reward r : sorted) {
            int row = 1 + index / 7;
            if (row >= rows - 1) {
                break;
            }
            double chance = percent(rewards, r);
            ItemStack item = icon(r);
            ItemMeta meta = item.getItemMeta();
            meta.lore(List.of(
                    plugin.parse("<!italic>" + rarityLabel(chance)),
                    plugin.msg("crate-chance", Placeholder.unparsed("chance",
                            String.format(Locale.ROOT, "%.1f", chance)))));
            item.setItemMeta(meta);
            inv.setItem(row * 9 + 1 + index % 7, item);
            index++;
        }
        long price = crateSection(id).getLong("price", 0);
        int buySlot = size - 5;
        if (price > 0) {
            holder.buyCrate = id;
            holder.buySlot = buySlot;
            ItemStack buy = new ItemStack(Material.GOLD_INGOT);
            ItemMeta buyMeta = buy.getItemMeta();
            buyMeta.displayName(plugin.msg("crate-gui-buy"));
            buyMeta.lore(List.of(
                    plugin.msg("crate-gui-price", Placeholder.unparsed("price", plugin.money(price))),
                    plugin.msg("crate-gui-click")));
            buyMeta.setEnchantmentGlintOverride(true);
            buy.setItemMeta(buyMeta);
            inv.setItem(buySlot, buy);
        } else {
            ItemStack no = new ItemStack(Material.BARRIER);
            ItemMeta noMeta = no.getItemMeta();
            noMeta.displayName(plugin.msg("crate-gui-nobuy"));
            no.setItemMeta(noMeta);
            inv.setItem(buySlot, no);
        }
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof UiHolder holder) {
            event.setCancelled(true);
            if (holder.buyCrate != null && event.getClickedInventory() == event.getView().getTopInventory()
                    && event.getSlot() == holder.buySlot && event.getWhoClicked() instanceof Player player) {
                purchase(player, holder.buyCrate);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof UiHolder) {
            event.setCancelled(true);
        }
    }

    // ---------- Védelem ----------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (placed.containsKey(key(event.getBlock()))) {
            event.setCancelled(true);
            plugin.send(event.getPlayer(), "crate-protected");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> placed.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> placed.containsKey(key(b)));
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.send(sender, "crate-usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> list(sender);
            case "buy" -> buy(sender, args);
            case "set" -> set(sender, args);
            case "place" -> place(sender, args);
            case "remove" -> remove(sender);
            case "givekey", "give" -> givekey(sender, args);
            default -> plugin.send(sender, "crate-usage");
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (!sender.hasPermission("smpcore.admin")) {
            plugin.send(sender, "no-permission");
            return false;
        }
        return true;
    }

    private void list(CommandSender sender) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("crates");
        plugin.send(sender, "crate-list-header");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            long price = section.getLong(id + ".price", 0);
            plugin.send(sender, "crate-list-line",
                    Placeholder.unparsed("id", id),
                    Placeholder.parsed("crate", crateName(id)),
                    Placeholder.unparsed("price", price > 0 ? plugin.money(price) : "-"));
        }
    }

    private void buy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (args.length < 2 || crateSection(args[1]) == null) {
            plugin.send(player, "crate-usage");
            return;
        }
        purchase(player, args[1]);
    }

    private void purchase(Player player, String id) {
        long price = crateSection(id).getLong("price", 0);
        if (price <= 0) {
            plugin.send(player, "crate-not-for-sale");
            return;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), price)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            plugin.send(player, "not-enough-money", Placeholder.unparsed("amount", plugin.money(price)));
            return;
        }
        giveItem(player, keyItem(id, 1));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        plugin.send(player, "crate-bought", Placeholder.parsed("crate", crateName(id)),
                Placeholder.unparsed("price", plugin.money(price)));
    }

    /** /crate place <id>: a nézett blokk tetejére ládát tesz és beállítja nyitható ládának. */
    private void place(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (args.length < 2 || crateSection(args[1]) == null) {
            plugin.send(player, "crate-unknown", Placeholder.unparsed("crate", args.length < 2 ? "?" : args[1]));
            return;
        }
        Block base = player.getTargetBlockExact(6);
        if (base == null) {
            plugin.send(player, "look-at-block");
            return;
        }
        String id = args[1];
        Block chest = base.getRelative(org.bukkit.block.BlockFace.UP);
        if (chest.getType().isAir()) {
            Material material = Material.matchMaterial(crateSection(id).getString("block", "ENDER_CHEST"));
            chest.setType(material == null || material.isAir() ? Material.ENDER_CHEST : material);
        }
        placed.put(key(chest), id);
        save();
        spawnHologram(chest, id);
        plugin.send(player, "crate-set", Placeholder.parsed("crate", crateName(id)));
    }

    private void set(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (args.length < 2 || crateSection(args[1]) == null) {
            plugin.send(player, "crate-unknown", Placeholder.unparsed("crate", args.length < 2 ? "?" : args[1]));
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            plugin.send(player, "look-at-block");
            return;
        }
        String id = args[1];
        placed.put(key(target), id);
        save();
        spawnHologram(target, id);
        plugin.send(player, "crate-set", Placeholder.parsed("crate", crateName(id)));
    }

    private void remove(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || placed.remove(key(target)) == null) {
            plugin.send(player, "look-at-block");
            return;
        }
        save();
        for (Entity entity : target.getWorld().getNearbyEntities(
                target.getLocation().add(0.5, 1.7, 0.5), 1, 2, 1)) {
            if (entity instanceof TextDisplay && entity.getScoreboardTags().contains(TAG)) {
                entity.remove();
            }
        }
        plugin.send(player, "crate-removed");
    }

    private void givekey(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 3) {
            plugin.send(sender, "crate-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        String id = args[2];
        if (crateSection(id) == null) {
            plugin.send(sender, "crate-unknown", Placeholder.unparsed("crate", id));
            return;
        }
        int amount = 1;
        if (args.length > 3) {
            long parsed = plugin.parseAmount(args[3]);
            if (parsed <= 0 || parsed > 64) {
                plugin.send(sender, "invalid-amount");
                return;
            }
            amount = (int) parsed;
        }
        giveItem(target, keyItem(id, amount));
        plugin.send(target, "crate-key-received",
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.parsed("crate", crateName(id)));
        plugin.send(sender, "crate-key-given", Placeholder.unparsed("player", target.getName()));
    }

    private void spawnHologram(Block block, String id) {
        block.getWorld().spawn(block.getLocation().add(0.5, 1.9, 0.5), TextDisplay.class, display -> {
            long price = crateSection(id).getLong("price", 0);
            String priceLine = price > 0 ? "<newline><gray>Kulcs ára: <yellow>" + plugin.money(price) : "";
            display.text(plugin.parse(crateName(id) + "<newline><newline>" + plugin.cfg("messages.crate-holo")
                    + priceLine));
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));
            display.addScoreboardTag(TAG);
        });
    }

    // ---------- Részecskék a ládák körül ----------

    public void start() {
        stop();
        particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickParticles, 20L, 3L);
    }

    public void stop() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    private void tickParticles() {
        angle += 0.4;
        for (String k : placed.keySet()) {
            String[] p = k.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) {
                continue;
            }
            Location base = new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]) + 0.5);
            boolean near = false;
            for (Player pl : w.getPlayers()) {
                if (pl.getLocation().distanceSquared(base) < 1600) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                continue;
            }
            for (int i = 0; i < 2; i++) {
                double a = angle + i * Math.PI;
                double y = 0.2 + ((angle * 0.3 + i * 0.7) % 1.5);
                w.spawnParticle(Particle.END_ROD, base.getX() + Math.cos(a) * 0.8, base.getY() + y,
                        base.getZ() + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
            }
        }
    }
}
