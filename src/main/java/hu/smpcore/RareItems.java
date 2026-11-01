package hu.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ritka, egyedi tárgyak (config: rare-items): saját név, leírás, enchantok, csillogás, szín/modell,
 * részecske-aura és különleges képesség (villám, életszívás, fagyasztás, tűz, wither).
 * Adás: /rareitem give <játékos> <id>; a ládákban "item: rare:<id>" nyereményként is szerepelhet.
 */
public final class RareItems implements Listener, CommandExecutor, TabCompleter {

    private final SMPCore plugin;
    private final NamespacedKey key;
    private BukkitTask auraTask;

    public RareItems(SMPCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "rare_item");
    }

    private volatile String packHash;

    public void refreshPack() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::fetchHash);
    }

    public void start() {
        stop();
        refreshPack();
        auraTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::aura, 20L, 8L);
    }

    public void stop() {
        if (auraTask != null) {
            auraTask.cancel();
            auraTask = null;
        }
    }

    private static final String PACK_BASE = "https://github.com/matebosscL17/mc/releases/download/latest/";

    private String packUrl() {
        String custom = plugin.getConfig().getString("resource-pack.custom-url", "");
        return custom.isEmpty() ? PACK_BASE + "penthouse-pack.zip" : custom;
    }

    private String hashUrl() {
        String custom = plugin.getConfig().getString("resource-pack.custom-sha1-url", "");
        return custom.isEmpty() ? PACK_BASE + "pack.sha1" : custom;
    }

    /** Letölti a csomag aktuális sha1-jét (belépéskor, így mindig egyezik a kiadott csomaggal). */
    private String fetchHash() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(hashUrl()))
                            .timeout(java.time.Duration.ofSeconds(8)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            String body = response.body().trim();
            if (response.statusCode() == 200 && body.matches("[0-9a-fA-F]{40}")) {
                packHash = body;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Nem sikerült letölteni a pack sha1-et: " + ex.getMessage());
        }
        return packHash;
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String sha1 = fetchHash();
            if (sha1 == null || sha1.length() != 40) {
                return;
            }
            byte[] hash = new byte[20];
            for (int i = 0; i < 20; i++) {
                hash[i] = (byte) Integer.parseInt(sha1.substring(i * 2, i * 2 + 2), 16);
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setResourcePack(java.util.UUID.nameUUIDFromBytes("penthouse-pack".getBytes()), packUrl(),
                            hash, plugin.parse(plugin.getConfig().getString("resource-pack.prompt",
                                    "<gold>Penthouse SMP csomag")),
                            plugin.getConfig().getBoolean("resource-pack.required", false));
                }
            }, 20L);
        });
    }

    private final java.util.Map<java.util.UUID, String> packStatus = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        packStatus.put(event.getPlayer().getUniqueId(), event.getStatus().name());
        plugin.getLogger().info("Resource pack állapot (" + event.getPlayer().getName() + "): " + event.getStatus());
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    event.getPlayer().sendMessage(plugin.parse("<red>A csomag nem töltődött be: <white>"
                            + event.getStatus().name() + " <gray>(szólj egy adminnak)"));
            default -> {
            }
        }
    }

    private ConfigurationSection section(String id) {
        return plugin.getConfig().getConfigurationSection("rare-items." + id);
    }

    /** Elkészíti a ritka tárgyat, vagy null-t ad ismeretlen azonosítóra. */
    public ItemStack create(String id) {
        ConfigurationSection s = section(id);
        if (s == null) {
            return null;
        }
        Material material = Material.matchMaterial(s.getString("material", "DIAMOND_SWORD"));
        if (material == null || material.isAir()) {
            return null;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.parse("<!italic>" + s.getString("name", id)));
        List<Component> lore = new ArrayList<>();
        for (String line : s.getStringList("lore")) {
            lore.add(plugin.parse("<!italic>" + line));
        }
        meta.lore(lore);
        ConfigurationSection enchants = s.getConfigurationSection("enchants");
        if (enchants != null) {
            for (String name : enchants.getKeys(false)) {
                Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
                if (enchantment != null) {
                    meta.addEnchant(enchantment, enchants.getInt(name), true);
                }
            }
        }
        if (s.getBoolean("glow", true)) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (s.getBoolean("unbreakable", false)) {
            meta.setUnbreakable(true);
        }
        String model = s.getString("model", "smpcore:" + id);
        if (!model.isEmpty()) {
            NamespacedKey modelKey = NamespacedKey.fromString(model);
            if (modelKey != null) {
                meta.setItemModel(modelKey);
            }
        }
        if (meta instanceof LeatherArmorMeta leather && s.contains("color")) {
            try {
                String hex = s.getString("color", "#ffffff").replace("#", "");
                leather.setColor(Color.fromRGB(Integer.parseInt(hex, 16)));
            } catch (NumberFormatException ignored) {
                // hibás szín: marad az alap
            }
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ---------- Képességek ----------

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        String id = idOf(attacker.getInventory().getItemInMainHand());
        ConfigurationSection s = id == null ? null : section(id);
        if (s == null) {
            return;
        }
        String ability = s.getString("ability", "");
        if (ability.isEmpty() || ThreadLocalRandom.current().nextDouble() > s.getDouble("chance", 0.25)) {
            return;
        }
        double amount = s.getDouble("amount", 4);
        Location at = victim.getLocation().add(0, 1, 0);
        switch (ability.toLowerCase(Locale.ROOT)) {
            case "lightning" -> {
                victim.getWorld().strikeLightningEffect(victim.getLocation());
                event.setDamage(event.getDamage() + amount);
            }
            case "lifesteal" -> {
                double max = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                attacker.setHealth(Math.min(max, attacker.getHealth() + event.getFinalDamage() * (amount / 10.0)));
                victim.getWorld().spawnParticle(Particle.HEART, attacker.getLocation().add(0, 2, 0), 5, 0.3, 0.3, 0.3);
            }
            case "freeze" -> {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2));
                victim.setFreezeTicks(140);
                victim.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 25, 0.4, 0.6, 0.4, 0.02);
            }
            case "fire" -> {
                victim.setFireTicks((int) (amount * 20));
                victim.getWorld().spawnParticle(Particle.FLAME, at, 20, 0.3, 0.5, 0.3, 0.03);
            }
            case "wither" -> {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1));
                victim.getWorld().spawnParticle(Particle.SMOKE, at, 20, 0.3, 0.5, 0.3, 0.02);
            }
            default -> {
                return;
            }
        }
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
    }

    // ---------- Jobb klikkes képességek (15 mp várakozással) ----------

    private final java.util.Map<String, Long> activeCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler
    public void onUse(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        org.bukkit.event.block.Action action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        String id = idOf(player.getInventory().getItemInMainHand());
        ConfigurationSection s = id == null ? null : section(id);
        if (s == null) {
            return;
        }
        String type = s.getString("active", s.getString("ability", ""));
        if (type.isEmpty()) {
            return;
        }
        if (plugin.spawn().isProtected(player.getLocation())) {
            player.sendActionBar(plugin.parse("<red>A spawnban nem használhatod."));
            return;
        }
        long cooldownMs = s.getLong("active-cooldown", 15) * 1000L;
        String ckey = player.getUniqueId() + ":" + id;
        long now = System.currentTimeMillis();
        Long last = activeCooldown.get(ckey);
        if (last != null && now - last < cooldownMs) {
            long left = (cooldownMs - (now - last)) / 1000 + 1;
            player.sendActionBar(plugin.parse("<red>Várakozás: <white>" + left + " <red>mp"));
            return;
        }
        boolean used = switch (type.toLowerCase(Locale.ROOT)) {
            case "lightning" -> castLightning(player, s);
            case "fire" -> castFire(player);
            case "lifesteal" -> castNova(player, s, Particle.DAMAGE_INDICATOR, 6, true, null);
            case "freeze" -> castNova(player, s, Particle.SNOWFLAKE, 3, false, PotionEffectType.SLOWNESS);
            case "wither" -> castNova(player, s, Particle.SMOKE, 4, false, PotionEffectType.WITHER);
            default -> false;
        };
        if (used) {
            activeCooldown.put(ckey, now);
            player.sendActionBar(plugin.parse("<green>Képesség használva!"));
        }
    }

    private boolean castLightning(Player player, ConfigurationSection s) {
        double range = s.getDouble("range", 40);
        org.bukkit.util.RayTraceResult hit = player.getWorld().rayTrace(player.getEyeLocation(),
                player.getEyeLocation().getDirection(), range, org.bukkit.FluidCollisionMode.NEVER, true, 0.6,
                e -> e != player && e instanceof LivingEntity);
        if (hit == null) {
            player.sendActionBar(plugin.parse("<red>Nincs célpont a közelben."));
            return false;
        }
        Location target = hit.getHitPosition().toLocation(player.getWorld());
        if (plugin.spawn().isProtected(target)) {
            player.sendActionBar(plugin.parse("<red>Ide nem csaphatsz le."));
            return false;
        }
        player.getWorld().strikeLightningEffect(target);
        double damage = s.getDouble("active-damage", 8);
        for (org.bukkit.entity.Entity e : target.getWorld().getNearbyEntities(target, 3, 3, 3)) {
            if (e instanceof LivingEntity living && e != player) {
                living.damage(damage, player);
                living.setFireTicks(60);
            }
        }
        return true;
    }

    private boolean castFire(Player player) {
        for (int i = -1; i <= 1; i++) {
            org.bukkit.util.Vector dir = player.getEyeLocation().getDirection().clone()
                    .rotateAroundY(Math.toRadians(i * 10));
            org.bukkit.entity.SmallFireball ball = player.launchProjectile(org.bukkit.entity.SmallFireball.class, dir.multiply(1.4));
            ball.setShooter(player);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
        return true;
    }

    private boolean castNova(Player player, ConfigurationSection s, Particle particle, double damage, boolean heal,
                             PotionEffectType effect) {
        double radius = s.getDouble("active-radius", 6);
        double dmg = s.getDouble("active-damage", damage);
        double healed = 0;
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof LivingEntity living && !(e instanceof org.bukkit.entity.ArmorStand)
                    && !plugin.spawn().isProtected(living.getLocation())) {
                living.damage(dmg, player);
                healed += dmg;
                if (effect != null) {
                    living.addPotionEffect(new PotionEffect(effect, 100, 1));
                }
                if (particle == Particle.SNOWFLAKE) {
                    living.setFreezeTicks(160);
                }
            }
        }
        if (heal && healed > 0) {
            double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            player.setHealth(Math.min(max, player.getHealth() + healed / 2));
        }
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40;
            player.getWorld().spawnParticle(particle, player.getLocation().add(Math.cos(a) * radius * 0.8, 0.5,
                    Math.sin(a) * radius * 0.8), 2, 0.1, 0.2, 0.1, 0.01);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1f);
        return true;
    }

    private void aura() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String id = idOf(player.getInventory().getItemInMainHand());
            ConfigurationSection s = id == null ? null : section(id);
            if (s == null || !s.contains("particle")) {
                continue;
            }
            try {
                Particle particle = Particle.valueOf(s.getString("particle", "END_ROD").toUpperCase(Locale.ROOT));
                player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1.1, 0), 3, 0.35, 0.5, 0.35, 0.01);
            } catch (IllegalArgumentException ignored) {
                // ismeretlen részecske: kihagyjuk
            }
        }
    }

    // ---------- Parancs ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        ConfigurationSection all = plugin.getConfig().getConfigurationSection("rare-items");
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(plugin.parse("<gold>Ritka tárgyak: <white>"
                    + (all == null ? "-" : String.join(", ", all.getKeys(false)))
                    + "<newline><gray>/rareitem give <játékos> <id>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("give") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                plugin.send(sender, "player-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            ItemStack item = create(args[2]);
            if (item == null) {
                sender.sendMessage(plugin.parse("<red>Ismeretlen ritka tárgy: <white>" + args[2]));
                return true;
            }
            for (ItemStack left : target.getInventory().addItem(item).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
            sender.sendMessage(plugin.parse("<green>Odaadva: <white>" + target.getName()));
            return true;
        }
        if (args[0].equalsIgnoreCase("pack") && sender instanceof Player self) {
            ItemStack hand = self.getInventory().getItemInMainHand();
            String model = hand.hasItemMeta() && hand.getItemMeta().hasItemModel()
                    ? hand.getItemMeta().getItemModel().toString() : "nincs";
            sender.sendMessage(plugin.parse("<gold>Csomag állapota: <white>"
                    + packStatus.getOrDefault(self.getUniqueId(), "még nem kapott jelentést")
                    + "<newline><gold>Kezedben lévő tárgy modellje: <white>" + model
                    + "<newline><gold>Csomag címe: <white>" + packUrl()
                    + "<newline><gold>sha1: <white>" + packHash));
            return true;
        }
        if (args[0].equalsIgnoreCase("create") && args.length >= 4) {
            String id = args[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
            Material material = Material.matchMaterial(args[2]);
            if (id.isEmpty() || material == null || material.isAir()) {
                sender.sendMessage(plugin.parse("<red>Hibás azonosító vagy anyag."));
                return true;
            }
            String base = "rare-items." + id;
            plugin.getConfig().set(base + ".material", material.name());
            plugin.getConfig().set(base + ".name", "<gold><bold>" + String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
            plugin.getConfig().set(base + ".lore", List.of("<dark_gray>Ritka tárgy"));
            plugin.getConfig().set(base + ".model", "smpcore:" + id);
            plugin.getConfig().set(base + ".glow", true);
            plugin.saveConfig();
            sender.sendMessage(plugin.parse("<green>Létrehozva: <white>" + id
                    + "<newline><gray>Kinézet: töltsd fel a <white>" + id + ".png <gray>fájlt a GitHub repo "
                    + "<white>pack/textures <gray>mappájába, a build után <white>/smpcore reload<gray>, és újra be kell lépni."
                    + "<newline><gray>Képesség/enchant a config.yml rare-items részében állítható."));
            return true;
        }
        sender.sendMessage(plugin.parse("<yellow>/rareitem list | give <játékos> <id> | create <id> <anyag> <név...>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("list", "give", "create", "pack"));
        } else if (args.length == 2) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 3) {
            ConfigurationSection all = plugin.getConfig().getConfigurationSection("rare-items");
            if (all != null) {
                out.addAll(all.getKeys(false));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
