package com.meteorsmp.core.economy;

import com.meteorsmp.core.PluginMain;
import com.meteorsmp.core.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ShopManager implements Listener {

    private static final String MAIN_TITLE = "§8§rShop";
    private static final String CAT_PREFIX = "§8§rShop - ";
    private static final String CONFIRM_PREFIX = "Buying ";

    private final PluginMain plugin;
    private final DatabaseManager db;
    private final SpawnerSellManager economy;

    private final Map<String, ShopCategory> categories = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ShopItem>> itemsByCategory = new ConcurrentHashMap<>();

    private final Map<UUID, String> viewingCategory = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> itemPage = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmState> confirming = new ConcurrentHashMap<>();

    public ShopManager(PluginMain plugin, DatabaseManager db, SpawnerSellManager economy) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
    }

    private record ShopCategory(ItemStack icon, boolean pagination, Integer catSlot) {}
    private record ShopItem(ItemStack item, double price, String currency, Integer slot) {}
    private static class ConfirmState {
        String category; int index; ItemStack item; int qty = 1;
        ConfirmState(String c, int i, ItemStack it) { category = c; index = i; item = it; }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    public void loadAll() {
        categories.clear();
        itemsByCategory.clear();
        try (Connection c = db.getRawConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM shop_categories");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] iconBytes = rs.getBytes("icon");
                    ItemStack icon = iconBytes != null ? ItemStack.deserializeBytes(iconBytes) : new ItemStack(Material.CHEST);
                    int catSlotVal = rs.getInt("cat_slot");
                    Integer catSlot = rs.wasNull() ? null : catSlotVal;
                    categories.put(rs.getString("name"),
                            new ShopCategory(icon, rs.getInt("pagination") != 0, catSlot));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM shop_items");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cat = rs.getString("category");
                    int idx = rs.getInt("idx");
                    ItemStack item = ItemStack.deserializeBytes(rs.getBytes("item"));
                    int slotVal = rs.getInt("slot");
                    Integer slot = rs.wasNull() ? null : slotVal;
                    itemsByCategory.computeIfAbsent(cat, k -> new TreeMap<>())
                            .put(idx, new ShopItem(item, rs.getDouble("price"), rs.getString("currency"), slot));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load shop data", e);
        }
        plugin.getLogger().info("Loaded " + categories.size() + " shop categories, " +
                itemsByCategory.values().stream().mapToInt(Map::size).sum() + " items.");
    }

    // ------------------------------------------------------------------
    // Admin setup commands
    // ------------------------------------------------------------------

    public void addCategory(Player admin, String name) {
        if (categories.containsKey(name)) {
            admin.sendMessage(ChatColor.RED + "[Shop] Category '" + name + "' already exists!");
            return;
        }
        ItemStack tool = admin.getInventory().getItemInMainHand();
        ItemStack icon = (tool.getType() == Material.AIR) ? new ItemStack(Material.CHEST) : tool.clone();
        categories.put(name, new ShopCategory(icon, true, null));
        persistCategory(name, icon, true, null);
        admin.sendMessage(ChatColor.GREEN + "[Shop] Created category '" + name + "' with " +
                (tool.getType() == Material.AIR ? "a default chest icon" : "your held item as the icon") + "!");
    }

    public void deleteCategory(Player admin, String name) {
        String cat = findCategory(name);
        if (cat == null) {
            admin.sendMessage(ChatColor.RED + "[Shop] Category not found!");
            return;
        }
        categories.remove(cat);
        itemsByCategory.remove(cat);
        try (Connection c = db.getRawConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM shop_categories WHERE name = ?")) {
                ps.setString(1, cat); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM shop_items WHERE category = ?")) {
                ps.setString(1, cat); ps.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "deleteCategory failed", e);
        }
        admin.sendMessage(ChatColor.GREEN + "[Shop] Fully deleted category '" + cat + "'!");
    }

    public void clearCategory(Player admin, String name) {
        String cat = findCategory(name);
        if (cat == null) {
            admin.sendMessage(ChatColor.RED + "[Shop] Category not found!");
            return;
        }
        itemsByCategory.remove(cat);
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM shop_items WHERE category = ?")) {
            ps.setString(1, cat); ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "clearCategory failed", e);
        }
        admin.sendMessage(ChatColor.GREEN + "[Shop] Cleared all items from category '" + cat + "'!");
    }

    public void addItem(Player admin, String catArg, String priceArg, String currencyArg) {
        String cat = findCategory(catArg);
        if (cat == null) {
            cat = catArg;
            categories.put(cat, new ShopCategory(new ItemStack(Material.CHEST), true, null));
            persistCategory(cat, new ItemStack(Material.CHEST), true, null);
            admin.sendMessage(ChatColor.GREEN + "[Shop] Created new category '" + cat + "'!");
        }

        ItemStack tool = admin.getInventory().getItemInMainHand();
        if (tool.getType() == Material.AIR) {
            admin.sendMessage(ChatColor.RED + "[Shop] You must hold an item in your hand!");
            return;
        }

        double price;
        String currency;
        if (priceArg == null || priceArg.equalsIgnoreCase("auto")) {
            double basePrice = 395;
            double rawCalc = basePrice + 250;
            price = Math.round(rawCalc / 50.0) * 50;
            currency = (currencyArg != null) ? currencyArg.toLowerCase(Locale.ROOT) : "money";
            admin.sendMessage(ChatColor.GREEN + "[Shop] Auto-calculated price: base ($" + (long) basePrice +
                    ") + $250 fee = $" + (long) rawCalc + " (rounded to $" + (long) price + ")");
        } else {
            price = parsePrice(priceArg);
            currency = (currencyArg != null) ? currencyArg.toLowerCase(Locale.ROOT) : "money";
        }

        if (!currency.equals("money") && !currency.equals("shards")) {
            admin.sendMessage(ChatColor.RED + "[Shop] Invalid currency! Use 'money' or 'shards'.");
            return;
        }

        Map<Integer, ShopItem> items = itemsByCategory.computeIfAbsent(cat, k -> new TreeMap<>());
        int index = items.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

        ItemStack toStore = tool.clone();
        toStore.setAmount(1);
        items.put(index, new ShopItem(toStore, price, currency, null));
        persistItem(cat, index, toStore, price, currency, null);

        ItemStack remaining = tool.clone();
        remaining.setAmount(Math.max(0, remaining.getAmount() - 1));
        admin.getInventory().setItemInMainHand(remaining.getAmount() == 0 ? null : remaining);

        admin.sendMessage(ChatColor.GREEN + "[Shop] Added item to category '" + cat + "' with price " +
                formatNumber(price) + " (" + currency + ")!");
    }

    private void persistCategory(String name, ItemStack icon, boolean pagination, Integer catSlot) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO shop_categories (name, icon, pagination, cat_slot) VALUES (?, ?, ?, ?)
                 ON CONFLICT(name) DO UPDATE SET icon = excluded.icon, pagination = excluded.pagination, cat_slot = excluded.cat_slot
                 """)) {
            ps.setString(1, name);
            ps.setBytes(2, icon.serializeAsBytes());
            ps.setInt(3, pagination ? 1 : 0);
            if (catSlot != null) ps.setInt(4, catSlot); else ps.setNull(4, java.sql.Types.INTEGER);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "persistCategory failed", e);
        }
    }

    private void persistItem(String cat, int idx, ItemStack item, double price, String currency, Integer slot) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO shop_items (category, idx, item, price, currency, slot) VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(category, idx) DO UPDATE SET item = excluded.item, price = excluded.price,
                     currency = excluded.currency, slot = excluded.slot
                 """)) {
            ps.setString(1, cat);
            ps.setInt(2, idx);
            ps.setBytes(3, item.serializeAsBytes());
            ps.setDouble(4, price);
            ps.setString(5, currency);
            if (slot != null) ps.setInt(6, slot); else ps.setNull(6, java.sql.Types.INTEGER);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "persistItem failed", e);
        }
    }

    private String findCategory(String input) {
        for (String cat : categories.keySet()) {
            if (cat.equalsIgnoreCase(input)) return cat;
        }
        return null;
    }

    private double parsePrice(String s) {
        s = s.toLowerCase(Locale.ROOT);
        char last = s.charAt(s.length() - 1);
        double multiplier = switch (last) {
            case 'k' -> 1_000d; case 'm' -> 1_000_000d; case 'b' -> 1_000_000_000d; case 't' -> 1_000_000_000_000d;
            default -> 1d;
        };
        String numPart = (multiplier != 1) ? s.substring(0, s.length() - 1) : s;
        try { return Double.parseDouble(numPart) * multiplier; } catch (NumberFormatException e) { return 0; }
    }

    private String formatNumber(double n) {
        if (n >= 1_000_000_000_000d) return trim(n / 1_000_000_000_000d) + "t";
        if (n >= 1_000_000_000d) return trim(n / 1_000_000_000d) + "b";
        if (n >= 1_000_000d) return trim(n / 1_000_000d) + "m";
        if (n >= 1_000d) return trim(n / 1_000d) + "k";
        return trim(n);
    }
    private String trim(double d) {
        return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ------------------------------------------------------------------
    // Player commands
    // ------------------------------------------------------------------

    public void openMainShop(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, MAIN_TITLE);
        player.openInventory(gui);
        refreshMainGui(player);
    }

    private void refreshMainGui(Player player) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < 27; i++) inv.setItem(i, null);

        int currentCount = 0;
        for (String cat : categories.keySet()) {
            if (currentCount >= 27) break;
            ShopCategory sc = categories.get(cat);
            ItemStack icon = sc.icon().clone();
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + cat);
            meta.setLore(List.of(ChatColor.GRAY + "Click to browse this category", ChatColor.BLACK + "CATNAME:" + cat));
            icon.setItemMeta(meta);

            int slot = (sc.catSlot() != null && sc.catSlot() >= 0 && sc.catSlot() <= 26) ? sc.catSlot() : currentCount;
            inv.setItem(slot, icon);
            currentCount++;
        }
    }

    public void openCategory(Player player, String catArg) {
        String cat = findCategory(catArg);
        if (cat == null) {
            player.sendMessage(ChatColor.RED + "[Shop] Error: category '" + catArg + "' was not found!");
            return;
        }
        viewingCategory.put(player.getUniqueId(), cat);
        itemPage.put(player.getUniqueId(), 1);

        boolean pagination = categories.get(cat).pagination();
        int maxItems = itemsByCategory.getOrDefault(cat, Map.of()).size();
        int maxPage = pagination ? Math.max(1, (int) Math.ceil(maxItems / 9.0)) : 1;

        String title = CAT_PREFIX + cat + (pagination ? " (1/" + maxPage + ")" : "");
        Inventory gui = Bukkit.createInventory(null, 27, title);
        player.openInventory(gui);
        refreshCategoryGui(player);
    }

    private void refreshCategoryGui(Player player) {
        UUID uuid = player.getUniqueId();
        String cat = viewingCategory.get(uuid);
        if (cat == null) return;
        int page = itemPage.getOrDefault(uuid, 1);
        Inventory inv = player.getOpenInventory().getTopInventory();

        boolean pagination = categories.get(cat).pagination();
        Map<Integer, ShopItem> items = itemsByCategory.getOrDefault(cat, Map.of());

        for (int i = 0; i < 27; i++) inv.setItem(i, null);

        if (pagination) {
            int maxItems = items.size();
            int maxPage = Math.max(1, (int) Math.ceil(maxItems / 9.0));
            if (page > maxPage) { page = maxPage; itemPage.put(uuid, maxPage); }

            if (page > 1) {
                inv.setItem(18, navPane(Material.RED_STAINED_GLASS_PANE,
                        ChatColor.RED + "" + ChatColor.BOLD + "Previous Page (page " + (page - 1) + ")",
                        "Click to go to the previous page"));
            } else {
                inv.setItem(18, navPane(Material.RED_STAINED_GLASS_PANE,
                        ChatColor.RED + "" + ChatColor.BOLD + "Back to Main Menu",
                        "Click to return to main shop"));
            }
            if (page < maxPage) {
                inv.setItem(26, navPane(Material.GREEN_STAINED_GLASS_PANE,
                        ChatColor.GREEN + "" + ChatColor.BOLD + "Next Page (page " + (page + 1) + ")",
                        "Click to go to the next page"));
            }

            for (Map.Entry<Integer, ShopItem> e : items.entrySet()) {
                int index = e.getKey();
                ShopItem si = e.getValue();
                int absSlot = (si.slot() != null) ? si.slot() : (index - 1);
                int itemPageNum = (absSlot / 9) + 1;
                if (itemPageNum != page) continue;
                int slotInRow = absSlot - ((itemPageNum - 1) * 9);
                int chestSlot = slotInRow + 9;
                if (chestSlot < 9 || chestSlot > 26) continue;

                inv.setItem(chestSlot, buildShopDisplayItem(si, index));
            }
        } else {
            inv.setItem(18, navPane(Material.RED_STAINED_GLASS_PANE,
                    ChatColor.RED + "" + ChatColor.BOLD + "Back to Main Menu", "Click to return to main shop"));

            int nextSlot = 0;
            for (Map.Entry<Integer, ShopItem> e : items.entrySet()) {
                int index = e.getKey();
                ShopItem si = e.getValue();
                ItemStack display = buildShopDisplayItem(si, index);
                if (si.slot() != null && si.slot() >= 0 && si.slot() <= 17) {
                    inv.setItem(si.slot(), display);
                } else {
                    while (nextSlot < 18 && (inv.getItem(nextSlot) != null && nextSlot != 18)) nextSlot++;
                    if (nextSlot < 18) { inv.setItem(nextSlot, display); nextSlot++; }
                }
            }
        }
    }

    private ItemStack navPane(Material mat, String name, String lore) {
        ItemStack pane = new ItemStack(mat);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(ChatColor.GRAY + lore));
        pane.setItemMeta(meta);
        return pane;
    }

    private ItemStack buildShopDisplayItem(ShopItem si, int index) {
        ItemStack display = si.item().clone();
        ItemMeta meta = display.getItemMeta();
        String priceStr = formatNumber(si.price());
        List<String> lore = new ArrayList<>();
        if ("shards".equals(si.currency())) {
            lore.add(ChatColor.AQUA + "Buy price: " + priceStr + " shards");
        } else {
            lore.add(ChatColor.YELLOW + "Buy price: " + ChatColor.GREEN + "$" + priceStr);
        }
        lore.add(ChatColor.GRAY + "Click to select quantity!");
        lore.add(ChatColor.BLACK + "SHOPINDEX:" + index);
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    // ------------------------------------------------------------------
    // Buy-confirm GUI (quantity +/- picker)
    // ------------------------------------------------------------------

    private void openConfirm(Player player, String cat, int index) {
        Map<Integer, ShopItem> items = itemsByCategory.getOrDefault(cat, Map.of());
        ShopItem si = items.get(index);
        if (si == null) {
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "[Shop] Error loading item!");
            return;
        }

        ConfirmState state = new ConfirmState(cat, index, si.item().clone());
        confirming.put(player.getUniqueId(), state);

        int maxStack = si.item().getType() == Material.TOTEM_OF_UNDYING ? 1 : si.item().getMaxStackSize();

        String itemName = si.item().hasItemMeta() && si.item().getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(si.item().getItemMeta().getDisplayName())
                : formatMaterialName(si.item().getType());

        Inventory gui = Bukkit.createInventory(null, 27, CONFIRM_PREFIX + itemName);
        player.openInventory(gui);
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, navPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ChatColor.GRAY.toString(), ""));
        }
        layoutConfirmButtons(gui, maxStack);
        updateConfirmDisplay(player, gui, si, state);
    }

    private void layoutConfirmButtons(Inventory gui, int maxStack) {
        if (maxStack <= 1) return;
        if (maxStack >= 64) {
            gui.setItem(9, amountPane(Material.RED_STAINED_GLASS_PANE, 64, ChatColor.RED + "" + ChatColor.BOLD + "-64", "Click to remove 64"));
            gui.setItem(10, amountPane(Material.RED_STAINED_GLASS_PANE, 32, ChatColor.RED + "" + ChatColor.BOLD + "-32", "Click to remove 32"));
        } else if (maxStack >= 16) {
            gui.setItem(9, amountPane(Material.RED_STAINED_GLASS_PANE, 16, ChatColor.RED + "" + ChatColor.BOLD + "-16", "Click to remove 16"));
            gui.setItem(10, amountPane(Material.RED_STAINED_GLASS_PANE, 8, ChatColor.RED + "" + ChatColor.BOLD + "-8", "Click to remove 8"));
        }
        gui.setItem(11, amountPane(Material.RED_STAINED_GLASS_PANE, 1, ChatColor.RED + "" + ChatColor.BOLD + "-1", "Click to remove 1"));
        gui.setItem(15, amountPane(Material.GREEN_STAINED_GLASS_PANE, 1, ChatColor.GREEN + "" + ChatColor.BOLD + "+1", "Click to add 1"));
        if (maxStack >= 64) {
            gui.setItem(16, amountPane(Material.GREEN_STAINED_GLASS_PANE, 32, ChatColor.GREEN + "" + ChatColor.BOLD + "+32", "Click to add 32"));
            gui.setItem(17, amountPane(Material.GREEN_STAINED_GLASS_PANE, 64, ChatColor.GREEN + "" + ChatColor.BOLD + "+64", "Click to add 64"));
        } else if (maxStack >= 16) {
            gui.setItem(16, amountPane(Material.GREEN_STAINED_GLASS_PANE, 8, ChatColor.GREEN + "" + ChatColor.BOLD + "+8", "Click to add 8"));
            gui.setItem(17, amountPane(Material.GREEN_STAINED_GLASS_PANE, 16, ChatColor.GREEN + "" + ChatColor.BOLD + "+16", "Click to add 16"));
        }
    }

    private ItemStack amountPane(Material mat, int amount, String name, String lore) {
        ItemStack pane = new ItemStack(mat, amount);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(ChatColor.GRAY + lore));
        pane.setItemMeta(meta);
        return pane;
    }

    private void updateConfirmDisplay(Player player, Inventory gui, ShopItem si, ConfirmState state) {
        double totalPrice = si.price() * state.qty;
        String priceStr = formatNumber(totalPrice);
        String itemName = si.item().hasItemMeta() && si.item().getItemMeta().hasDisplayName()
                ? ChatColor.stripColor(si.item().getItemMeta().getDisplayName())
                : formatMaterialName(si.item().getType());

        ItemStack display = si.item().clone();
        display.setAmount(state.qty);
        ItemMeta dMeta = display.getItemMeta();
        if ("shards".equals(si.currency())) {
            dMeta.setLore(List.of(ChatColor.GRAY + "Quantity: " + ChatColor.YELLOW + state.qty + "x",
                    ChatColor.AQUA + "Total price: " + priceStr + " shards"));
        } else {
            dMeta.setLore(List.of(ChatColor.GRAY + "Quantity: " + ChatColor.YELLOW + state.qty + "x",
                    ChatColor.YELLOW + "Total price: " + ChatColor.GREEN + "$" + priceStr));
        }
        display.setItemMeta(dMeta);
        gui.setItem(13, display);

        ItemStack cancel = new ItemStack(Material.RED_CONCRETE);
        ItemMeta cMeta = cancel.getItemMeta();
        cMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Cancel");
        cMeta.setLore(List.of(ChatColor.GRAY + "Click to cancel and go back"));
        cancel.setItemMeta(cMeta);
        gui.setItem(21, cancel);

        ItemStack confirm = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta fMeta = confirm.getItemMeta();
        fMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Confirm Purchase");
        String currencyLabel = "shards".equals(si.currency()) ? (priceStr + " shards") : ("$" + priceStr);
        fMeta.setLore(List.of(ChatColor.GRAY + "Click to buy " + ChatColor.YELLOW + state.qty + "x " + itemName,
                ChatColor.GRAY + "for " + ChatColor.YELLOW + currencyLabel));
        confirm.setItemMeta(fMeta);
        gui.setItem(23, confirm);
    }

    private String formatMaterialName(Material mat) {
        return mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ------------------------------------------------------------------
    // Inventory click routing
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        UUID uuid = player.getUniqueId();

        if (title.equals(MAIN_TITLE)) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            String cat = extractTag(clicked, "CATNAME:");
            if (cat != null) openCategory(player, cat);
            return;
        }

        if (title.startsWith(CAT_PREFIX)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            String cat = viewingCategory.get(uuid);
            if (cat == null) return;
            int page = itemPage.getOrDefault(uuid, 1);
            boolean pagination = categories.get(cat).pagination();

            if (pagination) {
                int maxItems = itemsByCategory.getOrDefault(cat, Map.of()).size();
                int maxPage = Math.max(1, (int) Math.ceil(maxItems / 9.0));
                if (slot == 18) {
                    if (page == 1) { openMainShop(player); return; }
                    itemPage.put(uuid, page - 1);
                    reopenCategoryTitle(player, cat, page - 1, maxPage);
                    return;
                } else if (slot == 26) {
                    if (page < maxPage) {
                        itemPage.put(uuid, page + 1);
                        reopenCategoryTitle(player, cat, page + 1, maxPage);
                    }
                    return;
                }
            } else if (slot == 18) {
                openMainShop(player);
                return;
            }

            String idxTag = extractTag(event.getCurrentItem(), "SHOPINDEX:");
            if (idxTag != null) {
                try {
                    int index = Integer.parseInt(idxTag);
                    openConfirm(player, cat, index);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        if (title.startsWith(CONFIRM_PREFIX)) {
            event.setCancelled(true);
            ConfirmState state = confirming.get(uuid);
            if (state == null) return;
            Map<Integer, ShopItem> items = itemsByCategory.getOrDefault(state.category, Map.of());
            ShopItem si = items.get(state.index);
            if (si == null) return;

            int maxStack = si.item().getType() == Material.TOTEM_OF_UNDYING ? 1 : si.item().getMaxStackSize();
            int slot = event.getRawSlot();
            boolean changed = false;

            switch (slot) {
                case 9 -> { state.qty -= (maxStack >= 64 ? 64 : maxStack >= 16 ? 16 : 0); changed = true; }
                case 10 -> { state.qty -= (maxStack >= 64 ? 32 : maxStack >= 16 ? 8 : 0); changed = true; }
                case 11 -> { state.qty -= 1; changed = true; }
                case 15 -> { state.qty += 1; changed = true; }
                case 16 -> { state.qty += (maxStack >= 64 ? 32 : maxStack >= 16 ? 8 : 0); changed = true; }
                case 17 -> { state.qty += (maxStack >= 64 ? 64 : maxStack >= 16 ? 16 : 0); changed = true; }
                case 21 -> { player.closeInventory(); openCategory(player, state.category); return; }
                case 23 -> { executePurchase(player, state, si); return; }
                default -> { return; }
            }

            if (changed) {
                state.qty = Math.max(1, Math.min(state.qty, maxStack));
                updateConfirmDisplay(player, event.getView().getTopInventory(), si, state);
            }
        }
    }

    private void reopenCategoryTitle(Player player, String cat, int page, int maxPage) {
        String title = CAT_PREFIX + cat + " (" + page + "/" + maxPage + ")";
        Inventory gui = Bukkit.createInventory(null, 27, title);
        player.openInventory(gui);
        refreshCategoryGui(player);
    }

    private void executePurchase(Player player, ConfirmState state, ShopItem si) {
        double totalPrice = si.price() * state.qty;
        if ("shards".equals(si.currency())) {
            long shards = economy.getShards(player.getUniqueId());
            if (shards < totalPrice) {
                player.sendMessage(ChatColor.RED + "[Shop] You don't have enough shards! You need " + formatNumber(totalPrice) + " shards.");
                return;
            }
            economy.setShards(player.getUniqueId(), shards - (long) totalPrice);
        } else {
            double bal = economy.getBalance(player);
            if (bal < totalPrice) {
                player.sendMessage(ChatColor.RED + "[Shop] You don't have enough money! You need $" + formatNumber(totalPrice) + ".");
                return;
            }
            economy.withdrawPlayer(player, totalPrice);
        }

        ItemStack toGive = si.item().clone();
        toGive.setAmount(state.qty);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);
        if (leftover.isEmpty()) {
            player.sendMessage(ChatColor.GREEN + "[Shop] Successfully purchased " + state.qty + "x to your inventory!");
        } else {
            leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            player.sendMessage(ChatColor.RED + "[Shop] Your inventory was full, so your items were dropped at your feet!");
        }
        confirming.remove(player.getUniqueId());
        player.closeInventory();
    }

    private String extractTag(ItemStack item, String tag) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return null;
        for (String line : item.getItemMeta().getLore()) {
            String stripped = ChatColor.stripColor(line);
            if (stripped.contains(tag)) return stripped.replace(tag, "");
        }
        return null;
    }
}
