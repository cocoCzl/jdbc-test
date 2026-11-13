package com.jdbctest.worker;

import com.jdbctest.catalog.TestCaseDescriptor;
import java.sql.SQLException;
import java.util.*;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.*;

/**
 * Records JUnit evidence without inventing outcomes for descendants of failed containers.
 */
final class WorkerExecutionEvidence implements TestExecutionListener {

    // Match the existing Server stack_trace column; reserve space for visible limit markers.
    private static final int STACK_TRACE_LIMIT = 12000;
    private static final int STACK_FRAME_LIMIT = 32;
    private static final String TRUNCATED = "... [TRUNCATED]\n";
    private static final String GRAPH_LIMIT =
        "[TRUNCATED: exception graph limit or repeated reference]\n";

    private final String runId;
    private final WorkerEventWriter events;
    private final Map<String, TestCaseDescriptor> methods = new LinkedHashMap<>();
    private final Map<String, Long> started = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
    private final List<Map<String, Object>> issues = new ArrayList<>();
    private TestPlan plan;
    private final Map<String, Map<String, String>> observations = new LinkedHashMap<>();
    private final Map<String, Boolean> declarations;

    private Map<String, Object> cleanup = Map.of("status", "UNKNOWN", "phase", "NOT_RECORDED");

    synchronized void recordCleanup(Map<String, Object> value) {
        cleanup = Map.copyOf(value);
        events.write("CLEANUP_FINISHED", runId, Map.of("cleanupEvidence", cleanup));
    }

    synchronized Map<String, Object> cleanupEvidence() {
        return cleanup;
    }


    WorkerExecutionEvidence(String runId, List<TestCaseDescriptor> selected,
        WorkerEventWriter events) {
        this(runId, selected, events, Map.of());
    }

    WorkerExecutionEvidence(String runId, List<TestCaseDescriptor> selected,
        WorkerEventWriter events, Map<String, Boolean> declarations) {
        this.runId = runId;
        this.events = events;
        this.declarations = Map.copyOf(declarations);
        selected.forEach(item -> methods.put(item.sourceClass() + "#" + item.sourceMethod(), item));
    }

    synchronized void recordUnavailable(TestCaseDescriptor item, Throwable error) {
        Map<String, Object> row = fields(item);
        row.put("status", "SKIPPED");
        row.put("durationMs", 0);
        row.put("phase", "DISCOVERY");
        row.put("contractInvoked", false);
        row.put("conclusion", "PREREQUISITE_UNMET");
        row.put("skipSource", "SCENARIO_CLASS_OR_API_UNAVAILABLE");
        row.putAll(failure(error));
        results.put(item.scenarioId(), row);
        events.write("CASE_FINISHED", runId, row);
    }

    @Override
    public void testPlanExecutionStarted(TestPlan value) {
        plan = value;
    }

    private TestCaseDescriptor descriptor(TestIdentifier id) {
        return id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
            .map(source -> methods.get(source.getClassName() + "#" + source.getMethodName()))
            .orElse(null);
    }

    @Override
    public synchronized void executionStarted(TestIdentifier id) {
        TestCaseDescriptor item = descriptor(id);
        if (item == null || !id.isTest()) {
            return;
        }
        started.put(item.scenarioId(), System.nanoTime());
        events.write("CASE_STARTED", runId, fields(item));
    }

    @Override
    public synchronized void reportingEntryPublished(TestIdentifier id,
        org.junit.platform.engine.reporting.ReportEntry entry) {
        TestCaseDescriptor item = descriptor(id);
        if (item != null) {
            observations.computeIfAbsent(item.scenarioId(), key -> new LinkedHashMap<>())
                .putAll(entry.getKeyValuePairs());
        }
    }

