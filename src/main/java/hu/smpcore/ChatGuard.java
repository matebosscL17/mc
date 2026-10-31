package hu.smpcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chat védelem: túl gyors üzenetküldés és ugyanazon üzenet ismételgetése tiltva (adminra nem vonatkozik). */
public final class ChatGuard implements Listener {

    private record Last(long time, String text) {
    }

    private final SMPCore plugin;
    private final Map<UUID, Last> last = new ConcurrentHashMap<>();

    public ChatGuard(SMPCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("smpcore.admin")) {
            return;
        }
        long cooldown = plugin.getConfig().getLong("chat.cooldown-ms", 1500);
        long repeat = plugin.getConfig().getLong("chat.repeat-seconds", 10) * 1000L;
        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).trim()
                .toLowerCase();
        long now = System.currentTimeMillis();
        Last before = last.get(player.getUniqueId());
        if (before != null) {
            if (now - before.time() < cooldown) {
                event.setCancelled(true);
                player.sendMessage(plugin.msg("chat-slow"));
                return;
            }
            if (repeat > 0 && now - before.time() < repeat && before.text().equals(text)) {
                event.setCancelled(true);
                player.sendMessage(plugin.msg("chat-repeat"));
                return;
            }
        }
        last.put(player.getUniqueId(), new Last(now, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
    }
}
