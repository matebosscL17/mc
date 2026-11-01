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
        String model = s.getString("model", "");
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
            out.addAll(List.of("list", "give", "create"));
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
