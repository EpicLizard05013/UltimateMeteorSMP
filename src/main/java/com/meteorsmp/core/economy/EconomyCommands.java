package com.meteorsmp.core.economy;

import com.meteorsmp.core.PluginMain;
import com.meteorsmp.core.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Port of shopbal.sk's remaining commands: /bal, /pay (+ confirm GUI),
 * /add-to-balance, /changebal, /changebalall (+ /confirm), /sellmulti,
 * /resetmulti, /sellmultiresetall. /sell and the Vault economy backing
 * are already in SpawnerSellManager; this class only adds what wasn't
 * there yet.
 */
public class EconomyCommands implements CommandExecutor, Listener {

    private static final String PAY_CONFIRM_TITLE = "Confirm Payment";

    private final PluginMain plugin;
    private final DatabaseManager db;
    private final SpawnerSellManager economy;

    private record PayRequest(UUID target, double amount) {}
    private record ChangeBalAllRequest(int code, double newAmount, double threshold, String arg1, String arg2) {}

    private final Map<UUID, PayRequest> pendingPayments = new ConcurrentHashMap<>();
    private final Map<UUID, ChangeBalAllRequest> pendingChangeBalAll = new ConcurrentHashMap<>();

    public EconomyCommands(PluginMain plugin, DatabaseManager db, SpawnerSellManager economy) {
        this.plugin = plugin;
        this.db = db;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "bal" -> handleBal(sender, args);
            case "pay" -> handlePay(sender, args);
            case "add-to-balance" -> handleAddToBalance(sender, args);
            case "changebal" -> handleChangeBal(sender, args);
            case "changebalall" -> handleChangeBalAll(sender, args);
            case "confirm" -> handleConfirm(sender, args);
            case "sellmulti" -> handleSellMulti(sender);
            case "resetmulti" -> handleResetMulti(sender, args);
            case "sellmultiresetall" -> handleResetAll(sender);
            default -> false;
        };
    }

    // ------------------------------------------------------------------

    private boolean handleBal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) && args.length == 0) {
            sender.sendMessage("Console must specify a player.");
            return true;
        }
        OfflinePlayer target = args.length > 0 ? Bukkit.getOfflinePlayer(args[0])
                : (OfflinePlayer) sender;
        double bal = economy.getBalance(target);
        String who = (sender instanceof Player p && target.getUniqueId().equals(p.getUniqueId())) ? "Your" : target.getName() + "'s";
        sender.sendMessage(ChatColor.GREEN + who + " balance is: " + ChatColor.YELLOW + "$" + formatCommas(bal));
        return true;
    }

    private boolean handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        if (args.length < 2) { player.sendMessage(ChatColor.RED + "[Economy] Usage: /pay <player> <amount>"); return true; }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[Economy] You cannot pay yourself!");
            return true;
        }

        double finalAmount = parseAmount(args[1]);
        if (finalAmount <= 0) {
            player.sendMessage(ChatColor.RED + "[Economy] Invalid amount specified! Use numbers like 50, 10m, or 50b.");
            return true;
        }

        double bal = economy.getBalance(player);
        if (bal < finalAmount) {
            player.sendMessage(ChatColor.RED + "[Economy] You don't have enough money! You need $" + formatBal(finalAmount) + ".");
            return true;
        }

        pendingPayments.put(player.getUniqueId(), new PayRequest(target.getUniqueId(), finalAmount));

        Inventory gui = Bukkit.createInventory(null, 27, PAY_CONFIRM_TITLE);
        gui.setItem(11, glassPane(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "" + ChatColor.BOLD + "Confirm Yes",
                "Click to send $" + formatBal(finalAmount) + " to " + target.getName() + "."));
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Payment Details");
        infoMeta.setLore(List.of(ChatColor.GRAY + "Recipient: " + ChatColor.YELLOW + target.getName(),
                ChatColor.GRAY + "Amount: " + ChatColor.GREEN + "$" + formatBal(finalAmount)));
        info.setItemMeta(infoMeta);
        gui.setItem(13, info);
        gui.setItem(15, glassPane(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "" + ChatColor.BOLD + "Cancel No",
                "Click to cancel this transaction."));
        player.openInventory(gui);
        return true;
    }

    @EventHandler
    public void onPayConfirmClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(PAY_CONFIRM_TITLE)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);

        PayRequest req = pendingPayments.get(player.getUniqueId());
        int slot = event.getRawSlot();

        if (slot == 11) {
            player.closeInventory();
            pendingPayments.remove(player.getUniqueId());
            if (req == null) {
                player.sendMessage(ChatColor.RED + "[Economy] Transaction expired or invalid.");
                return;
            }
            if (economy.getBalance(player) < req.amount()) {
                player.sendMessage(ChatColor.RED + "[Economy] You don't have enough money!");
                return;
            }
            economy.withdrawPlayer(player, req.amount());
            OfflinePlayer target = Bukkit.getOfflinePlayer(req.target());
            economy.depositPlayer(target, req.amount());
            player.sendMessage(ChatColor.GREEN + "[Economy] You successfully sent $" + formatBal(req.amount()) +
                    " to " + target.getName() + ".");
            if (target.isOnline() && target.getPlayer() != null) {
                target.getPlayer().sendMessage(ChatColor.GREEN + "[Economy] " + player.getName() +
                        " has sent you $" + formatBal(req.amount()) + ".");
            }
        } else if (slot == 15) {
            pendingPayments.remove(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(ChatColor.RED + "[Economy] Payment cancelled.");
        }
    }

    private ItemStack glassPane(Material mat, String name, String lore) {
        ItemStack pane = new ItemStack(mat);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(ChatColor.GRAY + lore));
        pane.setItemMeta(meta);
        return pane;
    }

    private boolean handleAddToBalance(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("Usage: /add-to-balance <player> <amount>"); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        double amount = parseAmount(args[1]);
        if (amount == 0 && !args[1].matches("0+(\\.0+)?[kmbt]?")) {
            sender.sendMessage(ChatColor.RED + "[Economy] Invalid amount specified!");
            return true;
        }
        economy.depositPlayer(target, amount);
        sender.sendMessage(ChatColor.GREEN + "[Economy] Added $" + formatBal(amount) + " to " + target.getName() + "'s balance.");
        return true;
    }

    private boolean handleChangeBal(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("Usage: /changebal <player> <amount>"); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        double amount = parseAmount(args[1]);
        economy.setBalanceDirect(target.getUniqueId(), amount);
        sender.sendMessage(ChatColor.GREEN + "[Economy] Set " + target.getName() + "'s balance to $" + formatBal(amount) + ".");
        return true;
    }

    private boolean handleChangeBalAll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "[Economy] Usage: /changebalall <new_amount> <threshold>");
            sender.sendMessage(ChatColor.GRAY + "Example: /changebalall 50m 24.9b");
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only (needs /confirm)."); return true; }

        double newAmount = parseAmount(args[0]);
        double threshold = parseAmount(args[1]);
        int matchingCount = economy.countBalancesAbove(threshold);

        int code = 1000 + (int) (Math.random() * 9000);
        pendingChangeBalAll.put(player.getUniqueId(), new ChangeBalAllRequest(code, newAmount, threshold, args[0], args[1]));

        player.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "WARNING: " + ChatColor.RED + "this cannot be undone!");
        player.sendMessage(ChatColor.RED + "You are about to change " + ChatColor.YELLOW + matchingCount +
                ChatColor.RED + " people's bals to " + ChatColor.GREEN + args[0] + ChatColor.RED + " over " + ChatColor.GREEN + args[1] + ChatColor.RED + "!");
        player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.YELLOW + "/confirm " + code + ChatColor.GRAY + " to execute this change.");
        return true;
    }

    private boolean handleConfirm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        ChangeBalAllRequest req = pendingChangeBalAll.get(player.getUniqueId());
        if (req == null) {
            player.sendMessage(ChatColor.RED + "[Economy] You have no pending balance actions to confirm.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "[Economy] Please enter the confirmation code. Usage: /confirm <code>");
            return true;
        }
        int entered;
        try { entered = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "[Economy] Incorrect confirmation code!");
            return true;
        }
        if (entered != req.code()) {
            player.sendMessage(ChatColor.RED + "[Economy] Incorrect confirmation code!");
            return true;
        }

        int count = economy.bulkSetBalancesAbove(req.threshold(), req.newAmount());
        player.sendMessage(ChatColor.GREEN + "[Economy] Successfully updated " + count + " player(s) bals to $" +
                req.arg1() + " over $" + req.arg2() + ".");
        pendingChangeBalAll.remove(player.getUniqueId());
        return true;
    }

    private boolean handleSellMulti(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        double multi = economy.getSellMultiplier(player.getUniqueId());
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[SellMulti] " + ChatColor.WHITE + "Your current global sell multiplier is: " +
                ChatColor.YELLOW + multi + "x");
        return true;
    }

    private boolean handleResetMulti(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "[SellMulti] Usage: /resetmulti <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        economy.resetMoneyMade(target.getUniqueId());
        sender.sendMessage(ChatColor.GREEN + "[SellMulti] Reset sell multiplier progress for " + target.getName() + ".");
        return true;
    }

    private boolean handleResetAll(CommandSender sender) {
        economy.resetAllMoneyMade();
        sender.sendMessage(ChatColor.GREEN + "[SellMulti] Reset sell multiplier progress for all players.");
        return true;
    }

    // ------------------------------------------------------------------

    private double parseAmount(String raw) {
        String s = raw.toLowerCase(Locale.ROOT);
        double multiplier = 1;
        if (s.contains("k")) { multiplier = 1_000; s = s.replace("k", ""); }
        else if (s.contains("m")) { multiplier = 1_000_000; s = s.replace("m", ""); }
        else if (s.contains("b")) { multiplier = 1_000_000_000; s = s.replace("b", ""); }
        else if (s.contains("t")) { multiplier = 1_000_000_000_000d; s = s.replace("t", ""); }
        try { return Double.parseDouble(s) * multiplier; } catch (NumberFormatException e) { return 0; }
    }

    private String formatBal(double n) { return economy.formatBalPublic(n); }
    private String formatCommas(double n) {
        return String.format(Locale.US, "%,.0f", n);
    }
}
