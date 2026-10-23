package com.jdbctest.server.driver;

import com.jdbctest.server.connection.DatabaseConnectionProfileRepository;
import com.jdbctest.server.run.TestRunRepository;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
final class DriverService {

    private static final Logger LOG = LoggerFactory.getLogger(DriverService.class);
    private static final Set<String> SORT_FIELDS = Set.of("name", "vendor", "version", "status",
        "createdAt");
    private final JdbcDriverPackageRepository packages;
    private final JdbcDriverArtifactRepository artifacts;
    private final DatabaseConnectionProfileRepository connections;
    private final TestRunRepository runs;
    private final DriverStorage storage;
    private final DriverValidationProcess validationProcess;
    private final TransactionTemplate transactions;

    DriverService(JdbcDriverPackageRepository packages, JdbcDriverArtifactRepository artifacts,
        DatabaseConnectionProfileRepository connections, TestRunRepository runs,
        DriverStorage storage,
        DriverValidationProcess validationProcess, TransactionTemplate transactions) {
        this.packages = packages;
        this.artifacts = artifacts;
        this.connections = connections;
        this.runs = runs;
        this.storage = storage;
        this.validationProcess = validationProcess;
        this.transactions = transactions;
    }

    DriverResponse create(CreateDriverRequest request, List<MultipartFile> files) {
        DriverStorage.StagedDriverPackage staged;
        try {
            staged = storage.stage(files);
        } catch (IOException exception) {
            throw new DriverException(HttpStatus.INTERNAL_SERVER_ERROR, "DRIVER_STORAGE_ERROR",
                "Unable to store driver artifacts");
        }
        try {
            Set<String> detected = DriverPackageInspector.detectDriverClasses(staged);
            String driverClass = selectDriverClass(optional(request.driverClass()), detected);
            String fingerprint = DriverPackageInspector.fingerprint(staged.artifacts());
            packages.findByPackageFingerprint(fingerprint).ifPresent(existing -> {
                throw duplicate(existing.getId());
            });
            JdbcDriverPackageEntity entity = new JdbcDriverPackageEntity(required(request.name()),
                required(request.vendor()), required(request.version()), driverClass,
                DriverSourceType.UPLOAD);
            entity.setUrlPrefix(optional(request.urlPrefix()));
            entity.setDataSources(request.dataSources());
            entity.setDefaultSqlDialect(defaultDialect(request.defaultSqlDialect()));
            entity.setPackageFingerprint(fingerprint);
            List<DriverStorage.StoredArtifact> stored = storage.moveToDriver(staged,
                entity.getId());
            try {
                return transactions.execute(status -> {
                    packages.saveAndFlush(entity);
                    List<JdbcDriverArtifactEntity> records = stored.stream().map(
                        item -> new JdbcDriverArtifactEntity(item.id(), entity, item.fileName(),
                            item.storageKey(), item.size(), item.sha256())).toList();
                    artifacts.saveAll(records);
                    return response(entity, records);
                });
            } catch (DataIntegrityViolationException exception) {
                storage.deleteDriver(entity.getId());
                JdbcDriverPackageEntity existing = packages.findByPackageFingerprint(fingerprint)
                    .orElse(null);
                throw duplicate(existing == null ? null : existing.getId());
            }
        } catch (IOException exception) {
            throw new DriverException(HttpStatus.INTERNAL_SERVER_ERROR, "DRIVER_STORAGE_ERROR",
                "Unable to finalize driver artifacts");
        } finally {
            storage.deleteStaging(staged);
        }
    }

    DriverResponse get(UUID id) {
        return response(requirePackage(id), artifacts.findByDriver_Id(id));
    }

