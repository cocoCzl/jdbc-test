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
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Pattern;

public final class JdbcContext {

    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");
    private static volatile HikariDataSource dataSource;
    private static volatile String currentAdapterId;
    private static volatile Config.NamespaceConfig namespace;

    private JdbcContext() {}

    public static void init(Config config) {
        currentAdapterId = config.db.adapterId;
        namespace = config.namespace;
        Map<String, Object> poolConfig = loadPoolConfig(config.db.adapterId, config.pool.profileDir);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.db.getJdbcUrl());
        hikari.setUsername(config.db.username);
        hikari.setPassword(config.db.password);
        if (!config.db.getDriverClass().isEmpty()) {
            hikari.setDriverClassName(config.db.getDriverClass());
        }
        hikari.setMaximumPoolSize(intVal(poolConfig, "maximumPoolSize", 10));
        hikari.setMinimumIdle(intVal(poolConfig, "minimumIdle", 2));
        hikari.setConnectionTimeout(intVal(poolConfig, "connectionTimeout", 30000));
        hikari.setIdleTimeout(intVal(poolConfig, "idleTimeout", 600000));
        hikari.setMaxLifetime(intVal(poolConfig, "maxLifetime", 1800000));
        if (poolConfig.containsKey("leakDetectionThreshold")) {
            hikari.setLeakDetectionThreshold(((Number) poolConfig.get("leakDetectionThreshold")).longValue());
        }

