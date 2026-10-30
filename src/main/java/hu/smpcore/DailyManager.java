package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/** /daily: napi jutalom, egymás utáni napokra egyre nagyobb. */
public final class DailyManager implements CommandExecutor {

    private final SMPCore plugin;
    private final File file;
    private final YamlConfiguration data;

    public DailyManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "daily.yml");
        this.data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(plugin.getConfig().getString("daily.timezone", "Europe/Budapest"));
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni a daily.yml-t: " + ex.getMessage());
        }
    }

    private long today() {
        return LocalDate.now(zone()).toEpochDay();
    }

    /** Belépéskor szól, ha ma még nem vette fel. */
    public void hint(Player player) {
        if (!player.isOnline()) {
            return;
        }
        long last = data.getLong(player.getUniqueId() + ".last", -1);
        if (last != today()) {
            plugin.send(player, "daily-hint");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        UUID id = player.getUniqueId();
        long today = today();
        long last = data.getLong(id + ".last", -1);

        if (last == today) {
            ZonedDateTime now = ZonedDateTime.now(zone());
            ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(zone());
            Duration left = Duration.between(now, next);
            plugin.send(player, "daily-already",
                    Placeholder.unparsed("time", left.toHours() + " ó " + left.toMinutesPart() + " p"));
            return true;
        }

        int streak = (last == today - 1) ? data.getInt(id + ".streak", 0) + 1 : 1;
        long base = plugin.getConfig().getLong("daily.base", 100);
        long bonus = plugin.getConfig().getLong("daily.bonus-per-day", 20);
        int maxStreak = plugin.getConfig().getInt("daily.max-streak-days", 7);
        long reward = base + bonus * Math.min(streak - 1, maxStreak);

        plugin.economy().add(id, reward);
        data.set(id + ".last", today);
        data.set(id + ".streak", streak);
        save();

        plugin.send(player, "daily-reward",
                Placeholder.unparsed("amount", plugin.money(reward)),
                Placeholder.unparsed("streak", String.valueOf(streak)));
        return true;
    }
}
