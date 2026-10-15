package com.jdbctest.extension;

import com.jdbctest.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.yaml.snakeyaml.Yaml;

public final class JdbcContext {

  private static final Pattern SAFE_NAMESPACE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");
  private static volatile DataSource dataSource;
  private static volatile String currentAdapterId;
  private static volatile Config.NamespaceConfig namespace;
  private static volatile Driver isolatedDriver;
  private static volatile TestObjects objects;

  private JdbcContext() {
  }

  public static ClassLoader driverClassLoader() {
    return isolatedDriver == null ? Thread.currentThread().getContextClassLoader()
        : isolatedDriver.getClass().getClassLoader();
  }

  public static Connection scopeConnection(Connection connection) throws SQLException {
    return ScopedDataSource.scope(connection, namespace);
  }

  public static void init(Config config) {
    FeatureProfile.load(config.db.dataSources.capabilities(
        config.adapter == null ? null : config.adapter.capabilities));
    lastCleanup = Map.of("status", "UNKNOWN", "phase", "INITIALIZING");
    objects = new TestObjects(config);
    currentAdapterId = config.db.adapterId;
    namespace = config.namespace;
    DataSource replacement = config.db.isDriverManagerMode()
        ? (isolatedDriver == null
        ? new DriverManagerDataSource(config.db.getDriverClass(), config.db.getJdbcUrl(),
        config.db.username,
        config.db.password, config.db.properties)
        : new DriverManagerDataSource(isolatedDriver, config.db.getJdbcUrl(), config.db.username,
            config.db.password, config.db.properties))
        : createHikariDataSource(config);
    closeDataSource(dataSource);
    dataSource = replacement;
    // Inspect the raw connection before auto-namespace creation or scoped fixture access.
    publishAndValidateMetadata(config);
    provisionNamespace(config);
    dataSource = new ScopedDataSource(dataSource, config);
  }

