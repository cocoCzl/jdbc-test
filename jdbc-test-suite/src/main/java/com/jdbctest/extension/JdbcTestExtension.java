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

public class JdbcTestExtension implements BeforeAllCallback, AfterAllCallback, AfterEachCallback,
        ParameterResolver, ExecutionCondition {

    private static final ExtensionContext.Namespace ROOT_NAMESPACE =
            ExtensionContext.Namespace.create(JdbcTestExtension.class);
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

        for (String ddlFile : annotation.ddl()) {
            Path path = resolvePath(config.ddl.basePath, adapterId, ddlFile);
            executeSqlFile(context, path);
        }

        for (String dmlFile : annotation.dml()) {
            Path path = resolvePath(config.dml.basePath, adapterId, dmlFile);
            executeSqlFile(context, path);
        }

    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (JdbcContext.getDataSource() != null) {
            try (Connection connection = JdbcContext.getConnection()) { JdbcContext.objects().cleanup(connection); }
        }
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
            String reason = String.format("Adapter explicitly declares capability disabled: %s",
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
                config.pool.profileDir, java.util.Objects.toString(config.namespace.objectPrefix, ""));
    }

    private void registerShutdownHook(ExtensionContext context) {
        context.getRoot().getStore(ROOT_NAMESPACE)
                .computeIfAbsent("jdbcContext", key -> new JdbcContextResource(),
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
        if (basePath != null && basePath.startsWith("classpath:")) {
            return Paths.get(basePath + "/" + dbType + "/" + fileName);
        }
        Path base = Paths.get(basePath);
        if (!base.isAbsolute()) {
            base = Paths.get(System.getProperty("user.dir", "."), basePath);
        }
        return base.resolve(dbType).resolve(fileName);
    }

    private void executeSqlFile(ExtensionContext context, Path path) throws IOException {
        String content;
        if (Files.exists(path)) {
            content = Files.readString(path);
        } else {
            String name = path.toString().replace('\\', '/');
            if (!name.startsWith("classpath:")) {
                throw new RuntimeException("SQL 文件不存在: " + path.toAbsolutePath());
            }
            String resource = name.substring("classpath:".length());
            try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new RuntimeException("SQL classpath 资源不存在: " + resource);
                }
                content = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        boolean hasGoBatches = content.matches("(?s).*\\bGO\\b.*");
        boolean hasSlashBatches = !hasGoBatches && content.matches("(?s).*\\n/\\s*\\n.*");
        String[] statements = hasGoBatches ? splitByGo(content)
                : hasSlashBatches ? splitBySlash(content)
                : splitBySemicolon(content);

        try (Connection conn = JdbcContext.getConnection();
             Statement stmt = conn.createStatement()) {

            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.isEmpty()) continue;

                try {
                    executeManagedStatement(stmt, JdbcContext.objects(), trimmed);

                } catch (SQLException e) {
                    throw new RuntimeException("SQL initialization failed: " + path.getFileName(), e);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("执行 SQL 文件时连接异常: " + path, e);
        }

    }

    static void executeManagedStatement(Statement statement, TestObjects objects, String sql) throws SQLException {
        String executable = SqlSplitter.withoutLeadingComments(sql);
        if (executable.isBlank()) return;
        if (executable.matches("(?is)^CREATE\\s+.*")) {
            objects.create(statement, executable);
        } else if (executable.matches("(?is)^DROP\\s+.*")) {
            throw new IllegalStateException("Unowned resource DROP is forbidden");
        } else {
            statement.execute(objects.sql(sql));
        }
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
            String trimmed = SqlSplitter.withoutLeadingComments(raw[i]).trim().toUpperCase(Locale.ENGLISH);
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

    private static class JdbcContextResource implements AutoCloseable {
        @Override
        public void close() {
            JdbcContext.shutdown();
            initialized = false;
            initializedConfigKey = null;
        }
    }
}
