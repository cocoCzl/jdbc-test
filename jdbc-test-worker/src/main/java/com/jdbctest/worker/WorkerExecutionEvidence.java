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

  private final String runId;
  private final WorkerEventWriter events;
  private final Map<String, TestCaseDescriptor> methods = new LinkedHashMap<>();
  private final Map<String, Long> started = new LinkedHashMap<>();
  private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
  private final List<Map<String, Object>> issues = new ArrayList<>();
  private TestPlan plan;
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
    this.runId = runId;
    this.events = events;
    selected.forEach(item -> methods.put(item.sourceClass() + "#" + item.sourceMethod(), item));
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
  public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
    TestCaseDescriptor item = descriptor(id);
    if (item == null || !id.isTest()) {
      if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("level", "CONTAINER");
        issue.put("containerId", id.getUniqueId());
        issue.put("status", result.getStatus().name());
        issue.put("affectedScenarios", plan == null ? List.of() : plan.getDescendants(id).stream()
            .filter(TestIdentifier::isTest).map(this::descriptor).filter(Objects::nonNull)
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
    result.getThrowable().ifPresent(error -> row.putAll(failure(error)));
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
      results.put(item.scenarioId(), row);
      events.write("CASE_FINISHED", runId, row);
    }
  }

  static Map<String, Object> failure(Throwable error) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("errorType", error.getClass().getName());
    value.put("message", CredentialSanitizer.sanitize(Objects.toString(error.getMessage(), "")));
    Throwable cause = error;
    List<Map<String, Object>> causes = new ArrayList<>();
    for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("errorType", cause.getClass().getName());
      detail.put("message", CredentialSanitizer.sanitize(Objects.toString(cause.getMessage(), "")));
      if (cause instanceof SQLException sql) {
        detail.put("sqlState", Objects.toString(sql.getSQLState(), ""));
        detail.put("vendorCode", sql.getErrorCode());
        if (!value.containsKey("sqlState")) {
          value.put("sqlState", detail.get("sqlState"));
          value.put("vendorCode", sql.getErrorCode());
        }
      }
      causes.add(detail);
    }
    value.put("causes", causes);
    return value;
  }

  static Map<String, Object> fields(TestCaseDescriptor item) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("scenarioId", item.scenarioId());
    fields.put("moduleId", item.module().id());
    fields.put("module", item.module().id());
    fields.put("displayName", item.contract());
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
      return "CONTAINER_FAILURE";
    }
    if (results.size() != methods.size()) {
      return "INCOMPLETE_EXECUTION";
    }
    if (started.isEmpty()) {
      return "NO_TESTS_EXECUTED";
    }
    if (results.values().stream()
        .anyMatch(row -> Set.of("FAILED", "ABORTED").contains(row.get("status")))) {
      return "TEST_FAILURE";
    }
    return "SUCCESS".equals(cleanup.get("status")) ? "COMPLETED" : "CLEANUP_FAILURE";
  }
}
