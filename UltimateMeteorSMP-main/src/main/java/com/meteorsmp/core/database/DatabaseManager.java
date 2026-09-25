package com.meteorsmp.core.database;

import com.meteorsmp.core.PluginMain;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private final PluginMain plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(PluginMain plugin) { this.plugin = plugin; }

    public void connect() {
        plugin.getDataFolder().mkdirs();
        String dbFile = plugin.getConfig().getString("database.file", "storage.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder().toPath().resolve(dbFile));
        config.setMaximumPoolSize(4);
        config.setPoolName("MeteorSMP-SQLite");
        this.dataSource = new HikariDataSource(config);
    }

    public void applySchema() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS skript_migrated_variables (
                    var_key      TEXT PRIMARY KEY,
                    var_type     TEXT NOT NULL,
                    string_value TEXT,
                    long_value   INTEGER,
                    double_value REAL,
                    uuid_value   TEXT,
                    blob_value   BLOB
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS balances (
                    uuid TEXT PRIMARY KEY,
                    balance REAL NOT NULL DEFAULT 0,
                    shards INTEGER NOT NULL DEFAULT 0
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS worth_prices (
                    item_id TEXT PRIMARY KEY,
                    price REAL NOT NULL,
                    category TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS money_made (
                    uuid TEXT NOT NULL,
                    category TEXT NOT NULL,
                    total REAL NOT NULL DEFAULT 0,
                    items_sold INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, category)
                )""");

            s.execute("""
    CREATE TABLE IF NOT EXISTS staff_flags (
        uuid TEXT PRIMARY KEY,
        vanished INTEGER NOT NULL DEFAULT 0,
        godmode INTEGER NOT NULL DEFAULT 0,
        staffchat INTEGER NOT NULL DEFAULT 0
    )""");
s.execute("""
    CREATE TABLE IF NOT EXISTS mutes (
        uuid TEXT PRIMARY KEY,
        reason TEXT,
        expiry INTEGER
    )""");
s.execute("""
    CREATE TABLE IF NOT EXISTS warnings (
        uuid TEXT NOT NULL,
        reason TEXT NOT NULL,
        warned_by TEXT,
        ts INTEGER NOT NULL
    )""");
        }
    }

    public Connection getRawConnection() throws SQLException { return dataSource.getConnection(); }
    public void close() { if (dataSource != null) dataSource.close(); }
}
