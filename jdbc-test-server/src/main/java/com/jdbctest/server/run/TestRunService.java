package com.jdbctest.server.run;

import com.jdbctest.catalog.AdapterCatalog;
import com.jdbctest.server.connection.DatabaseConnectionProfileEntity;
import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.driver.DriverPackageStatus;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.security.CredentialEncryptionService;
import com.jdbctest.server.security.JdbcUrlSanitizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class TestRunService {
    private static final Set<String> RUN_SORTS = Set.of("createdAt", "startedAt", "finishedAt", "status");
    private static final Set<String> RESULT_SORTS = Set.of("scenarioId", "durationMs", "startedAt", "status");
    private final TestRunRepository runs; private final TestRunSelectedScenarioRepository selected; private final TestRunModuleRepository modules;
    private final TestRunCredentialRepository credentials; private final TestCaseResultRepository results; private final DatabaseConnectionProfileRepository connections;
    private final JdbcDriverPackageRepository drivers; private final CredentialEncryptionService encryption; private final RunCatalogService catalog;
    private final TransactionTemplate transactions; private final TestRunScheduler scheduler;
    TestRunService(TestRunRepository runs, TestRunSelectedScenarioRepository selected, TestRunModuleRepository modules, TestRunCredentialRepository credentials,
                   TestCaseResultRepository results, DatabaseConnectionProfileRepository connections, JdbcDriverPackageRepository drivers,
                   CredentialEncryptionService encryption, RunCatalogService catalog, TransactionTemplate transactions, TestRunScheduler scheduler) {
        this.runs = runs; this.selected = selected; this.modules = modules; this.credentials = credentials; this.results = results; this.connections = connections;
        this.drivers = drivers; this.encryption = encryption; this.catalog = catalog; this.transactions = transactions; this.scheduler = scheduler;
    }
    TestRunResponse create(CreateTestRunRequest request) {
        if (!request.destructiveConsent()) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DESTRUCTIVE_CONSENT_REQUIRED", "Destructive test consent is required");
        TestRunResponse response = transactions.execute(status -> {
            DatabaseConnectionProfileEntity connection = connections.findById(request.connectionId()).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "Connection was not found"));
            if (!AdapterCatalog.contains(connection.getAdapterId())) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "ADAPTER_NOT_FOUND", "Connection adapter was not found");
            if (request.driverId() == null && connection.getDefaultDriver() == null) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_NOT_FOUND", "Connection has no default driver");
            UUID driverId = request.driverId() == null ? connection.getDefaultDriver().getId() : request.driverId();
            JdbcDriverPackageEntity driver = drivers.findById(driverId).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND", "Driver was not found"));
            if (driver.getStatus() != DriverPackageStatus.VALIDATED) throw problem(HttpStatus.CONFLICT, "DRIVER_NOT_VALIDATED", "Driver must be validated before use");
            ResolvedSelection selection = resolve(request, catalog.catalog());
            String password = request.password() == null ? encryption.decrypt(connection.getEncryptedPassword()) : request.password();
            if (password == null || password.isEmpty()) throw problem(HttpStatus.BAD_REQUEST, "CONNECTION_PASSWORD_REQUIRED", "A password is required to run tests");
            TestRunEntity run = new TestRunEntity(connection, driver, selection.profile(), connection.getName(), connection.getDatabaseType(), connection.getAdapterId(),
                    JdbcUrlSanitizer.sanitize(connection.getJdbcUrl()), driver.getName(), driver.getVendor(), driver.getVersion(), driver.getDriverClass(), driver.getPackageFingerprint());
            run.setTotalCases(selection.scenarios().size()); runs.save(run);
            selected.saveAll(selection.scenarios().stream().map(item -> new TestRunSelectedScenarioEntity(run, item.id(), item.moduleId())).toList());
            selection.modules().forEach(item -> modules.save(new TestRunModuleEntity(run, item.id(), item.name())));
            credentials.save(new TestRunCredentialEntity(run.getId(), encryption.encrypt(password)));
            return response(run, selection.modules().stream().map(RunCatalogService.Module::id).toList());
        });
        scheduler.enqueue(response.id());
        return response;
    }
    TestRunResponse get(UUID id) { return transactions.execute(status -> response(requiredRun(id), moduleIds(id))); }
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(UUID id, RunEventPublisher events) {
        return transactions.execute(tx -> {
            TestRunEntity run = runs.findForUpdate(id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Test run was not found"));
            var emitter = events.subscribe(id, response(run, moduleIds(id)));
            if (TestRunStateTransitions.terminal(run.getStatus())) events.complete(id);
            return emitter;
        });
    }
    PageResponse<TestRunResponse> list(int page, int size, String sort, TestRunStatus status) {
        var pageable = page(page, size, sort, RUN_SORTS, "createdAt,desc");
        return transactions.execute(tx -> {
            var value = status == null ? runs.findAll(pageable) : runs.findByStatus(status, pageable);
            return new PageResponse<>(value.getContent().stream().map(item -> response(item, moduleIds(item.getId()))).toList(), value.getNumber(), value.getSize(), value.getTotalElements(), value.getTotalPages());
        });
    }
    PageResponse<TestCaseResultResponse> results(UUID runId, int page, int size, String sort, TestCaseResultStatus status, String moduleId) {
        var pageable = page(page, size, sort, RESULT_SORTS, "scenarioId,asc");
        return transactions.execute(tx -> {
            requiredRun(runId);
            var value = status != null ? results.findByRun_IdAndStatus(runId, status, pageable) : moduleId != null ? results.findByRun_IdAndModuleId(runId, moduleId, pageable) : results.findByRun_Id(runId, pageable);
            return new PageResponse<>(value.getContent().stream().map(this::result).toList(), value.getNumber(), value.getSize(), value.getTotalElements(), value.getTotalPages());
        });
    }
    RunReportResponse report(UUID id) { return transactions.execute(tx -> { TestRunEntity run = requiredRun(id); if (!TestRunStateTransitions.terminal(run.getStatus())) throw problem(HttpStatus.CONFLICT, "RUN_NOT_FINISHED", "Run has not finished"); return new RunReportResponse(response(run, moduleIds(id)), results.findByRun_Id(id).stream().filter(item -> item.getStatus() == TestCaseResultStatus.FAILED || item.getStatus() == TestCaseResultStatus.ABORTED).map(this::result).toList()); }); }
    void cancel(UUID id) { scheduler.cancel(id); }
    TestRunEntity requiredRun(UUID id) { return runs.findById(id).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Test run was not found")); }
    void transition(TestRunEntity run, TestRunStatus target, String reason) { if (!TestRunStateTransitions.allows(run.getStatus(), target)) return; run.setStatus(target); if (target == TestRunStatus.PREPARING) run.markStarted(); if (TestRunStateTransitions.terminal(target)) { run.markFinished(); run.setFailureReason(reason); } runs.save(run); }
    void cleanupCredential(UUID runId) { credentials.deleteById(runId); }
    String consumeCredential(UUID runId) { TestRunCredentialEntity value = credentials.findById(runId).orElseThrow(() -> problem(HttpStatus.BAD_REQUEST, "CONNECTION_PASSWORD_REQUIRED", "Run credential is unavailable")); String password = encryption.decrypt(value.getEncryptedPassword()); credentials.delete(value); return password; }
    private ResolvedSelection resolve(CreateTestRunRequest request, RunCatalogService.Catalog source) {
        String profile = request.profile().trim().toUpperCase();
        if (!Set.of("CORE", "FULL", "CUSTOM").contains(profile)) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION", "Unknown test profile");
        Set<String> knownModules = source.modules().stream().map(RunCatalogService.Module::id).collect(java.util.stream.Collectors.toSet());
        Set<String> knownScenarios = source.scenarios().stream().map(RunCatalogService.Scenario::id).collect(java.util.stream.Collectors.toSet());
        Set<String> includeModules = names(request.modules()); Set<String> include = names(request.includeScenarios()); Set<String> exclude = names(request.excludeScenarios());
        if (!knownModules.containsAll(includeModules)) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MODULE", "Unknown test module");
        if (!knownScenarios.containsAll(include) || !knownScenarios.containsAll(exclude)) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SCENARIO", "Unknown test scenario");
        if ("CUSTOM".equals(profile) && includeModules.isEmpty() && include.isEmpty()) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION", "Custom selection requires a module or scenario");
        boolean full = !"CORE".equals(profile);
        List<RunCatalogService.Scenario> rows = source.scenarios().stream().filter(item -> full || "CORE".equals(item.profile()))
                .filter(item -> includeModules.isEmpty() || includeModules.contains(item.moduleId())).filter(item -> include.isEmpty() || include.contains(item.id()))
                .filter(item -> !exclude.contains(item.id())).sorted(Comparator.comparing(RunCatalogService.Scenario::id)).toList();
        if (rows.isEmpty()) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION", "Selection resolved to no scenarios");
        List<RunCatalogService.Module> resolvedModules = source.modules().stream().filter(item -> rows.stream().anyMatch(row -> row.moduleId().equals(item.id()))).toList();
        return new ResolvedSelection(profile, rows, resolvedModules);
    }
    private static Set<String> names(List<String> values) { if (values == null) return Set.of(); LinkedHashSet<String> result = new LinkedHashSet<>(); for (String value : values) { if (value == null || value.isBlank()) throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION", "Selection contains a blank name"); result.add(value); } return Set.copyOf(result); }
    private static PageRequest page(int page, int size, String sort, Set<String> allowed, String defaultSort) { if (page < 0 || size < 1 || size > 100) throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid pagination parameters"); String[] parts = (sort == null ? defaultSort : sort).split(",", -1); if (parts.length != 2 || !allowed.contains(parts[0])) throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported sort field"); return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(parts[1]), parts[0])); }
    private List<String> moduleIds(UUID runId) { return selected.findByRun_IdOrderByScenarioId(runId).stream().map(TestRunSelectedScenarioEntity::getModuleId).distinct().toList(); }
    private TestRunResponse response(TestRunEntity run, List<String> moduleIds) { Long duration = run.getStartedAt() == null ? null : Duration.between(run.getStartedAt(), run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt()).toMillis(); return new TestRunResponse(run.getId(), run.getStatus(), run.getProfile(), new TestRunResponse.ConnectionSnapshot(run.getConnectionNameSnapshot(), run.getDatabaseTypeSnapshot(), run.getAdapterIdSnapshot(), run.getJdbcUrlSanitizedSnapshot()), new TestRunResponse.DriverSnapshot(run.getDriver().getId(), run.getDriverNameSnapshot(), run.getDriverVendorSnapshot(), run.getDriverVersionSnapshot(), run.getDriverClassSnapshot(), run.getDriverFingerprintSnapshot()), moduleIds, run.getTotalCases(), run.getCompletedCases(), run.getPassed(), run.getFailed(), run.getSkipped(), run.getCurrentModule(), run.getCurrentTestCase(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(), duration, run.getFailureReason()); }
    private TestCaseResultResponse result(TestCaseResultEntity value) { return new TestCaseResultResponse(value.getId(), value.getScenarioId(), value.getModuleId(), value.getDisplayName(), value.getStatus(), value.getDurationMs(), value.getMessage(), value.getStackTrace(), value.getStartedAt(), value.getFinishedAt()); }
    private static RunException problem(HttpStatus status, String code, String detail) { return new RunException(status, code, detail); }
    private record ResolvedSelection(String profile, List<RunCatalogService.Scenario> scenarios, List<RunCatalogService.Module> modules) { }
}
