package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fejpénz: /bounty [játékos] [összeg], /bounty list. Aki megöli a játékost, megkapja. */
public final class BountyManager implements Listener, CommandExecutor {

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, Long> bounties = new HashMap<>();

    public BountyManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                bounties.put(UUID.fromString(key), yaml.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // hibás sor
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : bounties.entrySet()) {
            yaml.set(e.getKey().toString(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a bounties.yml-t: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }
        Long amount = bounties.remove(victim.getUniqueId());
        if (amount == null || amount <= 0) {
            return;
        }
        plugin.economy().add(killer.getUniqueId(), amount);
        save();
        Bukkit.broadcast(plugin.msg("bounty-claimed",
                Placeholder.unparsed("killer", killer.getName()),
                Placeholder.unparsed("victim", victim.getName()),
                Placeholder.unparsed("amount", plugin.money(amount))));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.send(sender, "bounty-usage");
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (args.length < 2) {
            plugin.send(player, "bounty-usage");
            return true;
        }
        OfflinePlayer target = plugin.findPlayer(args[0]);
        if (target == null) {
            plugin.send(player, "player-not-found", Placeholder.unparsed("player", args[0]));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.send(player, "bounty-self");
            return true;
        }
        long amount = plugin.parseAmount(args[1]);
        if (amount <= 0) {
            plugin.send(player, "invalid-amount");
            return true;
        }
        long min = plugin.getConfig().getLong("bounty.min", 100);
        if (amount < min) {
            plugin.send(player, "bounty-min", Placeholder.unparsed("amount", plugin.money(min)));
            return true;
        }
        if (!plugin.economy().withdraw(player.getUniqueId(), amount)) {
            plugin.send(player, "not-enough-money", Placeholder.unparsed("amount", plugin.money(amount)));
            return true;
        }
        long total = bounties.merge(target.getUniqueId(), amount, Long::sum);
        save();
        Bukkit.broadcast(plugin.msg("bounty-placed",
                Placeholder.unparsed("setter", player.getName()),
                Placeholder.unparsed("player", target.getName() != null ? target.getName() : args[0]),
                Placeholder.unparsed("amount", plugin.money(amount)),
                Placeholder.unparsed("total", plugin.money(total))));
        return true;
    }

    private void list(CommandSender sender) {
        if (bounties.isEmpty()) {
            plugin.send(sender, "bounty-none");
            return;
        }
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(bounties.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        plugin.send(sender, "bounty-list-header");
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : entries) {
            if (rank > 10) {
                break;
            }
            plugin.send(sender, "top-line",
                    Placeholder.unparsed("rank", String.valueOf(rank)),
                    Placeholder.unparsed("player", plugin.name(entry.getKey())),
                    Placeholder.unparsed("value", plugin.money(entry.getValue())));
            rank++;
        }
    }
}
