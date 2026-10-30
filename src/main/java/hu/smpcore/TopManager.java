package hu.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /top: grafikus toplista fejekkel, fülekkel (játékidő, ölés, halál, pénz) és a saját helyezéseddel. */
public final class TopManager implements Listener {

    private static final String[] TYPES = {"playtime", "kills", "deaths", "balance"};
    private static final int[] TAB_SLOTS = {1, 3, 5, 7};
    private static final Material[] TAB_ICONS = {Material.CLOCK, Material.DIAMOND_SWORD,
            Material.WITHER_SKELETON_SKULL, Material.GOLD_INGOT};
    private static final String[] TAB_NAMES = {"<yellow>Játékidő", "<red>Ölések", "<gray>Halálok", "<green>Pénz"};
    /** helyezés (1-től) -> slot */
    private static final int[] RANK_SLOTS = {13, 20, 24, 28, 29, 30, 31, 32, 33, 34};

    private static final class TopHolder implements InventoryHolder {
        private Inventory inventory;
        private String type;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SMPCore plugin;
    private final StatsManager stats;

    public TopManager(SMPCore plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.parse("<!italic>" + name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(plugin.parse("<!italic>" + line));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String rankColor(int rank) {
        return switch (rank) {
            case 1 -> "<gold>";
            case 2 -> "<#c0c0c0>";
            case 3 -> "<#cd7f32>";
            default -> "<white>";
        };
    }

    public void open(Player viewer, String type) {
        List<Map.Entry<UUID, Long>> list = stats.ranking(type);
        if (list == null) {
            return;
        }
        TopHolder holder = new TopHolder();
        holder.type = type;
        Inventory inv = Bukkit.createInventory(holder, 54,
                plugin.parse("<gradient:#ffd700:#ff8c00><bold>✦ PENTHOUSE TOPLISTA ✦"));
        holder.inventory = inv;

        ItemStack border = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemStack inner = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, i < 9 || i >= 45 ? border : inner);
        }

        for (int i = 0; i < TYPES.length; i++) {
            boolean active = TYPES[i].equals(type);
            ItemStack tab = named(TAB_ICONS[i], (active ? "<bold>" : "") + TAB_NAMES[i],
                    List.of(active ? "<green>▶ Ez a lista látszik" : "<gray>Kattints a megnyitáshoz"));
            if (active) {
                ItemMeta meta = tab.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                tab.setItemMeta(meta);
            }
            inv.setItem(TAB_SLOTS[i], tab);
        }

        for (int rank = 1; rank <= RANK_SLOTS.length; rank++) {
            if (rank > list.size()) {
                inv.setItem(RANK_SLOTS[rank - 1], named(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "<dark_gray>#" + rank + " <gray>üres", List.of()));
                continue;
            }
            Map.Entry<UUID, Long> entry = list.get(rank - 1);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getKey()));
            meta.displayName(plugin.parse("<!italic>" + rankColor(rank) + "<bold>#" + rank + " "
                    + plugin.name(entry.getKey())));
            meta.lore(List.of(plugin.parse("<!italic><gray>" + tabName(type) + ": <white>"
                    + stats.formatValue(type, entry.getValue()))));
            if (rank == 1) {
                meta.setEnchantmentGlintOverride(true);
            }
            head.setItemMeta(meta);
            inv.setItem(RANK_SLOTS[rank - 1], head);
        }

        // saját helyezés
        int own = -1;
        long ownValue = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getKey().equals(viewer.getUniqueId())) {
                own = i + 1;
                ownValue = list.get(i).getValue();
                break;
            }
        }
        ItemStack me = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meMeta = (SkullMeta) me.getItemMeta();
        meMeta.setOwningPlayer(viewer);
        meMeta.displayName(plugin.parse("<!italic><aqua><bold>Te: " + viewer.getName()));
        meMeta.lore(List.of(
                plugin.parse("<!italic><gray>Helyezésed: <white>" + (own < 0 ? "még nincs" : "#" + own)),
                plugin.parse("<!italic><gray>" + tabName(type) + ": <white>" + stats.formatValue(type, ownValue))));
        me.setItemMeta(meMeta);
        inv.setItem(49, me);

        viewer.openInventory(inv);
    }

    private static String tabName(String type) {
        return switch (type) {
            case "playtime" -> "Játékidő";
            case "kills" -> "Ölések";
            case "deaths" -> "Halálok";
            default -> "Pénz";
        };
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TopHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        for (int i = 0; i < TAB_SLOTS.length; i++) {
            if (event.getSlot() == TAB_SLOTS[i] && !TYPES[i].equals(holder.type)) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
                open(player, TYPES[i]);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TopHolder) {
            event.setCancelled(true);
        }
    }
}