    DriverPageResponse list(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new DriverException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Invalid pagination parameters");
        }
        String[] parts = sort == null ? new String[] {"createdAt", "desc"} : sort.split(",", -1);
        if (parts.length != 2 || !SORT_FIELDS.contains(parts[0])) {
            throw new DriverException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Unsupported sort field");
        }
        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new DriverException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Invalid sort direction");
        }
        var result = packages.findAll(PageRequest.of(page, size, Sort.by(direction, parts[0])));
        List<DriverResponse> items = result.getContent().stream()
            .map(entity -> response(entity, artifacts.findByDriver_Id(entity.getId()))).toList();
        return new DriverPageResponse(items, result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages());
    }

    DriverResponse update(UUID id, UpdateDriverRequest request) {
        JdbcDriverPackageEntity entity = requirePackage(id);
        return transactions.execute(status -> {
            entity.updateMetadata(required(request.name()), required(request.vendor()),
                required(request.version()), optional(request.urlPrefix()),
                required(request.driverClass()));
            if (request.dataSources() != null) {
                entity.setDataSources(request.dataSources());
            }
            if (request.defaultSqlDialect() != null) {
                entity.setDefaultSqlDialect(defaultDialect(request.defaultSqlDialect()));
            }
            packages.save(entity);
            return response(entity, artifacts.findByDriver_Id(id));
        });
    }

    void delete(UUID id) {
        JdbcDriverPackageEntity entity = requirePackage(id);
        if (connections.countByDefaultDriver_Id(id) > 0 || runs.countByDriver_Id(id) > 0) {
            throw new DriverException(HttpStatus.CONFLICT, "DRIVER_IN_USE",
                "Driver is referenced by a connection or test run");
        }
        try {
            transactions.executeWithoutResult(status -> {
                artifacts.deleteByDriver_Id(id);
                packages.delete(entity);
            });
        } catch (DataIntegrityViolationException exception) {
            throw new DriverException(HttpStatus.CONFLICT, "DRIVER_IN_USE",
                "Driver is referenced by another resource");
        }
        try {
            storage.deleteDriver(id);
        } catch (IOException exception) {
            LOG.warn("Driver storage cleanup failed for {}", id);
        }
    }

    DriverValidationResponse validate(UUID id) {
        JdbcDriverPackageEntity entity = requirePackage(id);
        List<java.nio.file.Path> files = artifacts.findByDriver_Id(id).stream()
            .map(item -> storage.resolve(item.getStorageKey())).toList();
        try {
            DriverValidationResponse result = validationProcess.validate(entity.getDriverClass(),
                files);
            transactions.executeWithoutResult(status -> {
                JdbcDriverPackageEntity current = requirePackage(id);
                current.setStatus(DriverPackageStatus.VALIDATED);
                packages.save(current);
            });
            return result;
        } catch (DriverException exception) {
            if (!"WORKER_UNAVAILABLE".equals(exception.code())) {
                transactions.executeWithoutResult(status -> {
                    JdbcDriverPackageEntity current = requirePackage(id);
                    current.setStatus(DriverPackageStatus.INVALID);
                    packages.save(current);
                });
            }
            throw exception;
        }
    }

    private static String defaultDialect(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!Set.of("oracle", "mysql", "postgresql", "dm").contains(value)) {
            throw new DriverException(HttpStatus.BAD_REQUEST, "ADAPTER_DIALECT_INVALID",
                "Select Oracle, MySQL, PostgreSQL or Dameng");
        }
        return com.jdbctest.catalog.SqlDialect.fromCode(value).code();
    }

    private static String selectDriverClass(String requested, Set<String> detected) {
        if (requested != null) {
            return requested;
        }
        if (detected.size() == 1) {
            return detected.iterator().next();
        }
        if (detected.size() > 1) {
            throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_CLASS_AMBIGUOUS",
                "Multiple JDBC driver classes were detected",
                java.util.Map.of("candidates", List.copyOf(detected)));
        }
        throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_INVALID",
            "driverClass is required when SPI metadata does not identify a driver");
    }

    private JdbcDriverPackageEntity requirePackage(UUID id) {
        return packages.findById(id).orElseThrow(
            () -> new DriverException(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND",
                "Driver was not found"));
    }

    private static DriverException duplicate(UUID id) {
        return new DriverException(HttpStatus.CONFLICT, "DRIVER_PACKAGE_ALREADY_EXISTS",
            "An identical driver package already exists",
            id == null ? java.util.Map.of() : java.util.Map.of("driverId", id));
    }

    private static String required(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new DriverException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Required metadata must not be blank");
        }
        return normalized;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static DriverResponse response(JdbcDriverPackageEntity entity,
        List<JdbcDriverArtifactEntity> records) {
        return new DriverResponse(entity.getId(), entity.getName(), entity.getVendor(),
            entity.getVersion(), entity.getDriverClass(), entity.getUrlPrefix(),
            entity.getSourceType(),
            entity.getStatus(), entity.getPackageFingerprint(), entity.getCreatedAt().toString(),
            entity.getUpdatedAt().toString(), records.stream().map(
            item -> new DriverArtifactResponse(item.getId(), item.getFileName(), item.getSize(),
                item.getSha256())).toList(), entity.getDataSources(), entity.getDefaultSqlDialect());
    }
}
