package com.jdbctest.extension;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
import org.junit.jupiter.api.extension.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JdbcTestExtension implements BeforeAllCallback, AfterAllCallback, AfterEachCallback,
        ParameterResolver, ExecutionCondition {

    private static final ExtensionContext.Namespace ROOT_NAMESPACE =
            ExtensionContext.Namespace.create(JdbcTestExtension.class);
    private static final Pattern CREATE_TABLE_PATTERN =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\S+)",
                    Pattern.CASE_INSENSITIVE);
    private static final int SQL_ERROR_TRUNCATE_LENGTH = 200;
    private static volatile boolean initialized = false;
    private static volatile String initializedConfigKey;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Config config = ensureInitialized();
        registerShutdownHook(context);

        Class<?> testClass = context.getRequiredTestClass();
        UseSqlScripts annotation = testClass.getAnnotation(UseSqlScripts.class);
        if (annotation == null) {
            return;
        }

        String adapterId = config.db.assetId;
        List<String> createdTables = new ArrayList<>();

        for (String ddlFile : annotation.ddl()) {
            Path path = resolvePath(config.ddl.basePath, adapterId, ddlFile);
            List<String> tables = executeSqlFile(context, path);
            createdTables.addAll(tables);
        }

        for (String dmlFile : annotation.dml()) {
            Path path = resolvePath(config.dml.basePath, adapterId, dmlFile);
            executeSqlFile(context, path);
        }

        context.getStore(ExtensionContext.Namespace.create(testClass))
                .put("createdTables", createdTables);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Config config = ensureInitialized();
        Class<?> testClass = context.getRequiredTestClass();
        UseSqlScripts annotation = testClass.getAnnotation(UseSqlScripts.class);
        if (annotation != null) {
            for (String cleanupFile : annotation.cleanup()) {
                Path path = resolvePath(config.ddl.basePath, config.db.assetId, cleanupFile);
                executeSqlFile(context, path);
            }
        }

        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) context.getStore(
            ExtensionContext.Namespace.create(testClass)).get("createdTables");

        if (tables == null || tables.isEmpty()) {
            return;
        }

        String quote = config.db.getIdentifierQuote();

        try (Connection conn = JdbcContext.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = tables.size() - 1; i >= 0; i--) {
                String table = tables.get(i);
                String quoted = quote + table.replace(quote, quote + quote) + quote;
                try {
                    if (config.db.isDialect("oracle")) {
                        stmt.execute("DROP TABLE " + table.toUpperCase(Locale.ENGLISH)
                                + " CASCADE CONSTRAINTS PURGE");
                    } else {
                        stmt.execute("DROP TABLE IF EXISTS " + quoted);
                    }
                } catch (SQLException e) {
                    System.err.println("[WARN] 清理表 " + table + " 失败: " + e.getMessage());
                    System.err.println("[JDBC_CLEANUP_ISSUE] " + cleanupIssueJson(testClass.getName(), table, e));
                }
            }
        }
    }

    private String cleanupIssueJson(String className, String table, SQLException e) {
        return "{"
                + "\"source_class\":\"" + jsonEscape(className) + "\","
                + "\"asset_type\":\"table\","
                + "\"asset_name\":\"" + jsonEscape(table) + "\","
                + "\"message\":\"" + jsonEscape(e.getMessage()) + "\","
                + "\"sql_state\":\"" + jsonEscape(e.getSQLState()) + "\","
                + "\"error_code\":" + e.getErrorCode()
                + "}";
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        ensureInitialized();

        Class<?> testClass = context.getRequiredTestClass();

        var testMethod = context.getTestMethod();
        if (testMethod.isPresent()) {
            RequiresFeature methodAnnotation = testMethod.get().getAnnotation(RequiresFeature.class);
            if (methodAnnotation != null) {
                return checkFeatures(methodAnnotation);
            }
        }

        RequiresFeature classAnnotation = testClass.getAnnotation(RequiresFeature.class);
        if (classAnnotation != null) {
            return checkFeatures(classAnnotation);
        }

        return ConditionEvaluationResult.enabled("无需特殊能力");
    }

    private ConditionEvaluationResult checkFeatures(RequiresFeature annotation) {
        List<String> missing = new ArrayList<>();
        for (String feature : annotation.value()) {
            if (!FeatureProfile.supports(feature)) {
                missing.add(feature);
            }
        }
        if (!missing.isEmpty()) {
            String reason = String.format("数据库不支持: %s",
                    String.join(", ", missing));
            return ConditionEvaluationResult.disabled(reason);
        }
        return ConditionEvaluationResult.enabled("所需能力全部支持");
    }

    private Config ensureInitialized() {
        Config config = ConfigLoader.load();
        String configKey = configKey(config);

        if (!initialized || !configKey.equals(initializedConfigKey) || JdbcContext.getDataSource() == null) {
            synchronized (JdbcTestExtension.class) {
                if (!initialized || !configKey.equals(initializedConfigKey) || JdbcContext.getDataSource() == null) {
                    JdbcContext.init(config);
                    FeatureProfile.load(config.adapter == null ? null : config.adapter.capabilities);
                    initialized = true;
                    initializedConfigKey = configKey;
                    return config;
                }
            }
        }
        return config;
    }

    private String configKey(Config config) {
        return String.join("\u0000",
                config.db.adapterId,
                config.db.getJdbcUrl(),
                config.db.getDriverClass(),
                config.db.username,
                config.pool.profileDir);
    }

    private void registerShutdownHook(ExtensionContext context) {
        context.getRoot().getStore(ROOT_NAMESPACE)
                .getOrComputeIfAbsent("jdbcContext", key -> new JdbcContextResource(),
                        JdbcContextResource.class);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == Connection.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        try {
            registerShutdownHook(extensionContext);
            Connection conn = JdbcContext.getConnection();
            extensionContext.getStore(ExtensionContext.Namespace.create(extensionContext.getRequiredTestClass()))
                    .put(extensionContext.getRequiredTestMethod().getName() + "_conn", conn);
            return conn;
        } catch (SQLException e) {
            throw new ParameterResolutionException("无法获取数据库连接", e);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Connection conn = (Connection) context.getStore(ExtensionContext.Namespace.create(context.getRequiredTestClass()))
                .remove(context.getRequiredTestMethod().getName() + "_conn");
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    private Path resolvePath(String basePath, String dbType, String fileName) {
        Path p = Paths.get(fileName);
        if (p.isAbsolute() || fileName.contains("/") || fileName.contains("\\")) {
            return Paths.get(fileName);
        }
        Path base = Paths.get(basePath);
        if (!base.isAbsolute()) {
            base = Paths.get(System.getProperty("user.dir", "."), basePath);
        }
        return base.resolve(dbType).resolve(fileName);
    }

    private List<String> executeSqlFile(ExtensionContext context, Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new RuntimeException("SQL 文件不存在: " + path.toAbsolutePath());
        }

        String content = Files.readString(path);
        boolean hasGoBatches = content.matches("(?s).*\\bGO\\b.*");
        boolean hasSlashBatches = !hasGoBatches && content.matches("(?s).*\\n/\\s*\\n.*");
        String[] statements = hasGoBatches ? splitByGo(content)
                : hasSlashBatches ? splitBySlash(content)
                : splitBySemicolon(content);
        List<String> tables = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (Connection conn = JdbcContext.getConnection();
             Statement stmt = conn.createStatement()) {

            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.isEmpty()) continue;

                try {
                    stmt.execute(trimmed);

                    Matcher m = CREATE_TABLE_PATTERN.matcher(trimmed);
                    if (m.find()) {
                        String tableName = m.group(1);
                        tableName = tableName.replaceAll("[\"'`]", "");
                        if (tableName.contains(".")) {
                            tableName = tableName.substring(tableName.lastIndexOf('.') + 1);
                        }
                        tables.add(tableName);
                    }
                } catch (SQLException e) {
                    if (isSoftError(e, trimmed)) {
                        String testName = context.getRequiredTestClass().getSimpleName();
                        String snippet = trimmed.substring(0, Math.min(SQL_ERROR_TRUNCATE_LENGTH, trimmed.length()));
                        System.err.printf("[%s] 忽略可恢复错误: %s%n  SQL: %s%n", testName, e.getMessage(), snippet);
                    } else {
                        String testName = context.getRequiredTestClass().getSimpleName();
                        String snippet = trimmed.substring(0, Math.min(SQL_ERROR_TRUNCATE_LENGTH, trimmed.length()));
                        String msg = String.format("[%s] SQL 执行异常: %s%n  SQL: %s", testName, e.getMessage(), snippet);
                        System.err.println(msg);
                        errors.add(msg);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("执行 SQL 文件时连接异常: " + path, e);
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(String.format(
                    "SQL 文件 %s 执行失败 (%d 条语句出错)", path.getFileName(), errors.size()));
        }

        return tables;
    }

    private boolean isSoftError(SQLException e, String sql) {
        String upper = sql.trim().toUpperCase();
        boolean isDrop = upper.startsWith("DROP") && !upper.startsWith("DROP DATABASE");
        boolean isCreateOrReplace = upper.startsWith("CREATE OR REPLACE");
        int code = e.getErrorCode();

        if (isDrop) {
            return code == 942   // Oracle: ORA-00942 表或视图不存在
                || code == 1051  // MySQL: Table doesn't exist
                || code == 1418  // Oracle: ORA-01418 指定的索引不存在
                || code == 4043  // Oracle: ORA-04043 object does not exist
                || code == 1305; // MySQL: FUNCTION does not exist
        }
        if (isCreateOrReplace) {
            return code == 955;  // Oracle: ORA-00955 名称已由现有对象使用（同名不同类对象冲突）
        }
        return false;
    }

    static String[] splitBySemicolon(String content) {
        return SqlSplitter.split(content, new SqlSplitter.Config() {
            @Override
            public boolean isBatchSeparator(int i, char c, char next, SqlSplitter.State ctx, CharSequence sql) {
                return c == ';' && ctx.beginDepth == 0 && !ctx.inDollarQuote;
            }

            @Override
            public int skipAfterMatch() { return 0; }
        });
    }

    static String[] splitByGo(String content) {
        return SqlSplitter.split(content, new SqlSplitter.Config() {
            @Override
            public boolean isBatchSeparator(int i, char c, char next, SqlSplitter.State ctx, CharSequence sql) {
                return (c == 'G' || c == 'g') && (next == 'O' || next == 'o')
                        && (i == 0 || Character.isWhitespace(sql.charAt(i - 1)))
                        && (i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2)));
            }

            @Override
            public int skipAfterMatch() { return 2; }
        });
    }

    static String[] splitBySlash(String content) {
        String[] raw = SqlSplitter.split(content, new SqlSplitter.Config() {
            @Override
            public boolean isBatchSeparator(int i, char c, char next, SqlSplitter.State ctx, CharSequence sql) {
                return c == '/' && (i == 0 || sql.charAt(i - 1) == '\n')
                        && (i + 1 >= sql.length() || Character.isWhitespace(sql.charAt(i + 1)));
            }

        });
        for (int i = 0; i < raw.length; i++) {
            String trimmed = raw[i].trim().toUpperCase(Locale.ENGLISH);
            // Preserve trailing ; for PL/SQL blocks and function/procedure/package/trigger definitions
            if (!trimmed.startsWith("BEGIN") && !trimmed.startsWith("DECLARE")
                    && !trimmed.startsWith("CREATE OR REPLACE FUNCTION")
                    && !trimmed.startsWith("CREATE OR REPLACE PROCEDURE")
                    && !trimmed.startsWith("CREATE OR REPLACE PACKAGE")
                    && !trimmed.startsWith("CREATE OR REPLACE TRIGGER")
                    && !trimmed.startsWith("CREATE FUNCTION")
                    && !trimmed.startsWith("CREATE PROCEDURE")
                    && !trimmed.startsWith("CREATE PACKAGE")
                    && !trimmed.startsWith("CREATE TRIGGER")) {
                raw[i] = raw[i].replaceAll(";\\s*$", "");
            }
        }
        return raw;
    }

    private static class JdbcContextResource implements ExtensionContext.Store.CloseableResource {
        @Override
        public void close() {
            JdbcContext.shutdown();
            initialized = false;
            initializedConfigKey = null;
        }
    }
}
