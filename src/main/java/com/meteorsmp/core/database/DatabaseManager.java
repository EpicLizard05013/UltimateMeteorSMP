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

    public void initialize() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File dbFile = new File(dataFolder, "database.db");

        HikariConfig config = new HikariConfig();
        config.setPoolName("MeteorSMP-SQLite");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1); // SQLite supports single-writer locking

        this.dataSource = new HikariDataSource(config);

        // Create missing database tables before any manager queries them
        createTables();
    }

    private void createTables() {
        String createCategoriesTable = """
            CREATE TABLE IF NOT EXISTS shop_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                icon_material TEXT NOT NULL,
                slot INTEGER NOT NULL
            );
        """;

        String createItemsTable = """
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
        """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createCategoriesTable);
            stmt.executeUpdate(createItemsTable);
            plugin.getLogger().info("SQLite database tables verified successfully.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create SQLite tables", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
