package com.jdbctest.extension;

import com.jdbctest.config.Config;
import com.jdbctest.config.ConfigLoader;
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
import org.junit.jupiter.api.extension.*;

public class JdbcTestExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback,
    AfterEachCallback, InvocationInterceptor, AfterTestExecutionCallback, ParameterResolver,
    ExecutionCondition {

    private static final ExtensionContext.Namespace ROOT_NAMESPACE =
        ExtensionContext.Namespace.create(JdbcTestExtension.class);
    private static volatile boolean initialized = false;
    private static volatile String initializedConfigKey;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        ensureInitialized();
        registerShutdownHook(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        enterPhase(context, "PREPARATION");
        Config config = ensureInitialized();
        if (requiredFeatures(context.getRequiredTestClass(), context.getRequiredTestMethod())
            .contains("sqlxml") && OracleDriverDependencies.appliesTo(config.db.getDriverClass())
            && !OracleDriverDependencies.xmlAvailable(JdbcContext.driverClassLoader())) {
            throw new ExecutionPrerequisiteException(
                "ORACLE_XML_MISSING: SQLXML requires xdb.jar and xmlparserv2.jar", null);
        }
        UseSqlScripts annotation = context.getRequiredTestMethod().getAnnotation(UseSqlScripts.class);
        if (annotation == null) {
            annotation = context.getRequiredTestClass().getAnnotation(UseSqlScripts.class);
        }
        if (annotation == null) {
            return;
        }
        var store = context.getParent().orElseThrow().getStore(ROOT_NAMESPACE);
        for (String ddl : annotation.ddl()) {
            prepare(context, store, resolvePath(config.ddl.basePath, config.db.assetId, ddl));
        }
        for (String dml : annotation.dml()) {
            prepare(context, store, resolvePath(config.dml.basePath, config.db.assetId, dml));
        }
        for (String check : annotation.checks()) {
            try (Connection connection = fixtureConnection();
                Statement statement = connection.createStatement()) {
                statement.execute(JdbcContext.sql(check));
            } catch (SQLException error) {
                throw new ExecutionPrerequisiteException("FIXTURE_SHAPE_UNAVAILABLE", error);
            }
        }
    }

    private void prepare(ExtensionContext context, ExtensionContext.Store store, Path path) {
        Object previous = store.get(path.toString());
        if (previous instanceof PreparationFailure failure) {
            throw new ExecutionPrerequisiteException("FIXTURE_BLOCKED: " + path.getFileName(),
                failure.error(), path.toString(), failure.method());
        }
        if (Boolean.TRUE.equals(previous)) {
            return;
        }
        try {
            executeSqlFile(context, path);
            store.put(path.toString(), Boolean.TRUE);
        } catch (IOException | ExecutionPrerequisiteException error) {
            store.put(path.toString(), new PreparationFailure(error,
                context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName()));
            throw new ExecutionPrerequisiteException("FIXTURE_UNAVAILABLE: " + path.getFileName(),
                error, path.toString(), null);
        }
    }

    private record PreparationFailure(Throwable error, String method) {
    }

    private Connection fixtureConnection() {
        try {
            return JdbcContext.getConnection();
        } catch (SQLException error) {
            throw new ParameterResolutionException("Target fixture connection failed",
                new TargetConnectionException(error));
        }
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
        ReflectiveInvocationContext<java.lang.reflect.Method> invocationContext,
        ExtensionContext context) throws Throwable {
        enterPhase(context, "EXECUTION");
        invocation.proceed();
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        context.getExecutionException().ifPresent(error ->
            context.publishReportEntry("jdbc.failurePhase", "EXECUTION"));
        enterPhase(context, "CLEANUP");
    }

    private record Phase(String name, long started) {
    }

    private static void finishPhase(ExtensionContext context) {
        var store = context.getStore(ROOT_NAMESPACE);
        Phase phase = store.remove("phase", Phase.class);
        if (phase != null) {
            context.publishReportEntry("jdbc.phaseDurationMs." + phase.name(),
                Long.toString((System.nanoTime() - phase.started()) / 1_000_000));
        }
    }

    private static void enterPhase(ExtensionContext context, String name) {
        finishPhase(context);
        context.getStore(ROOT_NAMESPACE).put("phase", new Phase(name, System.nanoTime()));
        context.publishReportEntry("jdbc.phase", name);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        JdbcContext.cleanupObjects();
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Config config = ConfigLoader.load();
        FeatureProfile.load(config.db.dataSources.capabilities(config.adapter.capabilities));

        Class<?> testClass = context.getRequiredTestClass();

        return checkFeatures(requiredFeatures(testClass, context.getTestMethod().orElse(null)));
    }

    static List<String> requiredFeatures(Class<?> testClass, java.lang.reflect.Method method) {
        var required = new java.util.LinkedHashSet<String>();
        RequiresFeature classAnnotation = testClass.getAnnotation(RequiresFeature.class);
        if (classAnnotation != null) {
            required.addAll(List.of(classAnnotation.value()));
        }
        RequiresFeature methodAnnotation = method == null ? null : method.getAnnotation(
            RequiresFeature.class);
        if (methodAnnotation != null) {
            required.addAll(List.of(methodAnnotation.value()));
        }
        return List.copyOf(required);
    }

    static ConditionEvaluationResult checkFeatures(List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String feature : required) {
            if (java.util.Set.of("data_source", "connection_pool", "xa_data_source").contains(feature)
                && !FeatureProfile.supports(feature)) {
                missing.add(feature);
            }
        }
        if (!missing.isEmpty()) {
            String reason = String.format(
                "PREREQUISITE_MISSING: unconfigured driver Provider: %s",
                String.join(", ", missing));
            return ConditionEvaluationResult.disabled(reason);
        }
        return ConditionEvaluationResult.enabled(
            "Standard capabilities require observation; prerequisites configured");
    }

    private Config ensureInitialized() {
        Config config = ConfigLoader.load();
        String configKey = configKey(config);

        if (!initialized || !configKey.equals(initializedConfigKey)
            || JdbcContext.getDataSource() == null) {
            synchronized (JdbcTestExtension.class) {
                if (!initialized || !configKey.equals(initializedConfigKey)
                    || JdbcContext.getDataSource() == null) {
                    JdbcContext.init(config);
                    FeatureProfile.load(config.db.dataSources.capabilities(
                        config.adapter == null ? null : config.adapter.capabilities));
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
    public boolean supportsParameter(ParameterContext parameterContext,
        ExtensionContext extensionContext)
        throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == Connection.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
        ExtensionContext extensionContext)
        throws ParameterResolutionException {
        try {
            registerShutdownHook(extensionContext);
            Connection conn = JdbcContext.getConnection();
            extensionContext.getStore(
                    ExtensionContext.Namespace.create(extensionContext.getRequiredTestClass()))
                .put(extensionContext.getRequiredTestMethod().getName() + "_conn", conn);
            return conn;
        } catch (SQLException e) {
            throw new ParameterResolutionException("Target connection failed",
                e instanceof TargetConnectionException ? e : new TargetConnectionException(e));
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Phase phase = context.getStore(ROOT_NAMESPACE).get("phase", Phase.class);
        if (phase != null && !"CLEANUP".equals(phase.name())) {
            context.getExecutionException().ifPresent(error ->
                context.publishReportEntry("jdbc.failurePhase", phase.name()));
            enterPhase(context, "CLEANUP");
        }
        try {
            Connection conn = (Connection) context.getStore(
                ExtensionContext.Namespace.create(context.getRequiredTestClass()))
                .remove(context.getRequiredTestMethod().getName() + "_conn");
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } finally {
            finishPhase(context);
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
                throw new ExecutionPrerequisiteException(
                    "SQL resource missing: " + path.toAbsolutePath(), null);
            }
            String resource = name.substring("classpath:".length());
            try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ExecutionPrerequisiteException(
                        "SQL classpath resource missing: " + resource, null);
                }
                content = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        boolean hasGoBatches = content.matches("(?s).*\\bGO\\b.*");
        boolean hasSlashBatches = !hasGoBatches && content.matches("(?s).*\\n/\\s*\\n.*");
        String[] statements = hasGoBatches ? splitByGo(content)
            : hasSlashBatches ? splitBySlash(content)
                : splitBySemicolon(content);

        try (Connection conn = fixtureConnection();
            Statement stmt = conn.createStatement()) {

            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                try {
                    executeManagedStatement(stmt, JdbcContext.objects(), trimmed);
                } catch (SQLException e) {
                    throw new ExecutionPrerequisiteException(
                        "SQL initialization failed: " + path.getFileName(), e);
                }
            }
        } catch (SQLException e) {
            throw new ParameterResolutionException("Fixture connection operation failed", e);
        }
    }

    static void executeManagedStatement(Statement statement, TestObjects objects, String sql)
        throws SQLException {
        String executable = SqlSplitter.withoutLeadingComments(sql);
        if (executable.isBlank()) {
            return;
        }
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
            public boolean isBatchSeparator(int i, char c, char next, SqlSplitter.State ctx,
                CharSequence sql) {
                return c == ';' && ctx.beginDepth == 0 && !ctx.inDollarQuote;
            }

            @Override
            public int skipAfterMatch() {
                return 0;
            }
        });
    }

    static String[] splitByGo(String content) {
        return SqlSplitter.split(content, new SqlSplitter.Config() {
            @Override
            public boolean isBatchSeparator(int i, char c, char next, SqlSplitter.State ctx,
                CharSequence sql) {
                return (c == 'G' || c == 'g') && (next == 'O' || next == 'o')
                    && (i == 0 || Character.isWhitespace(sql.charAt(i - 1)))
                    && (i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2)));
            }

            @Override
            public int skipAfterMatch() {
                return 2;
            }
        });
    }

    static String[] splitBySlash(String content) {
        String[] raw = SqlSplitter.split(content, new SqlSplitter.Config() {
            @Override
            public boolean isBatchSeparator(int i, char c, char next, SqlSplitter.State ctx,
                CharSequence sql) {
                return c == '/' && (i == 0 || sql.charAt(i - 1) == '\n')
                    && (i + 1 >= sql.length() || Character.isWhitespace(sql.charAt(i + 1)));
            }
        });
        for (int i = 0; i < raw.length; i++) {
            String trimmed = SqlSplitter.withoutLeadingComments(raw[i]).trim()
                .toUpperCase(Locale.ENGLISH);
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