  private static HikariDataSource createHikariDataSource(Config config) {
    Map<String, Object> poolConfig = loadPoolConfig(config.db.assetId, config.pool.profileDir);
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.db.getJdbcUrl());
    hikari.setUsername(config.db.username);
    hikari.setPassword(config.db.password);
    if (!config.db.getDriverClass().isEmpty()) {
      hikari.setDriverClassName(config.db.getDriverClass());
    }
    config.db.properties.forEach(hikari::addDataSourceProperty);
    hikari.setMaximumPoolSize(intVal(poolConfig, "maximumPoolSize", 10));
    hikari.setMinimumIdle(intVal(poolConfig, "minimumIdle", 2));
    hikari.setConnectionTimeout(intVal(poolConfig, "connectionTimeout", 30000));
    hikari.setIdleTimeout(intVal(poolConfig, "idleTimeout", 600000));
    hikari.setMaxLifetime(intVal(poolConfig, "maxLifetime", 1800000));
    if (poolConfig.containsKey("leakDetectionThreshold")) {
      hikari.setLeakDetectionThreshold(
          ((Number) poolConfig.get("leakDetectionThreshold")).longValue());
    }
    return new HikariDataSource(hikari);
  }

  private static void closeDataSource(DataSource source) {
    if (source instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception e) {
        throw new IllegalStateException("关闭数据源失败", e);
      }
    }
  }

  private static void provisionNamespace(Config config) {
    if (namespace == null) {
      return;
    }
    String name = namespace.name == null ? "" : namespace.name;
    if (!name.isEmpty() && !SAFE_NAMESPACE.matcher(name).matches()) {
      throw new IllegalStateException("测试命名空间名称不安全: " + name);
    }
    if ("auto".equalsIgnoreCase(namespace.mode)) {
      if (!namespace.destructiveConsent) {
        throw new IllegalStateException(
            "未确认 namespace.destructive_consent，拒绝创建测试命名空间");
      }
      if (name.isEmpty() || namespace.createSql == null || namespace.createSql.isBlank()) {
        throw new IllegalStateException("自动测试命名空间缺少 name/create_sql");
      }
      objects.recordNamespaceProvisioning("CREATING");
      try {
        executeRaw(expand(namespace.createSql, config));
        objects.recordNamespaceProvisioning("CREATED_RETAINED");
      } catch (RuntimeException e) {
        objects.recordNamespaceProvisioning("UNKNOWN");
        String privilege =
            config.preflight == null ? "" : config.preflight.namespaceCreatePrivilege;
        throw missingPrivilege(privilege == null || privilege.isBlank()
            ? "CREATE TEST NAMESPACE" : privilege, e);
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

  private static volatile Map<String, Object> metadataEvidence = Map.of("status", "UNKNOWN");

  public static Map<String, Object> metadataEvidence() {
    return metadataEvidence;
  }

  private static void publishAndValidateMetadata(Config config) {
    Map<String, Object> evidence = new java.util.LinkedHashMap<>();
    evidence.put("status", "UNKNOWN");
    metadataEvidence = Map.copyOf(evidence);
    try (Connection connection = dataSource.getConnection()) {
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
      evidence.put("databaseProductName", product);
      evidence.put("databaseProductVersion", productVersion);
      evidence.put("driverName", driver);
      evidence.put("driverVersion", driverVersion);
      metadataEvidence = Map.copyOf(evidence);
      validateRegex("数据库产品", product, config.db.expectedDatabaseProductRegex);
      validateRegex("JDBC 驱动", driver, config.db.expectedDriverNameRegex);
      validateVersion("数据库版本", productVersion, config.db.databaseVersionMin,
          config.db.databaseVersionMax);
      validateVersion("驱动版本", driverVersion, config.db.driverVersionMin,
          config.db.driverVersionMax);
      if (config.db.isDialect("oracle")
          && OracleDriverDependencies.appliesTo(config.db.getDriverClass())) {
        OracleDriverDependencies.preflight(connection, driverClassLoader(),
            config.preflight != null && config.preflight.requireSqlxml
                && FeatureProfile.supports("sqlxml"));
      }
      evidence.put("status", "MATCHED");
      metadataEvidence = Map.copyOf(evidence);
    } catch (RuntimeException error) {
      evidence.put("status", "REJECTED");
      metadataEvidence = Map.copyOf(evidence);
      throw new IllegalStateException("ADAPTER_PREFLIGHT_FAILED", error);
    } catch (SQLException e) {
      throw new IllegalStateException("JDBC 元数据预检失败", e);
    }
  }

  private static void validateRegex(String label, String observed, String regex) {
    if (regex != null && !regex.isBlank() && !Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
        .matcher(observed).find()) {
      throw new IllegalStateException(label + "超出适配范围: " + observed);
    }
  }

  private static void validateVersion(String label, String observed, String minimum,
      String maximum) {
    if ((minimum == null || minimum.isBlank()) && (maximum == null || maximum.isBlank())) {
      return;
    }
    var actual = version(observed);
    if (minimum != null && !minimum.isBlank() && compare(actual, version(minimum)) < 0) {
      throw new IllegalStateException(label + "低于适配范围: " + observed);
    }
    if (maximum != null && !maximum.isBlank() && compare(actual, version(maximum)) > 0) {
      throw new IllegalStateException(label + "高于适配范围: " + observed);
    }
  }

  private static java.math.BigInteger[] version(String value) {
    var matcher = Pattern.compile("[0-9]+(?:\\.[0-9]+)*").matcher(value == null ? "" : value);
    if (!matcher.find()) {
      throw new IllegalStateException("ADAPTER_VERSION_UNPARSEABLE");
    }
    return java.util.Arrays.stream(matcher.group().split("\\."))
        .map(java.math.BigInteger::new).toArray(java.math.BigInteger[]::new);
  }

  private static int compare(java.math.BigInteger[] left, java.math.BigInteger[] right) {
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      var l = i < left.length ? left[i] : java.math.BigInteger.ZERO;
      var r = i < right.length ? right[i] : java.math.BigInteger.ZERO;
      int result = l.compareTo(r);
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> loadPoolConfig(String assetId, String poolProfileDir) {
    if (poolProfileDir.startsWith("classpath:")) {
      String resource =
          "/" + poolProfileDir.substring("classpath:".length()) + "/" + assetId + ".yaml";
      try (InputStream input = JdbcContext.class.getResourceAsStream(resource)) {
        if (input == null) {
          throw new IllegalArgumentException("Missing packaged pool profile");
        }
        return (Map<String, Object>) new Yaml().load(input);
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("Cannot read packaged pool profile", exception);
      }
    }
    Path path = Path.of(System.getProperty("user.dir", "."), poolProfileDir, assetId + ".yaml");
    if (!Files.exists(path)) {
      path = Path.of(poolProfileDir, assetId + ".yaml");
    }
    try (InputStream in = new FileInputStream(path.toFile())) {
      return (Map<String, Object>) new Yaml().load(in);
    } catch (Exception e) {
      System.err.println(
          "[WARN] 加载连接池配置失败: " + path.toAbsolutePath() + " - " + e.getMessage());
      return Map.of();
    }
  }

  private static int intVal(Map<String, Object> map, String key, int defaultValue) {
    Object value = map.get(key);
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  public static TestObjects objects() {
    if (objects == null) {
      throw new IllegalStateException("Execution scope not initialized");
    }
    return objects;
  }

  public static String sql(String template) {
    return objects().sql(template);
  }

  public static String objectName(String logical) {
    return objects().name(logical);
  }

  public static DataSource getDataSource() {
    return dataSource;
  }

  public static Connection getConnection() throws SQLException {
    if (dataSource == null) {
      throw new IllegalStateException("数据源未初始化");
    }
    Connection connection = dataSource.getConnection();
    return connection;
  }

  private static void executeRaw(String sql) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("测试命名空间操作失败", e);
    }
  }

  private static String expand(String template, Config config) {
    String probe =
        config == null || config.preflight == null ? "jdbc_priv_probe" : config.preflight.probeName;
    return template
        .replace("{namespace}", namespace == null || namespace.name == null ? "" : namespace.name)
        .replace("{probe}", probe == null || probe.isBlank() ? "jdbc_priv_probe" : probe);
  }

  public static String getAdapterId() {
    return currentAdapterId;
  }

  private static volatile Map<String, Object> lastCleanup = Map.of("status", "UNKNOWN",
      "phase", "NOT_RECORDED");

  public static Map<String, Object> cleanupEvidence() {
    return lastCleanup;
  }

  public static void cleanupObjects() {
    if (objects == null || dataSource == null) {
      return;
    }
    try (Connection connection = getConnection()) {
      objects.cleanup(connection);
    } catch (SQLException exception) {
      objects.cleanupUnavailable(exception);
    }
    lastCleanup = objects.cleanupEvidence();
  }

  public static void shutdown() {
    if (dataSource == null) {
      return;
    }
    try {
      cleanupObjects();
      closeDataSource(dataSource);
    } finally {
      dataSource = null;
      namespace = null;
      isolatedDriver = null;
    }
  }

  public static void setIsolatedDriver(Driver driver) {
    isolatedDriver = java.util.Objects.requireNonNull(driver, "driver");
  }

  private static String json(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
