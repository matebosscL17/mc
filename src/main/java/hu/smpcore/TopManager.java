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
    private static final int[] TAB_SLOTS = {2, 3, 5, 6};
    private static final int TOGGLE_SLOT = 4;
    private static final Material[] TAB_ICONS = {Material.CLOCK, Material.DIAMOND_SWORD,
            Material.WITHER_SKELETON_SKULL, Material.GOLD_INGOT};
    private static final String[] TAB_NAMES = {"<yellow>Játékidő", "<red>Ölések", "<gray>Halálok", "<green>Pénz"};
    private static final Material[] TAB_PANES = {Material.YELLOW_STAINED_GLASS_PANE,
            Material.RED_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE};
    /** helyezés (1-től) -> slot: dobogó + alsó sor */
    private static final int[] RANK_SLOTS = {13, 21, 32, 46, 47, 48, 49, 50, 51, 52};
    private static final int[] GOLD_COLUMN = {22, 31, 40};
    private static final int[] SILVER_COLUMN = {30, 39};
    private static final int[] BRONZE_COLUMN = {41};

    private static final class TopHolder implements InventoryHolder {
        private Inventory inventory;
        private String type;
        private boolean weekly;

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
        open(viewer, type, false);
    }

    public void open(Player viewer, String type, boolean weeklyMode) {
        int typeIndex = java.util.Arrays.asList(TYPES).indexOf(type);
        if (typeIndex < 0) {
            return;
        }
        boolean weekly = weeklyMode && WeeklyManager.TYPES.contains(type);
        List<Map.Entry<UUID, Long>> list = weekly ? plugin.weekly().ranking(type) : stats.ranking(type);
        if (list == null) {
            return;
        }
        TopHolder holder = new TopHolder();
        holder.type = type;
        holder.weekly = weekly;
        Inventory inv = Bukkit.createInventory(holder, 54, plugin.parse(weekly
                ? "<gradient:#55ffff:#5555ff><bold>✦ HETI TOPLISTA ✦"
                : "<gradient:#ffd700:#ff8c00><bold>✦ PENTHOUSE TOPLISTA ✦"));
        holder.inventory = inv;

        ItemStack dark = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        ItemStack accent = named(TAB_PANES[typeIndex], " ", List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, i < 9 ? accent : dark);
        }
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, accent);
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

        if (WeeklyManager.TYPES.contains(type)) {
            inv.setItem(TOGGLE_SLOT, named(weekly ? Material.ENDER_EYE : Material.ENDER_PEARL,
                    weekly ? "<aqua><bold>Mód: Heti" : "<gold><bold>Mód: Összesített",
                    List.of("<gray>Kattints a váltáshoz",
                            weekly ? "<gray>Hátralévő idő: <white>" + plugin.weekly().timeLeft()
                                    : "<gray>A heti lista jutalmat ad!")));
        }

        // dobogó oszlopok
        fillColumn(inv, GOLD_COLUMN, Material.YELLOW_STAINED_GLASS_PANE, "<gold>1. hely");
        fillColumn(inv, SILVER_COLUMN, Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<#c0c0c0>2. hely");
        fillColumn(inv, BRONZE_COLUMN, Material.ORANGE_STAINED_GLASS_PANE, "<#cd7f32>3. hely");

        for (int rank = 1; rank <= RANK_SLOTS.length; rank++) {
            if (rank > list.size()) {
                if (rank > 3) {
                    inv.setItem(RANK_SLOTS[rank - 1], named(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            "<dark_gray>#" + rank + " <gray>üres", List.of()));
                } else {
                    inv.setItem(RANK_SLOTS[rank - 1], named(Material.BARRIER,
                            "<dark_gray>#" + rank + " <gray>üres", List.of()));
                }
                continue;
            }
            Map.Entry<UUID, Long> entry = list.get(rank - 1);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.getKey()));
            meta.displayName(plugin.parse("<!italic>" + rankColor(rank) + "<bold>#" + rank + " "
                    + plugin.name(entry.getKey())));
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.parse("<!italic><gray>" + (weekly ? "Heti " : "") + tabName(type).toLowerCase()
                    + ": <white>" + stats.formatValue(type, entry.getValue())));
            if (weekly && rank <= 3) {
                lore.add(plugin.parse("<!italic><gray>Jutalom: <green>" + plugin.weekly().describe(type, rank)));
            }
            meta.lore(lore);
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
        inv.setItem(45, me);

        // heti jutalmak
        if (WeeklyManager.TYPES.contains(type)) {
            inv.setItem(53, named(Material.CHEST, "<gold><bold>Heti jutalmak",
                    List.of("<yellow>1. hely: <white>" + plugin.weekly().describe(type, 1),
                            "<#c0c0c0>2. hely: <white>" + plugin.weekly().describe(type, 2),
                            "<#cd7f32>3. hely: <white>" + plugin.weekly().describe(type, 3),
                            "<gray>Hátralévő idő: <white>" + plugin.weekly().timeLeft())));
        } else {
            inv.setItem(53, named(Material.CHEST, "<gray>Heti jutalmak", List.of("<dark_gray>Ehhez a listához nincs.")));
        }

        viewer.openInventory(inv);
    }

    private void fillColumn(Inventory inv, int[] slots, Material pane, String name) {
        for (int slot : slots) {
            inv.setItem(slot, named(pane, name, List.of()));
        }
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
                open(player, TYPES[i], holder.weekly);
                return;
            }
        }
        if (event.getSlot() == TOGGLE_SLOT && WeeklyManager.TYPES.contains(holder.type)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
            open(player, holder.type, !holder.weekly);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TopHolder) {
            event.setCancelled(true);
        }
    }
}
