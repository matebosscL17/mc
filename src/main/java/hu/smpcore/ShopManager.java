package hu.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /shop (főmenü kategóriákkal, lapozható oldalak) és /sell [all] */
public final class ShopManager implements Listener, CommandExecutor {

    private static final int PAGE_SIZE = 45;
    private static final int[] MENU_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private record Category(String key, Material icon, String name, Map<Material, long[]> items) {
    }

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        /** null = főmenü */
        private String category;
        private int page;
        private final Map<Integer, String> menuSlots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SMPCore plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    /** anyag -> {vételár, eladási ár} (minden kategória együtt, /sell-hez) */
    private final Map<Material, long[]> prices = new LinkedHashMap<>();

    public ShopManager(SMPCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        prices.clear();
        ConfigurationSection cats = plugin.getConfig().getConfigurationSection("shop.categories");
        if (cats == null) {
            return;
        }
        for (String key : cats.getKeys(false)) {
            ConfigurationSection section = cats.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
            if (icon == null) {
                icon = Material.CHEST;
            }
            Map<Material, long[]> items = new LinkedHashMap<>();
            ConfigurationSection itemSection = section.getConfigurationSection("items");
            if (itemSection != null) {
                for (String itemKey : itemSection.getKeys(false)) {
                    Material material = Material.matchMaterial(itemKey);
                    if (material == null || !material.isItem()) {
                        plugin.getLogger().warning("Ismeretlen shop tárgy a configban: " + itemKey);
                        continue;
                    }
                    long[] price = {
                            itemSection.getLong(itemKey + ".buy", 0),
                            itemSection.getLong(itemKey + ".sell", 0)};
                    items.put(material, price);
                    prices.put(material, price);
                }
            }
            categories.put(key, new Category(key, icon, section.getString("name", key), items));
        }
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("shop")) {
            openMenu(player);
            return true;
        }
        // /sell [all]
        if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
            sellAll(player);
        } else {
            sellHand(player);
        }
        return true;
    }

    // ---------- GUI ----------

    private void openMenu(Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 36, plugin.parse(plugin.cfg("shop.title")));
        holder.inventory = inv;

        int i = 0;
        for (Category category : categories.values()) {
            if (i >= MENU_SLOTS.length) {
                break;
            }
            ItemStack icon = new ItemStack(category.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(plugin.parse(category.name()));
            meta.lore(List.of(plugin.msg("shop-category-lore",
                    Placeholder.unparsed("count", String.valueOf(category.items().size())))));
            icon.setItemMeta(meta);
            inv.setItem(MENU_SLOTS[i], icon);
            holder.menuSlots.put(MENU_SLOTS[i], category.key());
            i++;
        }
        player.openInventory(inv);
    }

    private void openCategory(Player player, String key, int page) {
        Category category = categories.get(key);
        if (category == null) {
            openMenu(player);
            return;
        }
        List<Map.Entry<Material, long[]>> entries = new ArrayList<>(category.items().entrySet());
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));

        ShopHolder holder = new ShopHolder();
        holder.category = key;
        holder.page = current;
        Component title = plugin.parse(plugin.cfg("shop.title") + " <dark_gray>» <reset>" + category.name()
                + " <gray>(" + (current + 1) + "/" + pages + ")");
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inv;

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= entries.size()) {
                break;
            }
            Map.Entry<Material, long[]> entry = entries.get(index);
            long buy = entry.getValue()[0];
            long sell = entry.getValue()[1];
            ItemStack item = new ItemStack(entry.getKey());
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = new ArrayList<>();
            if (buy > 0) {
                lore.add(plugin.msg("shop-lore-buy", Placeholder.unparsed("price", plugin.money(buy))));
            }
            if (sell > 0) {
                lore.add(plugin.msg("shop-lore-sell", Placeholder.unparsed("price", plugin.money(sell))));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }

        if (current > 0) {
            inv.setItem(45, nav(Material.ARROW, "shop-prev"));
        }
        inv.setItem(49, nav(Material.BARRIER, "shop-back"));
        if (current < pages - 1) {
            inv.setItem(53, nav(Material.ARROW, "shop-next"));
        }
        player.openInventory(inv);
    }

    private ItemStack nav(Material material, String messageKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.msg(messageKey));
        item.setItemMeta(meta);
        return item;
    }

    private void later(Runnable runnable) {
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();

        // Főmenü
        if (holder.category == null) {
            String key = holder.menuSlots.get(slot);
            if (key != null) {
                later(() -> openCategory(player, key, 0));
            }
            return;
        }

        // Kategória oldal: navigáció
        if (slot == 45 && holder.page > 0) {
            later(() -> openCategory(player, holder.category, holder.page - 1));
            return;
        }
        if (slot == 49) {
            later(() -> openMenu(player));
            return;
        }
        if (slot == 53) {
            later(() -> openCategory(player, holder.category, holder.page + 1));
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        long[] price = prices.get(clicked.getType());
        if (price == null) {
            return;
        }
        int amount = event.isShiftClick() ? 16 : 1;
        if (event.isLeftClick()) {
            buy(player, clicked.getType(), amount, price[0]);
        } else if (event.isRightClick()) {
            sell(player, clicked.getType(), amount, price[1]);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    // ---------- Vétel / eladás ----------

    private void buy(Player player, Material material, int amount, long unitPrice) {
        if (unitPrice <= 0) {
            plugin.send(player, "shop-cannot-buy");
            return;
        }
        UUID id = player.getUniqueId();
        long total = unitPrice * amount;
        if (!plugin.economy().withdraw(id, total)) {
            plugin.send(player, "not-enough-money", Placeholder.unparsed("amount", plugin.money(total)));
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
        int notGiven = 0;
        for (ItemStack left : leftover.values()) {
            notGiven += left.getAmount();
        }
        if (notGiven > 0) {
            plugin.economy().add(id, unitPrice * notGiven);
            plugin.send(player, "shop-inv-full");
        }
        int given = amount - notGiven;
        if (given > 0) {
            plugin.send(player, "shop-bought",
                    Placeholder.unparsed("count", String.valueOf(given)),
                    Placeholder.unparsed("item", pretty(material)),
                    Placeholder.unparsed("price", plugin.money(unitPrice * given)));
        }
    }

    private void sell(Player player, Material material, int amount, long unitPrice) {
        if (unitPrice <= 0) {
            plugin.send(player, "shop-not-sellable");
            return;
        }
        int sold = removePlain(player, material, Math.min(amount, countPlain(player, material)));
        if (sold <= 0) {
            plugin.send(player, "shop-nothing");
            return;
        }
        long total = unitPrice * sold;
        plugin.economy().add(player.getUniqueId(), total);
        plugin.send(player, "shop-sold",
                Placeholder.unparsed("count", String.valueOf(sold)),
                Placeholder.unparsed("item", pretty(material)),
                Placeholder.unparsed("price", plugin.money(total)));
    }

    private void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        long[] price = prices.get(hand.getType());
        if (hand.getType().isAir() || price == null || price[1] <= 0 || hand.hasItemMeta()) {
            plugin.send(player, "shop-not-sellable");
            return;
        }
        sell(player, hand.getType(), hand.getAmount(), price[1]);
    }

    private void sellAll(Player player) {
        long total = 0;
        int count = 0;
        for (Map.Entry<Material, long[]> entry : prices.entrySet()) {
            long sellPrice = entry.getValue()[1];
            if (sellPrice <= 0) {
                continue;
            }
            int have = countPlain(player, entry.getKey());
            if (have <= 0) {
                continue;
            }
            int removed = removePlain(player, entry.getKey(), have);
            total += sellPrice * removed;
            count += removed;
        }
        if (count == 0) {
            plugin.send(player, "shop-nothing");
            return;
        }
        plugin.economy().add(player.getUniqueId(), total);
        plugin.send(player, "shop-sold-all",
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("price", plugin.money(total)));
    }

    /** Csak a "sima" (nem elnevezett, nem enchantolt) tárgyakat számoljuk. */
    private int countPlain(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material && !item.hasItemMeta()) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int removePlain(Player player, Material material, int amount) {
        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material || item.hasItemMeta()) {
                continue;
            }
            int take = Math.min(left, item.getAmount());
            if (take == item.getAmount()) {
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
            left -= take;
        }
        player.getInventory().setStorageContents(contents);
        return amount - left;
    }
}
