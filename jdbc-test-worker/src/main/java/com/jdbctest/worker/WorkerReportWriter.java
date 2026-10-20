package com.jdbctest.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerReportWriter {

    private WorkerReportWriter() {
    }

    static Path write(Path workspace, WorkerRunRequest request,
        List<com.jdbctest.catalog.TestCaseDescriptor> selected, WorkerExecutionEvidence evidence)
        throws IOException {
        String runId = request.runId();
        List<Map<String, Object>> results = evidence.results();
        Path reportDir = workspace.resolve("report");
        Files.createDirectories(reportDir);
        long passed = results.stream().filter(row -> "PASSED".equals(row.get("status"))).count();
        long failed = results.stream().filter(row -> "FAILED".equals(row.get("status"))).count();
        long skipped = results.stream().filter(row -> "SKIPPED".equals(row.get("status"))).count();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_schema_version", "2.0.0");
        report.put("scope", request.scope());
        report.put("testDomain", "JDBC_CONTRACT");
        report.put("adapterSnapshot", request.adapterSnapshot());
        report.put("adapterExecution", request.adapterExecution() == null
            ? com.jdbctest.catalog.AdapterExecutionConfig.load(request.connection().adapterId())
            : request.adapterExecution());
        report.put("metadataEvidence", com.jdbctest.extension.JdbcContext.metadataEvidence());
        report.put("capabilities", request.capabilities());
        report.put("dataSources", request.driver().dataSources() == null ? Map.of()
            : request.driver().dataSources().toMap());
        report.put("objectPrefix", request.objectPrefix());
        report.put("selected",
            selected.stream().map(com.jdbctest.catalog.TestCaseDescriptor::scenarioId).sorted()
                .toList());
        report.put("started", evidence.started().stream().sorted().toList());
        var completed = results.stream().map(row -> row.get("scenarioId").toString())
            .collect(java.util.stream.Collectors.toSet());
        report.put("notRun",
            selected.stream().map(com.jdbctest.catalog.TestCaseDescriptor::scenarioId)
                .filter(id -> !evidence.started().contains(id) && !completed.contains(id)).sorted()
                .toList());
        report.put("incomplete",
            evidence.started().stream().filter(id -> !completed.contains(id)).sorted().toList());
        report.put("issues", evidence.issues());
        report.put("cleanupEvidence", evidence.cleanupEvidence());
        report.put("terminationReason", evidence.terminationReason());
        Path ledger = workspace.resolve("object-ledger.yaml");
        if (Files.isRegularFile(ledger)) {
            report.put("objectLedger", new org.yaml.snakeyaml.Yaml(
                new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions())).load(Files.readString(ledger)));
        }
        report.put("runId", runId);
        report.put("scenario_results", results.stream().collect(java.util.stream.Collectors.toMap(
            row -> String.valueOf(row.get("scenarioId")), row -> row, (a, b) -> a,
            LinkedHashMap::new)));
        report.put("summary",
            Map.of("total", selected.size(), "completed", results.size(), "started",
                evidence.started().size(), "passed", passed, "failed", failed, "skipped", skipped,
                "aborted",
                results.stream().filter(row -> "ABORTED".equals(row.get("status"))).count()));
        Path output = reportDir.resolve("report.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(output.toFile(), CredentialSanitizer.project(report));
        return output;
    }
}
