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
                System.out.println(new String(catalog.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return;
        }
        @SuppressWarnings("unchecked") Map<String,Object> request = json.readValue(System.in, Map.class);
        String runId = String.valueOf(request.get("runId"));
        @SuppressWarnings("unchecked") Map<String,Object> connection = (Map<String,Object>) request.get("connection");
        String mode = String.valueOf(connection.get("username"));
        Path workspace = Path.of(String.valueOf(request.get("workspace")));
        Files.createDirectories(workspace.resolve("report"));
        slot = java.nio.channels.FileChannel.open(workspace.getParent().getParent().resolve("worker-slot"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        slotLock = slot.tryLock();
        if (slotLock == null) { Files.writeString(workspace.getParent().getParent().resolve("concurrency-violation"), runId); System.exit(99); }
        if ("BLOCK_STUBBORN".equals(mode)) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { await(workspace.resolve("never-release")); }
            catch (java.io.IOException | InterruptedException exception) { Thread.currentThread().interrupt(); }
        }));
        Files.writeString(workspace.resolve("started"), Long.toString(ProcessHandle.current().pid()));
        await(workspace.resolve("proceed"));
        if ("INVALID_JSON".equals(mode)) { System.out.println("not-json"); return; }
        if ("EXIT_NON_ZERO".equals(mode)) System.exit(7);
        if (mode.startsWith("BLOCK")) await(workspace.resolve("release"));
        String eventRunId = "WRONG_RUN_ID".equals(mode) ? "other-run" : runId;
        System.out.println(json.writeValueAsString(Map.of("type", "RUN_STARTED", "runId", eventRunId)));
        if ("UNKNOWN_EVENT".equals(mode)) { System.out.println(json.writeValueAsString(Map.of("type", "UNKNOWN", "runId", eventRunId))); return; }
        if ("ERROR_SECRET".equals(mode)) {
            System.out.println(json.writeValueAsString(Map.of("type", "RUN_FAILED", "runId", runId, "errorType", "password=fake-secret token=fake-token")));
            return;
        }
        @SuppressWarnings("unchecked") Map<String,Object> selection = (Map<String,Object>) request.get("selection");
        @SuppressWarnings("unchecked") List<String> selected = (List<String>) selection.get("includeScenarios");
        List<Map<String,Object>> rows = new ArrayList<>();
        for (String scenario : selected) {
            if ("CASE_COUNT_MISMATCH".equals(mode) && rows.size() == selected.size() - 1) break;
            String module = scenario.substring(0, scenario.indexOf('.'));
            String status = "FAILED_RUN".equals(mode) ? "FAILED" : "MIXED".equals(mode) ? List.of("PASSED", "FAILED", "SKIPPED", "ABORTED").get(rows.size() % 4) : "PASSED";
            System.out.println(json.writeValueAsString(Map.of("type", "MODULE_STARTED", "runId", eventRunId, "moduleId", module)));
            System.out.println(json.writeValueAsString(Map.of("type", "CASE_STARTED", "runId", eventRunId, "moduleId", module, "scenarioId", scenario)));
            Map<String,Object> row = new LinkedHashMap<>(Map.of("scenarioId", scenario, "moduleId", module, "displayName", "case " + scenario, "status", status, "durationMs", 42));
            row.put("message", "password=fake-secret token=fake-token jdbc:postgresql://host/db?password=fake-secret");
            row.put("stackTrace", "trace password=fake-secret token=fake-token");
            rows.add(row);
            Map<String,Object> event = new LinkedHashMap<>(row); event.put("type", "CASE_FINISHED"); event.put("runId", eventRunId);
            System.out.println(json.writeValueAsString(event));
        }
        System.err.print("password=fake-"); System.err.flush();
        System.err.println("secret token=fake-token jdbc:postgresql://host/db?password=fake-secret");
        Map<String,Object> summary = Map.of("total", rows.size(), "passed", rows.stream().filter(row -> "PASSED".equals(row.get("status"))).count(), "failed", rows.stream().filter(row -> "FAILED".equals(row.get("status"))).count(), "skipped", rows.stream().filter(row -> "SKIPPED".equals(row.get("status"))).count());
        if ("REPORT_MISMATCH".equals(mode)) summary = Map.of("total", rows.size() + 1, "passed", 0, "failed", 0, "skipped", 0);
        Map<String,Object> reportRows = new LinkedHashMap<>(); rows.forEach(row -> reportRows.put(String.valueOf(row.get("scenarioId")), row));
        Files.writeString(workspace.resolve("report/report.json"), json.writeValueAsString(Map.of("runId", runId, "summary", summary, "scenario_results", reportRows)));
        if ("REPORT_INVALID".equals(mode)) Files.writeString(workspace.resolve("report/report.json"), "not-json");
        if ("DELAY_FINISH".equals(mode)) { Files.writeString(workspace.resolve("finish-ready"), "ready"); await(workspace.resolve("finish")); }
        boolean failed = rows.stream().anyMatch(row -> "FAILED".equals(row.get("status")) || "ABORTED".equals(row.get("status")));
        System.out.println(json.writeValueAsString(Map.of("type", "RUN_FINISHED", "runId", eventRunId, "status", failed ? "FAILED" : "PASSED", "scenarioCount", "FINISH_MISMATCH".equals(mode) ? rows.size() + 1 : selected.size())));
        if ("DELAY_FINISH".equals(mode)) { Files.writeString(workspace.resolve("finish-emitted"), "ready"); await(workspace.resolve("exit")); }
        Files.writeString(workspace.resolve("exited"), "done");
    }

    private static void await(Path signal) throws java.io.IOException, InterruptedException {
        try (var watcher = signal.getFileSystem().newWatchService()) {
            signal.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Files.exists(signal)) { var key = watcher.take(); key.pollEvents(); if (!key.reset()) throw new java.io.IOException("Signal directory unavailable"); }
        }
    }
}
