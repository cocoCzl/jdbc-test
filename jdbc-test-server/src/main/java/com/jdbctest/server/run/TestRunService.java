package com.jdbctest.server.run;

import com.jdbctest.server.connection.AdapterService;
import com.jdbctest.server.connection.AdapterResponse;
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

    private static final Set<String> RUN_SORTS = Set.of("createdAt", "startedAt", "finishedAt",
        "status");
    private static final Set<String> RESULT_SORTS = Set.of("scenarioId", "durationMs", "startedAt",
        "status");
    private final TestRunRepository runs;
    private final TestRunSelectedScenarioRepository selected;
    private final TestRunModuleRepository modules;
    private final TestRunCredentialRepository credentials;
    private final TestCaseResultRepository results;
    private final DatabaseConnectionProfileRepository connections;
    private final JdbcDriverPackageRepository drivers;
    private final CredentialEncryptionService encryption;
    private final RunCatalogService catalog;
    private final TransactionTemplate transactions;
    private final TestRunScheduler scheduler;
    private final ExecutionProperties executionProperties;
    private final AdapterService adapters;
    private final tools.jackson.databind.ObjectMapper mapper;

    TestRunService(TestRunRepository runs, TestRunSelectedScenarioRepository selected,
        TestRunModuleRepository modules, TestRunCredentialRepository credentials,
        TestCaseResultRepository results, DatabaseConnectionProfileRepository connections,
        JdbcDriverPackageRepository drivers,
        CredentialEncryptionService encryption, RunCatalogService catalog,
        TransactionTemplate transactions, TestRunScheduler scheduler,
        ExecutionProperties executionProperties, AdapterService adapters,
        tools.jackson.databind.ObjectMapper mapper) {
        this.adapters = adapters;
        this.mapper = mapper;
        this.runs = runs;
        this.selected = selected;
        this.modules = modules;
        this.credentials = credentials;
        this.results = results;
        this.connections = connections;
        this.drivers = drivers;
        this.encryption = encryption;
        this.catalog = catalog;
        this.transactions = transactions;
        this.scheduler = scheduler;
        this.executionProperties = executionProperties;
    }

    TestRunResponse create(CreateTestRunRequest request) {
        if (request.sqlPackageFingerprint() != null) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "SQL_PACKAGES_RETIRED",
                "SQL package execution has been retired; select standard JDBC cases");
        }
        if (!request.destructiveConsent()) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DESTRUCTIVE_CONSENT_REQUIRED",
                "Destructive test consent is required");
        }
        transactions.executeWithoutResult(tx -> {
            var connection = connections.findById(request.connectionId()).orElseThrow(
                () -> problem(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "Connection was not found"));
            requireActiveConnection(connection);
        });
        var source = catalog.catalog();
        TestRunResponse response = transactions.execute(status -> {
            DatabaseConnectionProfileEntity connection = connections.findById(
                    request.connectionId())
                .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND",
                    "Connection was not found"));
            requireActiveConnection(connection);
            if (request.driverId() == null && connection.getDefaultDriver() == null) {
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_NOT_FOUND",
                    "Connection has no default driver");
            }
            UUID driverId =
                request.driverId() == null ? connection.getDefaultDriver().getId()
                    : request.driverId();
            JdbcDriverPackageEntity driver = drivers.findById(driverId).orElseThrow(
                () -> problem(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND", "Driver was not found"));
            try {
                com.jdbctest.catalog.ExecutionSupport.requireActive(driver.getDriverClass(),
                    driver.getUrlPrefix(), driver.getDefaultSqlDialect());
            } catch (IllegalArgumentException exception) {
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DATABASE_SUPPORT_RETIRED",
                    "SQL Server execution is no longer supported");
            }
            if (driver.getStatus() != DriverPackageStatus.VALIDATED) {
                throw problem(HttpStatus.CONFLICT, "DRIVER_NOT_VALIDATED",
                    "Driver must be validated before use");
            }
            com.jdbctest.catalog.ExecutionScope scope = new com.jdbctest.catalog.ExecutionScope(
                connection.getExecutionDatabase(), connection.getExecutionSchema());
            var definition = adapters.required(connection.getAdapterId());
            var adapterSnapshot = AdapterResponse.from(definition);
            if (request.expectedAdapterFingerprint() != null
                && !request.expectedAdapterFingerprint().equals(adapterSnapshot.fingerprint())) {
                throw problem(HttpStatus.CONFLICT, "ADAPTER_CHANGED",
                    "Adapter changed; refresh and review its current version");
            }
            com.jdbctest.catalog.AdapterExecutionConfig adapterExecution;
            try {
                adapterExecution = definition.execution().requireDialect(connection.getSqlDialect());
            } catch (IllegalArgumentException exception) {
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "ADAPTER_DIALECT_UNSUPPORTED",
                    "Selected dialect has no matching adapter resources");
            }
            if ("compatible".equals(connection.getDatabaseType())) {
                // Explicit dialect reuse does not assert another product's metadata identity.
                adapterExecution = new com.jdbctest.catalog.AdapterExecutionConfig(
                    adapterExecution.id(), adapterExecution.assetId(), adapterExecution.dialect(),
                    adapterExecution.identifierQuote(), adapterExecution.capabilities(),
                    java.util.Map.of());
            }
            try {
                scope.validate(adapterExecution.sqlDialect());
            } catch (IllegalArgumentException exception) {
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "EXECUTION_SCOPE_REQUIRED",
                    exception.getMessage());
            }
            ResolvedSelection selection = resolve(request, source, connection.getAdapterId());
            String password =
                request.password() == null ? encryption.decrypt(connection.getEncryptedPassword())
                    : request.password();
            if (password == null) {
                throw problem(HttpStatus.BAD_REQUEST, "CONNECTION_PASSWORD_REQUIRED",
                    "A password is required to run tests");
            }
            TestRunEntity run = new TestRunEntity(connection, driver, selection.profile(),
                connection.getName(), connection.getDatabaseType(), connection.getAdapterId(),
                JdbcUrlSanitizer.sanitize(connection.getJdbcUrl()), driver.getName(),
                driver.getVendor(),
                driver.getVersion(), driver.getDriverClass(), driver.getPackageFingerprint());
            run.setName(request.name() == null || request.name().isBlank() ? null : request.name().strip());
            run.setTotalCases(selection.scenarios().size());
            var execution = new java.util.LinkedHashMap<String, Object>();
            execution.put("testDomain", "JDBC_CONTRACT");
            execution.put("adaptationMode", "compatible".equals(connection.getDatabaseType())
                ? "COMPATIBLE_DIALECT" : "BUILTIN_PRODUCT");
            execution.put("jdbcUrl", connection.getJdbcUrl());
            execution.put("username", connection.getUsername());
            execution.put("scope", scope);
            execution.put("destructiveConsent", request.destructiveConsent());
            execution.put("objectPrefix",
                "j" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            execution.put("adapterExecution", adapterExecution);
            execution.put("adapterSnapshot", adapterSnapshot);
            execution.put("dataSources", driver.getDataSources().toMap());
            execution.put("selection", java.util.Map.of(
                "catalogVersion", source.catalogVersion(), "semanticsVersion", 2,
                "profile", selection.profile(), "modules", names(request.modules()),
                "includeScenarios", names(request.includeScenarios()),
                "excludeScenarios", names(request.excludeScenarios()),
                "catalogCount", source.scenarios().stream()
                    .filter(item -> item.selectionEntry().appliesTo(connection.getAdapterId())).count(),
                "notApplicable", source.scenarios().stream()
                    .filter(item -> !item.selectionEntry().appliesTo(connection.getAdapterId()))
                    .map(RunCatalogService.Scenario::id).toList(),
                "userExcluded", source.scenarios().stream()
                    .filter(item -> item.selectionEntry().appliesTo(connection.getAdapterId()))
                    .map(RunCatalogService.Scenario::id)
                    .filter(id -> selection.scenarios().stream()
                        .noneMatch(item -> item.id().equals(id)))
                    .toList(), "selectedMetadata", selection.scenarios().stream()
                    .map(item -> item.descriptor(source.modules().stream()
                        .filter(module -> module.id().equals(item.moduleId()))
                        .findFirst().orElseThrow())).toList()));
            if (request.budgetSeconds() != null
                && (request.budgetSeconds() < 60 || request.budgetSeconds() > 7200)) {
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_BUDGET",
                    "Budget must be between 60 and 7200 seconds");
            }
            execution.put("budgetMs", request.budgetSeconds() == null
                ? executionProperties.getRunTimeout().toMillis() : request.budgetSeconds() * 1000L);
            execution.put("caseTimeoutMs", 60_000L);

            execution.put("capabilities",
                driver.getDataSources().capabilities(adapterExecution.capabilities()));
            run.freezeExecutionInput(
                mapper.writeValueAsString(execution));
            runs.save(run);
            selected.saveAll(selection.scenarios().stream()
                .map(item -> new TestRunSelectedScenarioEntity(run, item.id(), item.moduleId()))
                .toList());
            selection.modules()
                .forEach(
                    item -> modules.save(new TestRunModuleEntity(run, item.id(), item.name())));
            credentials.save(
                new TestRunCredentialEntity(run.getId(), encryption.encrypt(password)));
            return response(run,
                selection.modules().stream().map(RunCatalogService.Module::id).toList());
        });
        scheduler.enqueue(response.id());
        return response;
    }

    private void requireActiveConnection(DatabaseConnectionProfileEntity connection) {
        if (JdbcUrlSanitizer.containsCredentials(connection.getJdbcUrl())) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "CONNECTION_URL_CONTAINS_CREDENTIALS",
                "JDBC URL must not contain credentials");
        }
        adapters.required(connection.getAdapterId());
        try {
            com.jdbctest.catalog.ExecutionSupport.requireActive(connection.getDatabaseType(),
                connection.getSqlDialect(), connection.getJdbcUrl());
        } catch (IllegalArgumentException exception) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DATABASE_SUPPORT_RETIRED",
                "SQL Server execution is no longer supported");
        }
    }

    TestRunResponse get(UUID id) {
        return transactions.execute(status -> response(requiredRun(id), moduleIds(id)));
    }

    org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(UUID id,
        RunEventPublisher events, java.util.function.Consumer<Runnable> whenTransportReady) {
        return transactions.execute(tx -> {
            TestRunEntity run = runs.findForUpdate(id).orElseThrow(
                () -> problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Test run was not found"));
            var emitter = events.subscribe(id, response(run, moduleIds(id)), whenTransportReady);
            if (TestRunStateTransitions.terminal(run.getStatus())) {
                events.complete(id);
            }
            return emitter;
        });
    }

    PageResponse<TestRunResponse> list(int page, int size, String sort, TestRunStatus status) {
        return list(page, size, sort, status, null);
    }

    PageResponse<TestRunResponse> list(int page, int size, String sort, TestRunStatus status,
        TestRunStatusGroup statusGroup) {
        if (status != null && statusGroup != null) {
            throw problem(HttpStatus.BAD_REQUEST, "CONFLICTING_STATUS_FILTERS",
                "Use either status or statusGroup, not both");
        }
        var primary = page(page, size, sort, RUN_SORTS, "createdAt,desc");
        var pageable = primary.withSort(primary.getSort().and(Sort.by("id")));
        return transactions.execute(tx -> {
            org.springframework.data.jpa.domain.Specification<TestRunEntity> filter = statusGroup != null
                ? groupFilter(statusGroup) : (root, query, builder) -> status == null
                    ? builder.conjunction() : builder.equal(root.get("status"), status);
            var value = runs.findBy(filter, query -> query.as(RunListView.class).page(pageable));
            var ids = value.getContent().stream().map(RunListView::getId).toList();
            var selections = ids.isEmpty() ? java.util.Map.<UUID, List<TestRunSelectedScenarioRepository.SelectionCounts>>of()
                : selected.summarize(ids).stream().collect(java.util.stream.Collectors.groupingBy(
                    TestRunSelectedScenarioRepository.SelectionCounts::getRunId));
            var contracts = contractSummaries(ids);
            var items = value.getContent().stream().map(run -> {
                var counts = selections.getOrDefault(run.getId(), List.of());
                return response(run, counts.stream().map(TestRunSelectedScenarioRepository.SelectionCounts::getModuleId)
                    .sorted().toList(), ExecutionAccounting.summary(run, counts), contracts.get(run.getId()), true, run.getExecutionSummary());
            }).toList();
            return new PageResponse<>(items, value.getNumber(), value.getSize(), value.getTotalElements(),
                value.getTotalPages());
        });
    }

    PageResponse<TestCaseResultResponse> results(UUID runId, int page, int size, String sort,
        TestCaseResultStatus status, String moduleId) {
        var primary = page(page, size, sort, RESULT_SORTS, "scenarioId,asc");
        var pageable = primary.withSort(primary.getSort().and(Sort.by("scenarioId")));
        String module = moduleId == null || moduleId.isBlank() ? null : moduleId;
        return transactions.execute(tx -> {
            requiredRun(runId);
            var value = status != null && module != null
                ? results.findByRun_IdAndStatusAndModuleId(runId, status, module, pageable)
                : status != null ? results.findByRun_IdAndStatus(runId, status, pageable)
                    : module != null ? results.findByRun_IdAndModuleId(runId, module, pageable)
                        : results.findByRun_Id(runId, pageable);
            return new PageResponse<>(value.getContent().stream().map(this::result).toList(),
                value.getNumber(), value.getSize(), value.getTotalElements(),
                value.getTotalPages());
        });
    }

    RunReportResponse report(UUID id) {
        return transactions.execute(tx -> {
            TestRunEntity run = requiredRun(id);
            if (!TestRunStateTransitions.terminal(run.getStatus())) {
                throw problem(HttpStatus.CONFLICT, "RUN_NOT_FINISHED", "Run has not finished");
            }
            return new RunReportResponse(response(run, moduleIds(id)),
                results.findByRun_Id(id).stream()
                    .filter(item -> item.getStatus() == TestCaseResultStatus.FAILED
                        || item.getStatus() == TestCaseResultStatus.ABORTED).map(this::result)
                    .toList());
        });
    }

    void cancel(UUID id) {
        scheduler.cancel(id);
    }

    TestRunEntity requiredRun(UUID id) {
        return runs.findById(id).orElseThrow(
            () -> problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Test run was not found"));
    }

    void transition(TestRunEntity run, TestRunStatus target, String reason) {
        if (!TestRunStateTransitions.allows(run.getStatus(), target)) {
            return;
        }
        run.setStatus(target);
        if (target == TestRunStatus.PREPARING) {
            run.markStarted();
        }
        if (TestRunStateTransitions.terminal(target)) {
            run.markFinished();
            run.setFailureReason(reason);
        }
        runs.save(run);
    }

    void cleanupCredential(UUID runId) {
        credentials.deleteById(runId);
    }

    String consumeCredential(UUID runId) {
        TestRunCredentialEntity value = credentials.findById(runId).orElseThrow(
            () -> problem(HttpStatus.BAD_REQUEST, "CONNECTION_PASSWORD_REQUIRED",
                "Run credential is unavailable"));
        String password = encryption.decrypt(value.getEncryptedPassword());
        credentials.delete(value);
        return password;
    }

    /** Historical data is read as evidence, never revalidated or executed as a current package. */
    private tools.jackson.databind.JsonNode frozenSqlPackage(TestRunEntity run) {
        if (run.getExecutionInput() == null) {
            return null;
        }
        return mapper.readTree(
            JdbcUrlSanitizer.sanitizeJson(run.getExecutionInput())).get("sqlPackage");
    }

    private ResolvedSelection resolve(CreateTestRunRequest request,
        RunCatalogService.Catalog source, String adapterId) {
        if (request.catalogVersion() != null
            && !request.catalogVersion().equals(source.catalogVersion())) {
            throw problem(HttpStatus.CONFLICT, "CATALOG_CHANGED",
                "The test catalog changed; refresh and review the selection");
        }
        try {
            var selection = new com.jdbctest.catalog.TestSelection(
                com.jdbctest.catalog.TestProfile.valueOf(request.profile().trim().toUpperCase(
                    java.util.Locale.ROOT)), names(request.modules()),
                names(request.includeScenarios()), names(request.excludeScenarios()));
            var ids = Set.copyOf(com.jdbctest.catalog.ScenarioSelection.resolve(source.scenarios()
                .stream().map(RunCatalogService.Scenario::selectionEntry).toList(), selection,
                adapterId));
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("Selection resolved to no scenarios");
            }
            var rows = source.scenarios().stream().filter(item -> ids.contains(item.id()))
                .sorted(Comparator.comparing(RunCatalogService.Scenario::id)).toList();
            var resolvedModules = source.modules().stream()
                .filter(item -> rows.stream().anyMatch(row -> row.moduleId().equals(item.id())))
                .toList();
            return new ResolvedSelection(selection.profile().name(), rows, resolvedModules);
        } catch (IllegalArgumentException exception) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION", exception.getMessage());
        }
    }

    private static Set<String> names(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SELECTION",
                    "Selection contains a blank name");
            }
            result.add(value);
        }
        return Set.copyOf(result);
    }

    private static PageRequest page(int page, int size, String sort, Set<String> allowed,
        String defaultSort) {
        if (page < 0 || size < 1 || size > 100) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Invalid pagination parameters");
        }
        String[] parts = (sort == null ? defaultSort : sort).split(",", -1);
        if (parts.length != 2 || !allowed.contains(parts[0])) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported sort field");
        }
        try {
            return PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(parts[1]), parts[0]));
        } catch (IllegalArgumentException exception) {
            throw problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid sort direction");
        }
    }

    private List<String> moduleIds(UUID runId) {
        return selected.summarize(List.of(runId)).stream()
            .map(TestRunSelectedScenarioRepository.SelectionCounts::getModuleId).sorted().toList();
    }

    private org.springframework.data.jpa.domain.Specification<TestRunEntity> groupFilter(
        TestRunStatusGroup group) {
        if (group != TestRunStatusGroup.COMPLETED && group != TestRunStatusGroup.ABNORMAL) {
            return (root, query, builder) -> root.get("status").in(group.statuses());
        }
        // Match historical evidence using indexed correlated counts, before pagination/count.
        // JSON-derived flags are backfilled once by V15 and maintained by entity setters.
        return (root, query, builder) -> {
            var total = query.subquery(Long.class);
            var all = total.from(TestRunSelectedScenarioEntity.class);
            total.select(builder.count(all)).where(builder.equal(all.get("run").get("id"), root.get("id")));
            var started = query.subquery(Long.class);
            var begun = started.from(TestRunSelectedScenarioEntity.class);
            started.select(builder.count(begun)).where(builder.equal(begun.get("run").get("id"), root.get("id")),
                builder.isNotNull(begun.get("executionStartedAt")));
            var missing = query.subquery(Long.class);
            var unfinished = missing.from(TestRunSelectedScenarioEntity.class);
            missing.select(builder.count(unfinished)).where(builder.equal(unfinished.get("run").get("id"), root.get("id")),
                builder.isNull(unfinished.get("resultStatus")));
            var historical = builder.and(
                builder.equal(root.get("status"), TestRunStatus.FAILED),
                builder.isTrue(root.get("workerReportValidated")),
                builder.equal(root.get("workerTerminationReason"), "TEST_FAILURE"),
                root.get("failureReason").in("TEST_FAILURE", "WORKER_EXECUTION_FAILED"),
                builder.equal(root.get("evidenceVersion"), 2),
                builder.gt(root.get("totalCases"), 0),
                builder.equal(root.get("completedCases"), root.get("totalCases")),
                builder.equal(total, root.get("totalCases")), builder.gt(started, 0L), builder.equal(missing, 0L),
                builder.gt(builder.diff(builder.diff(root.<Integer>get("completedCases"), root.<Integer>get("passed")),
                    root.<Integer>get("skipped")), 0),
                builder.isTrue(root.get("cleanupSucceeded")), builder.isTrue(root.get("executionIssuesEmpty")),
                builder.or(builder.isNull(root.get("workerExitCode")), builder.equal(root.get("workerExitCode"), 4)));
            // SQL NULL means insufficient historical evidence, never a completed failure.
            var confirmed = builder.isTrue(builder.coalesce(historical, false));
            var states = root.get("status").in(group.statuses());
            return group == TestRunStatusGroup.COMPLETED ? builder.or(states, confirmed)
                : builder.and(states, builder.not(confirmed));
        };
    }

    private TestRunResponse response(TestRunEntity run, List<String> moduleIds) {
        var rows = selected.findByRun_IdOrderByScenarioId(run.getId());
        return response(run, moduleIds, ExecutionAccounting.of(run, rows),
            contractSummaries(List.of(run.getId())).get(run.getId()), false, run.getExecutionInput());
    }

    private TestRunResponse response(TestRunView run, List<String> moduleIds, ExecutionAccounting accounting,
        java.util.Map<String, Long> contracts, boolean summary, String executionInput) {
        var input = executionInput == null ? mapper.createObjectNode()
            : mapper.readTree(JdbcUrlSanitizer.sanitizeJson(executionInput));
        Long duration = run.getStartedAt() == null ? null : Duration.between(run.getStartedAt(),
            run.getFinishedAt() == null ? Instant.now() : run.getFinishedAt()).toMillis();
        return new TestRunResponse(run.getId(), run.getEventSequence(), run.getStatus(), run.getProfile(),
            new TestRunResponse.ConnectionSnapshot(run.getConnectionNameSnapshot(),
                run.getDatabaseTypeSnapshot(), run.getAdapterIdSnapshot(),
                JdbcUrlSanitizer.sanitize(run.getJdbcUrlSanitizedSnapshot())),
            new TestRunResponse.DriverSnapshot(run.getDriverId(), run.getDriverNameSnapshot(),
                run.getDriverVendorSnapshot(), run.getDriverVersionSnapshot(),
                run.getDriverClassSnapshot(), run.getDriverFingerprintSnapshot()), moduleIds,
            run.getTotalCases(), run.getCompletedCases(), run.getPassed(), run.getFailed(),
            run.getSkipped(), run.getCurrentModule(), run.getCurrentTestCase(), run.getCreatedAt(),
            run.getStartedAt(), run.getFinishedAt(), duration,
            JdbcUrlSanitizer.sanitize(run.getFailureReason()),
            accounting,
            scopeSnapshot(input), issueSnapshot(run), cleanupSnapshot(run), selectionSnapshot(input, summary),
            input.has("sqlPackage") ? null : contracts, adapterSnapshot(input),
            input.get("sqlPackage"), run.getWorkerTerminationReason(), run.isWorkerReportValidated(),
            input.path("adapterExecution").path("dialect").asString(null), run.getName(),
            RunExecutionOutcome.of(run, accounting),
            RunExecutionOutcome.historicalCaseFailure(run, accounting), run.getWorkerArtifactSha256(),
            run.getWorkerExitCode(),
            run.getWorkerFinalEvent() == null ? null
                : mapper.readTree(run.getWorkerFinalEvent()));
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> selectionSnapshot(tools.jackson.databind.JsonNode frozen, boolean summary) {
        if (!frozen.has("selection")) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>(mapper.convertValue(
            frozen.path("selection"), java.util.Map.class));
        if (summary) {
            value.keySet().retainAll(Set.of("catalogVersion", "catalogCount"));
        }
        value.put("budgetMs", frozen.path("budgetMs").asLong());
        value.put("caseTimeoutMs", frozen.path("caseTimeoutMs").asLong());
        return value;
    }

    private AdapterResponse adapterSnapshot(tools.jackson.databind.JsonNode input) {
        var node = input.path("adapterSnapshot");
        return node.isMissingNode() ? null : mapper.treeToValue(node, AdapterResponse.class);
    }

    private java.util.Map<UUID, java.util.Map<String, Long>> contractSummaries(List<UUID> ids) {
        java.util.Map<UUID, java.util.Map<String, Long>> summaries = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return summaries;
        }
        for (var row : results.summarize(ids)) {
            var counts = summaries.computeIfAbsent(row.getRunId(), key -> new java.util.LinkedHashMap<>());
            if (row.getConclusion() != null) {
                counts.merge(row.getConclusion(), row.getTotal(), Long::sum);
            }
            if ("NOT_EVALUATED".equals(row.getConclusion()) && "SUITE".equals(row.getRequirementScope())) {
                counts.merge("SUITE_REQUIREMENT_UNMET", row.getTotal(), Long::sum);
            }
            if (row.getInvoked()) {
                counts.merge("EXECUTED", row.getTotal(), Long::sum);
            }
        }
        summaries.values().removeIf(java.util.Map::isEmpty);
        return summaries;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> cleanupSnapshot(TestRunView run) {
        return run.getCleanupEvidence() == null
            ? java.util.Map.of("status", "UNKNOWN", "phase", "NOT_RECORDED")
            : mapper.readValue(run.getCleanupEvidence(),
                java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> scopeSnapshot(tools.jackson.databind.JsonNode input) {
        if (!input.has("scope")) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> scope = new java.util.LinkedHashMap<>(
            mapper.convertValue(input.path("scope"), java.util.Map.class));
        scope.put("objectPrefix", input.path("objectPrefix").asString());
        return scope;
    }

    @SuppressWarnings("unchecked")
    private List<java.util.Map<String, Object>> issueSnapshot(TestRunView run) {
        return run.getExecutionIssues() == null ? List.of()
            : mapper.readValue(
                JdbcUrlSanitizer.sanitizeJson(run.getExecutionIssues()), List.class);
    }

    java.util.Map<String, Object> evidenceReport(UUID id) {
        return transactions.execute(tx -> {
            TestRunEntity run = requiredRun(id);
            if (!Integer.valueOf(2).equals(run.getEvidenceVersion())) {
                return null;
            }
            if (!TestRunStateTransitions.terminal(run.getStatus())) {
                throw problem(HttpStatus.CONFLICT, "RUN_NOT_FINISHED", "Run has not finished");
            }
            var value = response(run, moduleIds(id));
            java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
            report.put("report_schema_version", "2.0.0");
            report.put("runId", id);
            report.put("source", "PERSISTED_WORKER_EVENTS");
            boolean validated = run.isWorkerReportValidated();
            var accounting = value.accounting();
            String reason = run.getWorkerTerminationReason();
            if (reason == null) {
                boolean serverTermination = run.getStatus() == TestRunStatus.CANCELLED
                    || run.getStatus() == TestRunStatus.INTERRUPTED
                    || "RUN_TIMEOUT".equals(run.getFailureReason());
                // Legacy v2 records predate the persisted Worker reason; cleanup remains independent.
                reason = !validated || serverTermination ? run.getFailureReason() == null
                    ? run.getStatus().name() : run.getFailureReason()
                    : !value.executionIssues().isEmpty() ? "CONTAINER_FAILURE"
                        : accounting.started() == 0 ? "NO_TESTS_EXECUTED"
                            : accounting.failed() + accounting.aborted() > 0 ? "TEST_FAILURE"
                                : "COMPLETED";
            }
            report.put("cleanupEvidence", value.cleanupEvidence());
            report.put("runStatus", run.getStatus());
            report.put("terminationReason", reason);
            report.put("scope",
                mapper.readTree(run.getExecutionInput())
                    .path("scope"));
            report.put("objectPrefix", value.executionScope().get("objectPrefix"));
            report.put("dataSources",
                mapper.readTree(run.getExecutionInput())
                    .path("dataSources"));
            report.put("metadataEvidence", run.getMetadataEvidence() == null
                ? java.util.Map.of("status", "UNKNOWN")
                : mapper.readTree(run.getMetadataEvidence()));
            report.put("adapterSnapshot", value.adapterSnapshot());
            report.put("testDomain", value.sqlPackage() == null ? "JDBC_CONTRACT" : "SQL_FEATURE");
            if (value.sqlPackage() != null) {
                report.put("sqlPackage", value.sqlPackage());
                report.put("sqlPackageOrigin", "LOCAL");
                report.put("sqlComparisonSupported", false);
            }
            report.put("adapterExecution",
                mapper.readTree(run.getExecutionInput())
                    .path("adapterExecution"));
            report.put("capabilities",
                mapper.readTree(run.getExecutionInput())
                    .path("capabilities"));
            report.put("accounting", accounting);
            report.put("selectionSnapshot", value.selectionSnapshot());
            report.put("contractSummary", value.contractSummary());
            report.put("summary",
                java.util.Map.of("total", accounting.selected(), "started", accounting.started(),
                    "completed", accounting.completed(),
                    "passed", accounting.passed(), "failed", accounting.failed(), "skipped",
                    accounting.skipped(), "aborted", accounting.aborted()));
            report.put("selected",
                accounting.scenarios().stream().map(ExecutionAccounting.Scenario::scenarioId)
                    .toList());
            report.put("started",
                accounting.scenarios().stream().filter(row -> row.startedAt() != null)
                    .map(ExecutionAccounting.Scenario::scenarioId).toList());
            report.put("notRun",
                accounting.scenarios().stream().filter(row -> row.state().equals("NOT_RUN"))
                    .map(ExecutionAccounting.Scenario::scenarioId).toList());
            report.put("incomplete",
                accounting.scenarios().stream().filter(row -> row.state().equals("INCOMPLETE"))
                    .map(ExecutionAccounting.Scenario::scenarioId).toList());
            report.put("issues", value.executionIssues());
            report.put("scenario_results", results.findByRun_Id(id).stream()
                .collect(
                    java.util.stream.Collectors.toMap(TestCaseResultEntity::getScenarioId, row -> {
                        var event = readCaseEvidence(row.getExecutionEvidence());
                        event.remove("type");
                        event.remove("timestamp");
                        event.remove("runId");
                        return event;
                    })));
            report.put("workerReportValidated", validated);
            return report;
        });
    }

    private TestCaseResultResponse result(TestCaseResultEntity value) {
        return new TestCaseResultResponse(value.getId(), value.getScenarioId(), value.getModuleId(),
            value.getDisplayName(), value.getStatus(), value.getDurationMs(),
            JdbcUrlSanitizer.sanitize(value.getMessage()),
            JdbcUrlSanitizer.sanitize(value.getStackTrace()), value.getStartedAt(),
            value.getFinishedAt(),
            value.getExecutionEvidence() == null ? null
                : readCaseEvidence(value.getExecutionEvidence()));
    }

    private java.util.Map<String, Object> readCaseEvidence(String raw) {
        if (raw == null) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            var node = mapper.readTree(JdbcUrlSanitizer.sanitizeJson(raw));
            if (node != null && node.isObject()) {
                return mapper.convertValue(node,
                    new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
            }
        } catch (tools.jackson.core.JacksonException exception) {
            org.slf4j.LoggerFactory.getLogger(TestRunService.class)
                .debug("Historical case evidence is unreadable; returning an explicit marker");
        }
        var unreadable = new java.util.LinkedHashMap<String, Object>();
        unreadable.put("evidenceReadError", "LEGACY_EVIDENCE_UNREADABLE");
        return unreadable;
    }

    private static RunException problem(HttpStatus status, String code, String detail) {
        return new RunException(status, code, detail);
    }

    private record ResolvedSelection(String profile, List<RunCatalogService.Scenario> scenarios,
        List<RunCatalogService.Module> modules) {

    }
}
