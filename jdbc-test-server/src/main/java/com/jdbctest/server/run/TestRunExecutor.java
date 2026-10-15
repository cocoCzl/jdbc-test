package com.jdbctest.server.run;

import com.jdbctest.server.driver.DriverStorage;
import com.jdbctest.server.driver.DriverStorageProperties;
import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.driver.JdbcDriverArtifactRepository;
import com.jdbctest.server.security.CredentialEncryptionService;
import com.jdbctest.server.security.JdbcUrlSanitizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns one long lived Worker process and consumes its NDJSON incrementally.
 */
@Component
final class TestRunExecutor {

  private final TestRunRepository runs;
  private final TestRunSelectedScenarioRepository selected;
  private final TestRunCredentialRepository credentials;
  private final TestCaseResultRepository results;
  private final JdbcDriverArtifactRepository artifacts;
  private final DriverStorage storage;
  private final CredentialEncryptionService encryption;
  private final DriverStorageProperties storageProperties;
  private final DriverWorkerProperties workerProperties;
  private final ExecutionProperties execution;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final RunEventPublisher events;
  private final java.util.Set<UUID> protocolStarted = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Process> active = new ConcurrentHashMap<>();

  TestRunExecutor(TestRunRepository runs, TestRunSelectedScenarioRepository selected,
      TestRunCredentialRepository credentials, TestCaseResultRepository results,
      JdbcDriverArtifactRepository artifacts, DriverStorage storage,
      CredentialEncryptionService encryption, DriverStorageProperties storageProperties,
      DriverWorkerProperties workerProperties, ExecutionProperties execution, ObjectMapper mapper,
      TransactionTemplate transactions, RunEventPublisher events) {
    this.runs = runs;
    this.selected = selected;
    this.credentials = credentials;
    this.results = results;
    this.artifacts = artifacts;
    this.storage = storage;
    this.encryption = encryption;
    this.storageProperties = storageProperties;
    this.workerProperties = workerProperties;
    this.execution = execution;
    this.mapper = mapper;
    this.transactions = transactions;
    this.events = events;
  }

