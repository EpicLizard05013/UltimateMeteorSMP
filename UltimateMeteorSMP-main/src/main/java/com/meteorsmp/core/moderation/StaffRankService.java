package com.meteorsmp.core.moderation;

import com.meteorsmp.core.database.DatabaseManager;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Port of bansystem.sk's getRankLevel()/isStaff() power-level system.
 * Reads rank strings from skript_migrated_variables (rank::<uuid>,
 * rank::<name>, rank::<name-lowercase>) — the raw import table — rather
 * than a dedicated ranks table, since rank.sk itself hasn't been ported
 * yet. Falls back to permission-node checks exactly as the original
 * script does (LuckPerms-style group.* nodes). Once rank.sk is ported,
 * swap lookupRankString()'s query for a real `ranks` table without
 * touching this class's public API.
 */
public class StaffRankService {

    private final DatabaseManager db;
    private final Logger logger;

    public StaffRankService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public int getRankLevel(Player p) {
        if (p.isOp()) return 100;

        int lvl = 0;
        String rankString = lookupRankString(p);
        if (rankString != null) {
            String clean = rankString.toLowerCase(Locale.ROOT)
                    .replace(" ", "").replace(".", "").replace("_", "").replace("-", "");
            lvl = Math.max(lvl, levelFromRankWord(clean));
        }

        if (lvl < 100 && hasAny(p, "*", "group.owner", "group.01_owner", "meteor.owner", "donutsmp.owner", "staff.owner", "rank.owner", "owner")) lvl = 100;
        if (lvl < 90 && hasAny(p, "group.manager", "group.02_manager", "meteor.manager", "donutsmp.manager", "staff.manager", "rank.manager", "manager")) lvl = 90;
        if (lvl < 85 && hasAny(p, "group.sradmin", "group.senior_admin", "group.sr_admin", "group.sradm", "group.03_sradmin", "group.senioradmin", "meteor.sradmin", "donutsmp.sradmin", "staff.sradmin", "rank.sradmin", "sradmin")) lvl = 85;
        if (lvl < 80 && hasAny(p, "group.admin", "group.administrator", "group.04_admin", "meteor.admin", "donutsmp.admin", "staff.admin", "rank.admin", "admin")) lvl = 80;
        if (lvl < 70 && hasAny(p, "group.srmod", "group.senior_mod", "group.sr_mod", "group.05_srmod", "group.seniormod", "meteor.srmod", "donutsmp.srmod", "staff.srmod", "rank.srmod", "srmod")) lvl = 70;
        if (lvl < 60 && hasAny(p, "group.mod", "group.moderator", "group.06_mod", "meteor.mod", "donutsmp.mod", "staff.mod", "rank.mod", "mod")) lvl = 60;
        if (lvl < 50 && hasAny(p, "group.helper", "group.07_helper", "meteor.helper", "meteor.staff", "donutsmp.helper", "donutsmp.staff", "staff.helper", "rank.helper", "helper")) lvl = 50;
        if (lvl < 40 && hasAny(p, "group.trainee", "group.08_trainee", "meteor.trainee", "donutsmp.trainee", "staff.trainee", "rank.trainee", "trainee")) lvl = 40;

        return lvl;
    }

    private int levelFromRankWord(String clean) {
        if (clean.contains("owner")) return 100;
        if (clean.contains("manager") || clean.contains("mngr")) return 90;
        if (clean.contains("sradmin") || clean.contains("senioradmin") || clean.contains("sradm")) return 85;
        if (clean.contains("admin") || clean.contains("administrator") || clean.contains("adm")) return 80;
        if (clean.contains("srmod") || clean.contains("seniormod")) return 70;
        if (clean.contains("mod") || clean.contains("moderator")) return 60;
        if (clean.contains("helper")) return 50;
        if (clean.contains("trainee")) return 40;
        return 0;
    }

    private boolean hasAny(Player p, String... perms) {
        for (String perm : perms) if (p.hasPermission(perm)) return true;
        return false;
    }

    public boolean isStaff(Player p, String minRank) {
        int lvl = getRankLevel(p);
        int req = switch (minRank.toUpperCase(Locale.ROOT)) {
            case "TRAINEE" -> 40;
            case "HELPER" -> 50;
            case "MOD", "MODERATOR" -> 60;
            case "SRMOD", "SENIOR", "SENIOR_MOD", "SR_MOD" -> 70;
            case "ADMIN", "ADMINISTRATOR" -> 80;
            case "SRADMIN", "SENIOR_ADMIN", "SR_ADMIN" -> 85;
            case "MANAGER" -> 90;
            case "OWNER" -> 100;
            default -> 50;
        };
        return lvl >= req;
    }

    private String lookupRankString(Player p) {
        String[] keys = {
                "rank::" + p.getUniqueId(),
                "rank::" + p.getName(),
                "rank::" + p.getName().toLowerCase(Locale.ROOT)
        };
        try (Connection c = db.getRawConnection()) {
            for (String key : keys) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT string_value FROM skript_migrated_variables WHERE var_key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String v = rs.getString("string_value");
                            if (v != null && !v.isEmpty()) return v;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "lookupRankString failed for " + p.getName(), e);
        }
        return null;
    }
}
