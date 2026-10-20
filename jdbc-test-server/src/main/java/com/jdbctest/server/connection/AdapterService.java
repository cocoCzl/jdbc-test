package com.jdbctest.server.connection;

import com.jdbctest.catalog.AdapterDefinition;
import com.jdbctest.catalog.AdapterPackageSnapshot;
import com.jdbctest.catalog.AdapterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** Data-only imports. Uploaded trust is a declaration, never platform provenance. */
@Service
public class AdapterService {

    private final ImportedAdapterRepository packages;
    private final DatabaseConnectionProfileRepository connections;
    private final ObjectMapper mapper;

    public AdapterService(ImportedAdapterRepository packages,
        DatabaseConnectionProfileRepository connections, ObjectMapper mapper) {
        this.packages = packages;
        this.connections = connections;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AdapterResponse> list() {
        List<AdapterResponse> result = new ArrayList<>(AdapterRegistry.bundled().list().stream()
            .map(AdapterResponse::from).toList());
        packages.findAll().stream().filter(row -> !row.isArchived())
            .map(row -> AdapterResponse.imported(snapshot(row))).forEach(result::add);
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public AdapterDefinition required(String id) {
        if (AdapterRegistry.bundled().contains(id)) {
            return AdapterRegistry.bundled().required(id);
        }
        return snapshot(requiredRow(id)).definition();
    }

    @Transactional(readOnly = true)
    public AdapterPackageSnapshot packageSnapshot(String id) {
        return AdapterRegistry.bundled().contains(id) ? null : snapshot(requiredRow(id));
    }

    @Transactional
    public AdapterResponse importPackage(MultipartFile file, String expectedFingerprint) {
        AdapterPackageSnapshot incoming;
        if (file.getSize() > 8L * 1_048_576) {
            throw invalid("ADAPTER_PACKAGE_TOO_LARGE");
        }
        try (var input = file.getInputStream()) {
            incoming = AdapterPackageSnapshot.readZip(input);
        } catch (IOException exception) {
            throw invalid("ADAPTER_ZIP_INVALID");
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
        String id = incoming.definition().id();
        if (AdapterRegistry.bundled().contains(id)) {
            throw conflict("ADAPTER_BUILTIN_IMMUTABLE", "Built-in adapter IDs cannot be replaced");
        }
        var existing = packages.findForUpdate(id);
        if (existing.isPresent()) {
            var row = existing.orElseThrow();
            if (!snapshot(row).definition().dialect().equals(incoming.definition().dialect())) {
                throw conflict("ADAPTER_DIALECT_CHANGE", "Use a new ID for a different dialect");
            }
            if (row.getFingerprint().equals(incoming.fingerprint()) && !row.isArchived()) {
                return AdapterResponse.imported(snapshot(row));
            }
            if (!row.getFingerprint().equals(expectedFingerprint)) {
                throw conflict("ADAPTER_UPDATE_REQUIRES_FINGERPRINT",
                    "Same ID exists; explicitly update with its current fingerprint");
            }
        } else if (expectedFingerprint != null) {
            throw conflict("ADAPTER_UPDATE_TARGET_MISSING", "Update target does not exist");
        }
        var row = existing.orElseGet(() -> new ImportedAdapterEntity(id));
        row.replace(incoming.fingerprint(), mapper.writeValueAsString(incoming));
        persist(row);
        return AdapterResponse.imported(incoming);
    }

    @Transactional
    public void archive(String id) {
        if (AdapterRegistry.bundled().contains(id)) {
            throw conflict("ADAPTER_BUILTIN_IMMUTABLE", "Built-in adapters cannot be removed");
        }
        var row = packages.findForUpdate(id).orElseThrow(
            () -> new ConnectionException(HttpStatus.NOT_FOUND, "ADAPTER_NOT_FOUND",
                "Adapter was not found"));
        if (connections.countByAdapterId(id) > 0) {
            throw conflict("ADAPTER_IN_USE", "Connections still reference this adapter");
        }
        row.archive();
        // Keep the content and ID reserved; historical runs have independent frozen copies.
        persist(row);
    }

    /** Called inside the connection write transaction; serializes archival and new references. */
    void lockForConnection(String id) {
        if (!AdapterRegistry.bundled().contains(id)) {
            packages.findForUpdate(id).filter(row -> !row.isArchived()).orElseThrow(
                () -> new ConnectionException(HttpStatus.NOT_FOUND, "ADAPTER_NOT_FOUND",
                    "Adapter was not found"));
        }
    }

    private void persist(ImportedAdapterEntity row) {
        try {
            packages.saveAndFlush(row);
        } catch (org.springframework.dao.OptimisticLockingFailureException
            | org.springframework.dao.DataIntegrityViolationException exception) {
            throw conflict("ADAPTER_CONCURRENT_CHANGE", "Adapter changed concurrently; refresh");
        }
    }

    private ImportedAdapterEntity requiredRow(String id) {
        return packages.findById(id).filter(row -> !row.isArchived()).orElseThrow(
            () -> new ConnectionException(HttpStatus.NOT_FOUND, "ADAPTER_NOT_FOUND",
                "Adapter was not found"));
    }

    private AdapterPackageSnapshot snapshot(ImportedAdapterEntity row) {
        return mapper.readValue(row.getSnapshotJson(), AdapterPackageSnapshot.class);
    }

    private ConnectionException invalid(String detail) {
        return new ConnectionException(HttpStatus.UNPROCESSABLE_CONTENT,
            "ADAPTER_PACKAGE_INVALID", detail);
    }

    private ConnectionException conflict(String code, String detail) {
        return new ConnectionException(HttpStatus.CONFLICT, code, detail);
    }
}
