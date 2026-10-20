package com.jdbctest.worker;

import com.jdbctest.catalog.SqlTestPackage;
import com.jdbctest.catalog.TestCaseDescriptor;
import com.jdbctest.extension.JdbcContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Sequential SQL feature execution inside the existing isolated Worker and authorized scope. */
final class SqlFeatureExecutor {

    private final WorkerRunRequest request;
    private final WorkerExecutionEvidence evidence;
    private Control control;

    SqlFeatureExecutor(WorkerRunRequest request, WorkerExecutionEvidence evidence) {
        this.request = request;
        this.evidence = evidence;
    }

    static List<TestCaseDescriptor> selected(WorkerRunRequest request) {
        if (request.selection() == null || !"CUSTOM".equals(request.selection().profile())
            || request.selection().includeScenarios() == null
            || request.selection().includeScenarios().isEmpty()
            || request.selection().excludeScenarios() != null && !request.selection().excludeScenarios().isEmpty()) {
            throw new IllegalArgumentException("SQL selection requires explicit CUSTOM case IDs");
        }
        var ids = request.selection().includeScenarios();
        var cases = request.sqlPackage().definition().cases().stream()
            .filter(item -> ids.contains(item.id())).toList();
        if (Set.copyOf(ids).size() != ids.size() || cases.size() != ids.size()) {
            throw new IllegalArgumentException("Unknown or duplicate SQL case selection");
        }
        return cases.stream().map(SqlTestPackage.SqlCase::descriptor).toList();
    }

    void execute() {
        control = new Control(java.nio.file.Path.of(request.workspace()).resolve("cancel.request"));
        try (Control owned = control) {
            var ids = request.selection().includeScenarios();
            int index = 0;
            for (var item : request.sqlPackage().definition().cases()) {
                if (ids.contains(item.id())) {
                    executeCase(item, "c" + index++ + "_");
                    if (Thread.currentThread().isInterrupted() || owned.cancelled) {
                        return;
                    }
                }
            }
        }
    }

