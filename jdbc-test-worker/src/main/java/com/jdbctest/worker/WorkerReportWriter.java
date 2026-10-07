package com.jdbctest.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerReportWriter {
    private WorkerReportWriter() { }
    static Path write(Path workspace, String runId, List<Map<String, Object>> results) throws IOException {
        Path reportDir = workspace.resolve("report");
        Files.createDirectories(reportDir);
        long passed = results.stream().filter(row -> "PASSED".equals(row.get("status"))).count();
        long failed = results.stream().filter(row -> "FAILED".equals(row.get("status"))).count();
        long skipped = results.stream().filter(row -> "SKIPPED".equals(row.get("status"))).count();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_schema_version", "1.0.0");
        report.put("runId", runId);
        report.put("scenario_results", results.stream().collect(java.util.stream.Collectors.toMap(
                row -> String.valueOf(row.get("scenarioId")), row -> row, (a, b) -> a, LinkedHashMap::new)));
        report.put("summary", Map.of("total", results.size(), "passed", passed, "failed", failed, "skipped", skipped));
        Path output = reportDir.resolve("report.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return output;
    }
}
