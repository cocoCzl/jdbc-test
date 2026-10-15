package com.jdbctest.server.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

public final class FakeWorkerMain {

  private static java.nio.channels.FileChannel slot;
  private static java.nio.channels.FileLock slotLock;

  public static void main(String[] args) throws Exception {
    ObjectMapper json = new ObjectMapper();
    if (args.length == 1 && "--describe-catalog".equals(args[0])) {
      try (var catalog = FakeWorkerMain.class.getResourceAsStream("/fake-catalog.json")) {
        System.out.println(
            new String(catalog.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> request = json.readValue(System.in,
        Map.class);
    String runId = String.valueOf(request.get("runId"));
    @SuppressWarnings("unchecked")
    Map<String, Object> connection = (Map<String, Object>) request.get(
        "connection");
    String mode = String.valueOf(connection.get("username"));
    Path workspace = Path.of(String.valueOf(request.get("workspace")));
    Files.createDirectories(workspace.resolve("report"));
    Files.writeString(workspace.resolve("input-evidence.json"), json.writeValueAsString(Map.of(
        "driver", request.get("driver"),
        "scope", request.get("scope"), "jdbcUrl", connection.get("jdbcUrl"), "username",
        connection.get("username"), "capabilities", request.get("capabilities"))));
    slot = java.nio.channels.FileChannel.open(
        workspace.getParent().getParent().resolve("worker-slot"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
    slotLock = slot.tryLock();
    if (slotLock == null) {
      Files.writeString(workspace.getParent().getParent().resolve("concurrency-violation"), runId);
      System.exit(99);
    }
    if ("BLOCK_STUBBORN".equals(mode)) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          await(workspace.resolve("never-release"));
        } catch (java.io.IOException | InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }));
    }
    Files.writeString(workspace.resolve("started"), Long.toString(ProcessHandle.current().pid()));
    await(workspace.resolve("proceed"));
    if ("INVALID_JSON".equals(mode)) {
      System.out.println("not-json");
      return;
    }
    if ("EXIT_NON_ZERO".equals(mode)) {
      System.exit(7);
    }
    if (mode.startsWith("BLOCK")) {
      await(workspace.resolve("release"));
    }
    String eventRunId = "WRONG_RUN_ID".equals(mode) ? "other-run" : runId;
    System.out.println(json.writeValueAsString(
        Map.of("type", "RUN_STARTED", "protocolVersion", 2, "runId", eventRunId)));
    if ("UNKNOWN_EVENT".equals(mode)) {
      System.out.println(json.writeValueAsString(Map.of("type", "UNKNOWN", "runId", eventRunId)));
      return;
    }
    if ("ERROR_SECRET".equals(mode)) {
      System.out.println(json.writeValueAsString(
          Map.of("type", "RUN_FAILED", "runId", runId, "errorType",
              "password=fake-secret token=fake-token")));
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> selection = (Map<String, Object>) request.get(
        "selection");
    @SuppressWarnings("unchecked")
    List<String> selected = (List<String>) selection.get(
        "includeScenarios");
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Map<String, Object>> issues = new ArrayList<>();
    if ("SETUP_FAILURE".equals(mode)) {
      Map<String, Object> issue = Map.of("level", "CONTAINER", "containerId", "fixture-container",
          "status", "FAILED",
          "errorType", "FixtureSetupException", "message", "fixture setup failed",
          "affectedScenarios", selected);
      issues.add(issue);
      var event = new LinkedHashMap<>(issue);
      event.put("type", "EXECUTION_ISSUE");
      event.put("runId", runId);
      System.out.println(json.writeValueAsString(event));
    }
    for (String scenario : selected) {
      if ("SETUP_FAILURE".equals(mode)) {
        break;
      }
      if ("PARTIAL_EXIT".equals(mode) && rows.size() == 1) {
        System.exit(7);
      }
      if ("CASE_COUNT_MISMATCH".equals(mode) && rows.size() == selected.size() - 1) {
        break;
      }
      String module = scenario.substring(0, scenario.indexOf('.'));
      String status = "ALL_SKIP".equals(mode) ? "SKIPPED" : "FAILED_RUN".equals(mode) ? "FAILED"
          : "MIXED".equals(mode) ? List.of("PASSED", "FAILED", "SKIPPED", "ABORTED")
              .get(rows.size() % 4) : "PASSED";
      System.out.println(json.writeValueAsString(
          Map.of("type", "MODULE_STARTED", "runId", eventRunId, "moduleId", module)));
      if (!"SKIPPED".equals(status)) {
        System.out.println(json.writeValueAsString(
            Map.of("type", "CASE_STARTED", "runId", eventRunId, "moduleId", module, "scenarioId",
                scenario)));
      }
      if ("START_NO_FINISH".equals(mode)) {
        System.exit(7);
      }
      Map<String, Object> row = new LinkedHashMap<>(
          Map.of("scenarioId", scenario, "moduleId", module, "displayName", "case " + scenario,
              "status", status, "durationMs", 42));
      row.put("message",
          "password=fake-secret token=fake-token jdbc:postgresql://host/db?password=fake-secret");
      row.put("stackTrace", "trace password=fake-secret token=fake-token");
      if ("SKIPPED".equals(status)) {
        row.put("skipSource", "fixture-capability");
      }
      rows.add(row);
      Map<String, Object> event = new LinkedHashMap<>(row);
      event.put("type", "CASE_FINISHED");
      event.put("runId", eventRunId);
      System.out.println(json.writeValueAsString(event));
    }
    System.err.print("password=fake-");
    System.err.flush();
    System.err.println("secret token=fake-token jdbc:postgresql://host/db?password=fake-secret");
    Map<String, Object> summary = Map.of("total", selected.size(), "completed", rows.size(),
        "started", rows.stream().filter(row -> !"SKIPPED".equals(row.get("status"))).count(),
        "aborted", rows.stream().filter(row -> "ABORTED".equals(row.get("status"))).count(),
        "passed", rows.stream().filter(row -> "PASSED".equals(row.get("status"))).count(), "failed",
        rows.stream().filter(row -> "FAILED".equals(row.get("status"))).count(), "skipped",
        rows.stream().filter(row -> "SKIPPED".equals(row.get("status"))).count());
    if ("REPORT_MISMATCH".equals(mode)) {
      summary = Map.of("total", rows.size() + 1, "passed", 0, "failed", 0, "skipped", 0);
    }
    Map<String, Object> reportRows = new LinkedHashMap<>();
    rows.forEach(row -> reportRows.put(String.valueOf(row.get("scenarioId")), row));
    boolean failed = rows.stream()
        .anyMatch(row -> "FAILED".equals(row.get("status")) || "ABORTED".equals(row.get("status")));
    Map<String, Object> cleanup = Map.of("status",
        "CLEANUP_FAILURE".equals(mode) ? "FAILED" : "SUCCESS", "attempted", 1,
        "confirmedDeleted", "CLEANUP_FAILURE".equals(mode) ? 0 : 1,
        "retainedObjects", "CLEANUP_FAILURE".equals(mode)
            ? List.of(Map.of("name", "fixture_owned", "sqlState", "42501", "vendorCode", 7)) : List.of());
    System.out.println(json.writeValueAsString(Map.of("type", "CLEANUP_FINISHED", "runId", runId,
        "cleanupEvidence", cleanup)));
    failed = failed || "CLEANUP_FAILURE".equals(mode);
    failed = failed || !issues.isEmpty() || "ALL_SKIP".equals(mode);
    String reason = !issues.isEmpty() ? "CONTAINER_FAILURE"
        : "ALL_SKIP".equals(mode) ? "NO_TESTS_EXECUTED"
            : "CLEANUP_FAILURE".equals(mode) ? "CLEANUP_FAILURE" : failed ? "TEST_FAILURE" : "COMPLETED";
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("runId", runId);
    report.put("report_schema_version", "2.0.0");
    report.put("summary", summary);
    report.put("scenario_results", reportRows);
    report.put("selected", selected);
    report.put("started", rows.stream().filter(row -> !"SKIPPED".equals(row.get("status")))
        .map(row -> row.get("scenarioId")).toList());
    report.put("notRun", selected.stream().filter(id -> !reportRows.containsKey(id)).toList());
    report.put("incomplete", List.of());
    report.put("issues", issues);
    report.put("cleanupEvidence", cleanup);
    report.put("terminationReason", reason);
    report.put("scope", request.get("scope"));
    report.put("capabilities", request.get("capabilities"));
    Object providers = ((Map<?, ?>) request.get("driver")).get("dataSources");
    if (providers instanceof Map<?, ?>) {
      report.put("dataSources", providers);
    }
    report.put("objectPrefix", request.get("objectPrefix"));
    Files.writeString(workspace.resolve("report/report.json"), json.writeValueAsString(report));
    if ("REPORT_INVALID".equals(mode)) {
      Files.writeString(workspace.resolve("report/report.json"), "not-json");
    }
    if ("DELAY_FINISH".equals(mode)) {
      Files.writeString(workspace.resolve("finish-ready"), "ready");
      await(workspace.resolve("finish"));
    }
    System.out.println(json.writeValueAsString(
        Map.of("type", "RUN_FINISHED", "terminationReason", reason, "runId", eventRunId, "status",
            failed ? "FAILED" : "PASSED", "scenarioCount",
            "FINISH_MISMATCH".equals(mode) ? rows.size() + 1 : selected.size())));
    if ("DELAY_FINISH".equals(mode)) {
      Files.writeString(workspace.resolve("finish-emitted"), "ready");
      await(workspace.resolve("exit"));
    }
    Files.writeString(workspace.resolve("exited"), "done");
  }

  private static void await(Path signal) throws java.io.IOException, InterruptedException {
    try (var watcher = signal.getFileSystem().newWatchService()) {
      signal.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
      while (!Files.exists(signal)) {
        var key = watcher.take();
        key.pollEvents();
        if (!key.reset()) {
          throw new java.io.IOException("Signal directory unavailable");
        }
      }
    }
  }
}
