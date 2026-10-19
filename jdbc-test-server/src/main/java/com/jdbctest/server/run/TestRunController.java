package com.jdbctest.server.run;

import com.jdbctest.server.driver.DriverStorageProperties;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
final class TestRunController {

    private final TestRunService service;
    private final RunEventPublisher events;
    private final DriverStorageProperties storage;

    TestRunController(TestRunService service, RunEventPublisher events,
        DriverStorageProperties storage) {
        this.service = service;
        this.events = events;
        this.storage = storage;
    }

    @PostMapping
    ResponseEntity<TestRunResponse> create(@Valid @RequestBody CreateTestRunRequest request) {
        TestRunResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/runs/" + response.id())).body(response);
    }

    @GetMapping
    PageResponse<TestRunResponse> list(@RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size,
        @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort,
        @RequestParam(value = "status", required = false) TestRunStatus status,
        @RequestParam(value = "statusGroup", required = false) String statusGroup,
        @RequestParam Map<String, String> parameters) {
        if (parameters.containsKey("status") && parameters.containsKey("statusGroup")) {
            throw new RunException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "CONFLICTING_STATUS_FILTERS", "Use either status or statusGroup, not both");
        }
        return service.list(page, size, sort, status, TestRunStatusGroup.parse(statusGroup));
    }

    @GetMapping("/{id}")
    TestRunResponse get(@PathVariable("id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Void> cancel(@PathVariable("id") UUID id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable("id") UUID id) {
        return service.subscribe(id, events);
    }

    @GetMapping("/{id}/results")
    PageResponse<TestCaseResultResponse> results(@PathVariable("id") UUID id,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size,
        @RequestParam(value = "sort", defaultValue = "scenarioId,asc") String sort,
        @RequestParam(value = "status", required = false) TestCaseResultStatus status,
        @RequestParam(value = "moduleId", required = false) String moduleId) {
        return service.results(id, page, size, sort, status, moduleId);
    }

    @GetMapping("/{id}/report")
    RunReportResponse report(@PathVariable("id") UUID id) {
        return service.report(id);
    }

    @GetMapping(value = "/{id}/report.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> reportJson(@PathVariable("id") UUID id) throws java.io.IOException {
        var evidence = service.evidenceReport(id);
        if (evidence != null) {
            Path ledger = storage.getDataDirectory().toAbsolutePath().normalize().resolve("runs")
                .resolve(id.toString()).resolve("object-ledger.yaml");
            if (Files.isRegularFile(ledger, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.size(ledger) <= 4 * 1024 * 1024) {
                try {
                    evidence.put("objectLedger", new org.yaml.snakeyaml.Yaml(
                        new org.yaml.snakeyaml.constructor.SafeConstructor(
                            new org.yaml.snakeyaml.LoaderOptions())).load(
                        Files.readString(ledger)));
                } catch (RuntimeException exception) {
                    evidence.put("cleanupState", "UNKNOWN_LEDGER_UNREADABLE");
                }
            } else {
                evidence.put("cleanupState", "UNKNOWN_OR_NO_CREATE_INTENT_RECORDED");
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(com.jdbctest.server.security.JdbcUrlSanitizer.sanitizeJson(
                        new tools.jackson.databind.ObjectMapper().writeValueAsString(evidence))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        service.report(id);
        Path path = storage.getDataDirectory().toAbsolutePath().normalize().resolve("runs")
            .resolve(id.toString()).resolve("report/report.json").normalize();
        if (!path.startsWith(storage.getDataDirectory().toAbsolutePath().normalize())
            || !Files.isRegularFile(path)) {
            throw new RunException(org.springframework.http.HttpStatus.NOT_FOUND,
                "REPORT_NOT_FOUND",
                "Report artifact was not found");
        }
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(
                com.jdbctest.server.security.JdbcUrlSanitizer.sanitizeJson(Files.readString(path))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                "REPORT_INCONSISTENT", "Report artifact is invalid");
        }
    }
}
