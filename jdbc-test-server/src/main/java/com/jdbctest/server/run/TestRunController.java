package com.jdbctest.server.run;

import com.jdbctest.server.driver.DriverStorageProperties;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/runs")
final class TestRunController {
    private final TestRunService service; private final RunEventPublisher events; private final DriverStorageProperties storage;
    TestRunController(TestRunService service, RunEventPublisher events, DriverStorageProperties storage) { this.service = service; this.events = events; this.storage = storage; }
    @PostMapping ResponseEntity<TestRunResponse> create(@Valid @RequestBody CreateTestRunRequest request) { TestRunResponse response = service.create(request); return ResponseEntity.created(URI.create("/api/runs/" + response.id())).body(response); }
    @GetMapping PageResponse<TestRunResponse> list(@RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size,
                                                    @RequestParam(value = "sort", defaultValue = "createdAt,desc") String sort, @RequestParam(value = "status", required = false) TestRunStatus status) { return service.list(page, size, sort, status); }
    @GetMapping("/{id}") TestRunResponse get(@PathVariable("id") UUID id) { return service.get(id); }
    @PostMapping("/{id}/cancel") ResponseEntity<Void> cancel(@PathVariable("id") UUID id) { service.cancel(id); return ResponseEntity.noContent().build(); }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter events(@PathVariable("id") UUID id) { return service.subscribe(id, events); }
    @GetMapping("/{id}/results") PageResponse<TestCaseResultResponse> results(@PathVariable("id") UUID id, @RequestParam(value = "page", defaultValue = "0") int page,
              @RequestParam(value = "size", defaultValue = "20") int size, @RequestParam(value = "sort", defaultValue = "scenarioId,asc") String sort,
              @RequestParam(value = "status", required = false) TestCaseResultStatus status, @RequestParam(value = "moduleId", required = false) String moduleId) { return service.results(id, page, size, sort, status, moduleId); }
    @GetMapping("/{id}/report") RunReportResponse report(@PathVariable("id") UUID id) { return service.report(id); }
    @GetMapping(value = "/{id}/report.json", produces = MediaType.APPLICATION_JSON_VALUE) ResponseEntity<byte[]> reportJson(@PathVariable("id") UUID id) throws java.io.IOException {
        service.report(id); Path path = storage.getDataDirectory().toAbsolutePath().normalize().resolve("runs").resolve(id.toString()).resolve("report/report.json").normalize();
        if (!path.startsWith(storage.getDataDirectory().toAbsolutePath().normalize()) || !Files.isRegularFile(path)) throw new RunException(org.springframework.http.HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "Report artifact was not found");
        try { return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(com.jdbctest.server.security.JdbcUrlSanitizer.sanitizeJson(Files.readString(path)).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (tools.jackson.core.JacksonException exception) { throw new RunException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "REPORT_INCONSISTENT", "Report artifact is invalid"); }
    }
}
