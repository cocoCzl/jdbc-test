package com.jdbctest.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private static final Map<Path, Config> CACHE = new HashMap<>();

    public static Config load() {
        Path path = resolveConfigPath();
        synchronized (CACHE) {
            Config cached = CACHE.get(path);
            if (cached != null) {
                return cached;
            }
        }

        try (InputStream in = new FileInputStream(path.toFile())) {
            Yaml yaml = new Yaml();
            Map<String, Object> raw = yaml.load(in);
            if (raw == null) {
                throw new IllegalStateException("配置文件为空");
            }
            Config config = parse(raw);
            synchronized (CACHE) {
                CACHE.put(path, config);
            }
            return config;
        } catch (Exception e) {
            throw new RuntimeException("加载配置文件失败: " + path, e);
        }
    }

    private static Path resolveConfigPath() {
        String configPath = System.getProperty("config.yaml");
        if (configPath == null || configPath.isBlank()) {
            configPath = System.getenv("CONFIG_PATH");
        }
        if (configPath == null || configPath.isBlank()) {
            Path localConfig = Path.of(System.getProperty("user.dir", "."), "configs", "config.yaml");
            configPath = Files.exists(localConfig) ? localConfig.toString() : "config.yaml";
        }

        Path path = Path.of(configPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir", "."), configPath);
        }
        if (!Files.exists(path)) {
            path = Path.of(configPath);
        }
        return path.toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    private static Config parse(Map<String, Object> raw) {
        Config config = new Config();

        Map<String, Object> db = (Map<String, Object>) raw.get("db");
        if (db == null) throw new IllegalStateException("配置中缺少 'db' 节");
        config.db = new Config.DbConfig();
        config.db.type = str(db.get("type"));
        config.db.adapterId = nonBlank(str(db.get("adapter_id")), config.db.type);
        config.db.assetId = nonBlank(str(db.get("asset_id")), config.db.adapterId);
        config.db.dialect = nonBlank(str(db.get("dialect")), config.db.type);
        config.db.username = str(db.get("username"));
        config.db.password = resolveEnv(str(db.get("password")));
        config.db.url = str(db.get("url"));
        config.db.driverClass = str(db.get("driver_class"));
        config.db.identifierQuote = nonBlank(str(db.get("identifier_quote")), "\"");
        config.db.expectedDatabaseProductRegex = str(db.get("expected_database_product_regex"));
        config.db.expectedDriverNameRegex = str(db.get("expected_driver_name_regex"));
        config.db.databaseVersionMin = str(db.get("database_version_min"));
        config.db.databaseVersionMax = str(db.get("database_version_max"));
        config.db.driverVersionMin = str(db.get("driver_version_min"));
        config.db.driverVersionMax = str(db.get("driver_version_max"));

        if (config.db.url.isEmpty()) throw new IllegalStateException("db.url 不能为空");

        config.ddl = new Config.DdlConfig();
        config.ddl.basePath = "ddl";
        Map<String, Object> ddl = (Map<String, Object>) raw.get("ddl");
        if (ddl != null && ddl.get("base_path") != null) config.ddl.basePath = str(ddl.get("base_path"));

        config.dml = new Config.DmlConfig();
        config.dml.basePath = "dml";
        Map<String, Object> dml = (Map<String, Object>) raw.get("dml");
        if (dml != null && dml.get("base_path") != null) config.dml.basePath = str(dml.get("base_path"));

        config.pool = new Config.PoolConfig();
        config.pool.profileDir = "pool";
        Map<String, Object> pool = (Map<String, Object>) raw.get("pool");
        if (pool != null && pool.get("profile_dir") != null) config.pool.profileDir = str(pool.get("profile_dir"));

        config.profile = new Config.ProfileConfig();
        config.profile.profileDir = "profile";
        Map<String, Object> profile = (Map<String, Object>) raw.get("profile");
        if (profile != null && profile.get("profile_dir") != null) config.profile.profileDir = str(profile.get("profile_dir"));

        config.namespace = new Config.NamespaceConfig();
        Map<String, Object> namespace = (Map<String, Object>) raw.get("namespace");
        if (namespace != null) {
            config.namespace.mode = nonBlank(str(namespace.get("mode")), "existing");
            config.namespace.name = str(namespace.get("name"));
            config.namespace.selection = nonBlank(str(namespace.get("selection")), "none");
            config.namespace.createSql = str(namespace.get("create_sql"));
            config.namespace.dropSql = str(namespace.get("drop_sql"));
            config.namespace.selectSql = str(namespace.get("select_sql"));
            config.namespace.dropOnExit = Boolean.TRUE.equals(namespace.get("drop_on_exit"));
            config.namespace.destructiveConsent = Boolean.TRUE.equals(namespace.get("destructive_consent"));
        }

        config.adapter = new Config.AdapterConfig();
        Map<String, Object> adapter = (Map<String, Object>) raw.get("adapter");
        if (adapter != null) {
            config.adapter.id = str(adapter.get("id"));
            config.adapter.trust = str(adapter.get("trust"));
            Map<String, Object> capabilities = (Map<String, Object>) adapter.get("capabilities");
            if (capabilities != null) {
                Map<String, Boolean> parsedCapabilities = new HashMap<>();
                for (var entry : capabilities.entrySet()) {
                    if (!(entry.getValue() instanceof Boolean)) {
                        throw new IllegalStateException("adapter.capabilities 必须是布尔值: " + entry.getKey());
                    }
                    parsedCapabilities.put(entry.getKey(), (Boolean) entry.getValue());
                }
                config.adapter.capabilities = Map.copyOf(parsedCapabilities);
            }
        }

        config.preflight = new Config.PreflightConfig();
        Map<String, Object> preflight = (Map<String, Object>) raw.get("preflight");
        if (preflight != null) {
            config.preflight.namespaceCreatePrivilege = str(preflight.get("namespace_create_privilege"));
            config.preflight.probeName = str(preflight.get("probe_name"));
            List<Map<String, Object>> checks = (List<Map<String, Object>>) preflight.get("privilege_checks");
            if (checks != null) {
                config.preflight.privilegeChecks = checks.stream().map(check -> {
                    Config.PrivilegeCheck parsed = new Config.PrivilegeCheck();
                    parsed.privilege = str(check.get("privilege"));
                    parsed.sql = str(check.get("sql"));
                    parsed.cleanupSql = str(check.get("cleanup_sql"));
                    return parsed;
                }).toList();
            }
        }

        config.concurrency = new Config.ConcurrencyConfig();
        Map<String, Object> concurrency = (Map<String, Object>) raw.get("concurrency");
        if (concurrency != null) {
            config.concurrency.enabled = Boolean.TRUE.equals(concurrency.get("enabled"));
            config.concurrency.threads = numVal(concurrency.get("threads"), 1).intValue();
            config.concurrency.timeout = numVal(concurrency.get("timeout"), 300).longValue();
        }

        config.execution = new Config.ExecutionConfig();
        Map<String, Object> execution = (Map<String, Object>) raw.get("execution");
        if (execution != null) config.execution.mode = str(execution.get("mode"));

        config.report = new Config.ReportConfig();
        Map<String, Object> report = (Map<String, Object>) raw.get("report");
        if (report != null) {
            config.report.outputDir = str(report.get("output_dir"));
            config.report.format = (List<String>) report.get("format");
        }

        config.testFilter = new Config.TestFilterConfig();
        Map<String, Object> testFilter = (Map<String, Object>) raw.get("test_filter");
        if (testFilter != null) {
            config.testFilter.includeTests = (List<String>) testFilter.get("include_tests");
            config.testFilter.excludeTests = (List<String>) testFilter.get("exclude_tests");
            config.testFilter.timeout = numVal(testFilter.get("timeout"), 300).longValue();
        }

        return config;
    }

    static void resetForTesting() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static Number numVal(Object v, int defaultVal) {
        if (v instanceof Number n) return n;
        return defaultVal;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String resolveEnv(String value) {
        if (value == null) return null;
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            m.appendReplacement(sb, Matcher.quoteReplacement(envValue != null ? envValue : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
