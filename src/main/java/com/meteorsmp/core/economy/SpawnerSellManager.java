package com.meteorsmp.core.economy;

import com.meteorsmp.core.PluginMain;
import com.meteorsmp.core.database.DatabaseManager;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

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
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();

    public SpawnerSellManager(PluginMain plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void loadWorthAndMultipliers() {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT item_id, price FROM worth_prices");
             ResultSet rs = ps.executeQuery()) {
            worthPrices.clear();
            while (rs.next()) {
                worthPrices.put(rs.getString("item_id"), rs.getDouble("price"));
            }
            plugin.getLogger().info("Loaded " + worthPrices.size() + " worth prices.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load worth_prices", e);
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
        double multi = 1.0;
        for (String cat : CATEGORIES) {
            multi += (getCategoryMultiplier(uuid, cat) - 1.0);
        }
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
        player.openInventory(gui);
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
                        Double unitPrice = worthPrices.get(subId);
                        if (unitPrice == null) {
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
                    // FIX: empty shulker now falls back to its own type price,
                    // then the generic shulker_box price, matching
                    // {worth::%_itemstr%} ? {worth::shulker_box} in shopbal.sk.
                    Double shulkerPrice = worthPrices.get(id);
                    if (shulkerPrice == null) shulkerPrice = worthPrices.get("shulker_box");

                    if (shulkerPrice != null) {
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

            Double unitPrice = worthPrices.get(id);
            if (unitPrice == null) {
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

    private final Set<UUID> autoSellEnabled = ConcurrentHashMap.newKeySet();

    public void tryHookSmartSpawner() {
        if (Bukkit.getPluginManager().getPlugin("SmartSpawner") == null) {
            plugin.getLogger().info("SmartSpawner not found — spawner auto-sell disabled.");
            return;
        }
        final String candidateEventClass = "github.nighter.smartspawner.api.events.SpawnerSellEvent";
        try {
            Class<?> eventClass = Class.forName(candidateEventClass);
            plugin.getLogger().info("Found candidate SmartSpawner event class: " + candidateEventClass +
                    " — reflective handler wiring is still a stub, not implemented yet.");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("SmartSpawner is installed but " + candidateEventClass +
                    " was not found. Verify the real class name against your jar before relying on this.");
        }
    }

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
    @Override public EconomyResponse createPlayerAccount(String playerName) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.SUCCESS, null); }
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
            balanceCache.clear(); // simplest correct option: cache is now stale for everyone touched
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
