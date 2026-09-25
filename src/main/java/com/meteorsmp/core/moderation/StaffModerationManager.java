package com.meteorsmp.core.moderation;

import com.meteorsmp.core.PluginMain;
import com.meteorsmp.core.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of bansystem.sk's core moderation toolkit: rank-gated commands
 * (god/vanish/survi/spect/warn/warnhistory/removewarn/mute/unmute/kick/
 * staffchat/modlist/rankcheck/sus/testmodlist), vanish/god/mute
 * enforcement listeners, and the tab-complete guard.
 *
 * NOT included (need home.sk/vault.sk ported first): /wipe, /tp,
 * /seeinventory, /enderchest(see), /seehome, /staffdelhome.
 *
 * Timespan parsing for /mute is simplified from Skript's natural-language
 * parser to compact tokens like "10m", "1h", "2d30m" (number+unit,
 * optionally chained) — s/m/h/d/w supported. If your staff are used to
 * typing "10 minutes" with a space, that won't parse; flag it if you
 * need the fuller grammar and I'll extend parseTimespan().
 */
public class StaffModerationManager implements Listener, CommandExecutor {

    private static final String TAG = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "Staff" + ChatColor.DARK_GRAY + "] ";
    private static final Pattern TIMESPAN_TOKEN = Pattern.compile("(\\d+)([smhdw])");

    private final PluginMain plugin;
    private final DatabaseManager db;
    private final StaffRankService ranks;

    private final Set<UUID> staffChatToggled = ConcurrentHashMap.newKeySet();

    public StaffModerationManager(PluginMain plugin, DatabaseManager db, StaffRankService ranks) {
        this.plugin = plugin;
        this.db = db;
        this.ranks = ranks;
    }

