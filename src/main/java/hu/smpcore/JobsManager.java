package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Foglalkozások: bányász, farmer, vadász. Munkáért pénz jár, a szinttel nő a jutalom.
 * Játékos által lerakott blokkokért és éretlen terményért nem jár pénz.
 */
public final class JobsManager implements Listener, CommandExecutor {

    private record BlockReward(String job, long amount) {
    }

    private static final String PLACED = "smpcore_placed";
    private static final List<String> JOBS = List.of("miner", "farmer", "hunter");
    /** Ezeknél csak a kifejlett (érett) termény ad pénzt. */
    private static final Set<Material> MATURE_ONLY = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART);

    private final SMPCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<Material, BlockReward> blockRewards = new HashMap<>();
    private final Map<EntityType, Long> mobRewards = new HashMap<>();
    private boolean dirty;

    public JobsManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "jobs.yml");
        this.data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        reload();
    }

    public void reload() {
        blockRewards.clear();
        mobRewards.clear();

        ConfigurationSection blocks = plugin.getConfig().getConfigurationSection("jobs.blocks");
        if (blocks != null) {
            for (String job : blocks.getKeys(false)) {
                ConfigurationSection section = blocks.getConfigurationSection(job);
                if (section == null) {
                    continue;
                }
                for (String key : section.getKeys(false)) {
                    Material material = Material.matchMaterial(key);
                    if (material == null) {
                        plugin.getLogger().warning("Ismeretlen blokk a jobs configban: " + key);
                        continue;
                    }
                    blockRewards.put(material, new BlockReward(job, section.getLong(key)));
                }
            }
        }

        ConfigurationSection mobs = plugin.getConfig().getConfigurationSection("jobs.mobs");
        if (mobs != null) {
            for (String key : mobs.getKeys(false)) {
                try {
                    mobRewards.put(EntityType.valueOf(key.toUpperCase()), mobs.getLong(key));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Ismeretlen mob a jobs configban: " + key);
                }
            }
        }
    }

    public void save() {
        if (!dirty) {
            return;
        }
        try {
            plugin.getDataFolder().mkdirs();
            data.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a jobs.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Szintek ----------

    private int actionsPerLevel() {
        return Math.max(1, plugin.getConfig().getInt("jobs.actions-per-level", 300));
    }

    private int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt("jobs.max-level", 10));
    }

    private long count(UUID id, String job) {
        return data.getLong(id + "." + job, 0);
    }

    private int level(UUID id, String job) {
        return (int) Math.min(maxLevel(), 1 + count(id, job) / actionsPerLevel());
    }

    private String jobName(String job) {
        String name = plugin.getConfig().getString("messages.job-name-" + job);
        return name != null ? name : job;
    }

    // ---------- Jutalmazás ----------

    private void reward(Player player, String job, long base) {
        UUID id = player.getUniqueId();
        int before = level(id, job);
        data.set(id + "." + job, count(id, job) + 1);
        dirty = true;
        int now = level(id, job);

        double bonus = plugin.getConfig().getDouble("jobs.bonus-percent-per-level", 5) / 100.0;
        long amount = Math.max(1, Math.round(base * (1 + bonus * (now - 1))));
        plugin.economy().add(id, amount);

        player.sendActionBar(plugin.msg("job-reward",
                Placeholder.unparsed("amount", plugin.money(amount)),
                Placeholder.unparsed("job", jobName(job))));
        if (now > before) {
            plugin.send(player, "job-levelup",
                    Placeholder.unparsed("job", jobName(job)),
                    Placeholder.unparsed("level", String.valueOf(now)));
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("jobs.enabled", true);
    }

    // ---------- Események ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (blockRewards.containsKey(type) && !MATURE_ONLY.contains(type)) {
            block.setMetadata(PLACED, new FixedMetadataValue(plugin, true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        boolean placed = block.hasMetadata(PLACED);
        if (placed) {
            block.removeMetadata(PLACED, plugin);
        }
        if (!enabled()) {
            return;
        }
        BlockReward reward = blockRewards.get(block.getType());
        if (reward == null || placed) {
            return;
        }
        if (MATURE_ONLY.contains(block.getType())
                && block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        reward(player, reward.job(), reward.amount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (!enabled() || event.getEntity() instanceof Player) {
            return;
        }
        Long base = mobRewards.get(event.getEntityType());
        if (base == null) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        reward(killer, "hunter", base);
    }

    // ---------- /jobs ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        UUID id = player.getUniqueId();
        plugin.send(player, "jobs-header");
        for (String job : JOBS) {
            int level = level(id, job);
            long count = count(id, job);
            String next = level >= maxLevel() ? "max"
                    : String.valueOf(actionsPerLevel() - (count % actionsPerLevel()));
            plugin.send(player, "jobs-line",
                    Placeholder.unparsed("job", jobName(job)),
                    Placeholder.unparsed("level", String.valueOf(level)),
                    Placeholder.unparsed("count", String.valueOf(count)),
                    Placeholder.unparsed("next", next));
        }
        return true;
    }
}
