package com.meteorsmp.core;

import com.meteorsmp.core.database.DatabaseManager;
import com.meteorsmp.core.economy.SpawnerSellManager;
import com.meteorsmp.core.database.SkriptVariablesImporter;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class PluginMain extends JavaPlugin {

    private DatabaseManager databaseManager;
    private SpawnerSellManager spawnerSellManager;
    private final Set<String> blacklist = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBlacklist();

        this.databaseManager = new DatabaseManager(this);
        try {
            databaseManager.connect();
            databaseManager.applySchema();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite storage — disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var migrateCmd = getCommand("migratevariables");
        if (migrateCmd != null) {
            migrateCmd.setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("meteorsmp.admin")) {
                    sender.sendMessage("§cYou don't have permission to run this.");
                    return true;
                }
                new SkriptVariablesImporter(this, databaseManager).runImport(sender);
                return true;
            });
        }

        this.spawnerSellManager = new SpawnerSellManager(this, databaseManager);
        spawnerSellManager.loadWorthAndMultipliers();
        getServer().getServicesManager().register(
                Economy.class, spawnerSellManager, this, ServicePriority.Highest);

        var sellCmd = getCommand("sell");
        if (sellCmd != null) {
            sellCmd.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                spawnerSellManager.openSellGui(player);
                return true;
            });
        }

        getServer().getPluginManager().registerEvents(spawnerSellManager, this);
        spawnerSellManager.tryHookSmartSpawner();

        getLogger().info("UltimateMeteorSMP enabled. Blacklisted scripts/commands: " + blacklist.size());
    }

    @Override
    public void onDisable() {
        if (spawnerSellManager != null) spawnerSellManager.flushAll();
        if (databaseManager != null) databaseManager.close();
        getServer().getServicesManager().unregisterAll(this);
    }

    private void loadBlacklist() {
        FileConfiguration cfg = getConfig();
        List<String> configured = cfg.getStringList("disabled-scripts");
        blacklist.addAll(configured);
    }

    public boolean isBlacklisted(String scriptOrCommandName) {
        return blacklist.contains(scriptOrCommandName.toLowerCase());
    }

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public SpawnerSellManager getSpawnerSellManager() { return spawnerSellManager; }

            this.shopManager = new ShopManager(this, databaseManager, spawnerSellManager);
        shopManager.loadAll();
        getServer().getPluginManager().registerEvents(shopManager, this);
        registerCommand("shop", (s, c, l, a) -> { if (s instanceof org.bukkit.entity.Player p) shopManager.openMainShop(p); return true; });
        registerCommand("openshopcategory", (s, c, l, a) -> { if (s instanceof org.bukkit.entity.Player p && a.length > 0) shopManager.openCategory(p, a[0]); return true; });
        registerCommand("addshopcat", (s, c, l, a) -> { if (s instanceof org.bukkit.entity.Player p && a.length > 0) shopManager.addCategory(p, a[0]); return true; });
        registerCommand("addshopitem", (s, c, l, a) -> {
            if (s instanceof org.bukkit.entity.Player p && a.length > 0)
                shopManager.addItem(p, a[0], a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
            return true;
        });
        registerCommand("delshopcat", (s, c, l, a) -> { if (s instanceof org.bukkit.entity.Player p && a.length > 0) shopManager.deleteCategory(p, a[0]); return true; });
        registerCommand("clearshopcat", (s, c, l, a) -> { if (s instanceof org.bukkit.entity.Player p && a.length > 0) shopManager.clearCategory(p, a[0]); return true; });

        this.economyCommands = new EconomyCommands(this, databaseManager, spawnerSellManager);
        getServer().getPluginManager().registerEvents(economyCommands, this);
        for (String cmd : List.of("bal", "pay", "add-to-balance", "changebal", "changebalall", "confirm",
                "sellmulti", "resetmulti", "sellmultiresetall")) {
            var c = getCommand(cmd);
            if (c != null) c.setExecutor(economyCommands);

                private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        var c = getCommand(name);
        if (c != null) c.setExecutor(executor);

                this.rankService = new StaffRankService(databaseManager, getLogger());
        this.staffModeration = new StaffModerationManager(this, databaseManager, rankService);
        getServer().getPluginManager().registerEvents(staffModeration, this);
        for (String cmd : List.of("rankcheck", "god", "vanish", "survi", "spect", "warn", "warnhistory",
                "removewarn", "mute", "unmute", "kick", "sus", "staffchat", "modlist", "testmodlist")) {
            registerCommand(cmd, staffModeration);
        }
    }
        }
}
