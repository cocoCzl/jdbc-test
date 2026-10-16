package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialOutputTest {

    @TempDir
    Path directory;

    @AfterEach
    void clearPassword() {
        CredentialSanitizer.password(null);
    }

    @Test
    void knownSecretIsRemovedBeforeBoundaryRedaction() {
        for (String password : passwords()) {
            CredentialSanitizer.password(password);
            String input = "connection failed password=" + password;
            String safe = CredentialSanitizer.sanitize(input);
            assertNoMarkers(safe);
            assertEquals(safe, CredentialSanitizer.sanitize(safe));
        }
    }

    @Test
    void rawProjectionProtectsNestedDiagnosticsWithoutChangingProtocolFields() {
        for (String password : passwords()) {
            CredentialSanitizer.password(password);
            String input = "connection failed password=" + password;
            var original = Map.of("message", input, "causes", List.of(Map.of("message", input)),
                "runId", password, "scenarioId", password, "status", password);
            var projected = (Map<?, ?>) CredentialSanitizer.project(original);
            assertNoMarkers(projected.get("message").toString());
            assertNoMarkers(projected.get("causes").toString());
            assertEquals(password, projected.get("runId"));
            assertEquals(password, projected.get("scenarioId"));
            assertEquals(password, projected.get("status"));
            assertEquals(projected, CredentialSanitizer.project(projected));
            assertEquals(input, original.get("message"));
        }
    }

    @Test
    void knownSecretInsideMaskDoesNotCorruptRepeatedProjection() {
        CredentialSanitizer.password("REDACTED");
        String safe = CredentialSanitizer.sanitize("driver echoed REDACTED");
        assertEquals("driver echoed [REDACTED]", safe);
        assertEquals(safe, CredentialSanitizer.sanitize(safe));
        assertEquals(Map.of("message", safe), CredentialSanitizer.project(Map.of("message", safe)));
    }

    @Test
    void maskPrefixDoesNotAllowSecretSuffix() {
        String safe = com.jdbctest.security.CredentialRedactor.sanitize(
            "password=[REDACTED]ReviewOnlyTail;encrypt=true");
        assertNoMarkers(safe);
        assertEquals("password=[REDACTED];encrypt=true", safe);
        assertEquals(safe, com.jdbctest.security.CredentialRedactor.sanitize(safe));
    }

    @Test
    void actualEventWriterProtectsRawNestedDiagnostics() throws Exception {
        verifyWriters(false);
    }

    @Test
    void actualReportWriterProtectsRawNestedDiagnostics() throws Exception {
        verifyWriters(true);
    }

    private void verifyWriters(boolean reportOnly) throws Exception {
        for (String password : passwords()) {
            CredentialSanitizer.password(password);
            var bytes = new ByteArrayOutputStream();
            var mapper = new ObjectMapper();
            var events = new WorkerEventWriter(mapper, new PrintStream(bytes));
            var fields = Map.<String, Object>of("message", "connection failed password=" + password,
                "causes", List.of(Map.of("message", "driver echoed " + password)), "vendorCode", 7);
            events.write("EXECUTION_ISSUE", "fixture", fields);
            if (!reportOnly) {
                assertNoMarkers(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals(7, mapper.readTree(bytes.toString()).path("vendorCode").asInt());
            var evidence = new WorkerExecutionEvidence("fixture", List.of(), events);
            evidence.recordCleanup(fields);
            var request = mapper.readValue("""
                {"runId":"fixture","connection":{"adapterId":"postgresql"},"driver":{}}
                """, WorkerRunRequest.class);
            Path report = WorkerReportWriter.write(directory, request, List.of(), evidence);
            String json = Files.readString(report);
            assertNoMarkers(json);
            assertEquals(7,
                mapper.readTree(json).path("cleanupEvidence").path("vendorCode").asInt());
            assertEquals("connection failed password=" + password, fields.get("message"));
        }
    }

    private static List<String> passwords() {
        return List.of("ReviewOnlyHead;ReviewOnlyTail", "ReviewOnlyHead&ReviewOnlyTail",
            "ReviewOnlyHead,ReviewOnlyTail", "[REDACTED]ReviewOnlyHeadReviewOnlyTail");
    }

    private static void assertNoMarkers(String value) {
        assertFalse(value.contains("ReviewOnlyHead"), value);
        assertFalse(value.contains("ReviewOnlyTail"), value);
    }

    @Test
    void eventsAndExceptionChainsUseTheSameSafeProjection() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var mapper = new ObjectMapper();
        CredentialSanitizer.password("UniqueBareSecret");
        var fields = WorkerExecutionEvidence.failure(new SQLException(
            "jdbc:oracle:thin:u/AuditOnly_Secret@host", "XX000", 7,
            new IllegalStateException("password={AuditOnly;SecretTail}}End}")));
        fields.put("message", "driver echoed UniqueBareSecret without a label");
        fields.put("nested", Map.of("access_token", "TokenSecret"));
        new WorkerEventWriter(mapper, new PrintStream(bytes)).write("EXECUTION_ISSUE", "fixture",
            fields);
        String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(output.contains("UniqueBareSecret"));
        CredentialSanitizer.password(null);
        assertFalse(output.contains("AuditOnly"));
        assertFalse(output.contains("SecretTail"));
        assertFalse(output.contains("TokenSecret"));
        assertEquals(7, mapper.readTree(output).path("vendorCode").asInt());
        assertEquals("TokenSecret", ((Map<?, ?>) fields.get("nested")).get("access_token"));
    }
}