  void execute(UUID runId) {
    RunInput input;
    try {
      input = prepare(runId);
    } catch (IllegalArgumentException exception) {
      fail(runId, "PREPARATION_FAILED");
      cleanup(runId);
      events.complete(runId);
      return;
    }
    if (input == null) {
      cleanup(runId);
      if (TestRunStateTransitions.terminal(status(runId))) {
        events.complete(runId);
      }
      return;
    }
    Process process = null;
    boolean finishedEvent = false;
    try {
      Path workspace = workspace(runId);
      Files.createDirectories(workspace.resolve("report"));
      Files.createDirectories(workspace.resolve("logs"));
      process = start(input, workspace);
      active.put(runId, process);
      if (!transition(runId, TestRunStatus.RUNNING, null)) {
        terminate(process);
        return;
      }
      Process actual = process;
      Thread stderr = Thread.ofVirtual()
          .start(() -> drainStderr(actual, workspace.resolve("logs/worker.log")));
      AtomicReference<JsonNode> terminalEvent = new AtomicReference<>();
      Thread stdout = Thread.ofVirtual().start(() -> {
        try (BufferedReader reader = actual.inputReader(StandardCharsets.UTF_8)) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (terminalEvent.get() != null) {
              throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                  "WORKER_PROTOCOL_ERROR", "Event after RUN_FINISHED");
            }
            if (handle(runId, line)) {
              terminalEvent.set(mapper.readTree(line));
            }
          }
        } catch (IOException | RunException | tools.jackson.core.JacksonException exception) {
          fail(runId, "WORKER_PROTOCOL_ERROR");
          terminateQuietly(actual);
        }
      });
      boolean exited = process.waitFor(execution.getRunTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        fail(runId, "RUN_TIMEOUT");
        terminate(process);
        stdout.join();
        stderr.join();
        return;
      }
      stdout.join();
      stderr.join();
      finishedEvent = terminalEvent.get() != null;
      TestRunStatus current = status(runId);
      if (TestRunStateTransitions.terminal(current)) {
        return;
      }
      if (!finishedEvent) {
        fail(runId, "WORKER_EXITED_UNEXPECTEDLY");
        return;
      }
      if (!transition(runId, TestRunStatus.GENERATING_REPORT, null)) {
        return;
      }
      if (!consistent(runId, terminalEvent.get(), workspace.resolve("report/report.json"))) {
        fail(runId, "REPORT_INCONSISTENT");
        return;
      }
      if (process.exitValue() != 0 || "FAILED".equals(text(terminalEvent.get(), "status"))) {
        fail(runId, "WORKER_EXECUTION_FAILED");
        return;
      }
      transition(runId, TestRunStatus.SUCCEEDED, null);
    } catch (com.jdbctest.server.driver.WorkerProcessRunner.ProtocolMismatchException exception) {
      fail(runId, "WORKER_PROTOCOL_MISMATCH");
    } catch (IOException exception) {
      fail(runId, "WORKER_UNAVAILABLE");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (process != null) {
        terminateQuietly(process);
      }
      if (status(runId) != TestRunStatus.CANCELLED) {
        fail(runId, "INTERRUPTED");
      }
    } finally {
      if (process != null && process.isAlive()) {
        terminateQuietly(process);
      }
      active.remove(runId);
      protocolStarted.remove(runId);
      cleanup(runId);
      if (TestRunStateTransitions.terminal(status(runId))) {
        events.complete(runId);
      }
    }
  }

  boolean cancel(UUID runId) {
    Process process = active.get(runId);
    if (process == null) {
      return false;
    }
    terminateQuietly(process);
    return true;
  }

  void interruptAll() {
    active.values().forEach(this::terminateQuietly);
  }

  private RunInput prepare(UUID runId) {
    return transactions.execute(tx -> {
      TestRunEntity run = runs.findForUpdate(runId).orElse(null);
      if (run == null || run.getStatus() != TestRunStatus.QUEUED) {
        return null;
      }
      transition(run, TestRunStatus.PREPARING, null);
      TestRunCredentialEntity credential = credentials.findById(runId).orElse(null);
      if (credential == null) {
        transition(run, TestRunStatus.FAILED, "CONNECTION_PASSWORD_REQUIRED");
        return null;
      }
      String password = encryption.decrypt(credential.getEncryptedPassword());
      credentials.delete(credential);
      if (run.getExecutionInput() == null) {
        throw new IllegalArgumentException("EXECUTION_SCOPE_REQUIRED");
      }
      JsonNode frozen = mapper.readTree(run.getExecutionInput());
      if (JdbcUrlSanitizer.containsCredentials(frozen.path("jdbcUrl").asString())) {
        throw new IllegalArgumentException("CONNECTION_URL_CONTAINS_CREDENTIALS");
      }
      List<String> paths = artifacts.findByDriver_Id(run.getDriver().getId()).stream()
          .map(item -> storage.resolve(item.getStorageKey()).toAbsolutePath().toString()).toList();
      List<TestRunSelectedScenarioEntity> scenarios = selected.findByRun_IdOrderByScenarioId(runId);
      return new RunInput(runId, run.getDriverClassSnapshot(), paths,
          frozen.path("jdbcUrl").asString(), frozen.path("username").asString(),
          run.getAdapterIdSnapshot(), password,
          "CUSTOM".equals(run.getProfile()) ? "FULL" : run.getProfile(),
          scenarios.stream().map(TestRunSelectedScenarioEntity::getModuleId)
              .collect(java.util.stream.Collectors.toSet()).stream().toList(),
          scenarios.stream().map(TestRunSelectedScenarioEntity::getScenarioId).toList(), frozen);
    });
  }

  private Process start(RunInput input, Path workspace) throws IOException, InterruptedException {
    new com.jdbctest.server.driver.WorkerProcessRunner(workerProperties,
        mapper).requireRunProtocol();
    Path executable = workerProperties.getExecutable();
    if (executable == null || !Files.isRegularFile(executable)) {
      throw new IOException("Worker executable unavailable");
    }
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process process = new ProcessBuilder(java, "-jar",
        executable.toAbsolutePath().toString()).start();
    Map<String, Object> request = new java.util.LinkedHashMap<>(
        Map.of("runId", input.runId().toString(), "driver",
            Map.of("driverClass", input.driverClass(), "artifacts", input.artifacts(),
                "dataSources", input.frozen().path("dataSources")),
            "connection", Map.of("jdbcUrl", input.jdbcUrl(), "username",
                input.username() == null ? "" : input.username(), "password", input.password(),
                "properties", Map.of(), "adapterId", input.adapterId()),
            "selection",
            Map.of("profile", input.profile(), "modules", input.modules(), "includeScenarios",
                input.scenarios(), "excludeScenarios", List.of()), "workspace",
            workspace.toString(), "timeoutMs", execution.getRunTimeout().toMillis()));
    request.put("protocolVersion", 2);
    request.put("scope", input.frozen().path("scope"));
    request.put("objectPrefix", input.frozen().path("objectPrefix").asString());
    request.put("capabilities", input.frozen().path("capabilities"));
    request.put("destructiveConsent", input.frozen().path("destructiveConsent").asBoolean(false));
    try (OutputStream output = process.getOutputStream()) {
      mapper.writeValue(output, request);
    } catch (IOException | tools.jackson.core.JacksonException exception) {
      terminateQuietly(process);
      throw exception;
    }
    return process;
  }

  private boolean handle(UUID runId, String line) {
    JsonNode event = mapper.readTree(line);
    if (event == null || !event.isObject() || !runId.toString().equals(text(event, "runId"))) {
      throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "WORKER_PROTOCOL_ERROR", "Worker runId does not match");
    }
    if (TestRunStateTransitions.terminal(status(runId))) {
      return false;
    }
    String type = required(event, "type");
    if ("RUN_STARTED".equals(type)) {
      if (event.path("protocolVersion").asInt(0) != 2 || !protocolStarted.add(runId)) {
        throw protocol("Missing/duplicate protocol v2 start");
      }
      return false;
    }
    if (!protocolStarted.contains(runId)) {
      throw protocol("Event before RUN_STARTED");
    }
    switch (type) {
      case "MODULE_STARTED" ->
          updateCurrent(runId, required(event, "moduleId"), null, "module.started");
      case "CASE_STARTED" -> {
        transactions.executeWithoutResult(tx -> {
          TestRunEntity run = runs.findForUpdate(runId).orElseThrow();
          if (TestRunStateTransitions.terminal(run.getStatus())) {
            return;
          }
          TestRunSelectedScenarioEntity item = selectedScenario(runId, event);
          if (item.getExecutionStartedAt() != null || item.getResultStatus() != null) {
            throw protocol("Duplicate or late CASE_STARTED");
          }
          item.started();
          selected.save(item);
        });
        updateCurrent(runId, required(event, "moduleId"), required(event, "scenarioId"),
            "case.started");
      }
      case "EXECUTION_ISSUE" -> transactions.executeWithoutResult(tx -> {
        TestRunEntity run = runs.findForUpdate(runId).orElseThrow();
        if (TestRunStateTransitions.terminal(run.getStatus())) {
          return;
        }
        if (!"CONTAINER".equals(required(event, "level"))) {
          throw protocol("Unknown execution issue level");
        }
        var issues = run.getExecutionIssues() == null ? mapper.createArrayNode()
            : (tools.jackson.databind.node.ArrayNode) mapper.readTree(run.getExecutionIssues());
        issues.add(
            mapper.readTree(JdbcUrlSanitizer.sanitizeJson(mapper.writeValueAsString(event))));
        run.setExecutionIssues(mapper.writeValueAsString(issues));
        runs.save(run);
        events.publish(runId, "run.issue",
            mapper.convertValue(issues.get(issues.size() - 1), Map.class));
      });
      case "CLEANUP_FINISHED" -> transactions.executeWithoutResult(tx -> {
        TestRunEntity run = runs.findForUpdate(runId).orElseThrow();
        if (TestRunStateTransitions.terminal(run.getStatus())) {
          return;
        }
        JsonNode cleanup = event.path("cleanupEvidence");
        if (!cleanup.isObject() || !List.of("SUCCESS", "FAILED", "UNKNOWN", "NOT_EXECUTED")
            .contains(text(cleanup, "status"))) {
          throw protocol("Invalid cleanup evidence");
        }
        String safe = JdbcUrlSanitizer.sanitizeJson(mapper.writeValueAsString(cleanup));
        if (run.getCleanupEvidence() != null && !run.getCleanupEvidence().equals(safe)) {
          throw protocol("Conflicting cleanup evidence");
        }
        run.setCleanupEvidence(safe);
        runs.save(run);
        events.publish(runId, "run.cleanup", Map.of("runId", runId, "cleanupEvidence",
            mapper.readValue(safe, Map.class)));
      });
      case "CASE_FINISHED" -> persistCase(runId, event);
      case "RUN_FAILED" -> fail(runId,
          text(event, "errorType") == null ? "WORKER_EXECUTION_FAILED" : text(event, "errorType"));
      case "RUN_FINISHED" -> {
        if (!List.of("PASSED", "FAILED").contains(required(event, "status")) || !event.path(
            "scenarioCount").isInt()) {
          throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
              "WORKER_PROTOCOL_ERROR", "Invalid finish summary");
        }
        return true;
      }
      case "MODULE_FINISHED" -> {
      }
      default -> throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "WORKER_PROTOCOL_ERROR", "Unknown worker event");
    }
    return false;
  }

  private void persistCase(UUID runId, JsonNode event) {
    transactions.executeWithoutResult(tx -> {
      TestRunEntity run = runs.findForUpdate(runId).orElseThrow();
      if (TestRunStateTransitions.terminal(run.getStatus())) {
        return;
      }
      String scenario = required(event, "scenarioId");
      String module = required(event, "moduleId");
      String status = required(event, "status");
      if (selected.findByRun_IdOrderByScenarioId(runId).stream().noneMatch(
          item -> scenario.equals(item.getScenarioId()) && module.equals(item.getModuleId()))
          || results.findByRun_Id(runId).stream()
          .anyMatch(item -> scenario.equals(item.getScenarioId()))) {
        throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
            "WORKER_PROTOCOL_ERROR", "Unselected or duplicate scenario");
      }
      TestCaseResultStatus parsed;
      try {
        parsed = TestCaseResultStatus.valueOf(status);
      } catch (IllegalArgumentException exception) {
        throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
            "WORKER_PROTOCOL_ERROR", "Unknown result status");
      }
      TestRunSelectedScenarioEntity item = selectedScenario(runId, event);
      if (item.getResultStatus() != null) {
        throw protocol("Duplicate case result");
      }
      if (parsed == TestCaseResultStatus.SKIPPED) {
        if (item.getExecutionStartedAt() != null || text(event, "message") == null || text(event,
            "message").isBlank() || text(event, "skipSource") == null) {
          throw protocol("Skip requires explicit pre-execution evidence");
        }
      } else if (item.getExecutionStartedAt() == null) {
        throw protocol("Result without CASE_STARTED");
      }
      item.finished(parsed.name());
      selected.save(item);
      TestCaseResultEntity value = new TestCaseResultEntity(run, scenario, module,
          text(event, "displayName") == null ? scenario : sanitize(text(event, "displayName")),
          parsed);
      value.setExecutionStartedAt(item.getExecutionStartedAt());
      value.setExecutionEvidence(JdbcUrlSanitizer.sanitizeJson(mapper.writeValueAsString(event)));
      value.complete(event.path("durationMs").isNumber() ? event.path("durationMs").asLong() : 0L,
          sanitize(text(event, "message")), sanitize(text(event, "stackTrace")));
      results.save(value);
      run.recordCase(parsed);
      runs.save(run);
      events.publish(runId, "case.finished",
          Map.of("runId", runId, "scenarioId", scenario, "moduleId", module, "displayName",
              value.getDisplayName(), "status", parsed.name(), "durationMs", value.getDurationMs(),
              "completedCases", run.getCompletedCases(), "totalCases", run.getTotalCases()));
      events.publish(runId, "run.progress",
          Map.of("runId", runId, "completedCases", run.getCompletedCases(), "totalCases",
              run.getTotalCases(), "passed", run.getPassed(), "failed", run.getFailed(), "skipped",
              run.getSkipped(), "accounting",
              ExecutionAccounting.of(run, selected.findByRun_IdOrderByScenarioId(runId))));
    });
  }

  private void updateCurrent(UUID id, String module, String test, String name) {
    transactions.executeWithoutResult(tx -> {
      TestRunEntity run = runs.findForUpdate(id).orElseThrow();
      if (!TestRunStateTransitions.terminal(run.getStatus())) {
        run.setCurrentModule(module);
        if (test != null) {
          run.setCurrentTestCase(test);
        }
        runs.save(run);
        events.publish(id, name, test == null ? Map.of("runId", id, "moduleId", module)
            : Map.of("runId", id, "moduleId", module, "scenarioId", test, "accounting",
                ExecutionAccounting.of(run, selected.findByRun_IdOrderByScenarioId(id))));
      }
    });
  }

  private boolean transition(UUID id, TestRunStatus next, String reason) {
    return Boolean.TRUE.equals(transactions.execute(tx -> {
      TestRunEntity run = runs.findForUpdate(id).orElse(null);
      return run != null && transition(run, next, reason);
    }));
  }

  private boolean transition(TestRunEntity run, TestRunStatus next, String reason) {
    if (!TestRunStateTransitions.allows(run.getStatus(), next)) {
      return false;
    }
    run.setStatus(next);
    if (next == TestRunStatus.PREPARING) {
      run.markStarted();
    }
    if (TestRunStateTransitions.terminal(next)) {
      run.markFinished();
      run.setFailureReason(sanitize(reason));
    }
    runs.save(run);
    String name = next == TestRunStatus.SUCCEEDED ? "run.finished"
        : next == TestRunStatus.FAILED ? "run.failed" : "run.status";
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("runId", run.getId());
    payload.put("status", next.name());
    if (reason != null) {
      payload.put("reason", sanitize(reason));
    }
    var accounting = ExecutionAccounting.of(run,
        selected.findByRun_IdOrderByScenarioId(run.getId()));
    if (accounting != null) {
      payload.put("accounting", accounting);
    }
    events.publish(run.getId(), name, payload);
    return true;
  }

  private void fail(UUID id, String reason) {
    transition(id, TestRunStatus.FAILED, reason);
  }

  private boolean consistent(UUID runId, JsonNode finish, Path artifact) throws IOException {
    if (!Files.isRegularFile(artifact)) {
      return false;
    }
    JsonNode report;
    try {
      String safe = JdbcUrlSanitizer.sanitizeJson(Files.readString(artifact));
      Files.writeString(artifact, safe);
      report = mapper.readTree(safe);
    } catch (tools.jackson.core.JacksonException exception) {
      return false;
    }
    if (report == null || !runId.toString().equals(text(report, "runId"))) {
      return false;
    }
    return Boolean.TRUE.equals(transactions.execute(tx -> {
      TestRunEntity run = runs.findForUpdate(runId).orElseThrow();
      List<TestCaseResultEntity> cases = results.findByRun_Id(runId);
      JsonNode summary = report.path("summary");
      JsonNode reportCases = report.path("scenario_results");
      boolean failed = cases.stream().anyMatch(
          item -> item.getStatus() == TestCaseResultStatus.FAILED
              || item.getStatus() == TestCaseResultStatus.ABORTED);
      var selection = selected.findByRun_IdOrderByScenarioId(runId);
      java.util.Set<String> chosen = selection.stream()
          .map(TestRunSelectedScenarioEntity::getScenarioId)
          .collect(java.util.stream.Collectors.toSet());
      java.util.Set<String> started = selection.stream()
          .filter(item -> item.getExecutionStartedAt() != null)
          .map(TestRunSelectedScenarioEntity::getScenarioId)
          .collect(java.util.stream.Collectors.toSet());
      java.util.Set<String> completed = cases.stream().map(TestCaseResultEntity::getScenarioId)
          .collect(java.util.stream.Collectors.toSet());
      var notRun = new java.util.HashSet<>(chosen);
      notRun.removeAll(started);
      notRun.removeAll(completed);
      var incomplete = new java.util.HashSet<>(started);
      incomplete.removeAll(completed);
      String reason = text(finish, "terminationReason");
      boolean complete = notRun.isEmpty() && incomplete.isEmpty();
      boolean hasIssues =
          run.getExecutionIssues() != null && mapper.readTree(run.getExecutionIssues()).size() > 0;
      boolean cleanupSuccess = run.getCleanupEvidence() != null
          && "SUCCESS".equals(text(mapper.readTree(run.getCleanupEvidence()), "status"));
      boolean expectedFailure = failed || hasIssues || !complete || started.isEmpty()
          || !cleanupSuccess;
      String expectedReason = hasIssues ? "CONTAINER_FAILURE" : !complete ? "INCOMPLETE_EXECUTION"
          : started.isEmpty() ? "NO_TESTS_EXECUTED" : failed ? "TEST_FAILURE"
              : !cleanupSuccess ? "CLEANUP_FAILURE" : "COMPLETED";
      boolean reasonValid = expectedReason.equals(reason) && (complete || hasIssues);
      JsonNode frozen = mapper.readTree(run.getExecutionInput());
      JsonNode recordedIssues =
          hasIssues ? mapper.readTree(run.getExecutionIssues()) : mapper.createArrayNode();
      var issuePayloads = mapper.createArrayNode();
      java.util.Set<String> affected = new java.util.HashSet<>();
      for (JsonNode issue : recordedIssues) {
        var payload = issue.deepCopy();
        if (payload instanceof tools.jackson.databind.node.ObjectNode object) {
          object.remove("type");
          object.remove("runId");
          object.remove("timestamp");
        }
        if (!payload.path("affectedScenarios").isArray()) {
          return false;
        }
        for (JsonNode scenario : payload.path("affectedScenarios")) {
          if (!scenario.isString() || !chosen.contains(scenario.asString())) {
            return false;
          }
          affected.add(scenario.asString());
        }
        issuePayloads.add(payload);
      }
      boolean valid = "2.0.0".equals(text(report, "report_schema_version")) && reasonValid
          && affected.containsAll(notRun) && affected.containsAll(incomplete)
          && java.util.Objects.equals(reason, text(report, "terminationReason"))
          && finish.path("scenarioCount").asInt(-1) == run.getTotalCases()
          && (expectedFailure ? "FAILED" : "PASSED").equals(text(finish, "status"))
          && exactIds(report.path("selected"), chosen) && exactIds(report.path("started"), started)
          && exactIds(report.path("notRun"), notRun) && exactIds(report.path("incomplete"),
          incomplete)
          && report.path("issues").equals(issuePayloads)
          && (run.getCleanupEvidence() == null ? report.path("cleanupEvidence").isMissingNode()
          : report.path("cleanupEvidence").equals(mapper.readTree(run.getCleanupEvidence())))
          && report.path("scope").equals(frozen.path("scope"))
          && report.path("objectPrefix").equals(frozen.path("objectPrefix"))
          && report.path("capabilities").equals(frozen.path("capabilities"))
          && (!frozen.has("dataSources")
          || report.path("dataSources").equals(frozen.path("dataSources")))
          && summary.path("total").asInt(-1) == run.getTotalCases()
          && summary.path("completed").asInt(-1) == cases.size()
          && summary.path("started").asInt(-1) == started.size()
          && summary.path("passed").asInt(-1) == run.getPassed()
          && summary.path("failed").asInt(-1) == run.getFailed()
          && summary.path("skipped").asInt(-1) == run.getSkipped()
          && summary.path("aborted").asInt(-1)
          == run.getCompletedCases() - run.getPassed() - run.getFailed() - run.getSkipped()
          && reportCases.isObject() && reportCases.size() == cases.size()
          && cases.stream()
          .allMatch(item -> sameResult(item, reportCases.path(item.getScenarioId())));
      if (valid) {
        run.setWorkerTerminationReason(reason);
        run.markWorkerReportValidated();
        runs.save(run);
      }
      return valid;
    }));
  }

  private boolean sameResult(TestCaseResultEntity value, JsonNode row) {
    if (!value.getStatus().name().equals(text(row, "status")) || !value.getScenarioId()
        .equals(text(row, "scenarioId")) || !value.getModuleId().equals(text(row, "moduleId"))
        || row.path("durationMs").asLong(-1) != value.getDurationMs() || !java.util.Objects.equals(
        value.getMessage(), text(row, "message"))) {
      return false;
    }
    var stored = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
        value.getExecutionEvidence());
    stored.remove("type");
    stored.remove("runId");
    stored.remove("timestamp");
    return stored.equals(row);
  }

  private static boolean exactIds(JsonNode value, java.util.Set<String> expected) {
    if (!value.isArray() || value.size() != expected.size()) {
      return false;
    }
    java.util.Set<String> actual = new java.util.HashSet<>();
    for (JsonNode item : value) {
      if (!item.isString() || !actual.add(item.asString())) {
        return false;
      }
    }
    return actual.equals(expected);
  }

  private TestRunSelectedScenarioEntity selectedScenario(UUID runId, JsonNode event) {
    String scenario = required(event, "scenarioId"), module = required(event, "moduleId");
    return selected.findByRun_IdOrderByScenarioId(runId).stream()
        .filter(item -> item.getScenarioId().equals(scenario) && item.getModuleId().equals(module))
        .findFirst().orElseThrow(() -> protocol("Unselected scenario/module"));
  }

  private static RunException protocol(String message) {
    return new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
        "WORKER_PROTOCOL_ERROR", message);
  }

  private TestRunStatus status(UUID id) {
    return transactions.execute(
        tx -> runs.findById(id).map(TestRunEntity::getStatus).orElse(TestRunStatus.FAILED));
  }

  private void cleanup(UUID id) {
    transactions.executeWithoutResult(tx -> credentials.deleteById(id));
  }

  private Path workspace(UUID id) {
    return storageProperties.getDataDirectory().toAbsolutePath().normalize().resolve("runs")
        .resolve(id.toString());
  }

  private void drainStderr(Process process, Path log) {
    try (var input = process.errorReader(
        StandardCharsets.UTF_8); var output = Files.newBufferedWriter(log)) {
      int total = 0;
      String line;
      while ((line = input.readLine()) != null) {
        String safe = sanitize(line) + System.lineSeparator();
        int allowed = Math.min(safe.length(), 10 * 1024 * 1024 - total);
        if (allowed > 0) {
          output.write(safe, 0, allowed);
          total += allowed;
        }
      }
    } catch (IOException exception) {
      org.slf4j.LoggerFactory.getLogger(TestRunExecutor.class)
          .warn("Worker diagnostic stream unavailable");
    }
  }

  private void terminate(Process process) throws InterruptedException {
    process.destroy();
    if (!process.waitFor(execution.getTerminationGracePeriod().toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      process.waitFor();
    }
  }

  private void terminateQuietly(Process process) {
    try {
      terminate(process);
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).isMissingNode() ? null : node.path(field).asString(null);
  }

  private static String required(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null || value.isBlank()) {
      throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "WORKER_PROTOCOL_ERROR", "Worker event is missing " + field);
    }
    return value;
  }

  private static String sanitize(String value) {
    return JdbcUrlSanitizer.sanitize(value == null ? null : value);
  }

  private record RunInput(UUID runId, String driverClass, List<String> artifacts, String jdbcUrl,
      String username, String adapterId, String password, String profile,
      List<String> modules, List<String> scenarios, JsonNode frozen) {

  }
}