    private void executeCase(SqlTestPackage.SqlCase item, String prefix) {
        evidence.sqlStarted(item.descriptor());
        long start = System.nanoTime();
        long deadline = start + item.timeoutSeconds() * 1_000_000_000L;
        var row = WorkerExecutionEvidence.fields(item.descriptor());
        row.put("testDomain", "SQL_FEATURE");
        row.put("contractInvoked", false);
        row.put("sqlPackageFingerprint", request.sqlPackage().fingerprint());
        String dialect = request.adapterExecution() == null
            ? com.jdbctest.catalog.AdapterExecutionConfig.load(request.connection().adapterId()).dialect()
            : request.adapterExecution().dialect();
        row.put("variant", dialect);
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> cleanup = new ArrayList<>();
        var variant = item.variants().get(dialect);
        String phase = "CONNECTION";
        String conclusion = "SQL_ASSERTIONS_SATISFIED";
        String status = "PASSED";
        try (Connection connection = JdbcContext.getConnection()) {
            phase = "PREPARATION";
            connection.setAutoCommit(true);
            var metadata = connection.getMetaData();
            var applies = item.appliesTo();
            row.put("applicabilityEvidence", Map.of("product", metadata.getDatabaseProductName(),
                "majorVersion", metadata.getDatabaseMajorVersion(), "modeDescription", applies.modeDescription()));
            if (variant == null || !applies.products().contains(metadata.getDatabaseProductName())
                || metadata.getDatabaseMajorVersion() < applies.minMajor()
                || metadata.getDatabaseMajorVersion() > applies.maxMajor()) {
                throw new PrerequisiteFailure("Product/dialect/version is outside declared applicability");
            }
            for (var step : item.prerequisites()) {
                runStep(connection, step, prefix, deadline, "PREREQUISITE", steps, control);
            }
            for (var step : variant.prepare()) {
                runStep(connection, step, prefix, deadline, "PREPARATION", steps, control);
            }
            connection.setAutoCommit(false);
            phase = "EXECUTION";
            try {
                for (var step : variant.execute()) {
                    runStep(connection, step, prefix, deadline, phase, steps, control);
                }
            } finally {
                try {
                    connection.rollback();
                } catch (SQLException error) {
                    cleanup.add(problem("TRANSACTION_ROLLBACK", error));
                }
            }
        } catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            row.putAll(sqlFailure(error));
            status = "FAILED";
            String operation = steps.isEmpty() ? "" : Objects.toString(steps.getLast().get("operation"), "");
            conclusion = conclusion(error, phase, operation, deadline);
        } finally {
            // Cleanup runs only after the active call unwinds, never concurrently with a JDBC call.
            if (variant != null) {
                try (Connection connection = JdbcContext.getConnection()) {
                    connection.setAutoCommit(true);
                    var resources = new ArrayList<>(variant.cleanup());
                    Collections.reverse(resources);
                    for (String logical : resources) {
                        try {
                            JdbcContext.objects().drop(connection, prefix + logical);
                            cleanup.add(Map.of("resource", logical, "status", "ATTEMPT_COMPLETED"));
                        } catch (SQLException | RuntimeException error) {
                            cleanup.add(problem(logical, error));
                        }
                    }
                } catch (SQLException | RuntimeException error) {
                    cleanup.add(problem("CLEANUP_CONNECTION", error));
                }
            }
        }
        if (cleanup.stream().anyMatch(entry -> "FAILED".equals(entry.get("status")))) {
            if ("PASSED".equals(status)) {
                status = "FAILED";
                conclusion = "CLEANUP_FAILURE";
            }
        }
        row.put("phase", phase);
        row.put("conclusion", conclusion);
        row.put("status", status);
        row.put("steps", steps);
        row.put("cleanupSteps", cleanup);
        row.put("durationMs", (System.nanoTime() - start) / 1_000_000L);
        evidence.sqlFinished(row);
    }

    static String conclusion(Throwable error, String phase, String operation, long deadline) {
        if (error instanceof CancellationException) {
            return "CANCELLED";
        }
        if (error instanceof TimeoutException || error instanceof SQLTimeoutException
            || error instanceof SQLException sql && "57014".equals(sql.getSQLState())
            && System.nanoTime() >= deadline) {
            return "TIMEOUT";
        }
        if ("CONNECTION".equals(phase) || error instanceof SQLException sql
            && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
            return "CONNECTION_FAILURE";
        }
        if (error instanceof SQLException sql && (SqlTestPackage.infrastructureState(sql.getSQLState())
            || Set.of(1031, 1044, 1045, 1142, 1143).contains(sql.getErrorCode()))) {
            return "ENVIRONMENT_FAILURE";
        }
        if (error instanceof PrerequisiteFailure || !"EXECUTION".equals(phase)
            && error instanceof AssertionError) {
            return "PREREQUISITE_UNMET";
        }
        if (error instanceof AssertionError) {
            return "SQL_ASSERTION_FAILED";
        }
        if ("CLOSE_STATEMENT".equals(operation)) {
            return "CLEANUP_FAILURE";
        }
        if (!"EXECUTION".equals(phase) || error instanceof SQLException
            && !Set.of("EXECUTE_SQL", "READ_RESULT").contains(operation)) {
            return "PREPARATION_FAILURE";
        }
        return error instanceof SQLException ? "SQL_EXECUTION_ERROR" : "PLATFORM_ERROR";
    }

    private static Map<String, Object> sqlFailure(Throwable error) {
        var failure = WorkerExecutionEvidence.failure(error);
        if (error.getSuppressed().length > 0) {
            failure.put("suppressed", java.util.Arrays.stream(error.getSuppressed()).limit(10)
                .map(WorkerExecutionEvidence::failure).toList());
        }
        return failure;
    }

    private static Map<String, Object> problem(String resource, Throwable error) {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("resource", resource);
        problem.put("status", "FAILED");
        problem.putAll(WorkerExecutionEvidence.failure(error));
        return problem;
    }

    private static String expand(String template, String prefix) {
        var matcher = java.util.regex.Pattern.compile("\\{\\{([a-z][a-z0-9_]*)}}").matcher(template);
        String expanded = matcher.replaceAll(result -> "{{" + prefix + result.group(1) + "}}");
        return JdbcContext.sql(expanded);
    }

    private static void checkDeadline(long deadline) throws TimeoutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("SQL execution interrupted");
        }
        if (System.nanoTime() >= deadline) {
            throw new TimeoutException("SQL case deadline exceeded");
        }
    }

    static void runStep(Connection connection, SqlTestPackage.Step step, String prefix,
        long deadline, String phase, List<Map<String, Object>> evidence) throws Exception {
        runStep(connection, step, prefix, deadline, phase, evidence, null);
    }

    private static void runStep(Connection connection, SqlTestPackage.Step step, String prefix,
        long deadline, String phase, List<Map<String, Object>> evidence, Control control) throws Exception {
        checkCancellation(control);
        checkDeadline(deadline);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("id", step.id());
        detail.put("phase", phase);
        String sql = expand(step.sql(), prefix);
        detail.put("sql", sql);
        detail.put("template", step.sql());
        detail.put("parameters", step.parameters());
        detail.put("expected", step.expect());
        detail.put("operation", "SAVEPOINT");
        evidence.add(detail);
        boolean expectsError = "ERROR".equals(step.expect().kind());
        Savepoint savepoint = null;
        Throwable primary = null;
        try {
            if (expectsError) {
                // A failed statement must not leave the next step in an aborted transaction.
                try {
                    savepoint = connection.setSavepoint();
                } catch (SQLException error) {
                    throw new PrerequisiteFailure("Expected-error step cannot establish savepoint", error);
                }
            }
            detail.put("operation", "PREPARE_STATEMENT");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                detail.put("operation", "CONFIGURE_STATEMENT");
                int seconds = Math.max(1, (int) ((deadline - System.nanoTime() + 999_999_999L) / 1_000_000_000L));
                statement.setQueryTimeout(seconds);
                statement.setMaxRows(1001);
                detail.put("operation", "BIND_PARAMETERS");
                for (int index = 0; index < step.parameters().size(); index++) {
                    bind(statement, index + 1, step.parameters().get(index));
                }
                if (step.sql().startsWith("CREATE TABLE ")) {
                    try (var ddl = connection.createStatement()) {
                        ddl.setQueryTimeout(seconds);
                        String managed = step.sql().replaceAll("\\{\\{([a-z][a-z0-9_]*)}}", "{{" + prefix + "$1}}");
                        if (control != null) {
                            control.active.set(ddl);
                            checkCancellation(control);
                        }
                        detail.put("operation", "EXECUTE_SQL");
                        int count = JdbcContext.objects().create(ddl, managed);
                        detail.put("actual", Map.of("count", count));
                        require(step.expect().count() == count, "Update count differs");
                    }
                } else {
                    boolean rows = false;
                    SQLException observed = null;
                    try {
                        if (control != null) {
                            control.active.set(statement);
                            checkCancellation(control);
                        }
                        detail.put("operation", "EXECUTE_SQL");
                        rows = statement.execute();
                    } catch (SQLException error) {
                        detail.put("actual", WorkerExecutionEvidence.failure(error));
                        checkCancellation(control);
                        if (!expectsError || !expectedError(step.expect(), error)) {
                            throw error;
                        }
                        observed = error;
                    }
                    if (expectsError) {
                        if (observed == null) {
                            detail.put("actual", Map.of("resultSet", rows));
                        }
                        require(observed != null, "Expected execution error was not thrown");
                    } else if ("ROWS".equals(step.expect().kind())) {
                        require(rows, "Expected result set");
                        detail.put("operation", "READ_RESULT");
                        try (ResultSet result = statement.getResultSet()) {
                            detail.put("actual", Map.of("columns", result.getMetaData().getColumnCount()));
                            List<List<Object>> actual = readRows(result, step.expect());
                            detail.put("actual", Map.of("columns", result.getMetaData().getColumnCount(),
                                "rowCount", actual.size(), "rows", actual));
                            compareRows(step.expect(), actual);
                        }
                    } else {
                        require(!rows, "Expected update count, got result set");
                        long count = statement.getLargeUpdateCount();
                        detail.put("actual", Map.of("count", count));
                        require(step.expect().count() == count, "Update count differs");
                    }
                    if (!expectsError) {
                        require(!statement.getMoreResults() && statement.getUpdateCount() == -1,
                            "Multiple results are outside SQL package v1");
                    }
                }
                detail.put("operation", "CLOSE_STATEMENT");
            }
            checkCancellation(control);
            checkDeadline(deadline);
            detail.put("status", "PASSED");
        } catch (Exception | AssertionError error) {
            primary = error;
            detail.put("status", "FAILED");
            detail.put("failure", sqlFailure(error));
            throw error;
        } finally {
            if (control != null) {
                control.active.set(null);
                if (control.cancelFailure != null) {
                    detail.put("cancelFailure", control.cancelFailure);
                }
            }
            if (savepoint != null) {
                try {
                    connection.rollback(savepoint);
                    connection.releaseSavepoint(savepoint);
                } catch (SQLException recovery) {
                    detail.put("status", "FAILED");
                    detail.put("recoveryFailure", WorkerExecutionEvidence.failure(recovery));
                    if (primary == null) {
                        throw recovery;
                    }
                    primary.addSuppressed(recovery);
                }
            }
        }
    }

    static boolean expectedError(SqlTestPackage.Expect expected, SQLException error) {
        if (SqlTestPackage.infrastructureState(error.getSQLState())
            || error instanceof SQLTimeoutException || error instanceof java.sql.SQLInvalidAuthorizationSpecException
            || Set.of(1031, 1044, 1045, 1142, 1143).contains(error.getErrorCode())) {
            return false;
        }
        boolean type = expected.exceptionType() == null;
        for (Class<?> current = error.getClass(); current != null; current = current.getSuperclass()) {
            type |= current.getName().equals(expected.exceptionType());
        }
        return type && (expected.sqlState() == null || expected.sqlState().equals(error.getSQLState()))
            && (expected.vendorCode() == null || expected.vendorCode() == error.getErrorCode());
    }

    private static void bind(PreparedStatement statement, int index, SqlTestPackage.Parameter parameter)
        throws SQLException {
        int jdbcType = switch (parameter.type()) {
            case "INTEGER" -> Types.INTEGER;
            case "DECIMAL" -> Types.DECIMAL;
            case "STRING" -> Types.VARCHAR;
            case "BOOLEAN" -> Types.BOOLEAN;
            default -> throw new IllegalArgumentException("Unsupported JDBC parameter type");
        };
        if (parameter.value() == null) {
            statement.setNull(index, jdbcType);
        } else {
            switch (parameter.type()) {
                case "INTEGER" -> statement.setInt(index, new java.math.BigInteger((String) parameter.value()).intValueExact());
                case "DECIMAL" -> statement.setBigDecimal(index, new BigDecimal((String) parameter.value()));
                case "STRING" -> statement.setString(index, (String) parameter.value());
                case "BOOLEAN" -> statement.setBoolean(index, (Boolean) parameter.value());
                default -> throw new IllegalArgumentException("Unsupported JDBC parameter type");
            }
        }
    }

    static List<List<Object>> readRows(ResultSet result, SqlTestPackage.Expect expected) throws SQLException {
        var metadata = result.getMetaData();
        require(metadata.getColumnCount() == expected.columns().size(), "Column count differs");
        for (int index = 0; index < expected.columns().size(); index++) {
            int type = metadata.getColumnType(index + 1);
            Set<Integer> supported = switch (expected.columns().get(index)) {
                case "INTEGER" -> Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT);
                case "DECIMAL" -> Set.of(Types.NUMERIC, Types.DECIMAL);
                case "STRING" -> Set.of(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGVARCHAR);
                case "BOOLEAN" -> Set.of(Types.BOOLEAN, Types.BIT);
                default -> Set.of();
            };
            require(supported.contains(type), "Unsupported actual JDBC type " + type + " for column " + (index + 1));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (result.next()) {
            require(rows.size() < 1000, "Result exceeds 1000-row limit");
            List<Object> cells = new ArrayList<>();
            for (int index = 0; index < expected.columns().size(); index++) {
                Object value = switch (expected.columns().get(index)) {
                    case "INTEGER" -> {
                        BigDecimal number = result.getBigDecimal(index + 1);
                        yield number == null ? null : number.toBigIntegerExact().toString();
                    }
                    case "DECIMAL" -> {
                        BigDecimal number = result.getBigDecimal(index + 1);
                        yield number == null ? null : number.toPlainString();
                    }
                    case "BOOLEAN" -> result.getBoolean(index + 1);
                    default -> result.getString(index + 1);
                };
                cells.add(result.wasNull() ? null : value);
            }
            rows.add(Collections.unmodifiableList(cells));
        }
        return List.copyOf(rows);
    }

    static void compareRows(SqlTestPackage.Expect expected, List<List<Object>> actual) {
        require(actual.size() == expected.rows().size(), "Row count differs");
        var left = normalized(expected, expected.rows());
        var right = normalized(expected, actual);
        require("ORDERED".equals(expected.order()) ? left.equals(right) : bag(left).equals(bag(right)),
            "Result values differ (" + expected.order() + ", decimal=" + expected.decimalMode() + ")");
    }

    private static List<List<Object>> normalized(SqlTestPackage.Expect expected, List<List<Object>> rows) {
        return rows.stream().map(row -> {
            require(row.size() == expected.columns().size(), "Column count differs");
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < row.size(); index++) {
                values.add(SqlTestPackage.canonical(expected.columns().get(index), row.get(index), expected.decimalMode()));
            }
            return Collections.unmodifiableList(values);
        }).toList();
    }

    private static Map<List<Object>, Integer> bag(List<List<Object>> rows) {
        Map<List<Object>, Integer> counts = new HashMap<>();
        rows.forEach(row -> counts.merge(row, 1, Integer::sum));
        return counts;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    private static void checkCancellation(Control control) {
        if (control != null && control.cancelled) {
            throw new CancellationException("SQL task cancellation requested");
        }
    }

    /** Only Statement.cancel crosses threads; rollback/drop/close stay on the execution thread. */
    private static final class Control implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicReference<java.sql.Statement> active =
            new java.util.concurrent.atomic.AtomicReference<>();
        private volatile boolean cancelled;
        private volatile boolean closed;
        private volatile Map<String, Object> cancelFailure;
        private final Thread monitor;

        Control(java.nio.file.Path marker) {
            monitor = Thread.ofVirtual().name("jdbc-sql-cancel").start(() -> {
                while (!closed) {
                    if (java.nio.file.Files.exists(marker)) {
                        cancelled = true;
                        var statement = active.get();
                        if (statement != null) {
                            try {
                                statement.cancel();
                            } catch (SQLException error) {
                                cancelFailure = WorkerExecutionEvidence.failure(error);
                            }
                        }
                        return;
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        @Override
        public void close() {
            closed = true;
            monitor.interrupt();
        }
    }

    private static final class PrerequisiteFailure extends Exception {
        PrerequisiteFailure(String message) {
            super(message);
        }

        PrerequisiteFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