    @Override
    public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
        TestCaseDescriptor item = descriptor(id);
        if (item == null || !id.isTest()) {
            if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("level", "CONTAINER");
                Throwable cause = result.getThrowable().orElse(null);
                for (int depth = 0; cause != null && depth < 10;
                    depth++, cause = cause.getCause()) {
                    if ("ADAPTER_PREFLIGHT_FAILED".equals(cause.getMessage())) {
                        issue.put("phase", "PREFLIGHT");
                        break;
                    }
                }
                issue.put("containerId", id.getUniqueId());
                issue.put("status", result.getStatus().name());
                issue.put("affectedScenarios",
                    plan == null ? List.of() : plan.getDescendants(id).stream()
                        .filter(TestIdentifier::isTest).map(this::descriptor)
                        .filter(Objects::nonNull)
                        .map(TestCaseDescriptor::scenarioId).sorted().toList());
                result.getThrowable().ifPresent(error -> issue.putAll(failure(error)));
                issues.add(issue);
                events.write("EXECUTION_ISSUE", runId, issue);
            }
            return;
        }
        Map<String, Object> row = fields(item);
        row.put("status", switch (result.getStatus()) {
            case SUCCESSFUL -> "PASSED";
            case FAILED -> "FAILED";
            case ABORTED -> "ABORTED";
        });
        row.put("durationMs",
            (System.nanoTime() - started.getOrDefault(item.scenarioId(), System.nanoTime()))
                / 1_000_000);
        Throwable error = result.getThrowable().orElse(null);
        if (error != null) {
            row.putAll(failure(error));
        }
        row.put("observations", observations.getOrDefault(item.scenarioId(), Map.of()));
        var observed = observations.getOrDefault(item.scenarioId(), Map.of());
        String phase = isPrerequisite(error)
            || hasCause(error, com.jdbctest.extension.TargetConnectionException.class)
            ? "PREPARATION"
            : observed.getOrDefault("jdbc.failurePhase", error == null ? "EXECUTION"
                : observed.getOrDefault("jdbc.phase", "EXECUTION"));
        row.putAll(conclusion(result.getStatus(), error, phase));
        row.put("phase", phase);
        row.put("contractInvoked", !isPrerequisite(error)
            && !hasCause(error, com.jdbctest.extension.TargetConnectionException.class)
            && ("EXECUTION".equals(phase)
                || observed.containsKey("jdbc.phaseDurationMs.EXECUTION")));
        Map<String, Long> durations = new LinkedHashMap<>();
        observed.forEach((name, duration) -> {
            if (name.startsWith("jdbc.phaseDurationMs.")) {
                durations.put(name.substring("jdbc.phaseDurationMs.".length()),
                    Long.parseLong(duration));
            }
        });
        row.put("phaseDurationsMs", durations);
        if (isPrerequisite(error)) {
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 10
                && !(cause instanceof com.jdbctest.extension.ExecutionPrerequisiteException); depth++) {
                cause = cause.getCause();
            }
            if (cause instanceof com.jdbctest.extension.ExecutionPrerequisiteException prerequisite) {
                row.put("prerequisite", Objects.toString(prerequisite.prerequisite(), ""));
                TestCaseDescriptor blocker = methods.get(prerequisite.blockedBy());
                if (blocker != null) {
                    row.put("blockedByScenario", blocker.scenarioId());
                }
            }
        }
        Map<String, Boolean> declared = new LinkedHashMap<>();
        item.requiredCapabilities().forEach(feature -> {
            if (declarations.containsKey(feature)) {
                declared.put(feature, declarations.get(feature));
            }
        });
        row.put("declaredCapabilities", declared);
        results.put(item.scenarioId(), row);
        events.write("CASE_FINISHED", runId, row);
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier id, String reason) {
        Set<TestIdentifier> skipped = new LinkedHashSet<>();
        skipped.add(id);
        if (plan != null && id.isContainer()) {
            skipped.addAll(plan.getDescendants(id));
        }
        for (TestIdentifier child : skipped) {
            TestCaseDescriptor item = descriptor(child);
            if (item == null || !child.isTest() || results.containsKey(item.scenarioId())) {
                continue;
            }
            Map<String, Object> row = fields(item);
            row.put("status", "SKIPPED");
            row.put("durationMs", 0);
            row.put("message", CredentialSanitizer.sanitize(reason));
            row.put("skipSource", id.getUniqueId());
            row.put("phase", "SELECTION");
            row.put("contractInvoked", false);
            row.put("conclusion", reason.startsWith("PREREQUISITE_MISSING:")
                ? "PREREQUISITE_UNMET" : "NOT_EVALUATED");
            results.put(item.scenarioId(), row);
            events.write("CASE_FINISHED", runId, row);
        }
    }

    private static boolean isPrerequisite(Throwable error) {
        return hasCause(error, com.jdbctest.extension.ExecutionPrerequisiteException.class);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (int depth = 0; error != null && depth < 10; depth++, error = error.getCause()) {
            if (type.isInstance(error)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> conclusion(TestExecutionResult.Status status, Throwable error) {
        return conclusion(status, error, "EXECUTION");
    }

    static Map<String, Object> conclusion(TestExecutionResult.Status status, Throwable error,
        String phase) {
        String value;
        Boolean conforms = null;
        if (hasCause(error, com.jdbctest.extension.TargetConnectionException.class)) {
            value = "CONNECTION_FAILURE";
        } else if (isPrerequisite(error)) {
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 10
                && !(cause instanceof com.jdbctest.extension.ExecutionPrerequisiteException);
                depth++) {
                cause = cause.getCause();
            }
            value = cause instanceof com.jdbctest.extension.ExecutionPrerequisiteException missing
                && missing.blockedBy() != null ? "BLOCKED_PREREQUISITE" : "PREREQUISITE_UNMET";
        } else if (hasCause(error, com.jdbctest.extension.JdbcContracts.UnsupportedAllowedException.class)) {
            value = "UNSUPPORTED_ALLOWED";
            conforms = true;
        } else if (hasCause(error, java.util.concurrent.TimeoutException.class)) {
            value = "TIMEOUT";
        } else if (hasCause(error,
            com.jdbctest.extension.JdbcContracts.UnsupportedRequiredException.class)) {
            value = "UNSUPPORTED_REQUIRED";
            conforms = false;
        } else if (hasCause(error, SQLException.class)) {
            // SQLState alone cannot distinguish a contract defect from permissions or fixtures.
            value = "NOT_EVALUATED";
        } else if (status == TestExecutionResult.Status.SUCCESSFUL) {
            value = "CONFORMS";
            conforms = true;
        } else if (hasCause(error,
            com.jdbctest.extension.JdbcContracts.SuiteRequirementAssertionError.class)) {
            value = "NOT_EVALUATED";
        } else if (hasCause(error,
            com.jdbctest.extension.JdbcContracts.ContractApplicabilityAssertionError.class)) {
            value = "NOT_EVALUATED";
        } else if (hasCause(error, AssertionError.class)) {
            value = "EXECUTION".equals(phase) ? "CONTRACT_VIOLATION" : "NOT_EVALUATED";
            if ("EXECUTION".equals(phase)) {
                conforms = false;
            }
        } else {
            // A runtime exception during a JDBC call can originate in the driver or the suite.
            // Failure alone does not establish either a platform defect or a JDBC violation.
            value = status == TestExecutionResult.Status.ABORTED || "EXECUTION".equals(phase)
                ? "NOT_EVALUATED" : "PLATFORM_ERROR";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion", value);
        if (conforms != null) {
            result.put("contractConforms", conforms);
        }
        if ("NOT_EVALUATED".equals(value) && hasCause(error,
            com.jdbctest.extension.JdbcContracts.SuiteRequirementAssertionError.class)) {
            result.put("requirementScope", "SUITE");
        }
        return result;
    }

    static Map<String, Object> failure(Throwable error) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("errorType", error.getClass().getName());
        value.put("message", diagnosticText(error.getMessage()));
        Throwable primary = error;
        var primarySeen = new IdentityHashMap<Throwable, Boolean>();
        for (int depth = 0; primary != null && depth < 10
            && primarySeen.put(primary, true) == null; depth++, primary = primary.getCause()) {
            if (primary instanceof SQLException sql) {
                value.put("sqlState", diagnosticText(sql.getSQLState()));
                value.put("vendorCode", sql.getErrorCode());
                break;
            }
        }
        var pending = new ArrayDeque<Map.Entry<String, Throwable>>();
        var seen = new IdentityHashMap<Throwable, Boolean>();
        pending.add(Map.entry("ROOT", error));
        List<Map<String, Object>> causes = new ArrayList<>();
        List<Map.Entry<String, Throwable>> traces = new ArrayList<>();
        boolean graphLimited = false;
        while (!pending.isEmpty() && causes.size() < 10) {
            var branch = pending.removeFirst();
            Throwable cause = branch.getValue();
            if (seen.put(cause, true) != null) {
                graphLimited = true;
                continue;
            }
            traces.add(branch);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("relation", branch.getKey());
            detail.put("errorType", cause.getClass().getName());
            detail.put("message", diagnosticText(cause.getMessage()));
            if (cause instanceof SQLException sql) {
                detail.put("sqlState", diagnosticText(sql.getSQLState()));
                detail.put("vendorCode", sql.getErrorCode());
                if (sql.getNextException() != null) {
                    if (pending.size() < 16) {
                        pending.addLast(Map.entry("NEXT_EXCEPTION", sql.getNextException()));
                    } else {
                        graphLimited = true;
                    }
                }
            }
            if (cause.getCause() != null) {
                if (pending.size() < 16) {
                    pending.addLast(Map.entry("CAUSE", cause.getCause()));
                } else {
                    graphLimited = true;
                }
            }
            for (Throwable suppressed : cause.getSuppressed()) {
                if (pending.size() >= 16) {
                    graphLimited = true;
                    break;
                }
                pending.addLast(Map.entry("SUPPRESSED", suppressed));
            }
            causes.add(detail);
        }
        value.put("causes", causes);
        value.put("stackTrace", stackTrace(traces, graphLimited || !pending.isEmpty()));
        return value;
    }

    private static String stackTrace(List<Map.Entry<String, Throwable>> traces,
        boolean graphLimited) {
        StringBuilder result = new StringBuilder();
        // Share the budget so wrapper frames cannot consume the failing cause's first frames.
        int budget = (STACK_TRACE_LIMIT - GRAPH_LIMIT.length()) / traces.size();
        for (var branch : traces) {
            Throwable error = branch.getValue();
            StringBuilder part = new StringBuilder();
            part.append(traceText(branch.getKey() + ": " + error.getClass().getName() + ": "
                + Objects.toString(error.getMessage(), ""), Math.min(600, budget / 3)))
                .append('\n');
            StackTraceElement[] frames = error.getStackTrace();
            int index = 0;
            while (index < frames.length && index < STACK_FRAME_LIMIT) {
                String frame = traceText("\tat " + frames[index], 500) + "\n";
                if (part.length() + frame.length() + TRUNCATED.length() > budget) {
                    break;
                }
                part.append(frame);
                index++;
            }
            if (index < frames.length) {
                part.append(TRUNCATED);
            }
            result.append(part);
        }
        if (graphLimited) {
            result.append(GRAPH_LIMIT);
        }
        return result.toString();
    }

    private static String traceText(String value, int limit) {
        // Redact before truncation, including secrets spanning the truncation boundary.
        String safe = CredentialSanitizer.sanitize(value);
        return safe.length() <= limit ? safe
            : safe.substring(0, limit - TRUNCATED.length()) + TRUNCATED;
    }

    private static String diagnosticText(String value) {
        String safe = CredentialSanitizer.sanitize(value);
        return safe.substring(0, Math.min(safe.length(), 2000));
    }

    static Map<String, Object> fields(TestCaseDescriptor item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenarioId", item.scenarioId());
        fields.put("moduleId", item.module().id());
        fields.put("module", item.module().id());
        fields.put("displayName", item.contract());
        fields.put("category", item.category().name());
        fields.put("jdbcVersion", item.jdbcVersion());
        fields.put("unsupportedPolicy", item.unsupportedPolicy());
        fields.put("requiredCapabilities", item.requiredCapabilities());
        return fields;
    }

    synchronized List<Map<String, Object>> results() {
        return List.copyOf(results.values());
    }

    synchronized List<Map<String, Object>> issues() {
        return List.copyOf(issues);
    }

    synchronized Set<String> started() {
        return Set.copyOf(started.keySet());
    }

    synchronized String terminationReason() {
        if (!issues.isEmpty()) {
            return issues.stream().anyMatch(issue -> "PREFLIGHT".equals(issue.get("phase")))
                ? "PREFLIGHT_FAILURE" : "CONTAINER_FAILURE";
        }
        if (results.size() != methods.size()) {
            return "INCOMPLETE_EXECUTION";
        }
        if (started.isEmpty()) {
            return "NO_TESTS_EXECUTED";
        }
        if (results.values().stream()
            .anyMatch(row -> Set.of("FAILED", "ABORTED").contains(row.get("status"))
                && !"UNSUPPORTED_ALLOWED".equals(row.get("conclusion")))) {
            return "TEST_FAILURE";
        }
        return "SUCCESS".equals(cleanup.get("status")) ? "COMPLETED" : "CLEANUP_FAILURE";
    }
}
