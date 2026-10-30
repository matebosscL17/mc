package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /balance, /pay, /baltop, /eco */
final class EconomyCommands implements CommandExecutor {

    private final SMPCore plugin;

    EconomyCommands(SMPCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "eco" -> eco(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void balance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                plugin.send(sender, "player-only");
                return;
            }
            plugin.send(p, "balance-self",
                    Placeholder.unparsed("amount", plugin.money(plugin.economy().get(p.getUniqueId()))));
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[0]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Placeholder.unparsed("player", args[0]));
            return;
        }
        plugin.send(sender, "balance-other",
                Placeholder.unparsed("player", target.getName() != null ? target.getName() : args[0]),
                Placeholder.unparsed("amount", plugin.money(plugin.economy().get(target.getUniqueId()))));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.send(p, "pay-usage");
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[0]);
        if (target == null) {
            plugin.send(p, "player-not-found", Placeholder.unparsed("player", args[0]));
            return;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            plugin.send(p, "pay-self");
            return;
        }
        long amount = plugin.parseAmount(args[1]);
        if (amount <= 0) {
            plugin.send(p, "invalid-amount");
            return;
        }
        if (!plugin.economy().withdraw(p.getUniqueId(), amount)) {
            plugin.send(p, "not-enough-money", Placeholder.unparsed("amount", plugin.money(amount)));
            return;
        }
        plugin.economy().add(target.getUniqueId(), amount);

        String targetName = target.getName() != null ? target.getName() : args[0];
        plugin.send(p, "pay-sent",
                Placeholder.unparsed("amount", plugin.money(amount)),
                Placeholder.unparsed("player", targetName));
        Player online = target.getPlayer();
        if (online != null) {
            plugin.send(online, "pay-received",
                    Placeholder.unparsed("amount", plugin.money(amount)),
                    Placeholder.unparsed("player", p.getName()));
        }
    }

    private void baltop(CommandSender sender) {
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(plugin.economy().all().entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        plugin.send(sender, "baltop-header");
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : list) {
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

    private void eco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.send(sender, "eco-usage");
            return;
        }
        OfflinePlayer target = plugin.findPlayer(args[1]);
        if (target == null) {
            plugin.send(sender, "player-not-found", Placeholder.unparsed("player", args[1]));
            return;
        }
        long amount = plugin.parseAmount(args[2]);
        if (amount <= 0) {
            plugin.send(sender, "invalid-amount");
            return;
        }
        UUID id = target.getUniqueId();
        switch (args[0].toLowerCase()) {
            case "give" -> plugin.economy().add(id, amount);
            case "take" -> plugin.economy().set(id, plugin.economy().get(id) - amount);
            case "set" -> plugin.economy().set(id, amount);
            default -> {
                plugin.send(sender, "eco-usage");
                return;
            }
        }
        plugin.send(sender, "eco-done",
                Placeholder.unparsed("player", target.getName() != null ? target.getName() : args[1]),
                Placeholder.unparsed("amount", plugin.money(plugin.economy().get(id))));
    }
}
