package hu.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /shop (GUI) és /sell [all] */
public final class ShopManager implements Listener, CommandExecutor {

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SMPCore plugin;
    /** anyag -> {vételár, eladási ár} */
    private final Map<Material, long[]> prices = new LinkedHashMap<>();

    public ShopManager(SMPCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        prices.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop.items");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning("Ismeretlen shop tárgy a configban: " + key);
                continue;
            }
            prices.put(material, new long[]{
                    section.getLong(key + ".buy", 0),
                    section.getLong(key + ".sell", 0)});
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
            open(player);
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

    private void open(Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, plugin.parse(plugin.cfg("shop.title")));
        holder.inventory = inv;

        int slot = 0;
        for (Map.Entry<Material, long[]> entry : prices.entrySet()) {
            if (slot >= 45) {
                break; // egy oldal: max 45 tárgy
            }
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
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    // ---------- GUI események ----------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder)) {
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
