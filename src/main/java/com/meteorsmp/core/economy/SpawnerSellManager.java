package com.meteorsmp.core.economy;

import com.meteorsmp.core.PluginMain;
import com.meteorsmp.core.database.DatabaseManager;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SpawnerSellManager implements net.milkbowl.vault.economy.Economy, Listener {

    private static final String SELL_GUI_TITLE = "Place items in here to sell";
    private static final Set<String> CATEGORIES = Set.of(
            "farming", "valuables", "mob_drops", "blocks", "armor",
            "fishing", "books", "brewing", "natural");

    private final PluginMain plugin;
    private final DatabaseManager db;
    private final Map<String, Double> worthPrices = new ConcurrentHashMap<>();
    private final Map<String, Double> shopBuyPrices = new ConcurrentHashMap<>();
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();

    public SpawnerSellManager(PluginMain plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void loadWorthAndMultipliers() {
        try (Connection c = db.getRawConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT item_id, price FROM worth_prices");
                 ResultSet rs = ps.executeQuery()) {
                worthPrices.clear();
                while (rs.next()) worthPrices.put(rs.getString("item_id"), rs.getDouble("price"));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT item_id, buy_price FROM shop_buy_prices");
                 ResultSet rs = ps.executeQuery()) {
                shopBuyPrices.clear();
                while (rs.next()) shopBuyPrices.put(rs.getString("item_id"), rs.getDouble("buy_price"));
            }
            plugin.getLogger().info("Loaded " + worthPrices.size() + " worth prices, " + shopBuyPrices.size() + " shop buy prices.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load worth_prices/shop_buy_prices", e);
        }
    }

    private double sellMarginPercent() {
        return plugin.getConfig().getDouble("shop.sell-margin-percent", 15.0);
    }

    public boolean isSellMultiEnabled() {
        return plugin.getConfig().getBoolean("economy.sellmulti-enabled", true);
    }

    public void deriveWorthPriceFromShop(String itemId, double buyPrice) {
        double margin = sellMarginPercent();
        double sellPrice = Math.round(buyPrice * (1 - margin / 100.0) * 100.0) / 100.0;
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO worth_prices (item_id, price, category) VALUES (?, ?, ?)
                 ON CONFLICT(item_id) DO UPDATE SET price = excluded.price
                 """)) {
            ps.setString(1, itemId);
            ps.setDouble(2, sellPrice);
            ps.setString(3, getSellCategory(itemId));
            ps.executeUpdate();
            worthPrices.put(itemId, sellPrice);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "deriveWorthPriceFromShop failed for " + itemId, e);
        }
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO shop_buy_prices (item_id, buy_price, currency) VALUES (?, ?, 'money')
                 ON CONFLICT(item_id) DO UPDATE SET buy_price = excluded.buy_price
                 """)) {
            ps.setString(1, itemId);
            ps.setDouble(2, buyPrice);
            ps.executeUpdate();
            shopBuyPrices.put(itemId, buyPrice);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "shop_buy_prices sync failed for " + itemId, e);
        }
    }

    private double getClampedSellPrice(String itemId) {
        Double raw = worthPrices.get(itemId);
        if (raw == null) return -1;
        Double buyPrice = shopBuyPrices.get(itemId);
        if (buyPrice == null) return raw;
        double cap = buyPrice * (1 - sellMarginPercent() / 100.0);
        return Math.min(raw, cap);
    }

    public long getShards(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT shards FROM balances WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("shards") : 0;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "getShards failed for " + uuid, e);
            return 0;
        }
    }

    public void setShards(UUID uuid, long amount) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO balances (uuid, shards) VALUES (?, ?)
                 ON CONFLICT(uuid) DO UPDATE SET shards = excluded.shards
                 """)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, amount);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "setShards failed for " + uuid, e);
        }
    }

    public String getSellCategory(String itemId) {
        if (containsAny(itemId, "wheat", "carrot", "potato", "beetroot", "seed", "melon",
                "pumpkin", "sugar_cane", "kelp", "cocoa", "crop", "mushroom")) return "farming";
        if (containsAny(itemId, "diamond", "emerald", "ingot", "raw_", "coal", "lapis",
                "redstone", "quartz", "amethyst", "gold", "iron", "copper")) return "valuables";
        if (containsAny(itemId, "bone", "flesh", "string", "spider_eye", "gunpowder", "slime",
                "ender_pearl", "blaze", "ghast", "magma", "phantom")) return "mob_drops";
        if (containsAny(itemId, "helmet", "chestplate", "leggings", "boots", "netherite",
                "sword", "axe", "bow", "shield", "trident")) return "armor";
        if (containsAny(itemId, "fish", "fishing_rod", "nautilus", "saddle", "name_tag")) return "fishing";
        if (containsAny(itemId, "enchanted_book", "book")) return "books";
        if (containsAny(itemId, "potion", "brewing", "glistering", "golden_carrot",
                "rabbit_foot", "dragon_breath", "nether_wart")) return "brewing";
        if (containsAny(itemId, "sapling", "leaf", "leaves", "flower", "grass", "vine", "lily",
                "cactus", "fern", "dead_bush", "log", "wood", "plank", "stem")) return "natural";
        return "blocks";
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    public double getCategoryMultiplier(UUID uuid, String category) {
        double money = getMoneyMade(uuid, category);
        double[] thresholds = {640_000_000_000d, 320_000_000_000d, 160_000_000_000d, 80_000_000_000d,
                40_000_000_000d, 20_000_000_000d, 10_000_000_000d, 8_000_000_000d, 4_000_000_000d,
                2_000_000_000d, 1_000_000_000d, 850_000_000d, 550_000_000d, 250_000_000d,
                25_000_000d, 5_000_000d, 1_000_000d, 500_000d, 150_000d, 25_000d};
        double[] multipliers = {3.0, 2.9, 2.8, 2.7, 2.6, 2.5, 2.4, 2.3, 2.2, 2.1,
                2.0, 1.9, 1.8, 1.7, 1.6, 1.5, 1.4, 1.3, 1.2, 1.1};
        for (int i = 0; i < thresholds.length; i++) {
            if (money >= thresholds[i]) return multipliers[i];
        }
        return 1.0;
    }

    public double getSellMultiplier(UUID uuid) {
        if (!isSellMultiEnabled()) return 1.0;
        double multi = 1.0;
        for (String cat : CATEGORIES) multi += (getCategoryMultiplier(uuid, cat) - 1.0);
        return multi;
    }

    private double getMoneyMade(UUID uuid, String category) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT total FROM money_made WHERE uuid = ? AND category = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble("total") : 0;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "getMoneyMade query failed", e);
            return 0;
        }
    }

    private void addMoneyMade(UUID uuid, String category, double amount, int itemsSold) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO money_made (uuid, category, total, items_sold) VALUES (?, ?, ?, ?)
                 ON CONFLICT(uuid, category) DO UPDATE SET
                     total = total + excluded.total,
                     items_sold = items_sold + excluded.items_sold
                 """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, category);
            ps.setDouble(3, amount);
            ps.setInt(4, itemsSold);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "addMoneyMade write failed", e);
        }
    }

    public void openSellGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, SELL_GUI_TITLE);
        gui.setItem(45, sellCategoryIcon(Material.WHEAT, "farming"));
        gui.setItem(46, sellCategoryIcon(Material.DIAMOND, "valuables"));
        gui.setItem(47, sellCategoryIcon(Material.BONE, "mob_drops"));
        gui.setItem(48, sellCategoryIcon(Material.OAK_LEAVES, "blocks"));
        gui.setItem(49, sellCategoryIcon(Material.NETHERITE_HELMET, "armor"));
        gui.setItem(50, sellCategoryIcon(Material.TROPICAL_FISH, "fishing"));
        gui.setItem(51, sellCategoryIcon(Material.ENCHANTED_BOOK, "books"));
        gui.setItem(52, sellCategoryIcon(Material.BREWING_STAND, "brewing"));
        gui.setItem(53, sellCategoryIcon(Material.BRICK, "natural"));
        player.openInventory(gui);
    }

    private ItemStack sellCategoryIcon(Material mat, String cat) {
        ItemStack icon = new ItemStack(mat);
        ItemMeta meta = icon.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + cat.replace('_', ' '));
        meta.setLore(List.of(ChatColor.GRAY + "Click to view your multiplier progress"));
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler
    public void onSellGuiClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equalsIgnoreCase(SELL_GUI_TITLE)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        double earnings = 0;
        int itemsSold = 0;
        boolean returnedAny = false;
        double oldMultiplier = getSellMultiplier(player.getUniqueId());
        Map<String, Double> earningsByCategory = new HashMap<>();
        Map<String, Integer> itemsByCategory = new HashMap<>();

        for (ItemStack item : event.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            String id = cleanTypeId(item);

            if (id.contains("spawner")) {
                player.getInventory().addItem(item);
                returnedAny = true;
                continue;
            }

            if (item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
                List<ItemStack> subItems = new ArrayList<>();
                for (ItemStack sub : box.getInventory().getContents()) {
                    if (sub != null && sub.getType() != Material.AIR) subItems.add(sub);
                }

                if (!subItems.isEmpty()) {
                    boolean soldAnySub = false;
                    for (ItemStack sub : subItems) {
                        String subId = cleanTypeId(sub);
                        double unitPrice = getClampedSellPrice(subId);
                        if (unitPrice < 0) {
                            player.getInventory().addItem(sub);
                            returnedAny = true;
                            continue;
                        }
                        double payout = unitPrice * sub.getAmount();
                        earnings += payout;
                        itemsSold += sub.getAmount();
                        soldAnySub = true;
                        String cat = getSellCategory(subId);
                        earningsByCategory.merge(cat, payout, Double::sum);
                        itemsByCategory.merge(cat, sub.getAmount(), Integer::sum);
                    }
                    if (soldAnySub) {
                        player.getInventory().addItem(new ItemStack(item.getType(), 1));
                    } else {
                        player.getInventory().addItem(item);
                        returnedAny = true;
                    }
                } else {
                    double shulkerPrice = getClampedSellPrice(id);
                    if (shulkerPrice < 0) shulkerPrice = getClampedSellPrice("shulker_box");

                    if (shulkerPrice >= 0) {
                        double payout = shulkerPrice * item.getAmount();
                        earnings += payout;
                        itemsSold += item.getAmount();
                        String cat = getSellCategory(id);
                        earningsByCategory.merge(cat, payout, Double::sum);
                        itemsByCategory.merge(cat, item.getAmount(), Integer::sum);
                    } else {
                        player.getInventory().addItem(item);
                        returnedAny = true;
                    }
                }
                continue;
            }

            double unitPrice = getClampedSellPrice(id);
            if (unitPrice < 0) {
                player.getInventory().addItem(item);
                returnedAny = true;
                continue;
            }
            double payout = unitPrice * item.getAmount();
            earnings += payout;
            itemsSold += item.getAmount();
            String cat = getSellCategory(id);
            earningsByCategory.merge(cat, payout, Double::sum);
            itemsByCategory.merge(cat, item.getAmount(), Integer::sum);
        }

        if (earnings > 0) {
            double finalEarnings = earnings * oldMultiplier;
            depositPlayer(player, finalEarnings);
            earningsByCategory.forEach((cat, amt) ->
                    addMoneyMade(player.getUniqueId(), cat, amt * oldMultiplier, itemsByCategory.getOrDefault(cat, 0)));

            double newMultiplier = getSellMultiplier(player.getUniqueId());
            if (newMultiplier > oldMultiplier) {
                player.sendMessage("§d§lMultiplier upgrade! §fYour global multiplier increased to §e" + newMultiplier + "x§f!");
            }
            player.sendActionBar(net.kyori.adventure.text.Component.text("+$" + formatBal(finalEarnings))
                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
            int fItemsSold = itemsSold;
            player.sendMessage("§a[Sell] §fSuccessfully sold " + fItemsSold + " items for §a$" +
                    formatBal(finalEarnings) + " §7(base: $" + formatBal(earnings) + " §ex" + oldMultiplier + "§7)");
        }
        if (returnedAny) {
            player.sendMessage("§c[Sell] §7Returned unsold / unpriced items to your inventory.");
        }
    }

    private String cleanTypeId(ItemStack item) {
        return item.getType().getKey().getKey().toLowerCase(Locale.ROOT);
    }

    private String formatBal(double n) {
        if (n >= 1_000_000_000_000d) return trimZero(n / 1_000_000_000_000d) + "t";
        if (n >= 1_000_000_000d) return trimZero(n / 1_000_000_000d) + "b";
        if (n >= 1_000_000d) return trimZero(n / 1_000_000d) + "m";
        if (n >= 1_000d) return trimZero(n / 1_000d) + "k";
        return trimZero(n);
    }

    private String trimZero(double d) {
        return (Math.floor(d * 10) / 10) == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(Math.floor(d * 10) / 10);
    }

    public void tryHookSmartSpawner() {
        if (Bukkit.getPluginManager().getPlugin("SmartSpawner") == null) {
            plugin.getLogger().info("SmartSpawner not found — spawner auto-sell disabled.");
            return;
        }
        final String candidateEventClass = "github.nighter.smartspawner.api.events.SpawnerSellEvent";
        try {
            Class.forName(candidateEventClass);
            plugin.getLogger().info("Found candidate SmartSpawner event class: " + candidateEventClass +
                    " — reflective handler wiring is still a stub, not implemented yet.");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("SmartSpawner is installed but " + candidateEventClass +
                    " was not found. Verify the real class name against your jar before relying on this.");
        }
    }

    // ------------------------------------------------------------------
    // /sell category-progress GUI
    // ------------------------------------------------------------------

    private static final int[] PROGRESS_SLOTS = {10, 19, 28, 37, 38, 39, 30, 21, 12, 3, 4, 5, 14, 23, 32, 41, 42, 43, 34, 25};
    private static final double[] PROGRESS_REQS = {25_000, 150_000, 500_000, 1_000_000, 5_000_000, 25_000_000, 250_000_000,
            550_000_000, 850_000_000, 1_000_000_000, 2_000_000_000, 4_000_000_000, 8_000_000_000, 10_000_000_000,
            20_000_000_000d, 40_000_000_000d, 80_000_000_000d, 160_000_000_000d, 320_000_000_000d, 640_000_000_000d};
    private static final double[] PROGRESS_MULTIS = {1.1,1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9,2.0,2.1,2.2,2.3,2.4,2.5,2.6,2.7,2.8,2.9,3.0};

    private void openCategoryProgressGui(Player p, String cat, String catName, Material icon) {
        Inventory gui = Bukkit.createInventory(null, 54, "§8ᴍᴜʟᴛɪᴘʟɪᴇʀ ᴘʀᴏɢʀᴇѕѕ: " + catName);
        double money = getMoneyMade(p.getUniqueId(), cat);

        ItemStack header = new ItemStack(icon);
        ItemMeta hMeta = header.getItemMeta();
        hMeta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + catName);
        hMeta.setLore(List.of(ChatColor.GRAY + "Category money made: " + ChatColor.GREEN + "$" + formatCommas(money)));
        header.setItemMeta(hMeta);
        gui.setItem(1, header);

        for (int i = 0; i < PROGRESS_SLOTS.length; i++) {
            boolean unlocked = money >= PROGRESS_REQS[i];
            ItemStack pane = new ItemStack(unlocked ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = pane.getItemMeta();
            meta.setDisplayName((unlocked ? ChatColor.GREEN : ChatColor.RED) + "Tier " + (i + 1) + (unlocked ? " (Unlocked)" : " (Locked)"));
            meta.setLore(List.of(
                    ChatColor.GRAY + "Requirement: " + (unlocked ? ChatColor.GREEN : ChatColor.RED) + "$" + formatCommas(PROGRESS_REQS[i]),
                    ChatColor.GRAY + "Reward: " + ChatColor.YELLOW + PROGRESS_MULTIS[i] + "x multiplier"));
            pane.setItemMeta(meta);
            gui.setItem(PROGRESS_SLOTS[i], pane);
        }

        ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Back to /sell");
        bMeta.setLore(List.of(ChatColor.GRAY + "Click to return to the sell menu."));
        back.setItemMeta(bMeta);
        gui.setItem(45, back);

        p.openInventory(gui);
    }

    // ------------------------------------------------------------------
    // /worth
    // ------------------------------------------------------------------

    private final Map<UUID, String> worthSort = new ConcurrentHashMap<>();
    private final Map<UUID, String> worthFilter = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> worthPage = new ConcurrentHashMap<>();
    private static final String WORTH_TITLE_PREFIX = "§a§lɪᴛᴇᴍ ᴘʀɪᴄᴇѕ";

    public void openWorthGui(Player p, int targetPage) {
        UUID uuid = p.getUniqueId();
        String sort = worthSort.getOrDefault(uuid, "desc");
        String filter = worthFilter.getOrDefault(uuid, "All");

        List<String> keys = new ArrayList<>(worthPrices.keySet());
        switch (sort) {
            case "desc" -> keys.sort((a, b) -> Double.compare(worthPrices.get(b), worthPrices.get(a)));
            case "asc" -> keys.sort(Comparator.comparingDouble(worthPrices::get));
            default -> keys.sort(String::compareTo);
        }

        List<String> filtered = new ArrayList<>();
        for (String key : keys) {
            Material mat = materialFor(key);
            boolean valid = switch (filter) {
                case "Blocks" -> mat != null && mat.isBlock();
                case "Tools" -> key.contains("pickaxe") || key.contains("axe") || key.contains("shovel") || key.contains("hoe") || key.contains("fishing_rod") || key.contains("shears");
                case "Food" -> mat != null && mat.isEdible();
                case "Combat" -> key.contains("sword") || key.contains("bow") || key.contains("helmet") || key.contains("chestplate") || key.contains("leggings") || key.contains("boots") || key.contains("shield") || key.contains("arrow") || key.contains("trident");
                case "Potions" -> key.contains("potion");
                case "Books" -> key.contains("book");
                default -> true;
            };
            if (valid) filtered.add(key);
        }
        if (filtered.isEmpty()) filtered = keys;

        int maxPage = Math.max(1, (int) Math.ceil(filtered.size() / 45.0));
        int page = Math.max(1, Math.min(targetPage, maxPage));
        worthPage.put(uuid, page);

        Inventory gui = Bukkit.createInventory(null, 54, WORTH_TITLE_PREFIX + " §8(§fpage " + page + "/" + maxPage + "§8)");

        int start = (page - 1) * 45;
        for (int i = 0; i < 45; i++) {
            int idx = start + i;
            if (idx < filtered.size()) {
                String key = filtered.get(idx);
                Material mat = materialFor(key);
                ItemStack display = new ItemStack(mat != null ? mat : Material.BARRIER);
                ItemMeta meta = display.getItemMeta();
                meta.setLore(List.of(ChatColor.GRAY + "Worth: " + ChatColor.GREEN + "$" + formatBal(worthPrices.get(key))));
                display.setItemMeta(meta);
                gui.setItem(i, display);
            } else {
                gui.setItem(i, grayPaneNamed(""));
            }
        }

        gui.setItem(45, page > 1
                ? navItem(Material.ARROW, ChatColor.GREEN + "" + ChatColor.BOLD + "Previous Page", "Click to go to page " + (page - 1))
                : grayPaneNamed("First Page"));

        String sortLabel = switch (sort) { case "desc" -> "Highest Price"; case "asc" -> "Lowest Price"; default -> "By name"; };
        gui.setItem(48, navItem(Material.CAULDRON, ChatColor.YELLOW + "" + ChatColor.BOLD + "Sort", "Currently: " + sortLabel + " (click to cycle)"));
        gui.setItem(49, navItem(Material.NAME_TAG, ChatColor.YELLOW + "" + ChatColor.BOLD + "Search Item", "Use /worth <item name> in chat!"));
        gui.setItem(50, navItem(Material.HOPPER, ChatColor.YELLOW + "" + ChatColor.BOLD + "Filter", "Currently: " + filter + " (click to cycle)"));
        gui.setItem(46, grayPaneNamed(""));
        gui.setItem(47, grayPaneNamed(""));
        gui.setItem(51, grayPaneNamed(""));
        gui.setItem(52, grayPaneNamed(""));

        gui.setItem(53, page < maxPage
                ? navItem(Material.ARROW, ChatColor.GREEN + "" + ChatColor.BOLD + "Next Page", "Click to go to page " + (page + 1))
                : grayPaneNamed("Last Page"));

        p.openInventory(gui);
    }

    private Material materialFor(String key) {
        try { return Material.valueOf(key.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    private ItemStack grayPaneNamed(String text) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = pane.getItemMeta();
        m.setDisplayName(text.isEmpty() ? "§7" : ChatColor.GRAY + text);
        pane.setItemMeta(m);
        return pane;
    }

    private ItemStack navItem(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.setDisplayName(name);
        m.setLore(List.of(ChatColor.GRAY + lore));
        item.setItemMeta(m);
        return item;
    }

    public boolean handleWorthCommand(Player p, String[] args) {
        if (args.length == 0) { openWorthGui(p, 1); return true; }
        Integer pageNum = tryParseInt(args[0]);
        if (pageNum != null) { openWorthGui(p, pageNum); return true; }

        String query = args[0].toLowerCase(Locale.ROOT).replace(' ', '_');
        if (query.equals("undy")) query = "totem_of_undying";
        Double price = worthPrices.get(query);
        if (price != null) {
            p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Worth " + ChatColor.DARK_GRAY + "» " +
                    ChatColor.YELLOW + query.replace('_', ' ') + ChatColor.DARK_GRAY + " » " + ChatColor.GREEN + "$" + formatCommas(price));
        } else {
            p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Worth " + ChatColor.DARK_GRAY + "» " + ChatColor.RED + "item '" + args[0] + "' was not found!");
        }
        return true;
    }

    private Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private String formatCommas(double n) {
        return String.format(Locale.US, "%,.0f", n);
    }

    @EventHandler
    public void onSellRelatedClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        UUID uuid = player.getUniqueId();

        if (title.startsWith(WORTH_TITLE_PREFIX)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            int page = worthPage.getOrDefault(uuid, 1);
            if (slot == 45) { if (page > 1) openWorthGui(player, page - 1); }
            else if (slot == 48) {
                String cur = worthSort.getOrDefault(uuid, "desc");
                worthSort.put(uuid, cur.equals("desc") ? "asc" : cur.equals("asc") ? "name" : "desc");
                openWorthGui(player, page);
            } else if (slot == 49) {
                player.sendMessage(ChatColor.GREEN + "[Worth] Type " + ChatColor.YELLOW + "/worth <item name>" + ChatColor.GREEN + " in chat to check a specific item's price!");
            } else if (slot == 50) {
                String cur = worthFilter.getOrDefault(uuid, "All");
                String next = switch (cur) {
                    case "All" -> "Blocks"; case "Blocks" -> "Tools"; case "Tools" -> "Food";
                    case "Food" -> "Combat"; case "Combat" -> "Potions"; case "Potions" -> "Books"; default -> "All";
                };
                worthFilter.put(uuid, next);
                openWorthGui(player, 1);
            } else if (slot == 53) {
                openWorthGui(player, page + 1);
            }
            return;
        }

        if (title.equalsIgnoreCase(SELL_GUI_TITLE)) {
            int slot = event.getRawSlot();
            if (slot >= 45 && slot <= 53) {
                event.setCancelled(true);
                switch (slot) {
                    case 45 -> openCategoryProgressGui(player, "farming", "ꜰᴀʀᴍɪɴɢ", Material.WHEAT);
                    case 46 -> openCategoryProgressGui(player, "valuables", "ᴠᴀʟᴜᴀʙʟᴇѕ", Material.DIAMOND);
                    case 47 -> openCategoryProgressGui(player, "mob_drops", "ᴍᴏʙ ᴅʀᴏᴘѕ", Material.BONE);
                    case 48 -> openCategoryProgressGui(player, "blocks", "ʙʟᴏᴄᴋѕ", Material.OAK_LEAVES);
                    case 49 -> openCategoryProgressGui(player, "armor", "ᴀʀᴍᴏʀ & ᴄᴏᴍʙᴀᴛ", Material.NETHERITE_HELMET);
                    case 50 -> openCategoryProgressGui(player, "fishing", "ꜰɪѕʜɪɴɢ", Material.TROPICAL_FISH);
                    case 51 -> openCategoryProgressGui(player, "books", "ʙᴏᴏᴋѕ", Material.ENCHANTED_BOOK);
                    case 52 -> openCategoryProgressGui(player, "brewing", "ʙʀᴇᴡɪɴɢ", Material.BREWING_STAND);
                    case 53 -> openCategoryProgressGui(player, "natural", "ɴᴀᴛᴜʀᴀʟ ɪᴛᴇᴍѕ", Material.BRICK);
                }
            }
            return;
        }

        if (title.startsWith("§8ᴍᴜʟᴛɪᴘʟɪᴇʀ ᴘʀᴏɢʀᴇѕѕ:")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45) {
                player.closeInventory();
                openSellGui(player);
            }
        }
    }

    // ------------------------------------------------------------------
    // Vault Economy implementation
    // ------------------------------------------------------------------

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "UltimateMeteorSMP"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return "$" + formatBal(amount); }
    @Override public String currencyNamePlural() { return "Dollars"; }
    @Override public String currencyNameSingular() { return "Dollar"; }
    @Override public boolean hasAccount(String playerName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String playerName, String worldName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }

    @Override public double getBalance(String playerName) { return getBalance(Bukkit.getOfflinePlayer(playerName)); }
    @Override public double getBalance(OfflinePlayer player) {
        return balanceCache.computeIfAbsent(player.getUniqueId(), this::loadBalanceFromDb);
    }
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    private double loadBalanceFromDb(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM balances WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble("balance") : 0;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "loadBalanceFromDb failed for " + uuid, e);
            return 0;
        }
    }

    @Override public boolean has(String playerName, double amount) { return getBalance(playerName) >= amount; }
    @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
    @Override public boolean has(String playerName, String world, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        double current = getBalance(player);
        if (current < amount) {
            return new EconomyResponse(0, current, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        double newBal = current - amount;
        persistBalance(player.getUniqueId(), newBal);
        return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, null);
    }
    @Override public EconomyResponse withdrawPlayer(String playerName, String world, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount); }
    public EconomyResponse depositPlayer(Player player, double amount) { return depositPlayer((OfflinePlayer) player, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        double newBal = getBalance(player) + amount;
        persistBalance(player.getUniqueId(), newBal);
        return new EconomyResponse(amount, newBal, EconomyResponse.ResponseType.SUCCESS, null);
    }
    @Override public EconomyResponse depositPlayer(String playerName, String world, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }

    private void persistBalance(UUID uuid, double newBalance) {
        balanceCache.put(uuid, newBalance);
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO balances (uuid, balance) VALUES (?, ?)
                 ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance
                 """)) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, newBalance);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "persistBalance failed for " + uuid, e);
        }
    }

    public void flushAll() { }

    @Override public EconomyResponse createBank(String name, String player) { return unsupported(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return unsupported(); }
    @Override public EconomyResponse deleteBank(String name) { return unsupported(); }
    @Override public EconomyResponse bankBalance(String name) { return unsupported(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return unsupported(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return unsupported(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return unsupported(); }
    @Override public List<String> getBanks() { return List.of(); }
    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }

    private EconomyResponse unsupported() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts not supported");
    }

    public void setBalanceDirect(UUID uuid, double amount) { persistBalance(uuid, amount); }

    public int countBalancesAbove(double threshold) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM balances WHERE balance > ?")) {
            ps.setDouble(1, threshold);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "countBalancesAbove failed", e);
            return 0;
        }
    }

    public int bulkSetBalancesAbove(double threshold, double newAmount) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE balances SET balance = ? WHERE balance > ?")) {
            ps.setDouble(1, newAmount);
            ps.setDouble(2, threshold);
            int count = ps.executeUpdate();
            balanceCache.clear();
            return count;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "bulkSetBalancesAbove failed", e);
            return 0;
        }
    }

    public void resetMoneyMade(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM money_made WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "resetMoneyMade failed", e);
        }
    }

    public void resetAllMoneyMade() {
        try (Connection c = db.getRawConnection(); var s = c.createStatement()) {
            s.execute("DELETE FROM money_made");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "resetAllMoneyMade failed", e);
        }
    }

    public String formatBalPublic(double n) { return formatBal(n); }
}