    // ------------------------------------------------------------------
    // Command dispatch
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "rankcheck" -> cmdRankCheck(player);
            case "god" -> cmdGod(player);
            case "vanish" -> cmdVanish(player);
            case "survi" -> cmdSurvi(player);
            case "spect" -> cmdSpect(player);
            case "warn" -> cmdWarn(player, args);
            case "warnhistory" -> cmdWarnHistory(player, args);
            case "removewarn" -> cmdRemoveWarn(player, args);
            case "mute" -> cmdMute(player, args);
            case "unmute" -> cmdUnmute(player, args);
            case "kick" -> cmdKick(player, args);
            case "sus" -> cmdSus(player, args);
            case "staffchat" -> cmdStaffChat(player, args);
            case "modlist" -> cmdModList(player);
            case "testmodlist" -> cmdTestModList(player);
            default -> false;
        };
    }

    private boolean denyUnlessStaff(Player p, String minRank) {
        if (!ranks.isStaff(p, minRank)) {
            p.sendMessage(ChatColor.RED + "Unknown command. Type '/help' for help.");
            return true;
        }
        return false;
    }

    private boolean cmdRankCheck(Player p) {
        p.sendMessage(ChatColor.GRAY + "Rank Power Level: " + ChatColor.YELLOW + ranks.getRankLevel(p));
        return true;
    }

    private boolean cmdGod(Player p) {
        if (denyUnlessStaff(p, "MOD")) return true;
        boolean now = toggleFlag(p.getUniqueId(), "godmode");
        p.sendMessage(TAG + (now ? ChatColor.GREEN + "God mode enabled." : ChatColor.RED + "God mode disabled."));
        return true;
    }

    private boolean cmdVanish(Player p) {
        if (denyUnlessStaff(p, "MOD")) return true;
        boolean now = toggleFlag(p.getUniqueId(), "vanished");
        if (now) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(p)) continue;
                if (ranks.isStaff(other, "MOD")) other.showPlayer(plugin, p);
                else other.hidePlayer(plugin, p);
            }
            p.sendMessage(TAG + ChatColor.GREEN + "You are now vanished.");
        } else {
            for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, p);
            p.sendMessage(TAG + ChatColor.RED + "You are no longer vanished.");
        }
        return true;
    }

    private boolean cmdSurvi(Player p) {
        if (denyUnlessStaff(p, "MOD")) return true;
        p.setGameMode(GameMode.SURVIVAL);
        p.sendMessage(TAG + ChatColor.GREEN + "Game mode set to " + ChatColor.YELLOW + "Survival" + ChatColor.GREEN + ".");
        return true;
    }

    private boolean cmdSpect(Player p) {
        if (denyUnlessStaff(p, "MOD")) return true;
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
            p.sendMessage(ChatColor.GREEN + "Exited spectator mode.");
        } else {
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage(ChatColor.GREEN + "Game mode set to " + ChatColor.YELLOW + "Spectator" + ChatColor.GREEN + ".");
        }
        return true;
    }

    private boolean cmdWarn(Player p, String[] args) {
        if (denyUnlessStaff(p, "HELPER")) return true;
        if (args.length < 2) { p.sendMessage(TAG + ChatColor.RED + "Usage: /warn <player> <reason>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        addWarning(targetUuid, reason, p.getName());
        if (target != null) target.sendMessage(TAG + ChatColor.RED + "You have been warned by " + p.getName() + " for: " + ChatColor.WHITE + reason);
        p.sendMessage(TAG + ChatColor.GREEN + "Successfully warned " + ChatColor.YELLOW + args[0] + ChatColor.GREEN + " for: " + ChatColor.WHITE + reason);
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "[" + ChatColor.DARK_RED + "Warn" + ChatColor.DARK_GRAY + "] " +
                ChatColor.YELLOW + args[0] + ChatColor.RED + " has been warned by " + ChatColor.YELLOW + p.getName() + ChatColor.RED + " for: " + ChatColor.WHITE + reason);
        return true;
    }

    private boolean cmdWarnHistory(Player p, String[] args) {
        if (denyUnlessStaff(p, "HELPER")) return true;
        if (args.length < 1) { p.sendMessage(TAG + ChatColor.RED + "Usage: /warnhistory <player>"); return true; }
        UUID targetUuid = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        List<String> reasons = getWarnings(targetUuid);
        p.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "--- " + ChatColor.AQUA + "Warnings for " + ChatColor.YELLOW + args[0] + ChatColor.DARK_GRAY + " " + ChatColor.BOLD + "---");
        if (reasons.isEmpty()) p.sendMessage(ChatColor.GRAY + "This player has no warnings.");
        else reasons.forEach(r -> p.sendMessage(ChatColor.RED + "- " + ChatColor.WHITE + r));
        return true;
    }

    private boolean cmdRemoveWarn(Player p, String[] args) {
        if (denyUnlessStaff(p, "MOD")) return true;
        if (args.length < 1) { p.sendMessage(TAG + ChatColor.RED + "Usage: /removewarn <player>"); return true; }
        UUID targetUuid = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        if (getWarnings(targetUuid).isEmpty()) {
            p.sendMessage(TAG + ChatColor.RED + args[0] + " has no warnings to remove.");
            return true;
        }
        clearWarnings(targetUuid);
        p.sendMessage(TAG + ChatColor.GREEN + "Successfully cleared all warnings for " + ChatColor.YELLOW + args[0] + ChatColor.GREEN + ".");
        return true;
    }

    private boolean cmdMute(Player p, String[] args) {
        if (denyUnlessStaff(p, "HELPER")) return true;
        if (args.length < 3) { p.sendMessage(TAG + ChatColor.RED + "Usage: /mute <player> <time> <reason>"); return true; }

        long durationMillis = parseTimespan(args[1]);
        if (durationMillis <= 0) {
            p.sendMessage(TAG + ChatColor.RED + "Invalid time — use tokens like 10m, 1h, 2d.");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetUuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        long expiry = System.currentTimeMillis() + durationMillis;

        setMute(targetUuid, reason, expiry);

        if (target != null) {
            target.sendMessage(TAG + ChatColor.RED + "You have been muted (chat) for " + ChatColor.YELLOW + args[1] +
                    ChatColor.RED + " by " + ChatColor.YELLOW + p.getName() + ChatColor.RED + " for: " + ChatColor.WHITE + reason);
        }
        p.sendMessage(TAG + ChatColor.GREEN + "Successfully muted " + ChatColor.YELLOW + args[0] + ChatColor.GREEN +
                " for " + ChatColor.WHITE + args[1] + ChatColor.GREEN + " (Reason: " + ChatColor.WHITE + reason + ChatColor.GREEN + ").");
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "[" + ChatColor.DARK_RED + "Mute" + ChatColor.DARK_GRAY + "] " +
                ChatColor.YELLOW + args[0] + ChatColor.RED + " has been muted by " + ChatColor.YELLOW + p.getName() +
                ChatColor.RED + " for " + ChatColor.WHITE + args[1] + ChatColor.RED + " (Reason: " + ChatColor.WHITE + reason + ChatColor.RED + ").");
        return true;

        // NOTE: original script also toggles a "voicechat" plugin mute and
        // LuckPerms voicechat.speak permission here. Not ported — I don't
        // have visibility into whether/how that plugin is still installed
        // post-migration. Flag it if you need that re-added.
    }

    private boolean cmdUnmute(Player p, String[] args) {
        if (denyUnlessStaff(p, "MOD")) return true;
        if (args.length < 1) { p.sendMessage(TAG + ChatColor.RED + "Usage: /unmute <player>"); return true; }
        UUID targetUuid = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        if (!isMuted(targetUuid)) { p.sendMessage(TAG + ChatColor.RED + args[0] + " is not muted."); return true; }
        clearMute(targetUuid);
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target != null) target.sendMessage(TAG + ChatColor.GREEN + "You have been unmuted by " + ChatColor.YELLOW + p.getName() + ChatColor.GREEN + ".");
        p.sendMessage(TAG + ChatColor.GREEN + "Successfully unmuted " + ChatColor.YELLOW + args[0] + ChatColor.GREEN + ".");
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "[" + ChatColor.DARK_RED + "Mute" + ChatColor.DARK_GRAY + "] " +
                ChatColor.YELLOW + args[0] + ChatColor.RED + " has been unmuted by " + ChatColor.YELLOW + p.getName() + ChatColor.RED + ".");
        return true;
    }

    private boolean cmdKick(Player p, String[] args) {
        if (denyUnlessStaff(p, "MOD")) return true;
        if (args.length < 2) { p.sendMessage(TAG + ChatColor.RED + "Usage: /kick <player> <reason>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { p.sendMessage(TAG + ChatColor.RED + args[0] + " is not online."); return true; }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        target.kick(net.kyori.adventure.text.Component.text(ChatColor.RED + "You have been kicked!\n" + ChatColor.GRAY + "Reason: " + ChatColor.WHITE + reason));
        Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "[" + ChatColor.DARK_RED + "Kick" + ChatColor.DARK_GRAY + "] " +
                ChatColor.YELLOW + args[0] + ChatColor.RED + " has been kicked by " + ChatColor.YELLOW + p.getName() + ChatColor.RED + " for: " + ChatColor.WHITE + reason);
        return true;
    }

    private boolean cmdSus(Player p, String[] args) {
        if (denyUnlessStaff(p, "HELPER")) return true;
        if (args.length < 1) { p.sendMessage(TAG + ChatColor.RED + "Usage: /sus <player>"); return true; }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (ranks.isStaff(other, "HELPER")) {
                other.sendMessage(TAG + ChatColor.RED + "[SUS] " + ChatColor.YELLOW + p.getName() +
                        ChatColor.RED + " has marked " + ChatColor.YELLOW + args[0] + ChatColor.RED + " as suspicious!");
            }
        }
        return true;
    }

    private boolean cmdStaffChat(Player p, String[] args) {
        if (denyUnlessStaff(p, "HELPER")) return true;
        if (args.length == 0) {
            if (staffChatToggled.remove(p.getUniqueId())) {
                p.sendMessage(TAG + ChatColor.RED + "Staff chat toggled OFF.");
            } else {
                staffChatToggled.add(p.getUniqueId());
                p.sendMessage(TAG + ChatColor.GREEN + "Staff chat toggled ON.");
            }
        } else {
            String msg = String.join(" ", args);
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (ranks.isStaff(other, "HELPER")) {
                    other.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "StaffChat" + ChatColor.DARK_GRAY + "] " +
                            ChatColor.YELLOW + p.getName() + ChatColor.DARK_GRAY + " » " + ChatColor.WHITE + msg);
                }
            }
        }
        return true;
    }

    private boolean cmdModList(Player p) {
        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.STRIKETHROUGH + "--------------------------------------------------");
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "[Helper & Up Commands]");
        p.sendMessage(ChatColor.YELLOW + "/mute <player> <time> <reason> " + ChatColor.GRAY + "- Mutes a player (Chat)");
        p.sendMessage(ChatColor.YELLOW + "/warn <player> <reason> " + ChatColor.GRAY + "- Warns a player");
        p.sendMessage(ChatColor.YELLOW + "/warnhistory <player> " + ChatColor.GRAY + "- Views a player's warnings");
        p.sendMessage(ChatColor.YELLOW + "/sus <player> " + ChatColor.GRAY + "- Alerts staff that a player is suspicious");
        p.sendMessage(ChatColor.YELLOW + "/staffchat [msg] " + ChatColor.GRAY + "- Toggles or sends a message in staff chat");
        p.sendMessage(ChatColor.YELLOW + "/modlist " + ChatColor.GRAY + "- Shows this list of commands");
        p.sendMessage("");
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "[Moderator & Up Commands]");
        p.sendMessage(ChatColor.YELLOW + "/survi " + ChatColor.GRAY + "- Sets your gamemode to Survival");
        p.sendMessage(ChatColor.YELLOW + "/spect " + ChatColor.GRAY + "- Sets your gamemode to Spectator");
        p.sendMessage(ChatColor.YELLOW + "/god " + ChatColor.GRAY + "- Toggles God Mode");
        p.sendMessage(ChatColor.YELLOW + "/vanish " + ChatColor.GRAY + "- Toggles staff vanish mode");
        p.sendMessage(ChatColor.YELLOW + "/removewarn <player> " + ChatColor.GRAY + "- Clears a player's warnings");
        p.sendMessage(ChatColor.YELLOW + "/unmute <player> " + ChatColor.GRAY + "- Unmutes a player");
        p.sendMessage(ChatColor.YELLOW + "/kick <player> <reason> " + ChatColor.GRAY + "- Kicks a player");
        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.STRIKETHROUGH + "--------------------------------------------------");
        return true;
    }

    private boolean cmdTestModList(Player p) {
        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.STRIKETHROUGH + "--------------------------------------------------");
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "[Your Available Staff Commands Check]");
        p.sendMessage(ChatColor.GRAY + "Your Power Level: " + ChatColor.YELLOW + ranks.getRankLevel(p));
        p.sendMessage("");
        p.sendMessage(ranks.isStaff(p, "HELPER")
                ? ChatColor.GREEN + "✔ Helper Commands: " + ChatColor.WHITE + "/mute, /warn, /warnhistory, /sus, /staffchat, /modlist"
                : ChatColor.RED + "✖ Helper Commands: " + ChatColor.GRAY + "No Access");
        p.sendMessage(ranks.isStaff(p, "MOD")
                ? ChatColor.GREEN + "✔ Moderator Commands: " + ChatColor.WHITE + "/survi, /spect, /god, /vanish, /removewarn, /unmute, /kick"
                : ChatColor.RED + "✖ Moderator Commands: " + ChatColor.GRAY + "No Access");
        return true;
    }

    // ------------------------------------------------------------------
    // Enforcement listeners
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        boolean staffMod = ranks.isStaff(p, "MOD");
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            if (getFlag(other.getUniqueId(), "vanished")) {
                if (staffMod) p.showPlayer(plugin, other);
                else p.hidePlayer(plugin, other);
            }
        }
        if (getFlag(p.getUniqueId(), "vanished")) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(p)) continue;
                if (ranks.isStaff(other, "MOD")) other.showPlayer(plugin, p);
                else other.hidePlayer(plugin, p);
            }
        }
        // Legacy tempban enforcement: reads whatever tempbanned::<uuid> /
        // tempban_expiry::<uuid> / tempban_reason::<uuid> your import
        // brought over. No command in this batch SETS a tempban (the
        // original script doesn't define one in bansystem.sk either) —
        // this only honors bans that already existed pre-migration.
        checkLegacyTempban(p);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        if (isMuted(uuid)) {
            Long expiry = getMuteExpiry(uuid);
            if (expiry != null && expiry > System.currentTimeMillis()) {
                event.setCancelled(true);
                p.sendMessage(TAG + ChatColor.RED + "You are currently muted! Reason: " + ChatColor.WHITE + getMuteReason(uuid));
                return;
            } else {
                clearMute(uuid);
            }
        }
        if (staffChatToggled.contains(uuid)) {
            event.setCancelled(true);
            String msg = event.getMessage();
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (ranks.isStaff(other, "HELPER")) {
                    other.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "StaffChat" + ChatColor.DARK_GRAY + "] " +
                            ChatColor.YELLOW + p.getName() + ChatColor.DARK_GRAY + " » " + ChatColor.WHITE + msg);
                }
            }
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!isMuted(p.getUniqueId())) return;
        String cmd = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (Set.of("msg", "tell", "w", "whisper", "r", "reply").contains(cmd)) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You are currently muted and cannot send private messages!");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim) {
            if (getFlag(victim.getUniqueId(), "godmode") || getFlag(victim.getUniqueId(), "vanished")) {
                event.setCancelled(true);
            }
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player attacker) {
            if (getFlag(attacker.getUniqueId(), "vanished")) {
                event.setCancelled(true);
                attacker.sendMessage(TAG + ChatColor.RED + "You cannot attack while vanished.");
            }
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (getFlag(event.getPlayer().getUniqueId(), "vanished")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TAG + ChatColor.RED + "You cannot break blocks while vanished.");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (getFlag(event.getPlayer().getUniqueId(), "vanished")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TAG + ChatColor.RED + "You cannot place blocks while vanished.");
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (getFlag(event.getPlayer().getUniqueId(), "vanished")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TAG + ChatColor.RED + "You cannot drop items while vanished.");
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player target && getFlag(target.getUniqueId(), "vanished")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player p)) return;
        String buf = event.getBuffer().toLowerCase(Locale.ROOT);
        boolean guarded = Stream.of("/god", "/vanish", "/v ", "/tp", "/survi", "/spect", "/wipe", "/warn ",
                        "/warnhistory", "/removewarn", "/mute", "/unmute", "/kick", "/seeinventory", "/invsee",
                        "/enderchestsee", "/seehome", "/staffdelhome", "/sus", "/staffchat", "/sc")
                .anyMatch(buf::startsWith);
        if (guarded && !ranks.isStaff(p, "TRAINEE")) {
            event.getCompletions().clear();
        }
    }

    // ------------------------------------------------------------------
    // Persistence helpers
    // ------------------------------------------------------------------

    private boolean toggleFlag(UUID uuid, String column) {
        boolean current = getFlag(uuid, column);
        setFlag(uuid, column, !current);
        return !current;
    }

    private boolean getFlag(UUID uuid, String column) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM staff_flags WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) != 0; }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "getFlag(" + column + ") failed", e);
            return false;
        }
    }

    private void setFlag(UUID uuid, String column, boolean value) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO staff_flags (uuid, " + column + ") VALUES (?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET " + column + " = excluded." + column)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, value ? 1 : 0);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "setFlag(" + column + ") failed", e);
        }
    }

    private void addWarning(UUID uuid, String reason, String warnedBy) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO warnings (uuid, reason, warned_by, ts) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, reason);
            ps.setString(3, warnedBy);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "addWarning failed", e);
        }
    }

    private List<String> getWarnings(UUID uuid) {
        List<String> result = new ArrayList<>();
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT reason FROM warnings WHERE uuid = ? ORDER BY ts")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("reason"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "getWarnings failed", e);
        }
        return result;
    }

    private void clearWarnings(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM warnings WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "clearWarnings failed", e);
        }
    }

    private void setMute(UUID uuid, String reason, long expiry) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mutes (uuid, reason, expiry) VALUES (?, ?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET reason = excluded.reason, expiry = excluded.expiry")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, reason);
            ps.setLong(3, expiry);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "setMute failed", e);
        }
    }

    private void clearMute(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM mutes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "clearMute failed", e);
        }
    }

    private boolean isMuted(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM mutes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "isMuted failed", e);
            return false;
        }
    }

    private Long getMuteExpiry(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT expiry FROM mutes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong("expiry") : null; }
        } catch (Exception e) {
            return null;
        }
    }

    private String getMuteReason(UUID uuid) {
        try (Connection c = db.getRawConnection();
             PreparedStatement ps = c.prepareStatement("SELECT reason FROM mutes WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString("reason") : ""; }
        } catch (Exception e) {
            return "";
        }
    }

    private long parseTimespan(String token) {
        Matcher m = TIMESPAN_TOKEN.matcher(token.toLowerCase(Locale.ROOT));
        long totalMillis = 0;
        boolean matchedAny = false;
        while (m.find()) {
            matchedAny = true;
            long amount = Long.parseLong(m.group(1));
            totalMillis += switch (m.group(2)) {
                case "s" -> amount * 1000L;
                case "m" -> amount * 60_000L;
                case "h" -> amount * 3_600_000L;
                case "d" -> amount * 86_400_000L;
                case "w" -> amount * 604_800_000L;
                default -> 0L;
            };
        }
        return matchedAny ? totalMillis : -1;
    }

    private void checkLegacyTempban(Player p) {
        UUID uuid = p.getUniqueId();
        try (Connection c = db.getRawConnection()) {
            String banned = queryString(c, "tempbanned::" + uuid);
            if (!"true".equalsIgnoreCase(banned)) return;
            String expiryStr = queryString(c, "tempban_expiry::" + uuid);
            String reason = queryString(c, "tempban_reason::" + uuid);
            long expiry = expiryStr != null ? Long.parseLong(expiryStr) : 0;
            if (expiry > System.currentTimeMillis()) {
                p.kick(net.kyori.adventure.text.Component.text(
                        ChatColor.RED + "You are temporarily banned!\n" + ChatColor.GRAY + "Reason: " + ChatColor.WHITE + reason));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "checkLegacyTempban failed for " + p.getName(), e);
        }
    }

    private String queryString(Connection c, String key) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT string_value, long_value FROM skript_migrated_variables WHERE var_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String s = rs.getString("string_value");
                if (s != null) return s;
                long l = rs.getLong("long_value");
                return rs.wasNull() ? null : String.valueOf(l);
            }
        }
    }
}
