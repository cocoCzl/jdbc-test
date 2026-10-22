package com.jdbctest.server.connection;

import com.jdbctest.server.driver.DriverPackageStatus;
import com.jdbctest.server.driver.DriverStorage;
import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.driver.JdbcDriverArtifactRepository;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.driver.WorkerProcessRunner;
import com.jdbctest.server.run.TestRunRepository;
import com.jdbctest.server.security.CredentialEncryptionService;
import com.jdbctest.server.security.JdbcUrlSanitizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
final class ConnectionService {

    private static final Set<String> SORT_FIELDS = Set.of("name", "databaseType",
        "lastConnectionTestAt", "createdAt");
    private final DatabaseConnectionProfileRepository connections;
    private final JdbcDriverPackageRepository drivers;
    private final JdbcDriverArtifactRepository artifacts;
    private final TestRunRepository runs;
    private final DriverStorage storage;
    private final CredentialEncryptionService encryption;
    private final WorkerProcessRunner worker;
    private final DriverWorkerProperties properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final AdapterService adapters;

    ConnectionService(DatabaseConnectionProfileRepository connections,
        JdbcDriverPackageRepository drivers, JdbcDriverArtifactRepository artifacts,
        TestRunRepository runs, DriverStorage storage, CredentialEncryptionService encryption,
        WorkerProcessRunner worker,
        DriverWorkerProperties properties, ObjectMapper mapper, TransactionTemplate transactions,
        AdapterService adapters) {
        this.adapters = adapters;
        this.connections = connections;
        this.drivers = drivers;
        this.artifacts = artifacts;
        this.runs = runs;
        this.storage = storage;
        this.encryption = encryption;
        this.worker = worker;
        this.properties = properties;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    ConnectionResponse create(CreateConnectionRequest request) {
        Values values = values(request.name(), request.databaseType(), request.adapterId(),
            request.jdbcUrl(), request.username(), request.description(),
            request.defaultDriverId());
        if (Boolean.TRUE.equals(request.savePassword()) && request.password() == null) {
            throw invalid("password is required when savePassword is true");
        }
        return transactions.execute(status -> {
            JdbcDriverPackageEntity driver = requiredValidatedDriver(values.driverId());
            DatabaseConnectionProfileEntity entity = new DatabaseConnectionProfileEntity(
                values.name(),
                values.databaseType(), values.adapterId(), values.jdbcUrl(), values.username());
            entity.setExecutionScope(optional(request.executionDatabase()),
                optional(request.executionSchema()));
            entity.setSqlDialect(effectiveDialect(values.adapterId(),
                request.sqlDialect() == null ? driver.getDefaultSqlDialect() : request.sqlDialect()));
            validateScope(entity);
            entity.setDescription(values.description());
            entity.setDefaultDriver(driver);
            if (Boolean.TRUE.equals(request.savePassword())) {
                entity.setEncryptedPassword(encryption.encrypt(request.password()));
            }
            return response(connections.save(entity));
        });
    }

    ConnectionResponse get(UUID id) {
        return transactions.execute(status -> response(requiredConnection(id)));
    }

    ConnectionPageResponse list(int page, int size, String sort) {
        return list(page, size, sort, null);
    }

    ConnectionPageResponse list(int page, int size, String sort, String adapterId) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Invalid pagination parameters");
        }
        String[] parts = sort == null ? new String[] {"createdAt", "desc"} : sort.split(",", -1);
        if (parts.length != 2 || !SORT_FIELDS.contains(parts[0])) {
            throw invalid("Unsupported sort field");
        }
        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid sort direction");
        }
        String filter = adapterId == null || adapterId.isBlank() ? null : adapterId.trim();
        if (filter != null && filter.length() > 100) {
            throw invalid("Invalid adapter filter");
        }
        return transactions.execute(status -> {
            var pageable = PageRequest.of(page, size, Sort.by(direction, parts[0]));
            var result = filter == null ? connections.findAll(pageable)
                : connections.findByAdapterId(filter, pageable);
            return new ConnectionPageResponse(
                result.getContent().stream().map(this::response).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
        });
    }

    ConnectionResponse update(UUID id, UpdateConnectionRequest request) {
        if (Boolean.TRUE.equals(request.clearSavedPassword()) && request.password() != null) {
            throw invalid("password and clearSavedPassword cannot both be supplied");
        }
        Values values = values(request.name(), request.databaseType(), request.adapterId(),
            request.jdbcUrl(), request.username(), request.description(),
            request.defaultDriverId());
        if (request.password() != null && !Boolean.TRUE.equals(request.savePassword())) {
            throw invalid("savePassword must be true when replacing password");
        }
        return transactions.execute(status -> {
            DatabaseConnectionProfileEntity entity = requiredConnection(id);
            JdbcDriverPackageEntity driver = requiredValidatedDriver(values.driverId());
            entity.setExecutionScope(
                request.executionDatabase() == null ? entity.getExecutionDatabase()
                    : optional(request.executionDatabase()),
                request.executionSchema() == null ? entity.getExecutionSchema()
                    : optional(request.executionSchema()));
            entity.update(values.name(), values.databaseType(), values.adapterId(),
                values.jdbcUrl(),
                values.username(), values.description(), driver);
            entity.setSqlDialect(effectiveDialect(values.adapterId(),
                request.sqlDialect() == null ? entity.getSqlDialect() : request.sqlDialect()));
            validateScope(entity);
            if (Boolean.TRUE.equals(request.clearSavedPassword())) {
                entity.setEncryptedPassword(null);
            } else if (request.password() != null) {
                entity.setEncryptedPassword(encryption.encrypt(request.password()));
            }
            return response(connections.save(entity));
        });
    }

    void delete(UUID id) {
        try {
            transactions.executeWithoutResult(status -> {
                DatabaseConnectionProfileEntity entity = requiredConnection(id);
                if (runs.countByConnection_Id(id) > 0) {
                    throw new ConnectionException(HttpStatus.CONFLICT, "CONNECTION_IN_USE",
                        "Connection is referenced by a test run");
                }
                connections.delete(entity);
            });
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new ConnectionException(HttpStatus.CONFLICT, "CONNECTION_IN_USE",
                "Connection is referenced by a test run");
        }
    }

    ConnectionProbeResponse testDraft(DraftConnectionTestRequest request) {
        Values values = values("draft", request.databaseType(), request.adapterId(),
            request.jdbcUrl(),
            request.username(), null, request.driverId());
        DriverSnapshot driver = transactions.execute(
            status -> driverSnapshot(requiredValidatedDriver(values.driverId())));
        String password = request.password();
        if (password == null && request.savedConnectionId() != null) {
            password = transactions.execute(status -> encryption.decrypt(
                requiredConnection(request.savedConnectionId()).getEncryptedPassword()));
        }
        if (password == null) {
            throw new ConnectionException(HttpStatus.BAD_REQUEST, "CONNECTION_PASSWORD_REQUIRED",
                "A password is required to test this connection");
        }
        return probe(driver, values.jdbcUrl(), values.username(), password);
    }

    ConnectionProbeResponse testSaved(UUID id, ConnectionTestRequest request) {
        SavedSnapshot saved = transactions.execute(status -> {
            DatabaseConnectionProfileEntity entity = requiredConnection(id);
            return new SavedSnapshot(entity.getJdbcUrl(), entity.getUsername(),
                entity.getEncryptedPassword(),
                driverSnapshot(requiredValidatedDriver(entity.getDefaultDriver() == null ? null
                    : entity.getDefaultDriver().getId())));
        });
        String password = request == null ? null : request.password();
        if (password == null) {
            password = encryption.decrypt(saved.encryptedPassword());
        }
        if (password == null) {
            throw new ConnectionException(HttpStatus.BAD_REQUEST, "CONNECTION_PASSWORD_REQUIRED",
                "A password is required to test this connection");
        }
        try {
            ConnectionProbeResponse result = probe(saved.driver(), saved.jdbcUrl(),
                saved.username(),
                password);
            recordTest(id, ConnectionTestStatus.SUCCEEDED);
            return result;
        } catch (ConnectionException exception) {
            recordTest(id, ConnectionTestStatus.FAILED);
            throw exception;
        }
    }

    private ConnectionProbeResponse probe(DriverSnapshot driver, String jdbcUrl, String username,
        String password) {
        if (JdbcUrlSanitizer.containsCredentials(jdbcUrl)) {
            throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                "CONNECTION_URL_CONTAINS_CREDENTIALS", "JDBC URL must not contain credentials");
        }
        try {
            WorkerProcessRunner.Result result = worker.run("--probe-connection", Map.of(
                "driver", Map.of("driverClass", driver.driverClass(), "artifacts",
                    driver.artifacts().stream().map(Path::toString).toList()),
                "connection",
                Map.of("jdbcUrl", jdbcUrl, "username", username == null ? "" : username, "password",
                    password == null ? "" : password)), properties.getConnectionProbeTimeout());
            if (result.timedOut()) {
                throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "CONNECTION_TIMEOUT",
                    "Connection test timed out");
            }
            JsonNode event = result.lines().stream().map(this::read).filter(Objects::nonNull)
                .filter(node -> "CONNECTION_PROBED".equals(node.path("type").asString()))
                .findFirst()
                .orElse(null);
            if (result.exitCode() != 0 || event == null) {
                throw failure(result, password);
            }
            return new ConnectionProbeResponse(true, text(event, "databaseProductName"),
                text(event, "databaseProductVersion"), text(event, "driverName"),
                text(event, "driverVersion"), integer(event, "jdbcMajorVersion"),
                integer(event, "jdbcMinorVersion"), text(event, "currentCatalog"),
                text(event, "currentSchema"), text(event, "targetContainer"));
        } catch (IOException exception) {
            throw new ConnectionException(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_UNAVAILABLE",
                "Connection test worker is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT, "CONNECTION_TIMEOUT",
                "Connection test interrupted");
        }
    }

    private ConnectionException failure(WorkerProcessRunner.Result result, String password) {
        JsonNode event = result.lines().stream().map(this::read).filter(Objects::nonNull)
            .filter(node -> "CONNECTION_PROBE_FAILED".equals(node.path("type").asString()))
            .findFirst()
            .orElse(null);
        String type =
            event == null ? "CONNECTION_FAILED"
                : event.path("errorType").asString("CONNECTION_FAILED");
        Set<String> supported = Set.of("DRIVER_DOES_NOT_ACCEPT_URL", "CONNECTION_DRIVER_LOAD_FAILED",
            "CONNECTION_METADATA_FAILED", "CONNECTION_AUTHENTICATION_FAILED",
            "CONNECTION_DATABASE_NOT_FOUND", "CONNECTION_REJECTED", "CONNECTION_UNKNOWN");
        String code = supported.contains(type) ? type : "CONNECTION_UNKNOWN";
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (event != null) {
            String stage = text(event, "stage");
            if (Set.of("LOADING_DRIVER", "ESTABLISHING_CONNECTION", "READING_METADATA",
                "CLOSING_CONNECTION").contains(stage == null ? "" : stage)) {
                details.put("stage", stage);
            }
            var diagnostics = event.path("diagnostics");
            if (diagnostics.isArray()) {
                var safe = new java.util.ArrayList<Map<String, Object>>();
                for (JsonNode item : diagnostics) {
                    if (safe.size() >= 8) {
                        break;
                    }
                    Map<String, Object> fields = new java.util.LinkedHashMap<>();
                    for (String key : List.of("message", "exceptionType", "sqlState")) {
                        String value = text(item, key);
                        if (value != null) {
                            if (password != null && !password.isEmpty()) {
                                value = value.replace(password, "[REDACTED]");
                            }
                            value = JdbcUrlSanitizer.sanitize(value);
                            fields.put(key, value.substring(0, Math.min(value.length(), 2000)));
                        }
                    }
                    if (item.path("vendorCode").isNumber()) {
                        fields.put("vendorCode", item.path("vendorCode").asInt());
                    }
                    safe.add(fields);
                }
                details.put("diagnostics", safe);
            }
        }
        return new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT, code,
            "Connection probe failed", details);
    }

    private String effectiveDialect(String adapterId, String requested) {
        var execution = adapters.required(adapterId).execution();
        try {
            return execution.requireDialect(requested).sqlDialect().code();
        } catch (IllegalArgumentException exception) {
            throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ADAPTER_DIALECT_UNSUPPORTED", "Selected dialect has no matching adapter resources");
        }
    }

    private void validateScope(DatabaseConnectionProfileEntity entity) {
        // Legacy connections without a scope stay editable/probeable; runs still require one.
        if (entity.getExecutionDatabase() == null && entity.getExecutionSchema() == null) {
            return;
        }
        try {
            new com.jdbctest.catalog.ExecutionScope(entity.getExecutionDatabase(),
                entity.getExecutionSchema()).validate(adapters.required(entity.getAdapterId())
                .execution().requireDialect(entity.getSqlDialect()).sqlDialect());
        } catch (IllegalArgumentException exception) {
            throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                "EXECUTION_SCOPE_REQUIRED", "Check the authorized database/schema scope");
        }
    }

    private void recordTest(UUID id, ConnectionTestStatus status) {
        transactions.executeWithoutResult(tx -> {
            DatabaseConnectionProfileEntity entity = requiredConnection(id);
            entity.recordConnectionTest(status);
            connections.save(entity);
        });
    }

    private Values values(String name, String databaseType, String adapterId, String jdbcUrl,
        String username, String description, UUID driverId) {
        String normalizedUrl = required(jdbcUrl, "jdbcUrl");
        if (JdbcUrlSanitizer.containsCredentials(normalizedUrl)) {
            throw new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
                "CONNECTION_URL_CONTAINS_CREDENTIALS", "JDBC URL must not contain credentials");
        }
        String normalizedAdapter = required(adapterId, "adapterId");
        adapters.required(normalizedAdapter);
        return new Values(required(name, "name"), required(databaseType, "databaseType"),
            normalizedAdapter, normalizedUrl, username == null ? "" : username.trim(),
            optional(description), driverId);
    }

    private JdbcDriverPackageEntity requiredValidatedDriver(UUID id) {
        if (id == null) {
            throw new ConnectionException(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND",
                "Connection has no driver");
        }
        JdbcDriverPackageEntity driver = drivers.findById(id).orElseThrow(
            () -> new ConnectionException(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND",
                "Driver was not found"));
        if (driver.getStatus() != DriverPackageStatus.VALIDATED) {
            throw new ConnectionException(HttpStatus.CONFLICT, "DRIVER_NOT_VALIDATED",
                "Driver must be validated before use");
        }
        return driver;
    }

    private DatabaseConnectionProfileEntity requiredConnection(UUID id) {
        return connections.findById(id).orElseThrow(
            () -> new ConnectionException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND",
                "Connection was not found"));
    }

    private DriverSnapshot driverSnapshot(JdbcDriverPackageEntity driver) {
        return new DriverSnapshot(driver.getDriverClass(),
            artifacts.findByDriver_Id(driver.getId()).stream()
                .map(item -> storage.resolve(item.getStorageKey())).toList());
    }

    private ConnectionResponse response(DatabaseConnectionProfileEntity entity) {
        JdbcDriverPackageEntity driver = entity.getDefaultDriver();
        DriverSummaryResponse summary = driver == null ? null : new DriverSummaryResponse(
            driver.getId(), driver.getName(), driver.getVendor(), driver.getVersion(),
            driver.getStatus(), driver.getDefaultSqlDialect());
        return new ConnectionResponse(entity.getId(), entity.getName(), entity.getDatabaseType(),
            entity.getAdapterId(), JdbcUrlSanitizer.sanitize(entity.getJdbcUrl()),
            entity.getUsername(),
            entity.getEncryptedPassword() != null, summary, entity.getDescription(),
            entity.getLastConnectionTestAt(), entity.getLastConnectionTestStatus(),
            entity.getCreatedAt(), entity.getUpdatedAt(), entity.getExecutionDatabase(),
            entity.getExecutionSchema(), entity.getSqlDialect());
    }

    private ConnectionException invalid(String message) {
        return new ConnectionException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static String required(String value, String name) {
        String result = optional(value);
        if (result == null) {
            throw new ConnectionException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                name + " is required");
        }
        return result;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean blank(String value) {
        return optional(value) == null;
    }

    private JsonNode read(String line) {
        try {
            return mapper.readTree(JdbcUrlSanitizer.sanitizeJson(line));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).isMissingNode() ? null : node.path(key).asString(null);
    }

    private static Integer integer(JsonNode node, String key) {
        return node.path(key).isNumber() ? node.path(key).asInt() : null;
    }

    private record Values(String name, String databaseType, String adapterId, String jdbcUrl,
        String username, String description, UUID driverId) {

    }

    private record DriverSnapshot(String driverClass, List<Path> artifacts) {

    }

    private record SavedSnapshot(String jdbcUrl, String username, String encryptedPassword,
        DriverSnapshot driver) {

    }
}
