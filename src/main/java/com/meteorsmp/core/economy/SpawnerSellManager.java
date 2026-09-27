package com.meteorsmp.core.economy;

import com.meteorsmp.core.PluginMain;
import com.meteorsmp.core.database.DatabaseManager;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerSellManager extends AbstractEconomy implements Listener, CommandExecutor, TabCompleter {

    private final PluginMain plugin;
    private final DatabaseManager databaseManager;

    private final Map<String, Double> itemPrices = new ConcurrentHashMap<>();
    private final Map<UUID, Double> sellMultipliers = new ConcurrentHashMap<>();
    
    // Worth GUI player states
    private final Map<UUID, String> worthSort = new ConcurrentHashMap<>();
    private final Map<UUID, String> worthFilter = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> worthPage = new ConcurrentHashMap<>();
    private final Set<UUID> worthChatSearch = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Track active sell GUI sessions to prevent dupe on close
    private final Set<UUID> activeSellGuis = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> processingSale = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final DecimalFormat FORMATTER = new DecimalFormat("#,##0.##");
    private static final String SELL_TITLE = ChatColor.GREEN + "" + ChatColor.BOLD + "Sell GUI";
    private static final String WORTH_TITLE_PREFIX = ChatColor.GREEN + "" + ChatColor.BOLD + "ɪᴛᴇᴍ ᴘʀɪᴄᴇѕ";

    public SpawnerSellManager(PluginMain plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public void loadWorthAndMultipliers() {
        itemPrices.clear();
        FileConfiguration config = plugin.getConfig();
        if (config.isConfigurationSection("prices")) {
            for (String key : config.getConfigurationSection("prices").getKeys(false)) {
                itemPrices.put(key.toLowerCase().replace(" ", "_"), config.getDouble("prices." + key));
            }
        }
        if (itemPrices.isEmpty()) {
            setDefaultWorthValues();
        }
    }

    public void setDefaultWorthValues() {
        Map<String, Double> d = new HashMap<>();
        d.put("wheat", 5.0); d.put("carrot", 4.0); d.put("potato", 4.0); d.put("beetroot", 4.0);
        d.put("melon_slice", 2.0); d.put("pumpkin", 15.0); d.put("sugar_cane", 8.0); d.put("kelp", 3.0);
        d.put("dried_kelp", 4.0); d.put("cocoa_beans", 6.0); d.put("cactus", 10.0); d.put("bamboo", 2.0);
        d.put("sweet_berries", 3.0); d.put("glow_berries", 5.0); d.put("nether_wart", 12.0);
        d.put("coal", 10.0); d.put("raw_copper", 8.0); d.put("copper_ingot", 12.0); d.put("raw_iron", 15.0);
        d.put("iron_ingot", 20.0); d.put("raw_gold", 25.0); d.put("gold_ingot", 35.0); d.put("redstone", 8.0);
        d.put("lapis_lazuli", 10.0); d.put("diamond", 691.0); d.put("emerald", 200.0);
        d.put("netherite_scrap", 500.0); d.put("netherite_ingot", 2500.0); d.put("amethyst_shard", 20.0); d.put("quartz", 15.0);
        d.put("rotten_flesh", 3.0); d.put("bone", 5.0); d.put("string", 5.0); d.put("spider_eye", 8.0);
        d.put("gunpowder", 15.0); d.put("ender_pearl", 40.0); d.put("blaze_rod", 60.0); d.put("ghast_tear", 120.0);
        d.put("slime_ball", 25.0); d.put("magma_cream", 30.0); d.put("phantom_membrane", 50.0);
        d.put("shulker_shell", 300.0); d.put("totem_of_undying", 1000.0); d.put("cobblestone", 1.0);
        d.put("stone", 2.0); d.put("deepslate", 2.0); d.put("dirt", 1.0); d.put("sand", 2.0);
        d.put("gravel", 2.0); d.put("granite", 3.0); d.put("diorite", 3.0); d.put("andesite", 3.0);
        d.put("oak_log", 8.0); d.put("spruce_log", 8.0); d.put("birch_log", 8.0); d.put("jungle_log", 8.0);
        d.put("acacia_log", 8.0); d.put("dark_oak_log", 8.0); d.put("mangrove_log", 8.0); d.put("cherry_log", 8.0);
        d.put("oak_planks", 2.0); d.put("glass", 4.0); d.put("obsidian", 50.0); d.put("cod", 8.0);
        d.put("salmon", 12.0); d.put("tropical_fish", 25.0); d.put("pufferfish", 30.0); d.put("apple", 10.0);
        d.put("golden_apple", 250.0); d.put("enchanted_golden_apple", 5000.0);

        itemPrices.putAll(d);
        for (Map.Entry<String, Double> entry : d.entrySet()) {
            plugin.getConfig().set("prices." + entry.getKey(), entry.getValue());
        }
        plugin.saveConfig();
    }

    public void tryHookSmartSpawner() {
        if (Bukkit.getPluginManager().isPluginEnabled("SmartSpawner")) {
            plugin.getLogger().info("Successfully hooked into SmartSpawner!");
        }
    }

    public void flushAll() {
        // Safe state cleanup on server stop/reload
    }

    // ==========================================
    // SELL GUI & EXPLOIT FIXES
    // ==========================================

    public void openSellGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, SELL_TITLE);
        
        // Fill border glass
        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        int[] borders = {0,1,2,3,4,5,6,7,8, 9,17, 18,26, 27,35, 36,44, 45,46,47,48,50,51,52,53};
        for (int b : borders) {
            gui.setItem(b, filler);
        }

        // Sell Confirm Button
        ItemStack confirm = createGuiItem(Material.GREEN_STAINED_GLASS_PANE, 
                ChatColor.GREEN + "" + ChatColor.BOLD + "CONFIRM SELL",
                ChatColor.GRAY + "Click to sell all items placed in the GUI!");
        gui.setItem(49, confirm);

        activeSellGuis.add(player.getUniqueId());
        player.openInventory(gui);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();

        // 1. SELL GUI HANDLING
        if (title.equalsIgnoreCase(SELL_TITLE)) {
            int slot = event.getRawSlot();
            
            // Top inventory interaction
            if (slot >= 0 && slot < 54) {
                // Prevent clicking borders or confirm button as raw items
                int[] borders = {0,1,2,3,4,5,6,7,8, 9,17, 18,26, 27,35, 36,44, 45,46,47,48,50,51,52,53};
                for (int b : borders) {
                    if (slot == b) {
                        event.setCancelled(true);
                        return;
                    }
                }

                if (slot == 49) {
                    event.setCancelled(true);
                    processSellTransaction(player, event.getInventory());
                    return;
                }
            }
            return;
        }

        // 2. WORTH GUI HANDLING
        if (title.contains(ChatColor.stripColor(WORTH_TITLE_PREFIX))) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;

            UUID uuid = player.getUniqueId();
            int page = worthPage.getOrDefault(uuid, 1);

            if (slot == 45 && page > 1) { // Prev Page
                openWorthGUI(player, page - 1);
            } else if (slot == 53) { // Next Page
                openWorthGUI(player, page + 1);
            } else if (slot == 48) { // Toggle Sort
                String curSort = worthSort.getOrDefault(uuid, "desc");
                if (curSort.equalsIgnoreCase("desc")) worthSort.put(uuid, "asc");
                else if (curSort.equalsIgnoreCase("asc")) worthSort.put(uuid, "name");
                else worthSort.put(uuid, "desc");
                openWorthGUI(player, page);
            } else if (slot == 49) { // Search
                player.closeInventory();
                worthChatSearch.add(uuid);
                player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴡᴏʀᴛʜ " + ChatColor.DARK_GRAY + "» " + ChatColor.WHITE + "ᴛʏᴘᴇ ᴛʜᴇ ɪᴛᴇᴍ ɴᴀᴍᴇ ᴛᴏ ѕᴇᴀʀᴄʜ ɪɴ ᴄʜᴀᴛ:");
            } else if (slot == 50) { // Filter
                String curF = worthFilter.getOrDefault(uuid, "All");
                List<String> order = List.of("All", "Blocks", "Tools", "Food", "Combat", "Potions", "Books");
                int idx = (order.indexOf(curF) + 1) % order.size();
                worthFilter.put(uuid, order.get(idx));
                openWorthGUI(player, 1);
            }
        }
    }

    private void processSellTransaction(Player player, Inventory inv) {
        UUID uuid = player.getUniqueId();
        if (processingSale.contains(uuid)) return;
        processingSale.add(uuid);

        double totalEarned = 0.0;
        int itemsSold = 0;

        for (int i = 0; i < 54; i++) {
            if (isSellSlot(i)) {
                ItemStack item = inv.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    double price = getItemPrice(item);
                    if (price > 0) {
                        totalEarned += price * item.getAmount();
                        itemsSold += item.getAmount();
                        inv.setItem(i, null); // Clear item
                    }
                }
            }
        }

        if (itemsSold > 0) {
            double multi = getMultiplier(uuid);
            double finalEarned = totalEarned * multi;
            depositPlayer(player, finalEarned);

            player.sendMessage(ChatColor.GREEN + "Sold " + itemsSold + " items for $" + FORMATTER.format(finalEarned) + "!");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        } else {
            player.sendMessage(ChatColor.RED + "No sellable items were found in the sell slots!");
        }

        processingSale.remove(uuid);
        activeSellGuis.remove(uuid);
        player.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String title = event.getView().getTitle();

        if (title.equalsIgnoreCase(SELL_TITLE)) {
            UUID uuid = player.getUniqueId();
            
            // If closed during active sell button execution, skip returning items
            if (processingSale.contains(uuid)) return;

            // Refund unsold items cleanly to inventory/ground
            Inventory inv = event.getInventory();
            for (int i = 0; i < 54; i++) {
                if (isSellSlot(i)) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && item.getType() != Material.AIR) {
                        inv.setItem(i, null);
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                        for (ItemStack drop : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    }
                }
            }
            activeSellGuis.remove(uuid);
            refreshWorthLoreInInventory(player);
        }
    }

    private boolean isSellSlot(int slot) {
        int[] borders = {0,1,2,3,4,5,6,7,8, 9,17, 18,26, 27,35, 36,44, 45,46,47,48,49,50,51,52,53};
        for (int b : borders) {
            if (slot == b) return false;
        }
        return true;
    }

    public double getItemPrice(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0.0;
        String key = item.getType().name().toLowerCase();
        return itemPrices.getOrDefault(key, 0.0);
    }

    // ==========================================
    // WORTH TOOLTIPS & LISTENERS
    // ==========================================

    public ItemStack applyWorthLore(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return item;
        double price = getItemPrice(item);
        if (price <= 0) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.hasDisplayName()) return item;

        String targetLore = ChatColor.GRAY + "Worth: " + ChatColor.GREEN + "$" + FORMATTER.format(price);
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        if (lore != null && !lore.isEmpty() && ChatColor.stripColor(lore.get(0)).startsWith("Worth:")) {
            return item;
        }

        List<String> newLore = new ArrayList<>();
        newLore.add(targetLore);
        if (lore != null) {
            for (String line : lore) {
                if (!ChatColor.stripColor(line).startsWith("Worth:")) {
                    newLore.add(line);
                }
            }
        }
        meta.setLore(newLore);
        item.setItemMeta(meta);
        return item;
    }

    public void refreshWorthLoreInInventory(Player player) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                player.getInventory().setItem(i, applyWorthLore(item));
            }
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        itemEntity.setItemStack(applyWorthLore(itemEntity.getItemStack()));
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item itemEntity = event.getItemDrop();
        itemEntity.setItemStack(applyWorthLore(itemEntity.getItemStack()));
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getCurrentItem() != null) {
            event.setCurrentItem(applyWorthLore(event.getCurrentItem()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshWorthLoreInInventory(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (worthChatSearch.remove(player.getUniqueId())) {
            event.setCancelled(true);
            String query = event.getMessage();
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴡᴏʀᴛʜ " + ChatColor.DARK_GRAY + "» " + ChatColor.WHITE + "ѕᴇᴀʀᴄʜɪɴɢ ꜰᴏʀ " + ChatColor.YELLOW + query + ChatColor.WHITE + "...");
                executeWorthQuery(player, query);
            });
        }
    }

    // ==========================================
    // WORTH GUI & COMMAND IMPLEMENTATION
    // ==========================================

    public void openWorthGUI(Player player, int targetPage) {
        UUID uuid = player.getUniqueId();
        String sort = worthSort.getOrDefault(uuid, "desc");
        String filter = worthFilter.getOrDefault(uuid, "All");

        List<String> keys = new ArrayList<>(itemPrices.keySet());

        // Sort items
        if (sort.equalsIgnoreCase("desc")) {
            keys.sort((a, b) -> Double.compare(itemPrices.get(b), itemPrices.get(a)));
        } else if (sort.equalsIgnoreCase("asc")) {
            keys.sort((a, b) -> Double.compare(itemPrices.get(a), itemPrices.get(b)));
        } else {
            Collections.sort(keys);
        }

        // Filter items
        List<String> filteredKeys = new ArrayList<>();
        for (String k : keys) {
            Material mat = Material.matchMaterial(k);
            if (mat == null) continue;
            boolean valid = true;

            switch (filter) {
                case "Blocks" -> valid = mat.isBlock();
                case "Tools" -> valid = k.contains("pickaxe") || k.contains("axe") || k.contains("shovel") || k.contains("hoe") || k.contains("shears");
                case "Food" -> valid = mat.isEdible();
                case "Combat" -> valid = k.contains("sword") || k.contains("bow") || k.contains("helmet") || k.contains("chestplate") || k.contains("leggings") || k.contains("boots") || k.contains("shield") || k.contains("arrow") || k.contains("trident");
                case "Potions" -> valid = k.contains("potion");
                case "Books" -> valid = k.contains("book");
            }
            if (valid) filteredKeys.add(k);
        }

        if (filteredKeys.isEmpty()) filteredKeys = keys;

        int totalKeys = filteredKeys.size();
        int maxPage = (int) Math.ceil((double) totalKeys / 45);
        if (maxPage < 1) maxPage = 1;

        int page = Math.max(1, Math.min(targetPage, maxPage));
        worthPage.put(uuid, page);

        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.GREEN + "" + ChatColor.BOLD + "ɪᴛᴇᴍ ᴘʀɪᴄᴇѕ " + ChatColor.DARK_GRAY + "(" + ChatColor.WHITE + "ᴘᴀɢᴇ " + page + "/" + maxPage + ChatColor.DARK_GRAY + ")");

        int startIndex = (page - 1) * 45;
        int endIndex = Math.min(startIndex + 45, totalKeys);

        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            String key = filteredKeys.get(i);
            Material mat = Material.matchMaterial(key);
            if (mat != null) {
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setLore(List.of(ChatColor.GRAY + "Worth: " + ChatColor.GREEN + "$" + FORMATTER.format(itemPrices.get(key))));
                    item.setItemMeta(meta);
                }
                gui.setItem(slot, item);
            }
            slot++;
        }

        // Fill remaining item slots with glass pane
        while (slot < 45) {
            gui.setItem(slot++, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }

        // Control Row
        if (page > 1) {
            gui.setItem(45, createGuiItem(Material.ARROW, ChatColor.GREEN + "" + ChatColor.BOLD + "ᴘʀᴇᴠɪᴏᴜѕ ᴘᴀɢᴇ", ChatColor.GRAY + "Click to go to page " + (page - 1)));
        } else {
            gui.setItem(45, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "First Page"));
        }

        gui.setItem(48, createGuiItem(Material.CAULDRON, ChatColor.YELLOW + "" + ChatColor.BOLD + "ѕᴏʀᴛ", 
                (sort.equalsIgnoreCase("desc") ? ChatColor.GREEN : ChatColor.GRAY) + "• Highest Price",
                (sort.equalsIgnoreCase("asc") ? ChatColor.GREEN : ChatColor.GRAY) + "• Lowest Price",
                (sort.equalsIgnoreCase("name") ? ChatColor.GREEN : ChatColor.GRAY) + "• By name"));

        gui.setItem(49, createGuiItem(Material.NAME_TAG, ChatColor.YELLOW + "" + ChatColor.BOLD + "ѕᴇᴀʀᴄʜ ɪᴛᴇᴍ", ChatColor.GRAY + "Click to type search query in chat!"));

        gui.setItem(50, createGuiItem(Material.HOPPER, ChatColor.YELLOW + "" + ChatColor.BOLD + "ꜰɪʟᴛᴇʀ",
                (filter.equalsIgnoreCase("All") ? ChatColor.GREEN : ChatColor.GRAY) + "• All",
                (filter.equalsIgnoreCase("Blocks") ? ChatColor.GREEN : ChatColor.GRAY) + "• Blocks",
                (filter.equalsIgnoreCase("Tools") ? ChatColor.GREEN : ChatColor.GRAY) + "• Tools",
                (filter.equalsIgnoreCase("Food") ? ChatColor.GREEN : ChatColor.GRAY) + "• Food",
                (filter.equalsIgnoreCase("Combat") ? ChatColor.GREEN : ChatColor.GRAY) + "• Combat",
                (filter.equalsIgnoreCase("Potions") ? ChatColor.GREEN : ChatColor.GRAY) + "• Potions",
                (filter.equalsIgnoreCase("Books") ? ChatColor.GREEN : ChatColor.GRAY) + "• Books"));

        if (page < maxPage) {
            gui.setItem(53, createGuiItem(Material.ARROW, ChatColor.GREEN + "" + ChatColor.BOLD + "ɴᴇxᴛ ᴘᴀɢᴇ", ChatColor.GRAY + "Click to go to page " + (page + 1)));
        } else {
            gui.setItem(53, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.GRAY + "Last Page"));
        }

        player.openInventory(gui);
    }

    private void executeWorthQuery(Player player, String query) {
        String internal = query.toLowerCase().replace(" ", "_");
        if (internal.equals("undy")) internal = "totem_of_undying";

        if (itemPrices.containsKey(internal)) {
            String display = internal.replace("_", " ");
            player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴡᴏʀᴛʜ " + ChatColor.DARK_GRAY + "» " + ChatColor.YELLOW + display + ChatColor.DARK_GRAY + " » " + ChatColor.GREEN + "$" + FORMATTER.format(itemPrices.get(internal)));
        } else {
            player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴡᴏʀᴛʜ " + ChatColor.DARK_GRAY + "» " + ChatColor.RED + "ɪᴛᴇᴍ '" + query + "' ᴡᴀѕ ɴᴏᴛ ꜰᴏᴜɴᴅ!");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("worth")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (args.length > 0) {
                try {
                    int page = Integer.parseInt(args[0]);
                    openWorthGUI(player, page);
                } catch (NumberFormatException e) {
                    executeWorthQuery(player, String.join(" ", args));
                }
            } else {
                openWorthGUI(player, 1);
            }
            return true;
        }

        if (cmd.equals("fixlore")) {
            if (sender instanceof Player player) {
                refreshWorthLoreInInventory(player);
                player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴇᴄᴏɴᴏᴍʏ " + ChatColor.DARK_GRAY + "» " + ChatColor.WHITE + "ʀᴇꜰʀᴇѕʜᴇᴅ ɪᴛᴇᴍ ᴡᴏʀᴛʜ ᴛᴏᴏʟᴛɪᴘѕ!");
            }
            return true;
        }

        if (cmd.equals("setdefaultworth")) {
            setDefaultWorthValues();
            sender.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴇᴄᴏɴᴏ繆ʏ " + ChatColor.DARK_GRAY + "» " + ChatColor.WHITE + "ᴅᴇꜰᴀᴜʟᴛ ɪᴛᴇᴍ ᴠᴀʟᴜᴇѕ ѕᴇᴛ ᴀɴᴅ ʀᴇʙᴜɪʟᴛ!");
            return true;
        }

        if (cmd.equals("worthchange")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /worthchange <item> <amount>");
                return true;
            }
            String itemStr = args[0].toLowerCase().replace(" ", "_");
            String rawAmount = args[1].toLowerCase();
            double mult = 1.0;
            if (rawAmount.endsWith("k")) { mult = 1_000.0; rawAmount = rawAmount.replace("k", ""); }
            else if (rawAmount.endsWith("m")) { mult = 1_000_000.0; rawAmount = rawAmount.replace("m", ""); }
            else if (rawAmount.endsWith("b")) { mult = 1_000_000_000.0; rawAmount = rawAmount.replace("b", ""); }
            else if (rawAmount.endsWith("t")) { mult = 1_000_000_000_000.0; rawAmount = rawAmount.replace("t", ""); }

            try {
                double val = Double.parseDouble(rawAmount) * mult;
                itemPrices.put(itemStr, val);
                plugin.getConfig().set("prices." + itemStr, val);
                plugin.saveConfig();
                sender.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴇᴄᴏɴᴏᴍʏ " + ChatColor.DARK_GRAY + "» " + ChatColor.GREEN + "ѕᴇᴛ ᴡᴏʀᴛʜ ᴏꜰ " + ChatColor.YELLOW + itemStr.replace("_", " ") + ChatColor.GREEN + " ᴛᴏ $" + FORMATTER.format(val) + "!");
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid amount specified!");
            }
            return true;
        }

        if (cmd.equals("fixworth")) {
            double min = args.length > 0 ? Double.parseDouble(args[0]) : 100;
            double max = args.length > 1 ? Double.parseDouble(args[1]) : 800;
            String cat = args.length > 2 ? args[2].toLowerCase() : "all";

            sender.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴇᴄᴏɴᴏᴍʏ " + ChatColor.DARK_GRAY + "» " + ChatColor.YELLOW + "ѕᴄᴀɴɴɪɴɢ ɪᴛᴇᴍѕ ᴀɴᴅ ᴀѕѕɪɢɴɪɴɢ ᴛɪᴇʀᴇᴅ ᴘʀɪᴄᴇѕ...");

            Random rng = new Random();
            for (Material mat : Material.values()) {
                if (mat.isAir() || !mat.isItem()) continue;
                String k = mat.name().toLowerCase();
                if (k.contains("command_block") || k.contains("barrier") || k.contains("structure") || k.contains("bedrock")) continue;

                double val;
                if (k.contains("netherite") || k.contains("elytra") || k.contains("totem")) {
                    val = 5000 + rng.nextInt(10000);
                } else if (k.contains("diamond") || k.contains("emerald")) {
                    val = 1000 + rng.nextInt(4000);
                } else if (k.contains("dirt") || k.contains("cobblestone") || k.contains("sand")) {
                    val = 1 + rng.nextInt(10);
                } else {
                    val = min + (max - min) * rng.nextDouble();
                }

                itemPrices.put(k, val);
                plugin.getConfig().set("prices." + k, val);
            }
            plugin.saveConfig();
            sender.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "ᴇᴄᴏɴᴏᴍʏ " + ChatColor.DARK_GRAY + "» " + ChatColor.GREEN + "ᴛɪᴇʀᴇᴅ ᴘʀɪᴄᴇѕ ᴜᴘᴅᴀᴛᴇᴅ!");
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            for (String key : itemPrices.keySet()) {
                if (key.startsWith(args[0].toLowerCase())) {
                    list.add(key.replace("_", " "));
                }
            }
            return list;
        }
        return Collections.emptyList();
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    public double getMultiplier(UUID uuid) {
        return sellMultipliers.getOrDefault(uuid, 1.0);
    }

    // ==========================================
    // VAULT ECONOMY IMPLEMENTATION
    // ==========================================

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "UltimateMeteorSMP-Economy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return "$" + FORMATTER.format(amount); }
    @Override public String currencyNamePlural() { return "$"; }
    @Override public String currencyNameSingular() { return "$"; }

    @Override
    public boolean hasAccount(String playerName) { return true; }
    @Override
    public double getBalance(String playerName) {
        Player p = Bukkit.getPlayer(playerName);
        return p != null ? getBalance(p) : 0.0;
    }
    public double getBalance(Player player) {
        try {
            return databaseManager.getBalance(player.getUniqueId());
        } catch (Exception e) {
            return 0.0;
        }
    }
    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        Player p = Bukkit.getPlayer(playerName);
        if (p == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player offline");
        try {
            databaseManager.setBalance(p.getUniqueId(), getBalance(p) - amount);
            return new EconomyResponse(amount, getBalance(p), EconomyResponse.ResponseType.SUCCESS, null);
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }
    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        Player p = Bukkit.getPlayer(playerName);
        if (p == null) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player offline");
        return depositPlayer(p, amount);
    }
    public EconomyResponse depositPlayer(Player player, double amount) {
        try {
            double current = getBalance(player);
            databaseManager.setBalance(player.getUniqueId(), current + amount);
            return new EconomyResponse(amount, current + amount, EconomyResponse.ResponseType.SUCCESS, null);
        } catch (Exception e) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
    }

    @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }
    @Override public double getBalance(String playerName, String worldName) { return getBalance(playerName); }
    @Override public boolean has(String playerName, double amount) { return getBalance(playerName) >= amount; }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public EconomyResponse createBank(String name, String player) { return null; }
    @Override public EconomyResponse deleteBank(String name) { return null; }
    @Override public EconomyResponse bankBalance(String name) { return null; }
    @Override public EconomyResponse bankHas(String name, double amount) { return null; }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return null; }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return null; }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return null; }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return null; }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
