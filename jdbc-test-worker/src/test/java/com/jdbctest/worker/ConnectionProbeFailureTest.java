package com.jdbctest.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectionProbeFailureTest {

    @Test
    void preservesNextCauseAndSuppressedBranchesWithoutRepeatingCycles() throws Exception {
        var failure = new SQLException("schema", "3F000", -2103);
        var cause = new SQLException("underlying", "08001", 11);
        failure.initCause(cause);
        failure.setNextException(new SQLException("next", "HY000", 12));
        failure.addSuppressed(new SQLException("close", "08006", 13));
        cause.initCause(failure);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            ConnectionProbeFailure.describe("ESTABLISHING_CONNECTION", failure));
        for (String state : java.util.List.of("3F000", "08001", "HY000", "08006")) {
            assertTrue(json.contains(state), state);
            assertEquals(json.indexOf(state), json.lastIndexOf(state));
        }
    }

    @Test
    void unknownMessagesNeverGuessUrlOrPasswordFailuresAndRedactSecrets() throws Exception {
        CredentialSanitizer.password("unlabelled-secret");
        var failure = new SQLException(
            "unknown unlabelled-secret jdbc:fixture:test?sslpassword=other-secret", "ZZ999", 71);
        failure.setNextException(new SQLException("next cause", "XX001", 72));
        Map<String, Object> result = ConnectionProbeFailure.describe("ESTABLISHING_CONNECTION",
            failure);
        assertEquals("CONNECTION_UNKNOWN", result.get("errorType"));
        assertEquals("ESTABLISHING_CONNECTION", result.get("stage"));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("unlabelled-secret"));
        assertFalse(json.contains("other-secret"));
        assertTrue(json.contains("ZZ999"));
        assertTrue(json.contains("XX001"));
        assertTrue(json.contains("71"));
        CredentialSanitizer.password(null);
    }

    @Test
    void authenticationStateDoesNotClaimThePasswordIsWrong() {
        var result = ConnectionProbeFailure.describe("ESTABLISHING_CONNECTION",
            new SQLException("rejected", "28000", 1017));
        assertEquals("CONNECTION_AUTHENTICATION_FAILED", result.get("errorType"));
        assertEquals("CONNECTION_DRIVER_LOAD_FAILED", ConnectionProbeFailure.describe(
            "LOADING_DRIVER", new NoClassDefFoundError("dependency")).get("errorType"));
        assertEquals("CONNECTION_METADATA_FAILED", ConnectionProbeFailure.describe(
            "READING_METADATA", new SQLException("metadata", "28000", 1)).get("errorType"));
    }

    @Test
    void malformedSqlStatesAreRedactedWithoutInventingAnAuthenticationCause() throws Exception {
        CredentialSanitizer.password("state-secret");
        try {
            var result = ConnectionProbeFailure.describe("ESTABLISHING_CONNECTION",
                new SQLException("unknown", "28-state-secret", 0));
            assertEquals("CONNECTION_UNKNOWN", result.get("errorType"));
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(result).contains("state-secret"));
        } finally {
            CredentialSanitizer.password(null);
        }
    }

    @Test
    void boundedDiagnosticsStopAtCyclesAndLongMessages() throws Exception {
        var failure = new SQLException("x".repeat(5000), "08001", 0);
        failure.initCause(failure.getCause() == null ? new RuntimeException(failure) : null);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            ConnectionProbeFailure.describe("ESTABLISHING_CONNECTION", failure));
        assertTrue(json.length() < 5000);
        assertTrue(json.contains("CONNECTION_UNKNOWN"));
    }
}
