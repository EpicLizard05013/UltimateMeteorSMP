package com.meteorsmp.core.database;

import ch.njol.skript.registrations.Classes;
import com.meteorsmp.core.PluginMain;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.logging.Level;

public class SkriptVariablesImporter {

    private static final String TABLE = "skript_migrated_variables";

    private final PluginMain plugin;
    private final DatabaseManager db;

    public SkriptVariablesImporter(PluginMain plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void runImport(CommandSender sender) {
        Path csv = plugin.getDataFolder().toPath().resolveSibling("Skript").resolve("variables.csv");
        if (!Files.exists(csv)) {
            sender.sendMessage("§cvariables.csv not found at " + csv);
            return;
        }

        sender.sendMessage("§eStarting async variable migration from " + csv + " ...");

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long total = 0, imported = 0, skippedBlacklisted = 0, failed = 0;

            try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
                 Connection conn = db.getRawConnection()) {

                conn.setAutoCommit(false);
                String insertSql = "INSERT OR REPLACE INTO " + TABLE +
                        " (var_key, var_type, string_value, long_value, double_value, uuid_value, blob_value) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)";
                PreparedStatement insert = conn.prepareStatement(insertSql);

                String line;
                int batch = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    total++;

                    ParsedRow row = parseLine(line);
                    if (row == null) { failed++; continue; }

                    if (isBlacklistedKey(row.key())) {
                        skippedBlacklisted++;
                        continue;
                    }

                    try {
                        bindAndBatch(insert, row);
                        insert.addBatch();
                        batch++;
                        imported++;
                    } catch (Exception e) {
                        failed++;
                        plugin.getLogger().log(Level.WARNING, "Failed to import variable: " + row.key(), e);
                    }

                    if (batch >= 500) {
                        insert.executeBatch();
                        conn.commit();
                        batch = 0;
                    }
                }
                insert.executeBatch();
                conn.commit();

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Variable migration failed", e);
                sender.sendMessage("§cMigration failed — see console.");
                return;
            }

            try {
                Files.move(csv, csv.resolveSibling("variables.csv.migrated"));
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not rename variables.csv after import", e);
            }

            long finalTotal = total, finalImported = imported, finalSkipped = skippedBlacklisted, finalFailed = failed;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                    "§aMigration complete. Read " + finalTotal + " lines — imported " + finalImported +
                    ", skipped (blacklisted) " + finalSkipped + ", failed " + finalFailed + "."));
        });
    }

    private boolean isBlacklistedKey(String key) {
        String scriptPrefix = key.contains("::") ? key.substring(0, key.indexOf("::")) : key;
        return plugin.isBlacklisted(scriptPrefix);
    }

    private record ParsedRow(String key, String type, byte[] raw) {}

    private ParsedRow parseLine(String line) {
        int firstComma = line.indexOf(", ");
        if (firstComma < 0) return null;
        int secondComma = line.indexOf(", ", firstComma + 2);
        if (secondComma < 0) return null;

        String key = unquote(line.substring(0, firstComma));
        String type = line.substring(firstComma + 2, secondComma).trim();
        String hex = line.substring(secondComma + 2).trim();

        if (hex.equalsIgnoreCase("null") || hex.equalsIgnoreCase("<none>")) return null;

        byte[] raw = hexDecode(hex);
        return new ParsedRow(key, type, raw);
    }

    private String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    private byte[] hexDecode(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private void bindAndBatch(PreparedStatement insert, ParsedRow row) throws Exception {
        Object value = Classes.deserialize(row.type(), row.raw());

        insert.setString(1, row.key());
        insert.setString(2, row.type());
        insert.setString(3, null);
        insert.setNull(4, java.sql.Types.BIGINT);
        insert.setNull(5, java.sql.Types.DOUBLE);
        insert.setString(6, null);
        insert.setBytes(7, null);

        switch (row.type()) {
            case "long" -> insert.setLong(4, ((Number) value).longValue());
            case "integer" -> insert.setLong(4, ((Number) value).longValue());
            case "double" -> insert.setDouble(5, ((Number) value).doubleValue());
            case "boolean" -> insert.setString(3, String.valueOf(value));
            case "string" -> insert.setString(3, (String) value);
            case "uuid" -> insert.setString(6, value.toString());
            case "date" -> insert.setLong(4, ((ch.njol.skript.util.Date) value).getTimestamp());
            case "offlineplayer" -> insert.setString(6, ((OfflinePlayer) value).getUniqueId().toString());
            case "location" -> {
                Location loc = (Location) value;
                insert.setString(3, loc.getWorld() != null ? loc.getWorld().getName() : "unknown");
                insert.setBytes(7, locationToBytes(loc));
            }
            case "itemstack" -> insert.setBytes(7, ((ItemStack) value).serializeAsBytes());
            case "itemtype" -> {
                ch.njol.skript.aliases.ItemType itemType = (ch.njol.skript.aliases.ItemType) value;
                insert.setString(3, itemType.toString());
                ItemStack representative = itemType.getRandom();
                if (representative != null) insert.setBytes(7, representative.serializeAsBytes());
            }
            case "textcomponent" -> insert.setString(3,
                    net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                            .serialize((net.kyori.adventure.text.Component) value));
            default -> {
                insert.setString(3, "UNHANDLED_TYPE:" + value);
                plugin.getLogger().warning("Unhandled variable type '" + row.type() + "' for key " + row.key());
            }
        }
    }

    private byte[] locationToBytes(Location loc) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(Double.BYTES * 3 + Float.BYTES * 2);
        buf.putDouble(loc.getX());
        buf.putDouble(loc.getY());
        buf.putDouble(loc.getZ());
        buf.putFloat(loc.getYaw());
        buf.putFloat(loc.getPitch());
        return buf.array();
    }
}
