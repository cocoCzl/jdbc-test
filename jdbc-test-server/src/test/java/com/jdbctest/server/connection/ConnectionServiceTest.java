package com.jdbctest.server.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jdbctest.server.driver.DriverPackageStatus;
import com.jdbctest.server.driver.DriverStorage;
import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.driver.JdbcDriverArtifactRepository;
import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import com.jdbctest.server.driver.JdbcDriverPackageRepository;
import com.jdbctest.server.driver.WorkerProcessRunner;
import com.jdbctest.server.run.TestRunRepository;
import com.jdbctest.server.security.CredentialEncryptionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ConnectionServiceTest {

    private final UUID driverId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final WorkerProcessRunner worker = mock(WorkerProcessRunner.class);
    private final DriverWorkerProperties properties = new DriverWorkerProperties();
    private ConnectionService service;

    @BeforeEach
    void setUp() {
        JdbcDriverPackageRepository drivers = mock(JdbcDriverPackageRepository.class);
        JdbcDriverArtifactRepository artifacts = mock(JdbcDriverArtifactRepository.class);
        JdbcDriverPackageEntity driver = mock(JdbcDriverPackageEntity.class);
        when(driver.getId()).thenReturn(driverId);
        when(driver.getStatus()).thenReturn(DriverPackageStatus.VALIDATED);
        when(driver.getDriverClass()).thenReturn("example.FakeDriver");
        when(drivers.findById(driverId)).thenReturn(Optional.of(driver));
        when(artifacts.findByDriver_Id(driverId)).thenReturn(List.of());
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        service = new ConnectionService(mock(DatabaseConnectionProfileRepository.class), drivers,
            artifacts, mock(TestRunRepository.class), mock(DriverStorage.class),
            mock(CredentialEncryptionService.class), worker, properties, new ObjectMapper(),
            transactions, new AdapterService());
    }

    @Test
    void uploadedAdapterReferencesCannotCreateOrUpdateConnectionsOrProbeDrafts() {
        var create = new CreateConnectionRequest("x", "Private", "retired-local",
            "jdbc:fixture:never-contacted", "u", null, false, driverId, null, null, null);
        var update = new UpdateConnectionRequest("x", "Private", "retired-local",
            "jdbc:fixture:never-contacted", "u", null, false, false, driverId, null, null, null);
        var draft = new DraftConnectionTestRequest("Private", "retired-local",
            "jdbc:fixture:never-contacted", "u", "synthetic", driverId);
        for (Runnable action : List.<Runnable>of(() -> service.create(create),
            () -> service.update(UUID.randomUUID(), update), () -> service.testDraft(draft))) {
            assertEquals("BUILTIN_ADAPTER_REQUIRED",
                assertThrows(ConnectionException.class, action::run).code());
        }
        org.mockito.Mockito.verifyNoInteractions(worker);
    }

    @Test
    void tlsCredentialUrlsAreRejectedByEveryDraftWriteEntryBeforeDriverUse() {
        for (String url : List.of(
            "jdbc:postgresql://fixture.invalid/test?sslpassword=ReviewOnlyTlsSecret",
            "jdbc:sqlserver://fixture.invalid;trustStorePassword=ReviewOnlyTlsSecret;encrypt=true")) {
            var create = new CreateConnectionRequest("x", "postgresql", "postgresql", url, "u",
                null,
                false, driverId, null, null, null);
            var update = new UpdateConnectionRequest("x", "postgresql", "postgresql", url, "u",
                null,
                false, false, driverId, null, null, null);
            var draft = new DraftConnectionTestRequest("postgresql", "postgresql", url, "u", "x",
                driverId);
            for (Runnable action : List.<Runnable>of(() -> service.create(create),
                () -> service.update(UUID.randomUUID(), update), () -> service.testDraft(draft))) {
                var error = assertThrows(ConnectionException.class, action::run);
                assertEquals("CONNECTION_URL_CONTAINS_CREDENTIALS", error.code());
                org.junit.jupiter.api.Assertions.assertFalse(
                    error.getMessage().contains("ReviewOnlyTls"));
            }
        }
        org.mockito.Mockito.verifyNoInteractions(worker);
    }

    @Test
    void everyDraftWriteEntryRejectsOracleCredentialsBeforeDriverUse() {
        String url = "jdbc:oracle:thin:audit_user/AuditOnly_Secret@host:1521:SID";
        var create = new CreateConnectionRequest("x", "oracle", "oracle", url, "u", null,
            false, driverId, null, null, null);
        var update = new UpdateConnectionRequest("x", "oracle", "oracle", url, "u", null,
            false, false, driverId, null, null, null);
        var draft = new DraftConnectionTestRequest("oracle", "oracle", url, "u", "x", driverId);
        for (Runnable action : List.<Runnable>of(() -> service.create(create),
            () -> service.update(UUID.randomUUID(), update), () -> service.testDraft(draft))) {
            var error = assertThrows(ConnectionException.class, action::run);
            assertEquals("CONNECTION_URL_CONTAINS_CREDENTIALS", error.code());
            org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("AuditOnly"));
        }
        org.mockito.Mockito.verifyNoInteractions(worker);
    }

    @Test
    void keepsNullFilteringWhileReadingSuccessfulProbeMetadata() throws Exception {
        respond(0, false, "diagnostic text\nnull\n{}\n"
            + "{\"type\":\"CONNECTION_PROBED\",\"databaseProductName\":\"Test DB\","
            + "\"jdbcMajorVersion\":4}\n");
        ConnectionProbeResponse result = service.testDraft(request());
        assertTrue(result.success());
        assertEquals("Test DB", result.databaseProductName());
        assertEquals(4, result.jdbcMajorVersion());
    }

    @Test
    void invalidJsonAndJsonNullCannotTurnIntoProbeSuccess() throws Exception {
        respond(0, false, "not-json\nnull\n{}\n");
        ConnectionException exception = assertThrows(ConnectionException.class,
            () -> service.testDraft(request()));
        assertEquals("CONNECTION_UNKNOWN", exception.code());
    }

    @Test
    void nonzeroWorkerExitCannotTurnIntoProbeSuccess() throws Exception {
        respond(1, false, "{\"type\":\"CONNECTION_PROBED\"}\n");
        ConnectionException exception = assertThrows(ConnectionException.class,
            () -> service.testDraft(request()));
        assertEquals("CONNECTION_UNKNOWN", exception.code());
    }

    @Test
    void retainsTypedFailureAfterNullAndInvalidLines() throws Exception {
        respond(1, false, "not-json\nnull\n"
            + "{\"type\":\"CONNECTION_PROBE_FAILED\","
            + "\"errorType\":\"DRIVER_DOES_NOT_ACCEPT_URL\"}\n");
        ConnectionException exception = assertThrows(ConnectionException.class,
            () -> service.testDraft(request()));
        assertEquals("DRIVER_DOES_NOT_ACCEPT_URL", exception.code());
    }

    @Test
    void retainsTimeoutFailure() throws Exception {
        respond(-1, true, "");
        ConnectionException exception = assertThrows(ConnectionException.class,
            () -> service.testDraft(request()));
        assertEquals("CONNECTION_TIMEOUT", exception.code());
    }

    @Test
    void retainsInterruptFlagAndFailure() throws Exception {
        when(worker.run(eq("--probe-connection"), any(),
            eq(properties.getConnectionProbeTimeout()))).thenThrow(new InterruptedException());
        try {
            ConnectionException exception = assertThrows(ConnectionException.class,
                () -> service.testDraft(request()));
            assertEquals("CONNECTION_TIMEOUT", exception.code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void structuredProbeFailureRetainsStageCodesAndRedactsSuppliedSecretAgain() throws Exception {
        respond(3, false, "{\"type\":\"CONNECTION_PROBE_FAILED\","
            + "\"errorType\":\"CONNECTION_METADATA_FAILED\",\"stage\":\"READING_METADATA\","
            + "\"diagnostics\":[{\"sqlState\":\"ZZ999\",\"vendorCode\":71,"
            + "\"message\":\"unlabelled fixture-only and password=other-secret\"}]}\n");
        var error = assertThrows(ConnectionException.class, () -> service.testDraft(request()));
        assertEquals("CONNECTION_METADATA_FAILED", error.code());
        assertEquals("READING_METADATA", error.properties().get("stage"));
        String json = new ObjectMapper().writeValueAsString(error.properties());
        assertTrue(json.contains("ZZ999"));
        assertTrue(json.contains("71"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("fixture-only"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("other-secret"));
    }

    @Test
    void legacyGenericAndUnknownWorkerErrorsDoNotBecomeUrlRejections() throws Exception {
        for (String type : List.of("CONNECTION_FAILED", "UNRECOGNIZED")) {
            respond(3, false, "{\"type\":\"CONNECTION_PROBE_FAILED\",\"errorType\":\""
                + type + "\"}\n");
            var error = assertThrows(ConnectionException.class, () -> service.testDraft(request()));
            assertEquals("CONNECTION_UNKNOWN", error.code());
            org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("URL"));
        }
    }

    private void respond(int exitCode, boolean timedOut, String stdout) throws Exception {
        when(worker.run(eq("--probe-connection"), any(),
            eq(properties.getConnectionProbeTimeout())))
            .thenReturn(new WorkerProcessRunner.Result(exitCode, timedOut, stdout, ""));
    }

    private DraftConnectionTestRequest request() {
        return new DraftConnectionTestRequest("PostgreSQL", "postgresql",
            "jdbc:postgresql://fixture.invalid/example", "fixture", "fixture-only", driverId);
    }
}
