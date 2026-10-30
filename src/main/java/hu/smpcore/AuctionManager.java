package hu.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Aukciósház: /ah, /ah sell [ár], /ah my. Lapozható GUI, kattintásra vétel. */
public final class AuctionManager implements Listener, CommandExecutor {

    private static final int PAGE_SIZE = 45;

    private static final class Listing {
        private final UUID id;
        private final UUID seller;
        private final long price;
        private final long created;
        private final ItemStack item;

        private Listing(UUID id, UUID seller, long price, long created, ItemStack item) {
            this.id = id;
            this.seller = seller;
            this.price = price;
            this.created = created;
            this.item = item;
        }
    }

    private static final class AuctionHolder implements InventoryHolder {
        private Inventory inventory;
        private int page;
        private boolean mine;
        private final List<Listing> shown = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();

    public AuctionManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
        load();
    }

    // ---------- Mentés / betöltés ----------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("listings")) {
            try {
                Listing listing = new Listing(
                        UUID.fromString((String) m.get("id")),
                        UUID.fromString((String) m.get("seller")),
                        ((Number) m.get("price")).longValue(),
                        ((Number) m.get("created")).longValue(),
                        ItemStack.deserializeBytes(Base64.getDecoder().decode((String) m.get("item"))));
                listings.put(listing.id, listing);
            } catch (Exception ex) {
                plugin.getLogger().warning("Hibás hirdetés az auctions.yml-ben, kihagyva: " + ex.getMessage());
            }
        }
    }

    private void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Listing l : listings.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id.toString());
            m.put("seller", l.seller.toString());
            m.put("price", l.price);
            m.put("created", l.created);
            m.put("item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
            list.add(m);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("listings", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nem sikerült menteni az auctions.yml-t: " + ex.getMessage());
        }
    }

    // ---------- Segédek ----------

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String describe(ItemStack item) {
        return item.getAmount() + "x " + pretty(item.getType());
    }

    private void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    // ---------- Parancsok ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "player-only");
            return true;
        }
        if (args.length == 0) {
            open(player, 0, false);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "sell" -> sell(player, args);
            case "my" -> open(player, 0, true);
            default -> plugin.send(player, "ah-usage");
        }
        return true;
    }

    private void sell(Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "ah-usage");
            return;
        }
        long price = plugin.parseAmount(args[1]);
        if (price <= 0) {
            plugin.send(player, "invalid-amount");
            return;
        }
        long min = plugin.getConfig().getLong("auction.min-price", 1);
        if (price < min) {
            plugin.send(player, "ah-min", Placeholder.unparsed("amount", plugin.money(min)));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.send(player, "ah-empty-hand");
            return;
        }
        int max = plugin.getConfig().getInt("auction.max-listings", 5);
        long own = listings.values().stream().filter(l -> l.seller.equals(player.getUniqueId())).count();
        if (own >= max) {
            plugin.send(player, "ah-limit", Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        Listing listing = new Listing(UUID.randomUUID(), player.getUniqueId(), price,
                System.currentTimeMillis(), hand.clone());
        listings.put(listing.id, listing);
        player.getInventory().setItemInMainHand(null);
        save();
        plugin.send(player, "ah-listed",
                Placeholder.unparsed("item", describe(listing.item)),
                Placeholder.unparsed("price", plugin.money(price)));
    }

    // ---------- GUI ----------

    private void open(Player player, int page, boolean mine) {
        List<Listing> source = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (!mine || l.seller.equals(player.getUniqueId())) {
                source.add(l);
            }
        }
        Collections.reverse(source); // legújabb elöl

        int pages = Math.max(1, (source.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));

        AuctionHolder holder = new AuctionHolder();
        holder.page = current;
        holder.mine = mine;
        holder.shown.addAll(source);

        Component title = plugin.parse(plugin.cfg(mine ? "auction.title-mine" : "auction.title"),
                Placeholder.unparsed("page", String.valueOf(current + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages)));
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inv;

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = current * PAGE_SIZE + i;
            if (index >= source.size()) {
                break;
            }
            Listing l = source.get(index);
            ItemStack display = l.item.clone();
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(plugin.msg("ah-lore-price", Placeholder.unparsed("price", plugin.money(l.price))));
            lore.add(plugin.msg("ah-lore-seller", Placeholder.unparsed("player", plugin.name(l.seller))));
            lore.add(plugin.msg(l.seller.equals(player.getUniqueId()) ? "ah-lore-own" : "ah-lore-buy"));
            meta.lore(lore);
            display.setItemMeta(meta);
            inv.setItem(i, display);
        }

        if (current > 0) {
            inv.setItem(45, navItem(Material.ARROW, "ah-prev"));
        }
        inv.setItem(49, navItem(mine ? Material.CHEST : Material.ENDER_CHEST, mine ? "ah-all" : "ah-mine"));
        if (current < pages - 1) {
            inv.setItem(53, navItem(Material.ARROW, "ah-next"));
        }
        player.openInventory(inv);
    }

    private ItemStack navItem(Material material, String messageKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.msg(messageKey));
        item.setItemMeta(meta);
        return item;
    }

    private void reopen(Player player, AuctionHolder holder) {
        plugin.getServer().getScheduler().runTask(plugin, () -> open(player, holder.page, holder.mine));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AuctionHolder holder)) {
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
        if (slot == 45 && holder.page > 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, holder.page - 1, holder.mine));
            return;
        }
        if (slot == 53) {
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, holder.page + 1, holder.mine));
            return;
        }
        if (slot == 49) {
            plugin.getServer().getScheduler().runTask(plugin, () -> open(player, 0, !holder.mine));
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) {
            return;
        }
        int index = holder.page * PAGE_SIZE + slot;
        if (index >= holder.shown.size()) {
            return;
        }
        Listing listing = holder.shown.get(index);
        if (!listings.containsKey(listing.id)) {
            plugin.send(player, "ah-gone");
            reopen(player, holder);
            return;
        }
        if (listing.seller.equals(player.getUniqueId())) {
            listings.remove(listing.id);
            save();
            give(player, listing.item.clone());
            plugin.send(player, "ah-cancelled", Placeholder.unparsed("item", describe(listing.item)));
        } else {
            buy(player, listing);
        }
        reopen(player, holder);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AuctionHolder) {
            event.setCancelled(true);
        }
    }

    private void buy(Player buyer, Listing listing) {
        if (!plugin.economy().withdraw(buyer.getUniqueId(), listing.price)) {
            plugin.send(buyer, "not-enough-money",
                    Placeholder.unparsed("amount", plugin.money(listing.price)));
            return;
        }
        long taxPercent = Math.max(0, Math.min(100, plugin.getConfig().getLong("auction.tax-percent", 5)));
        long payout = listing.price - listing.price * taxPercent / 100;
        plugin.economy().add(listing.seller, payout);
        listings.remove(listing.id);
        save();
        give(buyer, listing.item.clone());

        plugin.send(buyer, "ah-bought",
                Placeholder.unparsed("item", describe(listing.item)),
                Placeholder.unparsed("price", plugin.money(listing.price)));
        Player seller = Bukkit.getPlayer(listing.seller);
        if (seller != null) {
            plugin.send(seller, "ah-sold",
                    Placeholder.unparsed("item", describe(listing.item)),
                    Placeholder.unparsed("player", buyer.getName()),
                    Placeholder.unparsed("amount", plugin.money(payout)));
        }
    }
}
