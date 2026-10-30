package hu.smpcore;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Egyszerű, önálló gazdaság (egész számú egyenleg). Nem Vault-kompatibilis. */
public final class Economy {

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, Long> balances = new HashMap<>();
    private boolean dirty;

    public Economy(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
        load();
    }

    private long start() {
        return plugin.getConfig().getLong("economy.start-balance", 100);
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), yaml.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // hibás sor, kihagyjuk
            }
        }
    }

    public void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            yaml.set(e.getKey().toString(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a balances.yml-t: " + ex.getMessage());
        }
    }

    public void ensure(UUID id) {
        if (!balances.containsKey(id)) {
            balances.put(id, start());
            dirty = true;
        }
    }

    public long get(UUID id) {
        return balances.getOrDefault(id, start());
    }

    public void set(UUID id, long value) {
        balances.put(id, Math.max(0, value));
        dirty = true;
    }

    public void add(UUID id, long amount) {
        set(id, get(id) + amount);
    }

    public boolean withdraw(UUID id, long amount) {
        long current = get(id);
        if (current < amount) {
            return false;
        }
        set(id, current - amount);
        return true;
    }

    public Map<UUID, Long> all() {
        return balances;
    }
}
