package com.jdbctest.extension;

import com.jdbctest.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

public class JdbcContext {

    private static final int DEFAULT_MAX_POOL_SIZE = 10;
    private static final int DEFAULT_MIN_IDLE = 2;
    private static final long DEFAULT_CONNECTION_TIMEOUT = 30000;
    private static final long DEFAULT_IDLE_TIMEOUT = 600000;
    private static final long DEFAULT_MAX_LIFETIME = 1800000;

    private static volatile HikariDataSource dataSource;
    private static volatile Config.DbType currentDbType;

    public static void init(String jdbcUrl, String driverClass,
                            String username, String password, Config.DbType dbType,
                            String poolProfileDir) {
        currentDbType = dbType;

        Map<String, Object> poolConfig = loadPoolConfig(dbType, poolProfileDir);

        HikariConfig hConfig = new HikariConfig();
        hConfig.setJdbcUrl(jdbcUrl);
        hConfig.setUsername(username);
        hConfig.setPassword(password);
        if (driverClass != null && !driverClass.isEmpty()) {
            hConfig.setDriverClassName(driverClass);
        }
        hConfig.setMaximumPoolSize(intVal(poolConfig, "maximumPoolSize", DEFAULT_MAX_POOL_SIZE));
        hConfig.setMinimumIdle(intVal(poolConfig, "minimumIdle", DEFAULT_MIN_IDLE));
        hConfig.setConnectionTimeout(intVal(poolConfig, "connectionTimeout", (int) DEFAULT_CONNECTION_TIMEOUT));
        hConfig.setIdleTimeout(intVal(poolConfig, "idleTimeout", (int) DEFAULT_IDLE_TIMEOUT));
        hConfig.setMaxLifetime(intVal(poolConfig, "maxLifetime", (int) DEFAULT_MAX_LIFETIME));

        if (poolConfig.containsKey("leakDetectionThreshold")) {
            hConfig.setLeakDetectionThreshold(((Number) poolConfig.get("leakDetectionThreshold")).longValue());
        }

        HikariDataSource newDs = new HikariDataSource(hConfig);
        if (dataSource != null) {
            dataSource.close();
        }
        dataSource = newDs;
        publishJdbcMetadata();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadPoolConfig(Config.DbType dbType, String poolProfileDir) {
        String name = dbType.name().toLowerCase();
        Path path = Path.of(System.getProperty("user.dir", "."), poolProfileDir, name + ".yaml");
        if (!Files.exists(path)) {
            path = Path.of(poolProfileDir, name + ".yaml");
        }
        try (InputStream in = new FileInputStream(path.toFile())) {
            Yaml yaml = new Yaml();
            return (Map<String, Object>) yaml.load(in);
        } catch (Exception e) {
            System.err.println("[WARN] 加载连接池配置失败: " + path.toAbsolutePath() + " - " + e.getMessage());
            return Map.of();
        }
    }

    private static int intVal(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private static void publishJdbcMetadata() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            System.setProperty("jdbc.test.databaseProductName", nullToEmpty(meta.getDatabaseProductName()));
            System.setProperty("jdbc.test.databaseProductVersion", nullToEmpty(meta.getDatabaseProductVersion()));
            System.setProperty("jdbc.test.driverName", nullToEmpty(meta.getDriverName()));
            System.setProperty("jdbc.test.driverVersion", nullToEmpty(meta.getDriverVersion()));
            System.setProperty("jdbc.test.jdbcUrl", nullToEmpty(meta.getURL()));
        } catch (SQLException e) {
            System.err.println("[WARN] 收集 JDBC 元数据失败: " + e.getMessage());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static DataSource getDataSource() {
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("数据源未初始化，请确保 JdbcTestExtension 已加载");
        }
        return dataSource.getConnection();
    }

    public static Config.DbType getDbType() {
        return currentDbType;
    }

    public static void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
