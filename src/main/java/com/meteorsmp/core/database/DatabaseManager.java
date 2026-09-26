package com.meteorsmp.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.meteorsmp.core.PluginMain;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final PluginMain plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(PluginMain plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dbFile = new File(dataFolder, "database.db");

        HikariConfig config = new HikariConfig();
        config.setPoolName("MeteorSMP-SQLite");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1); // SQLite single connection limit

        this.dataSource = new HikariDataSource(config);
        plugin.getLogger().info("SQLite database connection pool initialized.");
    }

    public void applySchema() {
        String[] tableQueries = {
            """
            CREATE TABLE IF NOT EXISTS shop_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                icon_material TEXT NOT NULL,
                slot INTEGER NOT NULL
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS shop_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category_name TEXT NOT NULL,
                material TEXT NOT NULL,
                display_name TEXT,
                buy_price REAL NOT NULL,
                sell_price REAL NOT NULL,
                slot INTEGER NOT NULL,
                FOREIGN KEY(category_name) REFERENCES shop_categories(name) ON DELETE CASCADE
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS mutes (
                uuid TEXT PRIMARY KEY,
                reason TEXT,
                muted_by TEXT,
                expires_at INTEGER
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS bans (
                uuid TEXT PRIMARY KEY,
                reason TEXT,
                banned_by TEXT,
                expires_at INTEGER
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS skript_migrated_variables (
                key_name TEXT PRIMARY KEY,
                value_data TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS player_flags (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                flag_key TEXT NOT NULL,
                flag_value TEXT
            );
            """
        };

        try (Connection conn = getRawConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : tableQueries) {
                stmt.executeUpdate(sql);
            }
            plugin.getLogger().info("Database tables initialized successfully.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to apply database schema", e);
        }
    }

    public Connection getRawConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("HikariDataSource is not initialized. Call connect() first.");
        }
        return dataSource.getConnection();
    }

    public Connection getConnection() throws SQLException {
        return getRawConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