        HikariDataSource replacement = new HikariDataSource(hikari);
        if (dataSource != null) dataSource.close();
        dataSource = replacement;
        provisionNamespace(config);
        runPrivilegeChecks(config);
        publishAndValidateMetadata(config);
    }

    private static void provisionNamespace(Config config) {
        if (namespace == null) return;
        String name = namespace.name == null ? "" : namespace.name;
        if (!name.isEmpty() && !SAFE_NAMESPACE.matcher(name).matches()) {
            throw new IllegalStateException("测试命名空间名称不安全: " + name);
        }
        if ("auto".equalsIgnoreCase(namespace.mode)) {
            if (!namespace.destructiveConsent) {
                throw new IllegalStateException("未确认 namespace.destructive_consent，拒绝创建测试命名空间");
            }
            if (name.isEmpty() || namespace.createSql == null || namespace.createSql.isBlank()) {
                throw new IllegalStateException("自动测试命名空间缺少 name/create_sql");
            }
            try {
                executeRaw(expand(namespace.createSql, config));
            } catch (RuntimeException e) {
                String privilege = config.preflight == null ? "" : config.preflight.namespaceCreatePrivilege;
                throw missingPrivilege(privilege == null || privilege.isBlank()
                        ? "CREATE TEST NAMESPACE" : privilege, e);
            }
        }
    }

    private static void runPrivilegeChecks(Config config) {
        if (config.preflight == null || config.preflight.privilegeChecks == null) return;
        for (Config.PrivilegeCheck check : config.preflight.privilegeChecks) {
            if (check.sql == null || check.sql.isBlank()) continue;
            boolean attempted = false;
            try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
                attempted = true;
                statement.execute(expand(check.sql, config));
            } catch (SQLException | RuntimeException e) {
                throw missingPrivilege(check.privilege, e);
            } finally {
                if (attempted && check.cleanupSql != null && !check.cleanupSql.isBlank()) {
                    try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
                        statement.execute(expand(check.cleanupSql, config));
                    } catch (Exception ignored) {
                        // Best-effort cleanup for a preflight probe.
                    }
                }
            }
        }
    }

    private static IllegalStateException missingPrivilege(String privilege, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage() == null ? error.getMessage() : cause.getMessage();
        System.err.println("[JDBC_PREFLIGHT_ISSUE] {\"kind\":\"missing_privilege\",\"privilege\":\""
                + json(privilege) + "\",\"message\":\"" + json(message) + "\"}");
        return new IllegalStateException("最小权限预检失败 [" + privilege + "]: " + message, error);
    }

    private static void publishAndValidateMetadata(Config config) {
        try (Connection connection = getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = nullToEmpty(meta.getDatabaseProductName());
            String productVersion = nullToEmpty(meta.getDatabaseProductVersion());
            String driver = nullToEmpty(meta.getDriverName());
            String driverVersion = nullToEmpty(meta.getDriverVersion());
            System.setProperty("jdbc.test.databaseProductName", product);
            System.setProperty("jdbc.test.databaseProductVersion", productVersion);
            System.setProperty("jdbc.test.driverName", driver);
            System.setProperty("jdbc.test.driverVersion", driverVersion);
            System.setProperty("jdbc.test.jdbcUrl", "redacted");
            System.setProperty("jdbc.test.adapterId", config.db.adapterId);
            System.setProperty("jdbc.test.namespace", namespace == null ? "" : namespace.name);
            validateRegex("数据库产品", product, config.db.expectedDatabaseProductRegex);
            validateRegex("JDBC 驱动", driver, config.db.expectedDriverNameRegex);
            validateVersion("数据库版本", productVersion, config.db.databaseVersionMin, config.db.databaseVersionMax);
            validateVersion("驱动版本", driverVersion, config.db.driverVersionMin, config.db.driverVersionMax);
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC 元数据预检失败", e);
        }
    }

    private static void validateRegex(String label, String observed, String regex) {
        if (regex != null && !regex.isBlank() && !Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(observed).find()) {
            throw new IllegalStateException(label + "超出适配范围: " + observed);
        }
    }

    private static void validateVersion(String label, String observed, String minimum, String maximum) {
        int[] actual = version(observed);
        if (minimum != null && !minimum.isBlank() && compare(actual, version(minimum)) < 0) {
            throw new IllegalStateException(label + "低于适配范围: " + observed);
        }
        if (maximum != null && !maximum.isBlank() && compare(actual, version(maximum)) > 0) {
            throw new IllegalStateException(label + "高于适配范围: " + observed);
        }
    }

    private static int[] version(String value) {
        String[] parts = value == null ? new String[0] : value.split("[^0-9]+");
        int[] result = new int[] {0, 0, 0, 0};
        int index = 0;
        for (String part : parts) {
            if (!part.isEmpty() && index < result.length) result[index++] = Integer.parseInt(part);
        }
        return result;
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadPoolConfig(String adapterId, String poolProfileDir) {
        Path path = Path.of(System.getProperty("user.dir", "."), poolProfileDir, adapterId + ".yaml");
        if (!Files.exists(path)) path = Path.of(poolProfileDir, adapterId + ".yaml");
        try (InputStream in = new FileInputStream(path.toFile())) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            System.err.println("[WARN] 加载连接池配置失败: " + path.toAbsolutePath() + " - " + e.getMessage());
            return Map.of();
        }
    }

    private static int intVal(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    public static DataSource getDataSource() { return dataSource; }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) throw new IllegalStateException("数据源未初始化");
        Connection connection = dataSource.getConnection();
        applyNamespace(connection);
        return connection;
    }

    private static void applyNamespace(Connection connection) throws SQLException {
        if (namespace == null || namespace.name == null || namespace.name.isBlank()) return;
        switch (namespace.selection == null ? "none" : namespace.selection.toLowerCase()) {
            case "schema" -> connection.setSchema(namespace.name);
            case "catalog" -> connection.setCatalog(namespace.name);
            case "sql" -> {
                if (namespace.selectSql != null && !namespace.selectSql.isBlank()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(expand(namespace.selectSql, null));
                    }
                }
            }
            default -> { }
        }
    }

    private static void executeRaw(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("测试命名空间操作失败", e);
        }
    }

    private static String expand(String template, Config config) {
        String probe = config == null || config.preflight == null ? "jdbc_priv_probe" : config.preflight.probeName;
        return template
                .replace("{namespace}", namespace == null || namespace.name == null ? "" : namespace.name)
                .replace("{probe}", probe == null || probe.isBlank() ? "jdbc_priv_probe" : probe);
    }

    public static String getAdapterId() { return currentAdapterId; }

    public static void shutdown() {
        if (dataSource == null) return;
        if (namespace != null && namespace.dropOnExit && namespace.destructiveConsent
                && namespace.dropSql != null && !namespace.dropSql.isBlank()) {
            try {
                executeRaw(expand(namespace.dropSql, null));
            } catch (RuntimeException e) {
                String message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                System.err.println("[JDBC_CLEANUP_ISSUE] {\"asset_type\":\"namespace\",\"asset_name\":\""
                        + json(namespace.name) + "\",\"message\":\"" + json(message) + "\"}");
            }
        }
        dataSource.close();
        dataSource = null;
        namespace = null;
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
